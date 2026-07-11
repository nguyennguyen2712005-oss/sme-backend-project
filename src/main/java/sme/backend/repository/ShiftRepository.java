package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Shift;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByCashierIdAndStatus(UUID cashierId, Shift.ShiftStatus status);

    boolean existsByCashierIdAndStatus(UUID cashierId, Shift.ShiftStatus status);

    Page<Shift> findByWarehouseIdOrderByOpenedAtDesc(UUID warehouseId, Pageable pageable);

    // Danh sách ca theo kho — dùng cho MANAGER
    List<Shift> findByWarehouseIdAndStatus(UUID warehouseId, Shift.ShiftStatus status);

    // [FIX-6] Admin: xem toàn bộ ca chờ duyệt (tất cả chi nhánh).
    // ShiftRepository trước đây chỉ có findByWarehouseIdAndStatus(),
    // query WHERE warehouse_id = NULL luôn trả 0 rows → Admin thấy list rỗng.
    @Query("""
        SELECT s FROM Shift s
        WHERE s.status = :status
        ORDER BY s.closedAt DESC
        """)
    List<Shift> findAllByStatus(@Param("status") Shift.ShiftStatus status);

    @Query("""
        SELECT s FROM Shift s
        WHERE s.cashierId = :cashierId
        AND s.status = 'MANAGER_APPROVED'
        ORDER BY s.closedAt DESC
        """)
    List<Shift> findLatestApprovedByCashier(@Param("cashierId") UUID cashierId, Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(ct.amount), 0)
        FROM CashbookTransaction ct
        WHERE ct.shiftId = :shiftId
        AND ct.fundType = 'CASH_111'
        AND ct.transactionType = 'IN'
        """)
    java.math.BigDecimal sumCashInByShift(@Param("shiftId") UUID shiftId);

    @Query("""
        SELECT COALESCE(SUM(ct.amount), 0)
        FROM CashbookTransaction ct
        WHERE ct.shiftId = :shiftId
        AND ct.fundType = 'CASH_111'
        AND ct.transactionType = 'OUT'
        """)
    java.math.BigDecimal sumCashOutByShift(@Param("shiftId") UUID shiftId);
}
