package sme.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.response.PaymentQrResponse;
import sme.backend.entity.Customer;
import sme.backend.entity.PaymentTransaction;
import sme.backend.entity.Shift;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.CustomerRepository;
import sme.backend.repository.PaymentTransactionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentTransactionService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PayOSService payOSService;
    private final POSService posService;                
    private final ShiftService shiftService;
    private final CustomerRepository customerRepository;
    private final CodeGeneratorService codeGenerator;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.frontend-url}")
    private String frontendUrl;                          

    private static final int EXPIRE_MINUTES = 15;

    @Transactional
    public PaymentQrResponse createQr(CheckoutRequest req, UUID cashierId, UUID warehouseId) {

        Shift shift = shiftService.getOpenShiftByCashier(cashierId);
        if (!shift.getId().equals(req.getShiftId())) {
            throw new BusinessException("SHIFT_MISMATCH", "shiftId không khớp với ca làm việc đang mở");
        }
        
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_CART", "Giỏ hàng không được rỗng");
        }

        Customer customer = null;
        if (req.getCustomerId() != null) {
            customer = customerRepository.findById(req.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer", req.getCustomerId()));
        }

        // Tính tiền an toàn tại Server
        BigDecimal finalAmount = posService.previewFinalAmount(
                req.getItems(), customer,
                req.getPointsToUse() != null ? req.getPointsToUse() : 0,
                req.getPromotionCode());

        if (finalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Số tiền thanh toán phải lớn hơn 0");
        }

        String code = codeGenerator.nextPaymentTxnCode();   
        long payosOrderCode = System.currentTimeMillis();   

        String description = "DH" + String.format("%06d", payosOrderCode % 1_000_000);
        String returnUrl = frontendUrl + "/pos";
        String cancelUrl = frontendUrl + "/pos";

        var result = payOSService.createPaymentLink(
                payosOrderCode, finalAmount.longValueExact(), description, returnUrl, cancelUrl);

        String cartSnapshotJson;
        try {
            cartSnapshotJson = objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể lưu snapshot giỏ hàng", e);
        }

        PaymentTransaction txn = PaymentTransaction.builder()
                .code(code)
                .payosOrderCode(payosOrderCode)
                .payosPaymentLinkId(result.paymentLinkId())
                .shiftId(shift.getId())
                .cashierId(cashierId)
                .warehouseId(warehouseId)
                .customerId(customer != null ? customer.getId() : null)
                .amount(finalAmount)
                .status(PaymentTransaction.Status.PENDING)
                .cartSnapshot(cartSnapshotJson)
                .qrCode(result.qrCode())
                .checkoutUrl(result.checkoutUrl())
                .expiredAt(Instant.now().plus(EXPIRE_MINUTES, ChronoUnit.MINUTES))
                .build();
        paymentTransactionRepository.save(txn);

        log.info("Tạo QR thanh toán payOS: code={}, orderCode={}, amount={}", code, payosOrderCode, finalAmount);
        return toResponse(txn);
    }

    @Transactional(readOnly = true)
    public PaymentQrResponse getStatus(String code) {
        return toResponse(findByCodeOrThrow(code));
    }

    @Transactional
    public void cancel(String code) {
        PaymentTransaction txn = findByCodeOrThrow(code);
        if (txn.getStatus() != PaymentTransaction.Status.PENDING) return;
        txn.setStatus(PaymentTransaction.Status.CANCELLED);
        paymentTransactionRepository.save(txn);
        if (txn.getPayosPaymentLinkId() != null) {
            payOSService.cancelPaymentLink(txn.getPayosPaymentLinkId(), "Cashier cancelled");
        }
        pushWsUpdate(txn);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handleWebhook(Map<String, Object> payload) {
        try {
            Object signatureObj = payload.get("signature");
            Object dataObj = payload.get("data");
            if (signatureObj == null || !(dataObj instanceof Map)) {
                log.warn("Webhook payOS thiếu signature hoặc data, bỏ qua.");
                return;
            }
            Map<String, Object> data = (Map<String, Object>) dataObj;

            if (!payOSService.verifyWebhookSignature(data, String.valueOf(signatureObj))) {
                log.warn("Webhook payOS có signature KHÔNG hợp lệ — bỏ qua. orderCode={}", data.get("orderCode"));
                return;
            }

            Object orderCodeObj = data.get("orderCode");
            if (orderCodeObj == null) return;
            long orderCode = Long.parseLong(String.valueOf(orderCodeObj));

            PaymentTransaction txn = paymentTransactionRepository.findByPayosOrderCode(orderCode).orElse(null);
            if (txn == null) {
                log.warn("Không tìm thấy PaymentTransaction cho orderCode={} từ webhook payOS", orderCode);
                return;
            }
            if (txn.getStatus() == PaymentTransaction.Status.PAID) {
                log.info("Webhook payOS trùng lặp cho giao dịch đã PAID: {}", txn.getCode());
                return; 
            }

            txn.setStatus(PaymentTransaction.Status.PAID);
            txn.setPaidAt(Instant.now());
            Object referenceObj = data.get("reference");
            if (referenceObj != null) txn.setReference(String.valueOf(referenceObj));
            paymentTransactionRepository.save(txn);

            log.info("Webhook payOS xác nhận PAID: code={}, orderCode={}", txn.getCode(), orderCode);
            pushWsUpdate(txn);

        } catch (Exception e) {
            log.error("Lỗi xử lý webhook payOS: {}", e.getMessage(), e);
        }
    }

    private void pushWsUpdate(PaymentTransaction txn) {
        String topic = "/topic/payments/" + txn.getCode();
        Map<String, Object> msg = new java.util.HashMap<>();
        msg.put("type", "PAYMENT_STATUS");
        msg.put("code", txn.getCode());
        msg.put("status", txn.getStatus().name());
        msg.put("reference", txn.getReference()); 
        messagingTemplate.convertAndSend(topic, msg);
    }

    private PaymentTransaction findByCodeOrThrow(String code) {
        return paymentTransactionRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentTransaction", code));
    }

    private PaymentQrResponse toResponse(PaymentTransaction txn) {
        return PaymentQrResponse.builder()
                .code(txn.getCode()).qrCode(txn.getQrCode()).checkoutUrl(txn.getCheckoutUrl())
                .amount(txn.getAmount()).expiredAt(txn.getExpiredAt()).status(txn.getStatus().name())
                .reference(txn.getReference())
                .build();
    }
}