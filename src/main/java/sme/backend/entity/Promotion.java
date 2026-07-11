package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Promotion — Chương trình khuyến mãi / Mã giảm giá
 *
 * Hai loại:
 *   PERCENT      — giảm theo % (vd: giảm 10%). Nếu có maxDiscount, cap lại.
 *   FIXED_AMOUNT — giảm số tiền cố định (vd: giảm 50.000đ).
 *
 * Phạm vi áp dụng (applicable_to):
 *   ALL    — cả POS lẫn Online
 *   POS    — chỉ bán hàng tại quầy
 *   ONLINE — chỉ đơn hàng online
 *
 * Validation khi áp dụng mã:
 *   1. isActive = true
 *   2. now() BETWEEN startDate AND endDate
 *   3. Tổng đơn >= minOrderValue
 *   4. usageLimit IS NULL OR usedCount < usageLimit
 */
@Entity
@Table(name = "promotions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Mã coupon (VD: "SUMMER30", "NEWBOOK50"). Unique, không phân biệt HOA thường */
    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    /** Tên mô tả (VD: "Giảm 30% mùa hè") */
    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private PromotionType type;

    /**
     * Giá trị giảm:
     * - PERCENT: 10.0 = 10%
     * - FIXED_AMOUNT: 50000 = 50.000đ
     */
    @Column(name = "value", nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    /** Giá trị đơn hàng tối thiểu để áp dụng mã */
    @Column(name = "min_order_value", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal minOrderValue = BigDecimal.ZERO;

    /** Cap giảm giá tối đa (chỉ dùng cho PERCENT). Null = không giới hạn */
    @Column(name = "max_discount", precision = 19, scale = 4)
    private BigDecimal maxDiscount;

    /** Số lần dùng tối đa. Null = không giới hạn */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    /** Số lần đã dùng — tăng dần mỗi lần apply thành công */
    @Column(name = "used_count", nullable = false)
    @Builder.Default
    private Integer usedCount = 0;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "end_date", nullable = false)
    private Instant endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "applicable_to", length = 50)
    @Builder.Default
    private ApplicableTo applicableTo = ApplicableTo.ALL;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    // ─── Domain logic ───────────────────────────────────────

    public enum PromotionType { PERCENT, FIXED_AMOUNT }
    public enum ApplicableTo  { ALL, POS, ONLINE }

    /**
     * Tính số tiền giảm cho đơn hàng có tổng = orderTotal.
     * Không tự validate, gọi validate() trước.
     */
    public BigDecimal calculateDiscount(BigDecimal orderTotal) {
        BigDecimal discount;
        if (this.type == PromotionType.PERCENT) {
            discount = orderTotal.multiply(this.value)
                    .divide(BigDecimal.valueOf(100));
            if (this.maxDiscount != null && discount.compareTo(this.maxDiscount) > 0) {
                discount = this.maxDiscount;
            }
        } else {
            discount = this.value;
        }
        // Không giảm nhiều hơn tổng đơn
        return discount.min(orderTotal);
    }

    /** Tăng usedCount sau khi áp dụng thành công */
    public void incrementUsage() {
        this.usedCount = (this.usedCount == null ? 0 : this.usedCount) + 1;
    }

    /** Kiểm tra mã còn hiệu lực không */
    public boolean isValidNow(Instant now) {
        if (Boolean.FALSE.equals(this.isActive)) return false;
        if (now.isBefore(this.startDate) || now.isAfter(this.endDate)) return false;
        if (this.usageLimit != null && this.usedCount >= this.usageLimit) return false;
        return true;
    }
}
