package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateOrderRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.OrderResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.entity.Order;
import sme.backend.entity.User;
import sme.backend.exception.BusinessException;
import sme.backend.security.UserPrincipal;
import sme.backend.service.OrderService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(orderService.createOrder(req)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> getOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID warehouseId) {

        // Cashier và Manager không được dùng param warehouseId để xem kho khác
        UUID wid = (principal.getRole() == User.UserRole.ROLE_ADMIN)
                ? warehouseId : principal.getWarehouseId();

        if (principal.getRole() != User.UserRole.ROLE_ADMIN && wid == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán chi nhánh.");
        }

        Order.OrderStatus orderStatus = null;
        if (status != null && !status.isBlank() && !status.equals("ALL")) {
            try { orderStatus = Order.OrderStatus.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        Order.OrderType orderType = null;
        if (type != null && !type.isBlank() && !type.equals("ALL")) {
            try { orderType = Order.OrderType.valueOf(type.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(orderService.getOrders(
                        wid, orderStatus, orderType, keyword, pageable, principal.getRole()))));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getPendingOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {
        UUID wid = (principal.getRole() == User.UserRole.ROLE_ADMIN) && warehouseId != null
                ? warehouseId : principal.getWarehouseId();
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.getPendingOrders(wid, principal.getRole())));
    }

    /**
     * [FIX-4] Truyền thêm principal.getWarehouseId() để OrderService kiểm tra
     * Cashier/Manager chỉ xem đơn của chi nhánh mình.
     * Trước đây không truyền → bất kỳ Cashier nào cũng có thể xem mọi đơn.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.getOrderDetail(id, principal.getRole(), principal.getWarehouseId())));
    }

    @PostMapping("/suggest-branch")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> suggestBranchForOrder(
            @RequestBody CreateOrderRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.suggestBranchesForOrder(req.getProvinceCode(), req.getItems())));
    }

    // ─── 8 endpoints thay cho PATCH /{id}/status cũ ──────────────────────

    @PatchMapping("/{id}/confirm")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok("Đã xác nhận đơn hàng",
                orderService.confirmOrder(id, note, principal)));
    }

    @PatchMapping("/{id}/pack")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> pack(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok("Đã đóng gói đơn hàng",
                orderService.packOrder(id, note, principal)));
    }

    @PatchMapping("/{id}/ship")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> ship(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = body != null ? body.get("note") : null;
        String trackingCode = body != null ? body.get("trackingCode") : null;
        String shippingProvider = body != null ? body.get("shippingProvider") : null;
        return ResponseEntity.ok(ApiResponse.ok("Đã chuyển sang giao hàng",
                orderService.shipOrder(id, trackingCode, shippingProvider, note, principal)));
    }

    @PatchMapping("/{id}/mark-ready")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> markReady(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok("Đơn đã sẵn sàng cho khách lấy",
                orderService.markReadyForPickup(id, note, principal)));
    }

    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> complete(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok("Đơn hàng đã hoàn tất",
                orderService.completeOrder(id, note, principal)));
    }

    @PatchMapping("/{id}/return")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> returnOrder(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Đã ghi nhận hoàn trả",
                orderService.returnOrder(id, body.get("reason"), principal)));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<OrderResponse>> cancel(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Đã hủy đơn hàng",
                orderService.cancelOrder(id, body.get("reason"), principal)));
    }

    @PatchMapping("/{id}/force-cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> forceCancel(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Đã hủy khẩn cấp đơn hàng",
                orderService.forceCancelOrder(id, body.get("reason"), principal)));
    }

    @PatchMapping("/{id}/reassign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> reassign(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID newWarehouseId = UUID.fromString(body.get("warehouseId"));
        return ResponseEntity.ok(ApiResponse.ok("Đã tái phân công chi nhánh",
                orderService.reassignOrder(id, newWarehouseId, principal)));
    }
}
