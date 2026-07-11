package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * CodeGeneratorService — sinh mã nghiệp vụ có định dạng cố định
 *
 * Dùng PostgreSQL SEQUENCE thay vì System.currentTimeMillis():
 * - Atomic: không bao giờ trùng dù 1000 request cùng lúc
 * - Không lock bảng (SEQUENCE hoạt động ngoài transaction)
 * - Cache 50 → gần như không có overhead I/O
 *
 * Format mã: PREFIX-YYYYMMDD-NNNNNN
 *   INV-20240628-000001 — Hóa đơn POS
 *   ORD-20240628-000001 — Đơn hàng Online
 *   TRF-20240628-000001 — Phiếu Chuyển kho
 *   PO-20240628-000001  — Phiếu Nhập kho
 *   SK-20240628-000001  — Phiếu Kiểm kê
 *
 * LƯU Ý: Khi CYCLE=true, mã số resets về 1 sau 9,999,999.
 * Với bookstore quy mô nhỏ–vừa, điều này không gây duplicate
 * vì YYYYMMDD phần ngày thay đổi mỗi ngày.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeGeneratorService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbcTemplate;

    /** Mã hóa đơn POS: INV-20240628-000001 */
    public String nextInvoiceCode() {
        return format("INV", nextVal("seq_invoice_code"));
    }

    /** Mã hóa đơn Trả hàng: RET-20240628-000001 */
    public String nextReturnCode() {
        // Return invoice dùng chung sequence với Invoice để giữ số liên tục trong ngày
        return format("RET", nextVal("seq_invoice_code"));
    }

    /** Mã đơn hàng Online: ORD-20240628-000001 */
    public String nextOrderCode() {
        return format("ORD", nextVal("seq_order_code"));
    }

    /** Mã phiếu chuyển kho: TRF-20240628-000001 */
    public String nextTransferCode() {
        return format("TRF", nextVal("seq_transfer_code"));
    }

    /** Mã phiếu chuyển kho tự động (gom hàng đơn online): TRF-AUTO-20240628-000001 */
    public String nextAutoTransferCode() {
        return format("TRF-AUTO", nextVal("seq_transfer_code"));
    }

    /** Mã phiếu nhập kho: PO-20240628-000001 */
    public String nextPurchaseOrderCode() {
        return format("PO", nextVal("seq_purchase_order_code"));
    }

    /** Mã phiếu kiểm kê: SK-20240628-000001 */
    public String nextStockTakeCode() {
        return format("SK", nextVal("seq_stock_take_code"));
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
            // Fallback khi sequence chưa được tạo (môi trường dev/test)
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
