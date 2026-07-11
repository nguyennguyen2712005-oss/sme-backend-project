package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private UUID id;             // [NEW] null cho field-change (Envers không có 1 ID cố định)
    private String entityName;   // Tên bảng/đối tượng (VD: Sản phẩm, Đơn hàng)
    private UUID entityId;       // ID của đối tượng bị thay đổi
    private String actionType;   // CREATE, UPDATE, DELETE, hoặc tên action cụ thể (FORCE_CANCEL_ORDER...)
    private String changedBy;    // Người thực hiện
    private Instant changedAt;   // Thời gian thực hiện
    private Integer revision;    // Số thứ tự phiên bản (chỉ có ở field-change, null cho business action)

    // [NEW] Chi tiết dữ liệu cũ/mới — chỉ có ở business action (audit_logs table)
    private String oldValueJson; // JSON string, null nếu không áp dụng
    private String newValueJson;

    // [NEW] warehouseId của actor thực hiện hành động (để Admin filter theo chi nhánh)
    private UUID warehouseId;

    /** "BUSINESS_ACTION" (từ bảng audit_logs) hoặc "FIELD_CHANGE" (từ Envers) */
    private String source;
}
