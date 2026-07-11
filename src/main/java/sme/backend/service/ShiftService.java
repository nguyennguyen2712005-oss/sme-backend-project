package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CloseShiftRequest;
import sme.backend.dto.request.OpenShiftRequest;
import sme.backend.dto.response.ShiftResponse;
import sme.backend.entity.Shift;
import sme.backend.entity.User;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.InvoiceRepository;
import sme.backend.repository.ShiftRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final InvoiceRepository invoiceRepository;
    private final NotificationService notificationService;

    // ─────────────────────────────────────────────────────────
    // MỞ CA (POS-01)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public ShiftResponse openShift(UUID cashierId, UUID warehouseId, OpenShiftRequest req) {
        if (shiftRepository.existsByCashierIdAndStatus(cashierId, Shift.ShiftStatus.OPEN)) {
            throw new BusinessException("SHIFT_ALREADY_OPEN",
                    "Thu ngân này đang có ca làm việc đang mở. Vui lòng đóng ca cũ trước.");
        }

        Shift shift = Shift.builder()
                .warehouseId(warehouseId)
                .cashierId(cashierId)
                .startingCash(req.getStartingCash())
                .status(Shift.ShiftStatus.OPEN)
                .build();

        shift = shiftRepository.save(shift);
        log.info("Shift opened: {} by cashier: {}", shift.getId(), cashierId);
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // ĐÓNG CA MÙ (Blind Close)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public ShiftResponse closeShift(UUID cashierId, CloseShiftRequest req) {
        Shift shift = shiftRepository
                .findByCashierIdAndStatus(cashierId, Shift.ShiftStatus.OPEN)
                .orElseThrow(() -> new BusinessException("NO_OPEN_SHIFT",
                        "Không tìm thấy ca làm việc đang mở cho thu ngân này"));

        BigDecimal cashIn  = shiftRepository.sumCashInByShift(shift.getId());
        BigDecimal cashOut = shiftRepository.sumCashOutByShift(shift.getId());
        if (cashIn == null)  cashIn  = BigDecimal.ZERO;
        if (cashOut == null) cashOut = BigDecimal.ZERO;

        BigDecimal theoretical = shift.getStartingCash().add(cashIn).subtract(cashOut);
        shift.closeShift(req.getReportedCash(), theoretical, req.getDiscrepancyReason());
        shift = shiftRepository.save(shift);

        notificationService.notifyShiftClosed(shift);
        log.info("Shift closed: {} | theoretical={} | reported={} | discrepancy={}",
                shift.getId(), theoretical, req.getReportedCash(), shift.getDiscrepancyAmount());

        // [FIX-3] mapToResponse được gọi ở đây — Controller sẽ mask theoreticalCash
        // tuỳ theo role (xem POSController.closeShift).
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // DUYỆT CHỐT CA (Manager / Admin)
    // ─────────────────────────────────────────────────────────
    /**
     * [FIX-5] Thêm tham số managerWarehouseId để đảm bảo Manager chỉ duyệt
     * ca của chi nhánh mình. Trước đây bất kỳ Manager nào cũng duyệt được
     * bất kỳ ca nào nếu biết shiftId.
     *
     * @param shiftId             UUID ca cần duyệt
     * @param managerId           UUID người duyệt
     * @param approverWarehouseId warehouseId của người duyệt (null = ADMIN, không giới hạn)
     * @param approverRole        Role của người duyệt
     */
    @Transactional
    public ShiftResponse approveShift(UUID shiftId, UUID managerId,
                                       UUID approverWarehouseId, User.UserRole approverRole) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", shiftId));

        // [FIX-5] Manager chỉ được duyệt ca của chi nhánh mình.
        // Admin (warehouseId = null) không bị giới hạn.
        if (approverRole == User.UserRole.ROLE_MANAGER) {
            if (approverWarehouseId == null
                    || !approverWarehouseId.equals(shift.getWarehouseId())) {
                throw new BusinessException("ACCESS_DENIED",
                        "Bạn chỉ có thể duyệt ca làm việc của chi nhánh mình.");
            }
        }

        shift.approve(managerId);
        shift = shiftRepository.save(shift);
        log.info("Shift approved: {} by: {} (role={})", shiftId, managerId, approverRole);
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // QUERIES
    // ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Shift getOpenShiftByCashier(UUID cashierId) {
        return shiftRepository.findByCashierIdAndStatus(cashierId, Shift.ShiftStatus.OPEN)
                .orElseThrow(() -> new BusinessException("NO_OPEN_SHIFT",
                        "Thu ngân chưa mở ca. Vui lòng mở ca trước khi bán hàng."));
    }

    /**
     * [FIX-6] Trước đây hàm này gọi findByWarehouseIdAndStatus(null, CLOSED)
     * khi Admin dùng → SQL WHERE warehouse_id = NULL luôn trả 0 rows. Admin không
     * thấy được bất kỳ ca nào đang chờ duyệt.
     *
     * Giờ: warehouseId = null → Admin → dùng findAllByStatus() (không lọc kho).
     */
    @Transactional(readOnly = true)
    public List<ShiftResponse> getPendingShifts(UUID warehouseId) {
        List<Shift> shifts;
        if (warehouseId == null) {
            // Admin: xem toàn bộ ca chờ duyệt trong hệ thống
            shifts = shiftRepository.findAllByStatus(Shift.ShiftStatus.CLOSED);
        } else {
            // Manager: chỉ thấy ca của chi nhánh mình
            shifts = shiftRepository.findByWarehouseIdAndStatus(
                    warehouseId, Shift.ShiftStatus.CLOSED);
        }
        return shifts.stream().map(this::mapToResponse).toList();
    }

    // ─────────────────────────────────────────────────────────
    // MAPPER
    // ─────────────────────────────────────────────────────────
    public ShiftResponse mapToResponse(Shift shift) {
        BigDecimal revenue = BigDecimal.ZERO;
        if (shift.getId() != null) {
            try {
                BigDecimal r = invoiceRepository.sumRevenueByShift(shift.getId());
                if (r != null) revenue = r;
            } catch (Exception ignored) {}
        }
        return ShiftResponse.builder()
                .id(shift.getId())
                .warehouseId(shift.getWarehouseId())
                .cashierId(shift.getCashierId())
                .startingCash(shift.getStartingCash())
                .reportedCash(shift.getReportedCash())
                .theoreticalCash(shift.getTheoreticalCash())
                .discrepancyAmount(shift.getDiscrepancyAmount())
                .discrepancyReason(shift.getDiscrepancyReason())
                .status(shift.getStatus().name())
                .openedAt(shift.getOpenedAt())
                .closedAt(shift.getClosedAt())
                .approvedAt(shift.getApprovedAt())
                .totalRevenue(revenue)
                .build();
    }
}
