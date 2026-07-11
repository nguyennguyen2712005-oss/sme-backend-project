
package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import sme.backend.entity.*;
import sme.backend.repository.NotificationRepository;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationRepository notificationRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository; // ĐÃ THÊM: để tìm Manager của một kho

    @Async
    public void notifyLowStock(Inventory inventory) {
        if (inventory == null || inventory.getWarehouseId() == null) return;

        String topic = "/topic/warehouse/" + inventory.getWarehouseId() + "/low-stock";

        int minQty = inventory.getMinQuantity() != null ? inventory.getMinQuantity() : 0;
        int currentQty = inventory.getQuantity() != null ? inventory.getQuantity() : 0;

        String productName = productRepository.findById(inventory.getProductId())
                .map(Product::getName)
                .orElse("Sản phẩm không xác định");

        Map<String, Object> payload = Map.of(
                "type",        "LOW_STOCK",
                "productId",   inventory.getProductId(),
                "warehouseId", inventory.getWarehouseId(),
                "quantity",    currentQty,
                "minQuantity", minQty,
                "productName", productName
        );
        messagingTemplate.convertAndSend(topic, payload);

        Notification notification = Notification.builder()
                .type("LOW_STOCK")
                .title("⚠️ Cảnh báo tồn kho thấp")
                .message(String.format("Sản phẩm %s (ID: %s) tại kho %s chỉ còn %d sản phẩm",
                        productName, inventory.getProductId(), inventory.getWarehouseId(), currentQty))
                .payload(payload)
                .build();

        notificationRepository.save(notification);
        log.debug("Low stock alert sent for product={}", inventory.getProductId());
    }

    @Async
    public void notifyNewOrder(Order order, UUID warehouseId) {
        String topic = "/topic/warehouse/" + warehouseId + "/new-order";

        Map<String, Object> payload = Map.of(
                "type",      "NEW_ORDER",
                "orderId",   order.getId(),
                "orderCode", order.getCode(),
                "amount",    order.getFinalAmount() != null ? order.getFinalAmount() : 0,
                "type_order", order.getType() != null ? order.getType().name() : "DELIVERY"
        );
        messagingTemplate.convertAndSend(topic, payload);
        log.debug("New order notification sent: order={}", order.getCode());
    }

    @Async
    public void notifyShiftClosed(Shift shift) {
        String topic = "/topic/warehouse/" + shift.getWarehouseId() + "/shift-alert";

        Map<String, Object> payload = Map.of(
                "type",              "SHIFT_PENDING_APPROVAL",
                "shiftId",           shift.getId(),
                "cashierId",         shift.getCashierId(),
                "discrepancyAmount", shift.getDiscrepancyAmount() != null ? shift.getDiscrepancyAmount() : 0
        );

        messagingTemplate.convertAndSend(topic, payload);
        log.debug("Shift closed notification sent: shift={}", shift.getId());
    }

    @Async
    public void notifyTransferArrived(UUID transferId, UUID toWarehouseId) {
        String topic = "/topic/warehouse/" + toWarehouseId + "/transfer";

        Map<String, Object> payload = Map.of(
                "type",       "TRANSFER_ARRIVED",
                "transferId", transferId
        );

        messagingTemplate.convertAndSend(topic, payload);

        Notification notification = Notification.builder()
                .type("TRANSFER_ARRIVED")
                .title("📦 Cập nhật trạng thái chuyển kho")
                .message("Một phiếu chuyển kho liên quan đến chi nhánh của bạn vừa được cập nhật.")
                .payload(payload)
                .build();

        notificationRepository.save(notification);
    }

    /**
     * ĐÃ THÊM: Báo cho (các) Manager của một chi nhánh khi Admin force-cancel một
     * đơn hàng đang thuộc chi nhánh đó. Vì Admin không trực tiếp vận hành chi
     * nhánh (xem triết lý phân quyền ở Module 2), Manager cần biết ngay để xử lý
     * phần việc còn lại (báo khách, đối soát COD nếu có...).
     */
    @Async
    public void notifyOrderForceCancelled(Order order, String reason) {
        if (order.getAssignedWarehouseId() == null) return;
        String topic = "/topic/warehouse/" + order.getAssignedWarehouseId() + "/order-alert";

        Map<String, Object> payload = Map.of(
                "type",    "ORDER_FORCE_CANCELLED",
                "orderId", order.getId(),
                "orderCode", order.getCode(),
                "reason",  reason
        );
        messagingTemplate.convertAndSend(topic, payload);

        String message = "Đơn #" + order.getCode() + " đã bị Admin hủy khẩn cấp. Lý do: " + reason;
        notifyManagersOfWarehouse(order.getAssignedWarehouseId(), "ORDER_FORCE_CANCELLED",
                "🚨 Admin hủy khẩn cấp đơn hàng", message, payload);
    }

    /**
     * ĐÃ THÊM: Báo cho Manager của chi nhánh MỚI khi Admin tái phân công một đơn
     * hàng sang chi nhánh của họ - để họ biết và xác nhận lại đơn (đơn quay về
     * PENDING sau khi tái phân công).
     */
    @Async
    public void notifyOrderReassigned(Order order, UUID oldWarehouseId) {
        if (order.getAssignedWarehouseId() == null) return;
        String topic = "/topic/warehouse/" + order.getAssignedWarehouseId() + "/new-order";

        Map<String, Object> payload = Map.of(
                "type",      "ORDER_REASSIGNED",
                "orderId",   order.getId(),
                "orderCode", order.getCode(),
                "fromWarehouseId", oldWarehouseId != null ? oldWarehouseId.toString() : ""
        );
        messagingTemplate.convertAndSend(topic, payload);

        String message = "Đơn #" + order.getCode() + " vừa được Admin chuyển về chi nhánh của bạn. Vui lòng xác nhận lại đơn.";
        notifyManagersOfWarehouse(order.getAssignedWarehouseId(), "ORDER_REASSIGNED",
                "📦 Đơn hàng được tái phân công", message, payload);
    }

    private void notifyManagersOfWarehouse(UUID warehouseId, String type, String title,
                                            String message, Map<String, Object> payload) {
        List<User> managers = userRepository.findByWarehouseIdAndRoleAndIsActiveTrue(
                warehouseId, User.UserRole.ROLE_MANAGER);
        for (User manager : managers) {
            notificationRepository.save(Notification.builder()
                    .userId(manager.getId())
                    .type(type)
                    .title(title)
                    .message(message)
                    .payload(payload)
                    .build());
        }
    }

    public void markAsRead(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    public List<Notification> getUnread(UUID userId) {
        if (userId == null) {
            return notificationRepository.findByUserIdIsNullAndIsReadFalseOrderByCreatedAtDesc();
        }
        return notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);
    }

    public long countUnread(UUID userId) {
        if (userId == null) {
            return notificationRepository.countByUserIdIsNullAndIsReadFalse();
        }
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }
}
