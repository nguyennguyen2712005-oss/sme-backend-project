package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class StockTakeResponse {

    private UUID id;
    private String code;
    private UUID warehouseId;
    private String warehouseName;

    private UUID createdBy;
    private String createdByName;

    private UUID approvedBy;
    private String approvedByName;

    /** DRAFT | IN_PROGRESS | COMPLETED | APPROVED | CANCELLED */
    private String status;
    private String note;

    private Instant createdAt;
    private Instant completedAt;

    /** Tổng số sản phẩm trong phiếu */
    private int totalItems;

    /** Số sản phẩm có chênh lệch */
    private int discrepancyItems;

    private Boolean hasDiscrepancy;

    /** Rỗng trong list view, đầy đủ trong detail view */
    private List<ItemResponse> items;

    @Data
    @Builder
    public static class ItemResponse {
        private UUID id;
        private UUID productId;
        private String productName;
        private String isbnBarcode;

        /** Số tồn kho theo hệ thống tại thời điểm tạo phiếu */
        private Integer systemQuantity;

        /** Số đếm thực tế — null nếu chưa nhập */
        private Integer actualQuantity;

        /** actual - system (null nếu actualQuantity chưa nhập) */
        private Integer discrepancy;
    }
}
