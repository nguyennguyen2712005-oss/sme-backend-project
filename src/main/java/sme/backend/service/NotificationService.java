package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.entity.*;
import sme.backend.repository.NotificationRepository;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.UserRepository;

import java.util.HashMap;
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
    private final UserRepository userRepository;

    // ==============================================================================
    // CORE: LƯU DATABASE VÀ PUSH REAL-TIME (TRUE REAL-TIME)
    // ==============================================================================
    
    private void saveAndPushToWarehouse(UUID warehouseId, Notification notification) {
        // 1. ĐÃ SỬA: Gắn ID chi nhánh và lưu Database
        notification.setWarehouseId(warehouseId);
        Notification saved = notificationRepository.save(notification);
        
        // 2. Push cho toàn bộ nhân viên trong chi nhánh (Kênh chung)
        messagingTemplate.convertAndSend("/topic/warehouse/" + warehouseId + "/notifications", saved);
        
        // 3. Push cho Admin (Admin có kênh giám sát toàn cục)
        messagingTemplate.convertAndSend("/topic/admin/notifications", saved);
    }

    private void saveAndPushToUser(UUID userId, Notification notification) {
        notification.setUserId(userId);
        Notification saved = notificationRepository.save(notification);
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", saved);
    }

    // ==============================================================================
    // BUSINESS USE-CASES
    // ==============================================================================

    @Async
    public void notifyLowStock(Inventory inventory) {
        if (inventory == null || inventory.getWarehouseId() == null) return;

        String productName = productRepository.findById(inventory.getProductId())
                .map(Product::getName).orElse("Sản phẩm không xác định");

        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", inventory.getProductId());
        payload.put("warehouseId", inventory.getWarehouseId());
        payload.put("quantity", inventory.getQuantity() != null ? inventory.getQuantity() : 0);
        payload.put("minQuantity", inventory.getMinQuantity() != null ? inventory.getMinQuantity() : 0);
        payload.put("productName", productName);

        Notification notification = Notification.builder()
                .type("LOW_STOCK")
                .title("⚠️ Cảnh báo tồn kho thấp")
                .message(String.format("Sản phẩm %s tại kho chỉ còn %d sản phẩm", productName, inventory.getQuantity()))
                .payload(payload)
                .build();

        saveAndPushToWarehouse(inventory.getWarehouseId(), notification);
    }

    @Async
    public void notifyNewOrder(Order order, UUID warehouseId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId());
        payload.put("orderCode", order.getCode());
        payload.put("amount", order.getFinalAmount() != null ? order.getFinalAmount() : 0);
        payload.put("type_order", order.getType() != null ? order.getType().name() : "DELIVERY");

        Notification notification = Notification.builder()
                .type("NEW_ORDER")
                .title("🛒 Đơn hàng mới")
                .message("Có đơn hàng mới #" + order.getCode() + " vừa được phân bổ về chi nhánh.")
                .payload(payload)
                .build();

        saveAndPushToWarehouse(warehouseId, notification);
    }

    @Async
    public void notifyShiftClosed(Shift shift) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("shiftId", shift.getId());
        payload.put("cashierId", shift.getCashierId());
        payload.put("discrepancyAmount", shift.getDiscrepancyAmount() != null ? shift.getDiscrepancyAmount() : 0);

        Notification notification = Notification.builder()
                .type("SHIFT_PENDING_APPROVAL")
                .title("🕐 Ca làm việc chờ duyệt")
                .message("Thu ngân vừa đóng ca. Vui lòng kiểm tra và duyệt chốt ca.")
                .payload(payload)
                .build();

        List<User> managers = userRepository.findByWarehouseIdAndRoleAndIsActiveTrue(
                shift.getWarehouseId(), User.UserRole.ROLE_MANAGER);
        for (User manager : managers) {
            saveAndPushToUser(manager.getId(), notification);
        }
    }

    @Async
    public void notifyTransferArrived(UUID transferId, UUID toWarehouseId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transferId", transferId);

        Notification notification = Notification.builder()
                .type("TRANSFER_ARRIVED")
                .title("📦 Hàng luân chuyển đã đến")
                .message("Một chuyến hàng luân chuyển vừa đến chi nhánh của bạn. Vui lòng kiểm đếm và nhận hàng.")
                .payload(payload)
                .build();

        saveAndPushToWarehouse(toWarehouseId, notification);
    }

    @Async
    public void notifyOrderForceCancelled(Order order, String reason) {
        if (order.getAssignedWarehouseId() == null) return;
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId());
        payload.put("orderCode", order.getCode());
        payload.put("reason", reason);

        Notification notification = Notification.builder()
                .type("ORDER_FORCE_CANCELLED")
                .title("🚨 Admin hủy khẩn cấp đơn hàng")
                .message("Đơn #" + order.getCode() + " đã bị Admin hủy khẩn cấp. Lý do: " + reason)
                .payload(payload)
                .build();

        List<User> managers = userRepository.findByWarehouseIdAndRoleAndIsActiveTrue(
                order.getAssignedWarehouseId(), User.UserRole.ROLE_MANAGER);
        for (User manager : managers) {
            saveAndPushToUser(manager.getId(), notification);
        }
    }

    @Async
    public void notifyOrderReassigned(Order order, UUID oldWarehouseId) {
        if (order.getAssignedWarehouseId() == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId());
        payload.put("orderCode", order.getCode());
        payload.put("fromWarehouseId", oldWarehouseId != null ? oldWarehouseId.toString() : "");

        Notification notification = Notification.builder()
                .type("ORDER_REASSIGNED")
                .title("📦 Đơn hàng được tái phân công")
                .message("Đơn #" + order.getCode() + " vừa được Admin chuyển về chi nhánh của bạn. Vui lòng kiểm tra.")
                .payload(payload)
                .build();

        List<User> managers = userRepository.findByWarehouseIdAndRoleAndIsActiveTrue(
                order.getAssignedWarehouseId(), User.UserRole.ROLE_MANAGER);
        for (User manager : managers) {
            saveAndPushToUser(manager.getId(), notification);
        }
    }

    // ==============================================================================
    // QUERIES & UPDATES
    // ==============================================================================

    @Transactional
    public void markAsRead(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        List<Notification> unread = getUnread(userId);
        for (Notification n : unread) {
            n.setIsRead(true);
        }
        notificationRepository.saveAll(unread);
    }

    // ĐÃ THÊM: Đánh dấu đọc tất cả cho Manager
    @Transactional
    public void markAllAsReadForManager(UUID userId, UUID warehouseId) {
        List<Notification> unread = getUnreadForManager(userId, warehouseId);
        for (Notification n : unread) {
            n.setIsRead(true);
        }
        notificationRepository.saveAll(unread);
    }

    public List<Notification> getUnread(UUID userId) {
        if (userId == null) {
            return notificationRepository.findByUserIdIsNullAndIsReadFalseOrderByCreatedAtDesc();
        }
        return notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);
    }

    // ĐÃ THÊM: Truy vấn danh sách riêng cho Manager
    public List<Notification> getUnreadForManager(UUID userId, UUID warehouseId) {
        return notificationRepository.findUnreadForManager(userId, warehouseId);
    }

    public long countUnread(UUID userId) {
        if (userId == null) {
            return notificationRepository.countByUserIdIsNullAndIsReadFalse();
        }
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    // ĐÃ THÊM: Số đếm riêng cho Manager
    public long countUnreadForManager(UUID userId, UUID warehouseId) {
        return notificationRepository.countUnreadForManager(userId, warehouseId);
    }
}