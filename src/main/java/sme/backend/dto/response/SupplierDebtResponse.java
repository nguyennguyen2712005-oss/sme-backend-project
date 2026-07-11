
package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class SupplierDebtResponse {
    private UUID id;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private String purchaseOrderCode; // Thêm mã PO cho đẹp
    private UUID warehouseId;
    private String warehouseName;     // Hiển thị tên kho
    private BigDecimal totalDebt;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String status;
    private LocalDate dueDate;
    private Instant createdAt;
}
