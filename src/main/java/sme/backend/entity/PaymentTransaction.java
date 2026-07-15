package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentTransaction extends BaseSimpleEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "payos_order_code", nullable = false, unique = true)
    private Long payosOrderCode;

    @Column(name = "payos_payment_link_id")
    private String payosPaymentLinkId;

    @Column(name = "shift_id", nullable = false)
    private UUID shiftId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "cart_snapshot", columnDefinition = "TEXT", nullable = false)
    private String cartSnapshot;

    @Column(name = "qr_code", columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    private String reference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    public enum Status { PENDING, PAID, CANCELLED, EXPIRED }
}