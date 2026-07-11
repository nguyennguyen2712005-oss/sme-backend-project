package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.request.CloseShiftRequest;
import sme.backend.dto.request.OpenShiftRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.InvoiceResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.ShiftResponse;
import sme.backend.entity.User;
import sme.backend.exception.BusinessException;
import sme.backend.repository.InvoiceRepository;
import sme.backend.security.UserPrincipal;
import sme.backend.service.POSService;
import sme.backend.service.ShiftService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class POSController {

    private final ShiftService shiftService;
    private final POSService posService;
    private final InvoiceRepository invoiceRepository;

    // ─── Mở ca ───────────────────────────────────────────────
    @PostMapping("/shifts/open")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> openShift(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody OpenShiftRequest req) {

        if (principal.getWarehouseId() == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán vào chi nhánh");
        }
        ShiftResponse shift = shiftService.openShift(
                principal.getId(), principal.getWarehouseId(), req);
        return ResponseEntity.ok(ApiResponse.ok("Mở ca thành công", shift));
    }

    // ─── Đóng ca mù (Blind Close) ────────────────────────────
    @PostMapping("/shifts/close")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> closeShift(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CloseShiftRequest req) {

        ShiftResponse shift = shiftService.closeShift(principal.getId(), req);

        // [FIX-3] Blind Close: Thu ngân KHÔNG được biết số tiền lý thuyết và
        // chênh lệch ngay sau khi đóng ca. Chỉ Manager/Admin thấy được sau khi duyệt.
        // Mask tại Controller — data không bao giờ rời server đến Client Cashier.
        if (principal.getRole() == User.UserRole.ROLE_CASHIER) {
            shift.setTheoreticalCash(null);
            shift.setDiscrepancyAmount(null);
        }

        return ResponseEntity.ok(ApiResponse.ok("Đóng ca thành công", shift));
    }

    // ─── Trạng thái ca hiện tại ──────────────────────────────
    @GetMapping("/shifts/current")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> getCurrentShift(
            @AuthenticationPrincipal UserPrincipal principal) {
        try {
            var shift = shiftService.getOpenShiftByCashier(principal.getId());
            // Thu ngân xem trạng thái ca ĐANG MỞ — không cần mask (chưa có theoretical)
            return ResponseEntity.ok(ApiResponse.ok(shiftService.mapToResponse(shift)));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
    }

    // ─── Ca chờ duyệt ────────────────────────────────────────
    /**
     * [FIX-6] Admin cần truyền warehouseId = null để xem toàn bộ.
     * ShiftService.getPendingShifts(null) giờ dùng findAllByStatus() thay vì
     * findByWarehouseIdAndStatus(null, CLOSED) mà trước đây trả về rỗng.
     */
    @GetMapping("/shifts/pending")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> getPendingShifts(
            @AuthenticationPrincipal UserPrincipal principal) {
        // Admin: warehouseId = null → xem tất cả chi nhánh
        // Manager: warehouseId = chi nhánh của mình
        UUID warehouseId = (principal.getRole() == User.UserRole.ROLE_ADMIN)
                ? null : principal.getWarehouseId();
        return ResponseEntity.ok(ApiResponse.ok(shiftService.getPendingShifts(warehouseId)));
    }

    // ─── Duyệt ca ────────────────────────────────────────────
    /**
     * [FIX-5] Truyền thêm warehouseId và role của người duyệt vào ShiftService
     * để Service kiểm tra Manager không duyệt ca của chi nhánh khác.
     *
     * [FIX-ADMIN-SPEC] Chỉ MANAGER được duyệt ca — Admin ngồi trụ sở không thể
     * kiểm đếm tiền mặt thực tế thay Manager chi nhánh. Trước đây endpoint này
     * cho phép cả ADMIN, vi phạm Admin spec mục II "NHỮNG VIỆC ADMIN BỊ CẤM LÀM"
     * ("Không được Duyệt chốt ca — Admin chỉ xem báo cáo, không bấm nút duyệt thay").
     */
    @PostMapping("/shifts/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> approveShift(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        ShiftResponse shift = shiftService.approveShift(
                id,
                principal.getId(),
                principal.getWarehouseId(),  // null nếu là Admin
                principal.getRole());
        return ResponseEntity.ok(ApiResponse.ok("Duyệt ca thành công", shift));
    }

    // ─── Checkout ────────────────────────────────────────────
    @PostMapping("/checkout")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> checkout(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CheckoutRequest req) {

        if (principal.getWarehouseId() == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán chi nhánh");
        }
        InvoiceResponse invoice = posService.checkout(
                req, principal.getId(), principal.getWarehouseId());
        return ResponseEntity.ok(ApiResponse.ok("Thanh toán thành công", invoice));
    }

    // ─── Hóa đơn ─────────────────────────────────────────────
    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoice(@PathVariable UUID id) {
        var invoice = invoiceRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new sme.backend.exception.ResourceNotFoundException("Invoice", id));
        return ResponseEntity.ok(ApiResponse.ok(
                InvoiceResponse.builder()
                        .id(invoice.getId())
                        .code(invoice.getCode())
                        .type(invoice.getType().name())
                        .totalAmount(invoice.getTotalAmount())
                        .discountAmount(invoice.getDiscountAmount())
                        .finalAmount(invoice.getFinalAmount())
                        .pointsUsed(invoice.getPointsUsed())
                        .pointsEarned(invoice.getPointsEarned())
                        .createdAt(invoice.getCreatedAt())
                        .build()
        ));
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<InvoiceResponse>>> getInvoicesByShift(
            @RequestParam UUID shiftId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var paged = invoiceRepository.findByShiftIdOrderByCreatedAtDesc(
                shiftId, PageRequest.of(page, size));
        var mapped = paged.map(inv -> InvoiceResponse.builder()
                .id(inv.getId())
                .code(inv.getCode())
                .type(inv.getType().name())
                .totalAmount(inv.getTotalAmount())
                .finalAmount(inv.getFinalAmount())
                .customerId(inv.getCustomerId())
                .createdAt(inv.getCreatedAt())
                .build());
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(mapped)));
    }

    @GetMapping("/invoices/code/{code}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoiceByCode(@PathVariable String code) {
        var invoice = invoiceRepository.findByCode(code)
                .orElseThrow(() -> new sme.backend.exception.ResourceNotFoundException(
                        "Không tìm thấy hóa đơn mã: " + code));

        List<InvoiceResponse.ItemResponse> items = invoice.getItems().stream()
                .map(i -> InvoiceResponse.ItemResponse.builder()
                        .productId(i.getProductId())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .macPrice(i.getMacPrice())
                        .subtotal(i.getSubtotal())
                        .build()).toList();

        return ResponseEntity.ok(ApiResponse.ok(
                InvoiceResponse.builder()
                        .id(invoice.getId())
                        .code(invoice.getCode())
                        .type(invoice.getType().name())
                        .totalAmount(invoice.getTotalAmount())
                        .finalAmount(invoice.getFinalAmount())
                        .createdAt(invoice.getCreatedAt())
                        .items(items)
                        .build()
        ));
    }

    // ─── Trả hàng ─────────────────────────────────────────────
    public static class RefundRequestDTO {
        private UUID originalInvoiceId;
        private UUID shiftId;
        private List<POSService.RefundItem> items;
        private String returnDestination;
        private String note;

        public UUID getOriginalInvoiceId() { return originalInvoiceId; }
        public void setOriginalInvoiceId(UUID originalInvoiceId) { this.originalInvoiceId = originalInvoiceId; }
        public UUID getShiftId() { return shiftId; }
        public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }
        public List<POSService.RefundItem> getItems() { return items; }
        public void setItems(List<POSService.RefundItem> items) { this.items = items; }
        public String getReturnDestination() { return returnDestination; }
        public void setReturnDestination(String returnDestination) { this.returnDestination = returnDestination; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    @PostMapping("/refund")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> refund(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody RefundRequestDTO req) {

        if (principal.getWarehouseId() == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán chi nhánh");
        }

        InvoiceResponse invoice = posService.refund(
                req.getOriginalInvoiceId(), req.getShiftId(), req.getItems(),
                req.getReturnDestination(), principal.getId(),
                principal.getWarehouseId(), req.getNote());

        return ResponseEntity.ok(ApiResponse.ok("Trả hàng thành công", invoice));
    }
}
