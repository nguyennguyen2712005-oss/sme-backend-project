package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeGeneratorService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbcTemplate;

    public String nextInvoiceCode() {
        return format("INV", nextVal("seq_invoice_code"));
    }

    public String nextReturnCode() {
        return format("RET", nextVal("seq_invoice_code"));
    }

    public String nextOrderCode() {
        return format("ORD", nextVal("seq_order_code"));
    }

    public String nextTransferCode() {
        return format("TRF", nextVal("seq_transfer_code"));
    }

    public String nextAutoTransferCode() {
        return format("TRF-AUTO", nextVal("seq_transfer_code"));
    }

    public String nextPurchaseOrderCode() {
        return format("PO", nextVal("seq_purchase_order_code"));
    }

    public String nextStockTakeCode() {
        return format("SK", nextVal("seq_stock_take_code"));
    }

    // [MỚI] Mã giao dịch thanh toán QR (payOS)
    public String nextPaymentTxnCode() {
        return format("QR", nextVal("seq_payment_txn_code"));
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE
    // ─────────────────────────────────────────────────────────
    private long nextVal(String sequenceName) {
        try {
            Long val = jdbcTemplate.queryForObject(
                    "SELECT nextval(?)", Long.class, sequenceName);
            return val != null ? val : 1L;
        } catch (Exception e) {
            log.warn("Sequence '{}' chưa tồn tại, dùng timestamp fallback: {}",
                    sequenceName, e.getMessage());
            return System.currentTimeMillis() % 1_000_000;
        }
    }

    private String format(String prefix, long seq) {
        String date = LocalDate.now(ZONE).format(DATE_FMT);
        return String.format("%s-%s-%06d", prefix, date, seq);
    }
}