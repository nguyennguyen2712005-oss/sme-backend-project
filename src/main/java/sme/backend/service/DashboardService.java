package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.entity.Order;
import sme.backend.entity.Shift;
import sme.backend.entity.Warehouse;
import sme.backend.exception.BusinessException;
import sme.backend.repository.*;
import sme.backend.security.UserPrincipal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Dashboard Service — mỗi role có 1 method riêng, KHÔNG dùng chung rồi if/else.
 * Tách hẳn để không rò trường nhạy cảm khi thêm tính năng mới.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ShiftRepository shiftRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierDebtRepository supplierDebtRepository;
    private final UserRepository userRepository;

    // =====================================================================
    // CASHIER — "Ca làm việc của tôi"
    // =====================================================================
    @Transactional(readOnly = true)
    public Map<String, Object> getCashierDashboard(UserPrincipal principal) {
        UUID warehouseId = principal.getWarehouseId();
        if (warehouseId == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán chi nhánh.");
        }

        Map<String, Object> result = new LinkedHashMap<>();

        Optional<Shift> openShift = shiftRepository
                .findByCashierIdAndStatus(principal.getId(), Shift.ShiftStatus.OPEN);
        if (openShift.isPresent()) {
            Shift s = openShift.get();
            Map<String, Object> shiftInfo = new LinkedHashMap<>();
            shiftInfo.put("id", s.getId());
            shiftInfo.put("status", "OPEN");
            shiftInfo.put("openedAt", s.getOpenedAt());
            shiftInfo.put("startingCash", s.getStartingCash());
            result.put("shift", shiftInfo);

            // [FIX-9] Null-safe: sumRevenueByShift trả null khi chưa có hóa đơn nào
            BigDecimal revenue = invoiceRepository.sumRevenueByShift(s.getId());
            if (revenue == null) revenue = BigDecimal.ZERO;

            long invoiceCount = invoiceRepository.countByShiftId(s.getId());
            Map<String, Object> shiftStats = new LinkedHashMap<>();
            shiftStats.put("invoiceCount", invoiceCount);
            shiftStats.put("totalRevenue", revenue);
            result.put("currentShiftStats", shiftStats);
        } else {
            result.put("shift", Map.of("status", "NOT_OPEN"));
            result.put("currentShiftStats",
                    Map.of("invoiceCount", 0, "totalRevenue", BigDecimal.ZERO));
        }

        long pendingPackCount = orderRepository.countByAssignedWarehouseIdAndStatus(
                warehouseId, Order.OrderStatus.CONFIRMED);
        result.put("pendingPackCount", pendingPackCount);

        int lowStockCount = inventoryRepository.findLowStockByWarehouse(warehouseId).size();
        result.put("lowStockCount", lowStockCount);

        return result;
    }

    // =====================================================================
    // MANAGER — "Chi nhánh của tôi hôm nay"
    // =====================================================================
    @Transactional(readOnly = true)
    public Map<String, Object> getManagerDashboard(UserPrincipal principal) {
        UUID warehouseId = principal.getWarehouseId();
        if (warehouseId == null) {
            throw new BusinessException("NO_WAREHOUSE", "Tài khoản chưa được gán chi nhánh.");
        }
        return buildBranchDashboard(warehouseId);
    }

    private Map<String, Object> buildBranchDashboard(UUID warehouseId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Instant todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Instant todayEnd = todayStart.plus(1, ChronoUnit.DAYS);
        Instant yesterdayStart = todayStart.minus(1, ChronoUnit.DAYS);
        Instant monthStart = LocalDate.now(ZONE).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
        Instant lastMonthStart = LocalDate.now(ZONE).minusMonths(1)
                .withDayOfMonth(1).atStartOfDay(ZONE).toInstant();

        BigDecimal revenueToday = sumRevenueField(
                invoiceRepository.getRevenueReportDaily(warehouseId, todayStart, todayEnd));
        BigDecimal revenueYesterday = sumRevenueField(
                invoiceRepository.getRevenueReportDaily(warehouseId, yesterdayStart, todayStart));
        BigDecimal revenueThisMonth = sumRevenueField(
                invoiceRepository.getRevenueReportDaily(warehouseId, monthStart, todayEnd));
        BigDecimal revenueLastMonth = sumRevenueField(
                invoiceRepository.getRevenueReportDaily(warehouseId, lastMonthStart, monthStart));

        Map<String, Object> revenue = new LinkedHashMap<>();
        revenue.put("today", revenueToday);
        revenue.put("todayVsYesterdayPercent", percentChange(revenueYesterday, revenueToday));
        revenue.put("thisMonth", revenueThisMonth);
        revenue.put("monthVsLastMonthPercent", percentChange(revenueLastMonth, revenueThisMonth));
        result.put("revenue", revenue);

        long posCount = invoiceRepository.countPOSInvoices(warehouseId, todayStart, todayEnd);
        long onlineCount = orderRepository.countByAssignedWarehouseIdAndCreatedAtBetween(
                warehouseId, todayStart, todayEnd);
        result.put("invoiceCount", Map.of("pos", posCount, "online", onlineCount));

        List<Shift> pendingShiftsRaw = shiftRepository
                .findByWarehouseIdAndStatus(warehouseId, Shift.ShiftStatus.CLOSED);
        List<Map<String, Object>> pendingShifts = pendingShiftsRaw.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shiftId", s.getId());
            m.put("cashierId", s.getCashierId());
            m.put("closedAt", s.getClosedAt());
            m.put("discrepancyAmount", s.getDiscrepancyAmount());
            return m;
        }).toList();
        result.put("pendingShifts", pendingShifts);

        long pendingConfirm = orderRepository.countByAssignedWarehouseIdAndStatus(
                warehouseId, Order.OrderStatus.PENDING);
        long pendingPack = orderRepository.countByAssignedWarehouseIdAndStatus(
                warehouseId, Order.OrderStatus.CONFIRMED);
        result.put("orders", Map.of("pendingConfirm", pendingConfirm, "pendingPack", pendingPack));

        result.put("lowStockCount",
                inventoryRepository.findLowStockByWarehouse(warehouseId).size());

        LocalDate dueBefore = LocalDate.now(ZONE).plusDays(7);
        result.put("upcomingDebts",
                supplierDebtRepository.findDueSoonByWarehouse(warehouseId, dueBefore).size());

        return result;
    }

    // =====================================================================
    // ADMIN — "Toàn chuỗi — Giám sát, không can thiệp trực tiếp"
    // =====================================================================
    @Transactional(readOnly = true)
    public Map<String, Object> getAdminDashboard() {
        Map<String, Object> result = new LinkedHashMap<>();

        Instant todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Instant todayEnd = todayStart.plus(1, ChronoUnit.DAYS);
        Instant monthStart = LocalDate.now(ZONE).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();

        BigDecimal totalToday = sumRevenueField(
                invoiceRepository.getRevenueReportDaily(null, todayStart, todayEnd));
        BigDecimal totalMonth = sumRevenueField(
                invoiceRepository.getRevenueReportDaily(null, monthStart, todayEnd));
        result.put("totalRevenue", Map.of("today", totalToday, "thisMonth", totalMonth));

        List<Warehouse> branches = warehouseRepository.findByIsActiveTrueOrderByName();
        Instant yesterdayStart = todayStart.minus(1, ChronoUnit.DAYS);

        List<Map<String, Object>> revenueByBranch = new ArrayList<>();
        List<Map<String, Object>> alertBranches = new ArrayList<>();

        for (Warehouse w : branches) {
            BigDecimal today = sumRevenueField(
                    invoiceRepository.getRevenueReportDaily(w.getId(), todayStart, todayEnd));
            BigDecimal yesterday = sumRevenueField(
                    invoiceRepository.getRevenueReportDaily(w.getId(), yesterdayStart, todayStart));

            Map<String, Object> branchRevenue = new LinkedHashMap<>();
            branchRevenue.put("warehouseId", w.getId());
            branchRevenue.put("name", w.getName());
            branchRevenue.put("today", today);
            branchRevenue.put("yesterday", yesterday);
            revenueByBranch.add(branchRevenue);

            List<String> issues = new ArrayList<>();
            long pendingShiftsCount = shiftRepository
                    .findByWarehouseIdAndStatus(w.getId(), Shift.ShiftStatus.CLOSED).size();
            if (pendingShiftsCount > 0) issues.add(pendingShiftsCount + " ca chờ duyệt");
            int lowStock = inventoryRepository.findLowStockByWarehouse(w.getId()).size();
            if (lowStock > 0) issues.add(lowStock + " sản phẩm sắp hết hàng");

            if (!issues.isEmpty()) {
                String managerName = null;
                if (w.getManagerId() != null) {
                    managerName = userRepository.findById(w.getManagerId())
                            .map(sme.backend.entity.User::getFullName).orElse(null);
                }
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("warehouseId", w.getId());
                alert.put("name", w.getName());
                alert.put("managerName", managerName);
                alert.put("issues", issues);
                alertBranches.add(alert);
            }
        }
        result.put("revenueByBranch", revenueByBranch);
        result.put("alertBranches", alertBranches);

        long totalOnlineOrdersToday = orderRepository
                .countAllByCreatedAtBetween(todayStart, todayEnd);
        result.put("totalOnlineOrders", Map.of("today", totalOnlineOrdersToday));
        result.put("totalSupplierDebt",
                supplierDebtRepository.getTotalOutstandingDebtAllWarehouses());

        return result;
    }

    // =====================================================================
    // HELPERS
    // =====================================================================
    private BigDecimal sumRevenueField(List<Map<String, Object>> rows) {
        BigDecimal total = BigDecimal.ZERO;
        if (rows == null) return total;
        for (Map<String, Object> row : rows) {
            Object v = row.get("revenue");
            if (v instanceof Number n) {
                total = total.add(BigDecimal.valueOf(n.doubleValue()));
            }
        }
        return total;
    }

    private double percentChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return (current != null && current.compareTo(BigDecimal.ZERO) > 0) ? 100.0 : 0.0;
        }
        return current.subtract(previous)
                .divide(previous, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }
}
