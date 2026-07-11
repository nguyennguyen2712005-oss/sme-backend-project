package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import sme.backend.entity.Promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PromotionResponse {
    private UUID id;
    private String code;
    private String name;
    private String type;           // PERCENT | FIXED_AMOUNT
    private BigDecimal value;
    private BigDecimal minOrderValue;
    private BigDecimal maxDiscount;
    private Integer usageLimit;
    private Integer usedCount;
    private Instant startDate;
    private Instant endDate;
    private String applicableTo;   // ALL | POS | ONLINE
    private Boolean isActive;
    private Instant createdAt;

    /** Tính toán từ frontend để hiện thị dễ hơn */
    private boolean isExpired;
    private boolean isValid;
    private int remainingUses;

    /** Chỉ có khi gọi /validate — số tiền giảm thực tế */
    private BigDecimal discountAmount;
}
