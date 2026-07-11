package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.StockTakeRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.StockTakeResponse;
import sme.backend.security.UserPrincipal;
import sme.backend.service.StockTakeService;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * StockTakeController — Module Kiểm kê
 * Chỉ MANAGER và ADMIN. Cashier bị chặn hoàn toàn.
 */
@RestController
@RequestMapping("/stock-takes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")  // class-level: tất cả method đều require
public class StockTakeController {

    private final StockTakeService stockTakeService;

    /** Danh sách phiếu kiểm kê */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<StockTakeResponse>>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(stockTakeService.getStockTakes(warehouseId, principal, pageable))));
    }

    /** Chi tiết một phiếu */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StockTakeResponse>> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(stockTakeService.getStockTake(id, principal)));
    }

    /** Tạo phiếu kiểm kê mới (DRAFT) */
    @PostMapping
    public ResponseEntity<ApiResponse<StockTakeResponse>> create(
            @Valid @RequestBody StockTakeRequest.Create req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(stockTakeService.createStockTake(req, principal)));
    }

    /** Thêm sản phẩm vào phiếu DRAFT */
    @PostMapping("/{id}/products")
    public ResponseEntity<ApiResponse<StockTakeResponse>> addProducts(
            @PathVariable UUID id,
            @RequestBody Map<String, List<UUID>> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<UUID> productIds = body.get("productIds");
        return ResponseEntity.ok(ApiResponse.ok(
                stockTakeService.addProducts(id, productIds, principal)));
    }

    /** Xóa một sản phẩm khỏi phiếu DRAFT */
    @DeleteMapping("/{id}/products/{productId}")
    public ResponseEntity<ApiResponse<StockTakeResponse>> removeProduct(
            @PathVariable UUID id,
            @PathVariable UUID productId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                stockTakeService.removeProduct(id, productId, principal)));
    }

    /** Bắt đầu đếm (DRAFT → IN_PROGRESS) */
    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<StockTakeResponse>> start(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Bắt đầu kiểm kê", stockTakeService.startCounting(id, principal)));
    }

    /** Nhập số lượng thực tế (nhiều sản phẩm một lần) */
    @PatchMapping("/{id}/count")
    public ResponseEntity<ApiResponse<StockTakeResponse>> updateCount(
            @PathVariable UUID id,
            @RequestBody List<StockTakeRequest.ItemCount> counts,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                stockTakeService.updateActualQuantity(id, counts, principal)));
    }

    /** Hoàn thành kiểm kê (IN_PROGRESS → COMPLETED) */
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<StockTakeResponse>> complete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Hoàn thành kiểm kê", stockTakeService.completeStockTake(id, principal)));
    }

    /** Duyệt và áp dụng điều chỉnh kho (COMPLETED → APPROVED) */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<StockTakeResponse>> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = (body != null) ? body.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok(
                "Duyệt kiểm kê thành công — tồn kho đã được điều chỉnh",
                stockTakeService.approveStockTake(id, note, principal)));
    }

    /** Hủy phiếu (không ảnh hưởng tồn kho) */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<StockTakeResponse>> cancel(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Đã hủy phiếu kiểm kê",
                stockTakeService.cancelStockTake(id, body.get("reason"), principal)));
    }
}
