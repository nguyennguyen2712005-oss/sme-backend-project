package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.config.AppProperties;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.response.InvoiceResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.InsufficientStockException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class POSService {

    private final ShiftService shiftService;
    private final InventoryService inventoryService;
    private final InventoryRepository inventoryRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final CashbookTransactionRepository cashbookRepository;
    private final AppProperties appProperties;
    private final PromotionService promotionService; 
    private final CodeGeneratorService codeGenerator; 
    private final UserRepository userRepository; // [FIX A3] để lấy fullName của cashier

    // ─────────────────────────────────────────────────────────
    // CHECKOUT
    // ─────────────────────────────────────────────────────────
    @Transactional
    public InvoiceResponse checkout(CheckoutRequest req, UUID cashierId, UUID warehouseId) {

        // 1. Validate shift đang OPEN
        Shift shift = shiftService.getOpenShiftByCashier(cashierId);
        if (!shift.getId().equals(req.getShiftId())) {
            throw new BusinessException("SHIFT_MISMATCH",
                    "shiftId không khớp với ca làm việc đang mở");
        }

        // 2. Load customer (optional)
        Customer customer = null;
        if (req.getCustomerId() != null) {
            customer = customerRepository.findById(req.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Customer", req.getCustomerId()));
        }

        // 3. Validate tồn kho TOÀN BỘ trước — Pessimistic Lock, chưa trừ
        for (CheckoutRequest.CartItemRequest cartItem : req.getItems()) {
            productRepository.findById(cartItem.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product", cartItem.getProductId()));
            Inventory inv = inventoryRepository
                    .findByProductAndWarehouseWithLock(cartItem.getProductId(), warehouseId)
                    .orElseThrow(() -> new InsufficientStockException(
                            "Không tìm thấy tồn kho cho sản phẩm " + cartItem.getProductId()));
            if (inv.getAvailableQuantity() < cartItem.getQuantity()) {
                throw new InsufficientStockException(
                        "Sản phẩm không đủ hàng. Khả dụng: " + inv.getAvailableQuantity());
            }
        }

        // 4-5. Tính tiền — dùng chung với previewFinalAmount() ở luồng tạo QR payOS.
        // commitPromotionUsage = true vì đây là checkout THẬT, cần tăng usedCount ngay.
        int pointsToUse = req.getPointsToUse() != null ? req.getPointsToUse() : 0;
        String promotionCode = req.getPromotionCode();
        PricingResult pricing = computePricing(
                req.getItems(), customer, pointsToUse, promotionCode, true);

        BigDecimal totalAmount    = pricing.totalAmount();
        BigDecimal discountAmount = pricing.discountAmount();
        BigDecimal finalAmount    = pricing.finalAmount();

        // 5. Validate tổng tiền thanh toán
        BigDecimal totalPaid = req.getPayments().stream()
                .map(CheckoutRequest.PaymentRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalPaid.compareTo(finalAmount) < 0) {
            throw new BusinessException("INSUFFICIENT_PAYMENT",
                    String.format("Tổng tiền thanh toán (%.0f) < Tổng hóa đơn (%.0f)",
                            totalPaid, finalAmount));
        }

        // 6. Build Invoice
        String invoiceNote = buildNote(req.getNote(), promotionCode, pointsToUse);
        Invoice invoice = Invoice.builder()
                .code(codeGenerator.nextInvoiceCode()) 
                .shiftId(shift.getId())
                .customerId(customer != null ? customer.getId() : null)
                .type(Invoice.InvoiceType.SALE)
                .cashierId(cashierId)
                .totalAmount(totalAmount)
                .discountAmount(discountAmount)
                .finalAmount(finalAmount)
                .pointsUsed(pointsToUse)
                .note(invoiceNote)
                .build();

        for (CheckoutRequest.CartItemRequest cartItem : req.getItems()) {
            Product product = productRepository.findById(cartItem.getProductId()).orElseThrow();
            invoice.addItem(InvoiceItem.builder()
                    .productId(product.getId())
                    .quantity(cartItem.getQuantity())
                    .unitPrice(cartItem.getUnitPrice())
                    .macPrice(product.getMacPrice())
                    .subtotal(cartItem.getUnitPrice()
                            .multiply(BigDecimal.valueOf(cartItem.getQuantity())))
                    .build());
        }

        for (CheckoutRequest.PaymentRequest p : req.getPayments()) {
            InvoicePayment.PaymentMethod method;
            try {
                method = InvoicePayment.PaymentMethod.valueOf(p.getMethod().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_PAYMENT_METHOD",
                        "Phương thức không hợp lệ: " + p.getMethod());
            }
            invoice.addPayment(InvoicePayment.builder()
                    .method(method).amount(p.getAmount()).reference(p.getReference()).build());
        }

        // Lưu Invoice TRƯỚC để có invoiceId thực
        invoice = invoiceRepository.save(invoice);
        final UUID invoiceId = invoice.getId();

        // 7. Trừ kho với invoiceId chính xác
        for (CheckoutRequest.CartItemRequest cartItem : req.getItems()) {
            inventoryService.deductForPOSSale(
                    cartItem.getProductId(), warehouseId,
                    cartItem.getQuantity(), invoiceId, cashierId.toString());
        }

        // 8. Trừ điểm sau khi invoice đã confirm thành công
        if (pointsToUse > 0 && customer != null) {
            customer.deductPoints(pointsToUse);
        }

        // 9. Ghi Sổ quỹ
        for (InvoicePayment p : invoice.getPayments()) {
            recordCashbook(shift, p.getMethod(), p.getAmount(), warehouseId);
        }

        // 10. Cộng điểm tích lũy
        int pointsEarned = 0;
        if (customer != null) {
            pointsEarned = finalAmount.divide(
                    BigDecimal.valueOf(appProperties.getBusiness().getLoyaltyPointsPerVnd()),
                    0, RoundingMode.DOWN).intValue();
            customer.addPoints(pointsEarned);
            customer.setTotalSpent(customer.getTotalSpent().add(finalAmount));
            customerRepository.save(customer);
            invoice.setPointsEarned(pointsEarned);
            invoice = invoiceRepository.save(invoice);
        }

        log.info("Checkout OK: invoice={}, total={}, discount={}, cashier={}",
                invoice.getCode(), totalAmount, discountAmount, cashierId);
        return buildInvoiceResponse(invoice);
    }

    // ─────────────────────────────────────────────────────────
    // REFUND 
    // ─────────────────────────────────────────────────────────
   @Transactional
    public InvoiceResponse refund(UUID originalInvoiceId, UUID shiftId,
                                  List<RefundItem> items, String returnDestination,
                                  UUID cashierId, UUID warehouseId, String note) {

        if (!invoiceRepository.existsByIdAndWarehouseId(originalInvoiceId, warehouseId)) {
            throw new BusinessException("ACCESS_DENIED",
                    "Hóa đơn này không thuộc chi nhánh của bạn.");
        }
        Invoice original = invoiceRepository.findByIdWithDetails(originalInvoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", originalInvoiceId));
        if (original.getType() != Invoice.InvoiceType.SALE) {
            throw new BusinessException("INVALID_REFUND",
                    "Không thể tạo trả hàng cho hóa đơn trả hàng");
        }

        // Double refund check
        List<Invoice> existingReturns = invoiceRepository
                .findReturnInvoicesWithItemsByReturnOfId(originalInvoiceId);
        Map<UUID, Integer> alreadyReturnedQty = new HashMap<>();
        for (Invoice ret : existingReturns) {
            for (InvoiceItem ri : ret.getItems()) {
                alreadyReturnedQty.merge(ri.getProductId(), Math.abs(ri.getQuantity()), Integer::sum);
            }
        }

        for (RefundItem ri : items) {
            InvoiceItem originalItem = original.getItems().stream()
                    .filter(i -> i.getProductId().equals(ri.productId())).findFirst().orElseThrow();
            int alreadyReturned = alreadyReturnedQty.getOrDefault(ri.productId(), 0);
            int remaining = originalItem.getQuantity() - alreadyReturned;
            if (ri.quantity() <= 0) throw new BusinessException("INVALID_RETURN_QTY", "Số lượng hoàn phải > 0");
            if (ri.quantity() > remaining) {
                throw new BusinessException("EXCESSIVE_RETURN",
                        String.format("Sản phẩm %s: đã hoàn %d/%d. Còn lại tối đa %d.",
                                ri.productId(), alreadyReturned, originalItem.getQuantity(), remaining));
            }
        }

        // Phase 1: Build return invoice
        Invoice returnInvoice = Invoice.builder()
                .code(codeGenerator.nextReturnCode()) 
                .shiftId(shiftId)
                .customerId(original.getCustomerId())
                .type(Invoice.InvoiceType.RETURN)
                .cashierId(cashierId)
                .returnOfId(original.getId())
                .note(note)
                .build();

        BigDecimal totalRefund = BigDecimal.ZERO;
        List<UUID>    productIds = new ArrayList<>();
        List<Integer> quantities = new ArrayList<>();

        for (RefundItem ri : items) {
            InvoiceItem oi = original.getItems().stream()
                    .filter(i -> i.getProductId().equals(ri.productId())).findFirst().orElseThrow();
            returnInvoice.addItem(InvoiceItem.builder()
                    .productId(ri.productId()).quantity(-ri.quantity())
                    .unitPrice(oi.getUnitPrice()).macPrice(oi.getMacPrice())
                    .subtotal(oi.getUnitPrice().multiply(BigDecimal.valueOf(-ri.quantity())))
                    .build());
            totalRefund = totalRefund.add(oi.getUnitPrice().multiply(BigDecimal.valueOf(ri.quantity())));
            productIds.add(ri.productId());
            quantities.add(ri.quantity());
        }
        returnInvoice.setTotalAmount(totalRefund);
        returnInvoice.setFinalAmount(totalRefund);

        returnInvoice = invoiceRepository.save(returnInvoice);
        final UUID returnInvoiceId = returnInvoice.getId();

        // Phase 2: Hoàn kho
        for (int i = 0; i < productIds.size(); i++) {
            inventoryService.returnToStock(productIds.get(i), warehouseId, quantities.get(i),
                    returnInvoiceId,
                    (returnDestination != null) ? returnDestination : "STOCK",
                    cashierId.toString());
        }

        // Phase 3: Phiếu Chi Sổ quỹ
        Shift shift = shiftService.getOpenShiftByCashier(cashierId);
        cashbookRepository.save(CashbookTransaction.builder()
                .warehouseId(warehouseId).shiftId(shift.getId())
                .fundType(CashbookTransaction.FundType.CASH_111)
                .transactionType(CashbookTransaction.TransactionType.OUT)
                .referenceType("INVOICE").referenceId(returnInvoiceId)
                .amount(totalRefund)
                .description("Trả hàng hóa đơn #" + original.getCode())
                .createdBy(cashierId.toString()).build());

        // =================================================================
        // [SỬA LỖI COMPILER] Gán vào biến final để sử dụng an toàn trong Lambda
        final BigDecimal finalTotalRefund = totalRefund;
        // =================================================================

        if (original.getCustomerId() != null) {
            customerRepository.findById(original.getCustomerId()).ifPresent(customer -> {
                // Sử dụng finalTotalRefund thay vì totalRefund
                int pointsToDeduct = finalTotalRefund.divide(
                        BigDecimal.valueOf(appProperties.getBusiness().getLoyaltyPointsPerVnd()),
                        0, RoundingMode.DOWN).intValue();

                // Trừ điểm (không để bị âm)
                if (customer.getLoyaltyPoints() >= pointsToDeduct) {
                    customer.deductPoints(pointsToDeduct);
                } else {
                    customer.setLoyaltyPoints(0);
                }

                // Giảm tổng chi tiêu
                BigDecimal newTotal = customer.getTotalSpent().subtract(finalTotalRefund);
                customer.setTotalSpent(newTotal.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newTotal);

                customerRepository.save(customer);
            });
        }
        log.info("Refund OK: return={}, original={}, amount={}",
                returnInvoice.getCode(), original.getCode(), totalRefund);
        return buildInvoiceResponse(returnInvoice);
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────

    /** Kết quả tính tiền — dùng chung cho cả checkout() thật và preview QR payOS. */
    private record PricingResult(BigDecimal totalAmount, BigDecimal promotionDiscount,
                                  BigDecimal loyaltyDiscount, BigDecimal discountAmount,
                                  BigDecimal finalAmount) {}

    /**
     * [FIX-SECURITY] Tính tiền tập trung tại MỘT nơi duy nhất, luôn dựa trên dữ
     * liệu đã validate ở Server (items, mã khuyến mãi, điểm) — KHÔNG BAO GIỜ tin
     * số tiền do Client tự tính rồi gửi lên.
     *
     * @param commitPromotionUsage true = tăng usedCount ngay (checkout thật);
     *                             false = chỉ xem trước, không đổi dữ liệu (tạo QR payOS).
     */
    private PricingResult computePricing(List<CheckoutRequest.CartItemRequest> items,
                                          Customer customer, int pointsToUse,
                                          String promotionCode, boolean commitPromotionUsage) {

        BigDecimal totalAmount = items.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal promotionDiscount = BigDecimal.ZERO;
        if (promotionCode != null && !promotionCode.isBlank()) {
            promotionDiscount = commitPromotionUsage
                    ? promotionService.applyPromotion(promotionCode.trim(), totalAmount, "POS")
                    : promotionService.validateCode(promotionCode.trim(), totalAmount, "POS").getDiscountAmount();
        }

        BigDecimal afterPromotion = totalAmount.subtract(promotionDiscount);
        BigDecimal loyaltyDiscount = BigDecimal.ZERO;
        if (pointsToUse > 0 && customer != null) {
            if (pointsToUse % 500 != 0) {
                throw new BusinessException("INVALID_POINTS", "Chỉ có thể quy đổi điểm theo mốc 500 điểm.");
            }
            if (customer.getLoyaltyPoints() < pointsToUse) {
                throw new BusinessException("INSUFFICIENT_POINTS",
                        "Điểm tích lũy không đủ. Hiện có: " + customer.getLoyaltyPoints());
            }
            loyaltyDiscount = BigDecimal.valueOf(pointsToUse)
                    .multiply(BigDecimal.valueOf(appProperties.getBusiness().getLoyaltyPointsRedeemValue()));
            if (loyaltyDiscount.compareTo(afterPromotion) > 0) {
                loyaltyDiscount = afterPromotion;
            }
        }

        BigDecimal discountAmount = promotionDiscount.add(loyaltyDiscount);
        BigDecimal finalAmount = totalAmount.subtract(discountAmount);
        return new PricingResult(totalAmount, promotionDiscount, loyaltyDiscount, discountAmount, finalAmount);
    }

    /**
     * [MỚI] Tính trước finalAmount cho luồng tạo QR payOS — public để
     * PaymentTransactionService gọi được. readOnly vì không được có side-effect.
     */
    @Transactional(readOnly = true)
    public BigDecimal previewFinalAmount(List<CheckoutRequest.CartItemRequest> items, Customer customer,
                                          int pointsToUse, String promotionCode) {
        return computePricing(items, customer, pointsToUse, promotionCode, false).finalAmount();
    }

    /**
     * [FIX-A3] Map InvoiceItem -> ItemResponse kèm tên sản phẩm thật.
     */
    private List<InvoiceResponse.ItemResponse> buildItemResponses(List<InvoiceItem> items) {
        if (items == null || items.isEmpty()) return List.of();

        Map<UUID, Product> productMap = productRepository
                .findAllById(items.stream().map(InvoiceItem::getProductId).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));

        return items.stream().map(i -> {
            Product p = productMap.get(i.getProductId());
            return InvoiceResponse.ItemResponse.builder()
                    .productId(i.getProductId())
                    .productName(p != null ? p.getName() : "Sản phẩm đã xoá")
                    .isbnBarcode(p != null ? p.getIsbnBarcode() : null)
                    .quantity(i.getQuantity())
                    .unitPrice(i.getUnitPrice())
                    .macPrice(i.getMacPrice())
                    .subtotal(i.getSubtotal())
                    .build();
        }).toList();
    }

    private InvoiceResponse buildInvoiceResponse(Invoice invoice) {
        List<InvoiceResponse.ItemResponse> items = buildItemResponses(invoice.getItems());

        List<InvoiceResponse.PaymentResponse> payments = invoice.getPayments() == null ? List.of()
                : invoice.getPayments().stream().map(p -> InvoiceResponse.PaymentResponse.builder()
                        .method(p.getMethod().name()).amount(p.getAmount())
                        .reference(p.getReference()).build()).toList();

        // [FIX-A3] Lấy tên Thu ngân và Khách hàng
        String cashierName = userRepository.findById(invoice.getCashierId())
                .map(User::getFullName).orElse(null);

        String customerName = null;
        String customerPhone = null;
        if (invoice.getCustomerId() != null) {
            Customer c = customerRepository.findById(invoice.getCustomerId()).orElse(null);
            if (c != null) {
                customerName = c.getFullName();
                customerPhone = c.getPhoneNumber();
            }
        }

        return InvoiceResponse.builder()
                .id(invoice.getId()).code(invoice.getCode())
                .shiftId(invoice.getShiftId()).customerId(invoice.getCustomerId())
                .returnOfId(invoice.getReturnOfId()).type(invoice.getType().name())
                .totalAmount(invoice.getTotalAmount()).discountAmount(invoice.getDiscountAmount())
                .finalAmount(invoice.getFinalAmount()).pointsUsed(invoice.getPointsUsed())
                .pointsEarned(invoice.getPointsEarned()).note(invoice.getNote())
                .cashierName(cashierName)
                .customerName(customerName)
                .customerPhone(customerPhone)
                .createdAt(invoice.getCreatedAt()).items(items).payments(payments).build();
    }

    /**
     * [FIX-A3] Chuyển logic tra cứu hóa đơn theo mã từ Controller vào Service
     */
    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceByCode(String code) {
        Invoice invoice = invoiceRepository.findByCodeWithDetails(code)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hóa đơn mã: " + code));
        return buildInvoiceResponse(invoice);
    }

    private void recordCashbook(Shift shift, InvoicePayment.PaymentMethod method,
                                 BigDecimal amount, UUID warehouseId) {
        CashbookTransaction.FundType fundType =
                method == InvoicePayment.PaymentMethod.CASH
                        ? CashbookTransaction.FundType.CASH_111
                        : CashbookTransaction.FundType.BANK_112;
        cashbookRepository.save(CashbookTransaction.builder()
                .warehouseId(warehouseId).shiftId(shift.getId())
                .fundType(fundType)
                .transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("INVOICE").amount(amount)
                .description("Thu tiền bán hàng - " + method.name())
                .createdBy(shift.getCashierId().toString()).build());
    }

    private String buildNote(String baseNote, String promotionCode, int pointsUsed) {
        StringBuilder sb = new StringBuilder();
        if (promotionCode != null && !promotionCode.isBlank()) {
            sb.append("[Mã KM: ").append(promotionCode.toUpperCase()).append("] ");
        }
        if (pointsUsed > 0) {
            sb.append("[Đổi ").append(pointsUsed).append(" điểm] ");
        }
        if (baseNote != null && !baseNote.isBlank()) sb.append(baseNote);
        return sb.toString().trim();
    }

    public record RefundItem(UUID productId, int quantity) {}
}