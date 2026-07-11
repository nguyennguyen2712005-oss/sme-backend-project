package sme.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.response.AuditLogResponse;
import sme.backend.entity.AuditLog;
import sme.backend.repository.AuditLogRepository;
import sme.backend.security.UserPrincipal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * AuditLogService — Nhật ký hệ thống cho Admin.
 *
 * [FIX - Admin spec] Trước đây getGlobalAuditLogs() CHỈ query 4 bảng Envers
 * (users_audit, products_audit, warehouses_audit, orders_audit) và KHÔNG BAO GIỜ
 * đọc bảng audit_logs — nơi logAction() ghi các hành động nghiệp vụ nhạy cảm
 * (FORCE_CANCEL_ORDER, REASSIGN_ORDER, APPROVE_STOCK_TAKE...).
 *
 * Hậu quả: Lý do Admin hủy khẩn cấp đơn hàng, dữ liệu cũ/mới của reassign —
 * tất cả bị lưu vào DB nhưng KHÔNG BAO GIỜ hiển thị cho Admin xem lại.
 * Vi phạm trực tiếp yêu cầu "Admin dùng cái này để truy cứu trách nhiệm".
 *
 * Đã sửa: UNION thêm bảng audit_logs vào kết quả, trả về cả oldValue/newValue,
 * thêm phân trang + filter theo entityType/action/changedBy/khoảng thời gian.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final EntityManager entityManager;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void logAction(UserPrincipal actor, String action, String entityType, String entityId,
                           Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            AuditLog logEntry = AuditLog.builder()
                    .userId(actor != null ? actor.getId() : null)
                    .username(actor != null ? actor.getUsername() : "SYSTEM")
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .oldValue(oldValue)
                    .newValue(newValue)
                    .warehouseId(actor != null ? actor.getWarehouseId() : null)
                    .build();
            auditLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("Không ghi được audit log cho action={}, entity={}/{}: {}",
                    action, entityType, entityId, e.getMessage());
        }
    }

    /**
     * [FIX] Nhật ký toàn hệ thống — bao gồm CẢ Envers (field-level changes) LẪN
     * bảng audit_logs (business actions có oldValue/newValue chi tiết).
     *
     * Hỗ trợ filter theo entityType, action, changedBy, khoảng ngày, và phân trang.
     */
    @Transactional(readOnly = true)
    public AuditLogPageResult getGlobalAuditLogs(AuditLogFilter filter, int page, int size) {
        List<AuditLogResponse> allLogs = new ArrayList<>();
        allLogs.addAll(fetchExplicitAuditLogs(filter));
        allLogs.addAll(fetchEnversAuditLogs(filter));

        // Sắp xếp mới nhất trước
        allLogs.sort((a, b) -> {
            if (a.getChangedAt() == null) return 1;
            if (b.getChangedAt() == null) return -1;
            return b.getChangedAt().compareTo(a.getChangedAt());
        });

        int total = allLogs.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AuditLogResponse> pageContent = allLogs.subList(from, to);

        return new AuditLogPageResult(pageContent, total,
                (int) Math.ceil((double) total / size), page);
    }

    /** Giữ lại signature cũ cho code đang gọi limit đơn giản (backward-compat) */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getGlobalAuditLogs(int limit) {
        return getGlobalAuditLogs(new AuditLogFilter(), 0, limit).content();
    }

    // ─────────────────────────────────────────────────────────
    // [NEW] Đọc từ bảng audit_logs — business actions có oldValue/newValue
    // ─────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<AuditLogResponse> fetchExplicitAuditLogs(AuditLogFilter filter) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, action, entity_type, entity_id, username,
                   old_value::text, new_value::text, created_at, warehouse_id
            FROM audit_logs
            WHERE 1=1
            """);
        Map<String, Object> params = new HashMap<>();

        if (filter.entityType() != null && !filter.entityType().isBlank()) {
            sql.append(" AND entity_type = :entityType");
            params.put("entityType", filter.entityType());
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            sql.append(" AND action = :action");
            params.put("action", filter.action());
        }
        if (filter.changedBy() != null && !filter.changedBy().isBlank()) {
            sql.append(" AND LOWER(username) LIKE LOWER(:changedBy)");
            params.put("changedBy", "%" + filter.changedBy() + "%");
        }
        if (filter.fromDate() != null) {
            sql.append(" AND created_at >= :fromDate");
            params.put("fromDate", Timestamp.from(filter.fromDate()));
        }
        if (filter.toDate() != null) {
            sql.append(" AND created_at <= :toDate");
            params.put("toDate", Timestamp.from(filter.toDate()));
        }
        sql.append(" ORDER BY created_at DESC LIMIT 500"); // safety cap trước khi merge+sort

        Query query = entityManager.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);

        List<Object[]> results = query.getResultList();
        List<AuditLogResponse> logs = new ArrayList<>();
        for (Object[] row : results) {
            logs.add(AuditLogResponse.builder()
                    .id((UUID) row[0])
                    .actionType((String) row[1])
                    .entityName(mapEntityTypeToVietnamese((String) row[2]))
                    .entityId(parseUuidSafe(row[3]))
                    .changedBy((String) row[4])
                    .oldValueJson((String) row[5])
                    .newValueJson((String) row[6])
                    .changedAt(toInstant(row[7]))
                    .warehouseId((UUID) row[8])
                    .source("BUSINESS_ACTION")
                    .build());
        }
        return logs;
    }

    // ─────────────────────────────────────────────────────────
    // Đọc từ bảng Envers — field-level snapshot changes
    // ─────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<AuditLogResponse> fetchEnversAuditLogs(AuditLogFilter filter) {
        // Nếu filter chỉ định entityType cụ thể và KHÔNG phải 1 trong 4 loại Envers,
        // bỏ qua Envers hoàn toàn để tránh quét dư thừa.
        Set<String> enversTypes = Set.of("Người dùng", "Sản phẩm", "Chi nhánh", "Đơn hàng");
        if (filter.entityType() != null && !filter.entityType().isBlank()
                && !enversTypes.contains(filter.entityType())) {
            return List.of();
        }

        String sql = """
            SELECT * FROM (
                SELECT 'Người dùng' as entity_name, id as entity_id, revtype,
                       COALESCE(updated_by, created_by, 'SYSTEM') as changed_by,
                       rev, COALESCE(updated_at, created_at) as changed_at
                FROM users_audit
                UNION ALL
                SELECT 'Sản phẩm' as entity_name, id as entity_id, revtype,
                       COALESCE(updated_by, created_by, 'SYSTEM') as changed_by,
                       rev, COALESCE(updated_at, created_at) as changed_at
                FROM products_audit
                UNION ALL
                SELECT 'Chi nhánh' as entity_name, id as entity_id, revtype,
                       COALESCE(updated_by, created_by, 'SYSTEM') as changed_by,
                       rev, COALESCE(updated_at, created_at) as changed_at
                FROM warehouses_audit
                UNION ALL
                SELECT 'Đơn hàng' as entity_name, id as entity_id, revtype,
                       COALESCE(updated_by, created_by, 'SYSTEM') as changed_by,
                       rev, COALESCE(updated_at, created_at) as changed_at
                FROM orders_audit
            ) AS combined_audit
            WHERE changed_at IS NOT NULL
            ORDER BY changed_at DESC
            LIMIT 500
            """;

        Query query = entityManager.createNativeQuery(sql);
        List<Object[]> results = query.getResultList();
        List<AuditLogResponse> logs = new ArrayList<>();

        for (Object[] row : results) {
            String entityName = (String) row[0];
            if (filter.entityType() != null && !filter.entityType().isBlank()
                    && !filter.entityType().equals(entityName)) {
                continue;
            }
            UUID entityId = (UUID) row[1];
            Number revtypeNum = (Number) row[2];
            String actionType = switch (revtypeNum.intValue()) {
                case 0 -> "CREATE";
                case 1 -> "UPDATE";
                case 2 -> "DELETE";
                default -> "UNKNOWN";
            };
            String changedBy = (String) row[3];

            if (filter.changedBy() != null && !filter.changedBy().isBlank()
                    && (changedBy == null || !changedBy.toLowerCase()
                            .contains(filter.changedBy().toLowerCase()))) {
                continue;
            }
            if (filter.action() != null && !filter.action().isBlank()
                    && !filter.action().equals(actionType)) {
                continue;
            }

            Integer revision = ((Number) row[4]).intValue();
            Instant changedAt = toInstant(row[5]);

            if (filter.fromDate() != null && changedAt != null && changedAt.isBefore(filter.fromDate())) continue;
            if (filter.toDate() != null && changedAt != null && changedAt.isAfter(filter.toDate())) continue;

            logs.add(AuditLogResponse.builder()
                    .entityName(entityName)
                    .entityId(entityId)
                    .actionType(actionType)
                    .changedBy(changedBy)
                    .revision(revision)
                    .changedAt(changedAt)
                    .source("FIELD_CHANGE")
                    .build());
        }
        return logs;
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────
    private Instant toInstant(Object val) {
        if (val == null) return null;
        if (val instanceof Timestamp ts) return ts.toInstant();
        if (val instanceof Date d) return d.toInstant();
        return null;
    }

    private UUID parseUuidSafe(Object val) {
        if (val == null) return null;
        try {
            return UUID.fromString(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String mapEntityTypeToVietnamese(String entityType) {
        if (entityType == null) return "Khác";
        return switch (entityType) {
            case "ORDER" -> "Đơn hàng";
            case "SHIFT" -> "Ca làm việc";
            case "STOCK_TAKE" -> "Kiểm kê";
            case "WAREHOUSE" -> "Chi nhánh";
            case "USER" -> "Người dùng";
            case "PRODUCT" -> "Sản phẩm";
            default -> entityType;
        };
    }

    // ─────────────────────────────────────────────────────────
    // DTOs nội bộ
    // ─────────────────────────────────────────────────────────
    public record AuditLogFilter(
            String entityType, String action, String changedBy,
            Instant fromDate, Instant toDate) {
        public AuditLogFilter() { this(null, null, null, null, null); }
    }

    public record AuditLogPageResult(
            List<AuditLogResponse> content, int totalElements,
            int totalPages, int currentPage) {}
}
