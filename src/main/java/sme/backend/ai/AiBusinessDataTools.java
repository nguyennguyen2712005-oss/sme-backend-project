package sme.backend.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import sme.backend.dto.response.LowStockItem;
import sme.backend.entity.CashbookTransaction;
import sme.backend.entity.Order;
import sme.backend.entity.Supplier;
import sme.backend.entity.SupplierDebt;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class AiBusinessDataTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final List<Order.OrderStatus> TRACKED_ORDER_STATUSES = List.of(
            Order.OrderStatus.PENDING,
            Order.OrderStatus.CONFIRMED,
            Order.OrderStatus.PACKED,
            Order.OrderStatus.SHIPPING,
            Order.OrderStatus.READY_FOR_PICKUP
    );

    // ── Phạm vi truy cập, khóa cứng theo người dùng đang chat ───────────
    private final UUID scopeWarehouseId;      // null nếu là ADMIN
    private final String scopeWarehouseLabel; // null nếu là ADMIN, ví dụ "Chi nhánh Quận 1"
    private final boolean isAdmin;

    // ── Repository (AiService truyền vào từ các bean đã có sẵn) ─────────
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final SupplierDebtRepository supplierDebtRepository;
    private final SupplierRepository supplierRepository;
    private final CashbookTransactionRepository cashbookTransactionRepository;
    private final WarehouseRepository warehouseRepository;

    // =====================================================================
    // TOOL 1 — DOANH THU / LỢI NHUẬN GỘP
    // =====================================================================
    @Tool(description = "Tra cứu doanh thu, giá vốn (COGS) và lợi nhuận gộp thực tế trong một khoảng thời gian. " +
            "Dùng khi người dùng hỏi về doanh thu, doanh số, lợi nhuận, kinh doanh có lãi không.")
    public Map<String, Object> getRevenueSummary(
            @ToolParam(description = "Ngày bắt đầu, định dạng yyyy-MM-dd. Bỏ trống sẽ lấy 30 ngày gần nhất.", required = false)
            String fromDate,
            @ToolParam(description = "Ngày kết thúc, định dạng yyyy-MM-dd. Bỏ trống sẽ lấy đến hôm nay.", required = false)
            String toDate,
            @ToolParam(description = "Mã chi nhánh cụ thể (ví dụ 'HN01'). CHỈ áp dụng cho ADMIN — nếu người dùng là MANAGER, tham số này luôn bị bỏ qua.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        Instant[] range = resolveDateRange(fromDate, toDate);
        List<Map<String, Object>> rows = invoiceRepository.getRevenueReportDaily(scope.warehouseId(), range[0], range[1]);

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        long invoiceCount = 0;
        for (Map<String, Object> row : rows) {
            revenue = revenue.add(toBigDecimal(row.get("revenue")));
            cogs = cogs.add(toBigDecimal(row.get("cogs")));
            Object ic = row.get("invoice_count");
            if (ic instanceof Number n) invoiceCount += n.longValue();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("fromDate", range[0].toString());
        result.put("toDate", range[1].toString());
        result.put("revenue", revenue);
        result.put("cogs", cogs);
        result.put("grossProfit", revenue.subtract(cogs));
        result.put("invoiceOrOrderCount", invoiceCount);
        log.info("[AI-TOOL] getRevenueSummary scope={} from={} to={} -> revenue={}", scope.label(), range[0], range[1], revenue);
        return result;
    }

    // =====================================================================
    // TOOL 2 — CẢNH BÁO TỒN KHO THẤP
    // =====================================================================
    @Tool(description = "Lấy danh sách sản phẩm sắp hết hàng (tồn kho <= ngưỡng cảnh báo tối thiểu). " +
            "Dùng khi người dùng hỏi về hàng sắp hết, cần nhập thêm hàng gì.")
    public Map<String, Object> getLowStockAlert(
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        List<LowStockItem> items = scope.warehouseId() != null
                ? inventoryRepository.findLowStockWithNameByWarehouse(scope.warehouseId())
                : inventoryRepository.findAllLowStockWithName();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("totalLowStockItems", items.size());
        result.put("items", items.stream().limit(20).toList());
        log.info("[AI-TOOL] getLowStockAlert scope={} -> {} sản phẩm", scope.label(), items.size());
        return result;
    }

    // =====================================================================
    // TOOL 3 — HÀNG TỒN ĐỌNG (DEAD STOCK)
    // =====================================================================
    @Tool(description = "Lấy danh sách hàng tồn kho lâu ngày không phát sinh bán (hàng ế / dead stock). " +
            "Dùng khi người dùng hỏi về hàng không bán được, cần xả kho hoặc khuyến mãi để đẩy hàng.")
    public Map<String, Object> getDeadStockAlert(
            @ToolParam(description = "Số ngày không phát sinh bán để coi là tồn đọng. Mặc định 90 ngày nếu bỏ trống.", required = false)
            Integer days,
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        int d = (days == null || days <= 0) ? 90 : days;
        List<Map<String, Object>> rows = scope.warehouseId() != null
                ? inventoryRepository.findDeadStockByWarehouse(scope.warehouseId(), d)
                : inventoryRepository.findDeadStockAll(d);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("daysThreshold", d);
        result.put("totalDeadStockItems", rows.size());
        result.put("items", rows.stream().limit(20).toList());
        return result;
    }

    // =====================================================================
    // TOOL 4 — TOP SẢN PHẨM BÁN CHẠY
    // =====================================================================
    @Tool(description = "Lấy danh sách sản phẩm bán chạy nhất theo số lượng (gộp cả bán tại quầy POS và đơn online đã giao) " +
            "trong một khoảng thời gian. Dùng khi người dùng hỏi sản phẩm nào bán chạy, nên nhập thêm hàng gì.")
    public Map<String, Object> getTopSellingProducts(
            @ToolParam(description = "Ngày bắt đầu yyyy-MM-dd. Bỏ trống lấy 30 ngày gần nhất.", required = false)
            String fromDate,
            @ToolParam(description = "Ngày kết thúc yyyy-MM-dd. Bỏ trống lấy đến hôm nay.", required = false)
            String toDate,
            @ToolParam(description = "Số lượng sản phẩm muốn lấy, mặc định 10, tối đa 20.", required = false)
            Integer limit,
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        Instant[] range = resolveDateRange(fromDate, toDate);
        int lim = (limit == null || limit <= 0) ? 10 : Math.min(limit, 20);

        List<Map<String, Object>> rows = productRepository.findTopSellingProducts(
                scope.warehouseId(), range[0], range[1], lim);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("fromDate", range[0].toString());
        result.put("toDate", range[1].toString());
        result.put("topProducts", rows);
        return result;
    }

    // =====================================================================
    // TOOL 5 — GIÁ TRỊ TỒN KHO HIỆN TẠI
    // =====================================================================
    @Tool(description = "Lấy báo cáo giá trị tồn kho hiện tại: số lượng mặt hàng (SKU), tổng số lượng và tổng giá trị " +
            "tồn kho theo giá vốn bình quân (MAC). Dùng khi người dùng hỏi tồn kho đang trị giá bao nhiêu.")
    public Map<String, Object> getInventoryValueReport(
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        List<Map<String, Object>> rows = scope.warehouseId() != null
                ? inventoryRepository.getInventoryValueReportByWarehouse(scope.warehouseId())
                : inventoryRepository.getInventoryValueReportAll();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("report", rows);
        return result;
    }

    // =====================================================================
    // TOOL 6 — CÔNG NỢ NHÀ CUNG CẤP
    // =====================================================================
    @Tool(description = "Lấy tổng công nợ phải trả nhà cung cấp hiện tại và danh sách khoản nợ sắp đến hạn thanh toán. " +
            "Dùng khi người dùng hỏi về công nợ, dòng tiền phải trả sắp tới.")
    public Map<String, Object> getSupplierDebtSummary(
            @ToolParam(description = "Số ngày tới để coi là 'sắp đến hạn', mặc định 7 ngày.", required = false)
            Integer dueWithinDays,
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        int days = (dueWithinDays == null || dueWithinDays <= 0) ? 7 : dueWithinDays;
        LocalDate dueBefore = LocalDate.now(ZONE).plusDays(days);

        BigDecimal totalOutstanding;
        List<SupplierDebt> dueSoon;
        if (scope.warehouseId() != null) {
            dueSoon = supplierDebtRepository.findDueSoonByWarehouse(scope.warehouseId(), dueBefore);
            totalOutstanding = supplierDebtRepository.findOutstandingDebtsByWarehouse(scope.warehouseId())
                    .stream().map(SupplierDebt::getRemainingAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            dueSoon = supplierDebtRepository.findAllDueSoon(dueBefore);
            totalOutstanding = supplierDebtRepository.getTotalOutstandingDebtAllWarehouses();
        }

        List<Map<String, Object>> dueSoonList = dueSoon.stream().limit(20).map(sd -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierName", supplierRepository.findById(sd.getSupplierId())
                    .map(Supplier::getName).orElse("(không rõ)"));
            m.put("remainingAmount", sd.getRemainingAmount());
            m.put("dueDate", sd.getDueDate());
            m.put("status", sd.getStatus());
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("totalOutstandingDebt", totalOutstanding == null ? BigDecimal.ZERO : totalOutstanding);
        result.put("dueWithinDays", days);
        result.put("dueSoonCount", dueSoon.size());
        result.put("dueSoonList", dueSoonList);
        return result;
    }

    // =====================================================================
    // TOOL 7 — SỐ DƯ QUỸ TIỀN MẶT / NGÂN HÀNG
    // =====================================================================
    @Tool(description = "Lấy số dư quỹ tiền mặt tại két và tiền gửi ngân hàng hiện tại. " +
            "Dùng khi người dùng hỏi về quỹ, số dư, khả năng thanh toán ngay.")
    public Map<String, Object> getCashbookBalance(
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        BigDecimal cash;
        BigDecimal bank;
        if (scope.warehouseId() != null) {
            cash = cashbookTransactionRepository.getCurrentBalanceByWarehouse(scope.warehouseId(), CashbookTransaction.FundType.CASH_111);
            bank = cashbookTransactionRepository.getCurrentBalanceByWarehouse(scope.warehouseId(), CashbookTransaction.FundType.BANK_112);
        } else {
            cash = cashbookTransactionRepository.getCurrentBalanceAll(CashbookTransaction.FundType.CASH_111);
            bank = cashbookTransactionRepository.getCurrentBalanceAll(CashbookTransaction.FundType.BANK_112);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("cashBalance", cash == null ? BigDecimal.ZERO : cash);
        result.put("bankBalance", bank == null ? BigDecimal.ZERO : bank);
        return result;
    }

    // =====================================================================
    // TOOL 8 — ĐƠN HÀNG TỒN ĐỌNG THEO TRẠNG THÁI
    // =====================================================================
    @Tool(description = "Lấy số lượng đơn hàng online đang tồn đọng theo từng trạng thái xử lý " +
            "(chờ xác nhận, chờ đóng gói, đang giao, chờ khách lấy...). Dùng khi người dùng hỏi về đơn hàng tồn, vận hành.")
    public Map<String, Object> getOrderBacklog(
            @ToolParam(description = "Mã chi nhánh cụ thể. Chỉ áp dụng cho ADMIN.", required = false)
            String warehouseCode) {

        WarehouseScope scope = resolveScope(warehouseCode);
        if (scope.error() != null) return errorResult(scope.error());

        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (Order.OrderStatus status : TRACKED_ORDER_STATUSES) {
            long count = scope.warehouseId() != null
                    ? orderRepository.countByAssignedWarehouseIdAndStatus(scope.warehouseId(), status)
                    : orderRepository.countByStatus(status);
            byStatus.put(status.name(), count);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.label());
        result.put("ordersByStatus", byStatus);
        return result;
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private record WarehouseScope(UUID warehouseId, String label, String error) {}

    private WarehouseScope resolveScope(String warehouseCode) {
        if (!isAdmin) {
            return new WarehouseScope(scopeWarehouseId, "chi nhánh của bạn (" + scopeWarehouseLabel + ")", null);
        }
        if (warehouseCode == null || warehouseCode.isBlank()) {
            return new WarehouseScope(null, "toàn hệ thống", null);
        }
        return warehouseRepository.findByCode(warehouseCode.trim().toUpperCase())
                .map(w -> new WarehouseScope(w.getId(), "chi nhánh " + w.getName(), null))
                .orElseGet(() -> new WarehouseScope(null, null,
                        "Không tìm thấy chi nhánh với mã '" + warehouseCode + "'. Hãy hỏi lại người dùng mã chi nhánh chính xác."));
    }

    private Map<String, Object> errorResult(String message) {
        return Map.of("error", message);
    }

    private Instant[] resolveDateRange(String fromDate, String toDate) {
        LocalDate to;
        try {
            to = (toDate == null || toDate.isBlank()) ? LocalDate.now(ZONE) : LocalDate.parse(toDate.trim());
        } catch (Exception e) {
            to = LocalDate.now(ZONE);
        }
        LocalDate from;
        try {
            from = (fromDate == null || fromDate.isBlank()) ? to.minusDays(30) : LocalDate.parse(fromDate.trim());
        } catch (Exception e) {
            from = to.minusDays(30);
        }
        Instant fromInstant = from.atStartOfDay(ZONE).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZONE).toInstant(); 
        return new Instant[]{fromInstant, toInstant};
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}