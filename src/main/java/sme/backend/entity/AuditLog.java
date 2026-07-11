
package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * Nhật ký hành động thủ công (manual audit trail).
 *
 * LƯU Ý: bảng audit_logs đã tồn tại sẵn trong schema (database.txt) với đầy đủ
 * cột action/entity_type/old_value/new_value... nhưng trước đây KHÔNG có entity
 * Java nào trỏ vào - tức là bảng này chưa từng được ghi dữ liệu. AuditLogService
 * cũ chỉ đọc các bảng *_audit do Hibernate Envers tự sinh (snapshot toàn bộ field
 * khi entity thay đổi), không có khái niệm "ghi 1 dòng log có ngữ nghĩa nghiệp vụ"
 * như "Admin huỷ khẩn cấp đơn X vì lý do Y".
 *
 * Entity này lấp đúng vào chỗ trống đó, dùng cho các hành động cần audit trail
 * rõ ràng, có thể tra cứu nhanh: force-cancel đơn hàng, tái phân công chi nhánh...
 * Envers vẫn tiếp tục chạy song song (ghi lại giá trị field thay đổi trên Order),
 * còn bảng này ghi lại NGỮ CẢNH hành động (ai, hành động gì, lý do gì).
 */
@Entity
@Table(name = "audit_logs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AuditLog extends BaseSimpleEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(length = 100)
    private String username;

    /** Ví dụ: FORCE_CANCEL_ORDER, REASSIGN_ORDER, CONFIRM_ORDER */
    @Column(nullable = false, length = 100)
    private String action;

    /** Ví dụ: ORDER, SHIFT, WAREHOUSE */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", length = 255)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private Map<String, Object> oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private Map<String, Object> newValue;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "warehouse_id")
    private UUID warehouseId;
}
