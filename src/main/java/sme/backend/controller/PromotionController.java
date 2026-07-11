package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.PromotionRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.PromotionResponse;
import sme.backend.service.PromotionService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PromotionController — Quản lý chương trình khuyến mãi
 *
 * Phân quyền:
 *   GET  /promotions/active   — Cashier, Manager, Admin (để hiện dropdown trong POS)
 *   POST /promotions/validate — Cashier, Manager, Admin (preview discount trước khi tính tiền)
 *   GET  /promotions/**       — Manager, Admin (xem danh sách, chi tiết)
 *   POST/PUT/DELETE           — Admin only (quản lý)
 */
@RestController
@RequestMapping("/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    // ─── Public-ish (cần login, Cashier cũng dùng được) ────

    /** Danh sách mã đang hoạt động — cho POS dropdown */
    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<PromotionResponse>>> listActive() {
        return ResponseEntity.ok(ApiResponse.ok(promotionService.listActive()));
    }

    /**
     * Validate mã và xem trước số tiền giảm — KHÔNG tăng usedCount.
     * Body: { "code": "SUMMER30", "orderTotal": 500000, "channel": "POS" }
     */
    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PromotionResponse>> validate(
            @Valid @RequestBody PromotionRequest.ValidateCode req) {
        return ResponseEntity.ok(ApiResponse.ok(
                promotionService.validateCode(req.getCode(), req.getOrderTotal(), req.getChannel())));
    }

    // ─── Manager + Admin ────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<PromotionResponse>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(promotionService.list(keyword, pageable))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PromotionResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(promotionService.getById(id)));
    }

    // ─── Admin only ─────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PromotionResponse>> create(
            @Valid @RequestBody PromotionRequest.Create req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(promotionService.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PromotionResponse>> update(
            @PathVariable UUID id,
            @RequestBody PromotionRequest.Update req) {
        return ResponseEntity.ok(ApiResponse.ok(promotionService.update(id, req)));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        promotionService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã tắt mã khuyến mãi", null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        promotionService.deactivate(id); // Soft delete bằng cách deactivate
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa mã khuyến mãi", null));
    }
}
