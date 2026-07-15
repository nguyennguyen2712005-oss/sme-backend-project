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

        BigDecimal finalAmount = posService.previewFinalAmount(
                req.getItems(), customer,
                req.getPointsToUse() != null ? req.getPointsToUse() : 0,
                req.getPromotionCode());

        if (finalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Số tiền thanh toán phải lớn hơn 0");
        }

        String code = codeGenerator.nextPaymentTxnCode();   
        long mockOrderCode = System.currentTimeMillis();   
        String gatewayOrderId = "DH" + mockOrderCode;

        // Dùng API tạo QR miễn phí (VietQR.io) với số tài khoản tùy ý
        String BANK_ID = "MB"; // Tên viết tắt ngân hàng: MB, VCB, BIDV...
        String BANK_ACCOUNT = "123456789"; // Số TK tùy ý
        String ACCOUNT_NAME = "NGUYEN VAN A"; // Tên chủ thẻ
        
        String qrUrl = String.format("https://img.vietqr.io/image/%s-%s-compact2.jpg?amount=%d&addInfo=%s&accountName=%s", 
                BANK_ID, BANK_ACCOUNT, finalAmount.longValueExact(), gatewayOrderId, ACCOUNT_NAME.replace(" ", "%20"));

        String cartSnapshotJson;
        try {
            cartSnapshotJson = objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể lưu snapshot giỏ hàng", e);
        }

        PaymentTransaction txn = PaymentTransaction.builder()
                .code(code)
                .payosOrderCode(mockOrderCode) // Tận dụng lại cột cũ để khỏi lỗi DB
                .payosPaymentLinkId("") // Tận dụng lại cột cũ
                .shiftId(shift.getId())
                .cashierId(cashierId)
                .warehouseId(warehouseId)
                .customerId(customer != null ? customer.getId() : null)
                .amount(finalAmount)
                .status(PaymentTransaction.Status.PENDING)
                .cartSnapshot(cartSnapshotJson)
                .qrCode(qrUrl) // Đưa thẳng link ảnh vào đây
                .checkoutUrl(qrUrl)
                .expiredAt(Instant.now().plus(EXPIRE_MINUTES, ChronoUnit.MINUTES))
                .build();
        paymentTransactionRepository.save(txn);

        log.info("Tạo QR Demo thành công: code={}, amount={}", code, finalAmount);
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
        pushWsUpdate(txn);
    }

    // Webhook giờ không còn tác dụng vì ta xài nút bấm Demo ở Frontend.
    // Cứ để nguyên method rỗng để lỡ endpoint /webhook có bị gọi cũng không văng lỗi.
    @Transactional
    public void handleWebhook(Map<String, Object> payload) {
        log.info("Chế độ Demo: Bỏ qua webhook.");
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