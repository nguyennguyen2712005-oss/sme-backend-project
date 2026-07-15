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
import sme.backend.service.PaymentTransactionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class POSController {

    private final ShiftService shiftService;
    private final POSService posService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentTransactionService paymentTransactionService; // [MỚI]

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
            return ResponseEntity.ok(ApiResponse.ok(shiftService.mapToResponse(shift)));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
    }

    // ─── Ca chờ duyệt ────────────────────────────────────────
    @GetMapping("/shifts/pending")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> getPendingShifts(
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID warehouseId = (principal.getRole() == User.UserRole.ROLE_ADMIN)
                ? null : principal.getWarehouseId();
        return ResponseEntity.ok(ApiResponse.ok(shiftService.getPendingShifts(warehouseId)));
    }

    // ─── Duyệt ca ────────────────────────────────────────────
    @PostMapping("/shifts/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> approveShift(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        ShiftResponse shift = shiftService.approveShift(
                id,
                principal.getId(),
                principal.getWarehouseId(),  
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

    // [FIX-A3] Rút gọn, uỷ quyền cho Service xử lý để lấy đủ tên SP/Khách hàng
    @GetMapping("/invoices/code/{code}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoiceByCode(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(posService.getInvoiceByCode(code)));
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

    // ─── [MỚI] Thanh toán QR payOS ───────────────────────────
    @PostMapping("/payments/qr")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<sme.backend.dto.response.PaymentQrResponse>> createPaymentQr(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CheckoutRequest req) {

        if (principal.getWarehouseId() == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán chi nhánh");
        }
        var qr = paymentTransactionService.createQr(req, principal.getId(), principal.getWarehouseId());
        return ResponseEntity.ok(ApiResponse.ok(qr));
    }

    @GetMapping("/payments/{code}/status")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<sme.backend.dto.response.PaymentQrResponse>> getPaymentStatus(
            @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(paymentTransactionService.getStatus(code)));
    }

    @PostMapping("/payments/{code}/cancel")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<Void>> cancelPaymentQr(@PathVariable String code) {
        paymentTransactionService.cancel(code);
        return ResponseEntity.ok(ApiResponse.ok("Đã huỷ giao dịch", null));
    }

    // [PUBLIC] payOS gọi endpoint này khi có biến động số dư — KHÔNG qua JWT.
    @PostMapping("/payments/webhook")
    public ResponseEntity<Map<String, Object>> payosWebhook(@RequestBody Map<String, Object> payload) {
        paymentTransactionService.handleWebhook(payload);
        return ResponseEntity.ok(Map.of("success", true));
    }
}