package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data 
@Builder
@NoArgsConstructor // <--- THÊM DÒNG NÀY ĐỂ JACKSON CÓ THỂ KHỞI TẠO OBJECT
@AllArgsConstructor // <--- THÊM DÒNG NÀY (Bắt buộc phải có khi dùng chung @Builder và @NoArgsConstructor)
public class ProductResponse {
    private UUID id;
    private UUID categoryId;
    private String categoryName;
    private UUID supplierId;
    private String isbnBarcode;
    private String sku;
    private String name;
    private String description;
    private BigDecimal retailPrice;
    private BigDecimal wholesalePrice;
    private BigDecimal macPrice;
    private String imageUrl;
    private String unit;
    private BigDecimal weight;
    private Boolean isActive;
    private Instant createdAt;
    private Integer availableQuantity;
}