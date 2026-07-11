
package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.NotAudited;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Audited
@AuditTable("orders_audit")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Order extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** Kho được thuật toán Smart Routing chỉ định đóng gói */
    @Column(name = "assigned_warehouse_id")
    private UUID assignedWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    /** DELIVERY = giao tận nơi | BOPIS = mua online lấy tại quầy */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private OrderType type = OrderType.DELIVERY;

    @Column(name = "shipping_name", length = 150)
    private String shippingName;

    @Column(name = "shipping_phone", length = 20)
    private String shippingPhone;

    @Column(name = "shipping_address", nullable = false, columnDefinition = "TEXT")
    private String shippingAddress;

    /** Mã tỉnh/thành phố của địa chỉ giao hàng - dùng cho routing */
    @Column(name = "province_code", nullable = false, length = 20)
    private String provinceCode;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "shipping_fee", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "discount_amount", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "final_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal finalAmount = BigDecimal.ZERO;

    @Column(name = "payment_method", nullable = false, length = 50)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 50)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "tracking_code", length = 100)
    private String trackingCode;

    @Column(name = "shipping_provider", length = 50)
    private String shippingProvider;

    @Column(name = "cod_reconciled")
    @Builder.Default
    private Boolean codReconciled = false;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "packed_by")
    private UUID packedBy;

    @Column(name = "packed_at")
    private Instant packedAt;

    @Column(name = "cancelled_reason", columnDefinition = "TEXT")
    private String cancelledReason;

    @NotAudited
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @NotAudited
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    /**
     * LƯU Ý KHI ĐỌC ENUM NÀY:
     * - status là cột VARCHAR(50) trong DB (KHÔNG phải Postgres ENUM thật), nên việc
     *   thêm/sửa giá trị enum dưới đây KHÔNG cần ALTER TABLE / migration gì cả.
     * - Pipeline cũ (PENDING -> PACKING -> SHIPPING -> DELIVERED) bị xoá khái niệm
     *   "đã xác nhận" và "đã đóng gói xong" làm một (PACKING vừa là "đang xử lý"
     *   vừa là cổng để Cashier có thể tự ý chuyển thẳng sang SHIPPING/DELIVERED/CANCELLED
     *   vì validateTransition cũ không quan tâm role). Enum mới tách rõ 2 bước này
     *   để mỗi bước có thể gắn một @PreAuthorize / business rule riêng ở Service.
     */
    public enum OrderStatus {
        WAITING_FOR_CONSOLIDATION, // Hệ thống đang gom hàng từ nhiều kho (giữ nguyên, đang hoạt động tốt)
        PENDING,                   // Khách vừa đặt / Telesale vừa tạo - chờ Manager xác nhận
        CONFIRMED,                 // Manager đã xác nhận còn hàng - sẵn sàng để đóng gói
        PACKED,                    // Cashier/Manager đã đóng gói xong
        SHIPPING,                  // Đang giao (chỉ áp dụng cho type = DELIVERY)
        READY_FOR_PICKUP,          // Sẵn sàng cho khách lấy tại quầy (chỉ áp dụng cho type = BOPIS)
        DELIVERED,                 // Hoàn tất - đã giao hoặc khách đã lấy tại quầy
        CANCELLED,                 // Đã hủy
        RETURNED                   // Đã hoàn trả sau khi DELIVERED
    }

    public enum OrderType {
        DELIVERY, BOPIS
    }

    public enum PaymentStatus {
        UNPAID, PAID, REFUNDED
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void addStatusHistory(OrderStatusHistory history) {
        statusHistory.add(history);
        history.setOrder(this);
    }

    public void transitionTo(OrderStatus newStatus, String note, String changedBy) {
        validateTransition(this.status, newStatus, this.type);

        OrderStatusHistory history = OrderStatusHistory.builder()
                .oldStatus(this.status.name())
                .newStatus(newStatus.name())
                .note(note)
                .changedBy(changedBy)
                .build();

        this.status = newStatus;
        this.addStatusHistory(history);
    }

    /**
     * State machine có phân biệt theo OrderType:
     * - DELIVERY: PACKED -> SHIPPING -> DELIVERED
     * - BOPIS:    PACKED -> READY_FOR_PICKUP -> DELIVERED (đơn cũ trước đây dùng
     *   chung pipeline với DELIVERY dù nghiệp vụ BOPIS không hề "giao đi" - đây là
     *   một lỗ hổng thực tế của code cũ, không phải giả định).
     *
     * Việc AI role nào được PHÉP gọi transition này không được check ở đây - tầng
     * này chỉ đảm bảo tính hợp lệ của state machine. Quyền theo role được chốt ở
     * OrderService (mỗi action có method + @PreAuthorize riêng ở Controller).
     */
    private void validateTransition(OrderStatus from, OrderStatus to, OrderType type) {
        boolean valid = switch (from) {
            case WAITING_FOR_CONSOLIDATION -> to == OrderStatus.PENDING || to == OrderStatus.CANCELLED;
            case PENDING    -> to == OrderStatus.CONFIRMED || to == OrderStatus.CANCELLED;
            case CONFIRMED  -> to == OrderStatus.PACKED || to == OrderStatus.CANCELLED;
            case PACKED     -> (type == OrderType.DELIVERY && to == OrderStatus.SHIPPING)
                             || (type == OrderType.BOPIS && to == OrderStatus.READY_FOR_PICKUP)
                             || to == OrderStatus.CANCELLED;
            case SHIPPING         -> to == OrderStatus.DELIVERED || to == OrderStatus.RETURNED;
            case READY_FOR_PICKUP -> to == OrderStatus.DELIVERED || to == OrderStatus.CANCELLED;
            case DELIVERED  -> to == OrderStatus.RETURNED;
            default -> false; // CANCELLED, RETURNED là trạng thái cuối (terminal)
        };
        if (!valid) {
            throw new IllegalStateException(
                    String.format("Không thể chuyển trạng thái từ %s sang %s (loại đơn: %s)", from, to, type)
            );
        }
    }
}
