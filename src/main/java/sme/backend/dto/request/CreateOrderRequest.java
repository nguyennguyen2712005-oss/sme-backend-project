package sme.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreateOrderRequest {

    @NotNull private UUID customerId;
    @NotBlank private String shippingName;
    @NotBlank private String shippingPhone;
    @NotBlank private String shippingAddress;
    @NotBlank private String provinceCode;

    @NotEmpty @Valid
    private List<OrderItemRequest> items;

    @NotBlank private String paymentMethod;
    private String type;     // DELIVERY (default) | BOPIS
    private String note;
    private UUID assignedWarehouseId;
    
    // ĐÃ THÊM: 3 trường mới
    @Min(0) private BigDecimal shippingFee;
    private String promotionCode;
    private Integer pointsToUse;

    @Data
    public static class OrderItemRequest {
        @NotNull private UUID productId;
        @NotNull @Min(1) private Integer quantity;
    }
}