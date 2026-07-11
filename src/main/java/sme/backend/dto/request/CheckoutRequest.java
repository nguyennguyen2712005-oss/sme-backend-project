package sme.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CheckoutRequest — Body của POST /pos/checkout
 *
 * [NEW] Thêm trường promotionCode để apply mã khuyến mãi khi thanh toán.
 * Nếu cả promotionCode lẫn pointsToUse đều có giá trị, hệ thống ưu tiên
 * promotionCode trước rồi mới áp điểm.
 */
@Data
public class CheckoutRequest {

    @NotNull(message = "shiftId bắt buộc")
    private UUID shiftId;

    private UUID customerId;

    @NotEmpty(message = "Giỏ hàng không được rỗng")
    @Valid
    private List<CartItemRequest> items;

    @Valid
    @NotEmpty(message = "Phương thức thanh toán bắt buộc")
    private List<PaymentRequest> payments;

    /** Số điểm tích lũy muốn đổi (bội số 500). 0 = không dùng điểm */
    private Integer pointsToUse;

    /**
     * [NEW] Mã khuyến mãi (coupon code). Null / blank = không áp mã.
     * Backend gọi PromotionService.applyPromotion() để validate + tính discount.
     */
    private String promotionCode;

    private String note;

    // ─── Nested DTOs ────────────────────────────────────────

    @Data
    public static class CartItemRequest {
        @NotNull
        private UUID productId;

        @Min(1)
        private int quantity;

        @NotNull
        private BigDecimal unitPrice;
    }

    @Data
    public static class PaymentRequest {
        @NotNull
        private String method; // CASH | CARD | MOMO | ...

        @NotNull
        @Min(0)
        private BigDecimal amount;

        private String reference; // Mã giao dịch chuyển khoản / ví điện tử
    }
}
