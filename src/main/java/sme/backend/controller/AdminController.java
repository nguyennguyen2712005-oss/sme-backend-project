package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.service.AuditLogService;

import java.time.Instant;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuditLogService auditLogService;

    /**
     * GET /admin/audit-logs
     * [FIX] Nhật ký toàn hệ thống — giờ bao gồm CẢ business actions (audit_logs
     * table, có oldValue/newValue) LẪN field-changes (Envers). Hỗ trợ filter +
     * phân trang để Admin truy cứu trách nhiệm hiệu quả hơn limit cứng cũ.
     *
     * Chỉ Admin mới được xem (Admin spec, mục IV.5 "Độc quyền Giám sát").
     */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AuditLogService.AuditLogPageResult>> getAuditLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String changedBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {

        var filter = new AuditLogService.AuditLogFilter(
                entityType, action, changedBy, fromDate, toDate);
        var result = auditLogService.getGlobalAuditLogs(filter, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Lấy nhật ký hệ thống thành công", result));
    }
}
