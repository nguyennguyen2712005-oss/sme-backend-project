package sme.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import sme.backend.entity.Promotion;

import java.math.BigDecimal;
import java.time.Instant;

public class PromotionRequest {

    @Data
    public static class Create {
        @NotBlank(message = "Mã khuyến mãi bắt buộc")
        @Pattern(regexp = "^[A-Z0-9_-]{3,20}$", message = "Mã chỉ chứa A-Z, 0-9, gạch dưới/ngang, 3-20 ký tự")
        private String code;

        @NotBlank(message = "Tên chương trình bắt buộc")
        private String name;

        @NotNull(message = "Loại khuyến mãi bắt buộc")
        private Promotion.PromotionType type;

        @NotNull(message = "Giá trị bắt buộc")
        @DecimalMin(value = "0.01", message = "Giá trị phải > 0")
        private BigDecimal value;

        @DecimalMin(value = "0", message = "Giá trị đơn tối thiểu không thể âm")
        private BigDecimal minOrderValue;

        /** Chỉ dùng cho PERCENT — null = không giới hạn */
        private BigDecimal maxDiscount;

        /** null = không giới hạn */
        @Min(value = 1, message = "Số lần dùng tối thiểu là 1")
        private Integer usageLimit;

        @NotNull(message = "Ngày bắt đầu bắt buộc")
        private Instant startDate;

        @NotNull(message = "Ngày kết thúc bắt buộc")
        private Instant endDate;

        private Promotion.ApplicableTo applicableTo;
    }

    @Data
    public static class Update {
        private String name;
        private BigDecimal value;
        private BigDecimal minOrderValue;
        private BigDecimal maxDiscount;
        private Integer usageLimit;
        private Instant startDate;
        private Instant endDate;
        private Promotion.ApplicableTo applicableTo;
        private Boolean isActive;
    }

    /** Validate mã từ POS / checkout */
    @Data
    public static class ValidateCode {
        @NotBlank
        private String code;
        @NotNull
        @DecimalMin("0")
        private BigDecimal orderTotal;
        /** "POS" hoặc "ONLINE" */
        private String channel;
    }
}
