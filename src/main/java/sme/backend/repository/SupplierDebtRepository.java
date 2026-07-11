package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.SupplierDebt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupplierDebtRepository extends JpaRepository<SupplierDebt, UUID> {

    Optional<SupplierDebt> findByPurchaseOrderId(UUID purchaseOrderId);

    List<SupplierDebt> findBySupplierIdAndStatusNot(UUID supplierId, SupplierDebt.DebtStatus status);

    List<SupplierDebt> findByStatus(SupplierDebt.DebtStatus status);

    List<SupplierDebt> findByStatusNot(SupplierDebt.DebtStatus status);

    @Query("""
        SELECT COALESCE(SUM(sd.totalDebt - sd.paidAmount), 0)
        FROM SupplierDebt sd
        WHERE sd.supplierId = :sid
        AND sd.status != 'PAID'
        """)
    BigDecimal getTotalOutstandingBySupplierId(@Param("sid") UUID sid);

    // Tính tổng nợ NCC theo Chi nhánh
    @Query("""
        SELECT COALESCE(SUM(sd.totalDebt - sd.paidAmount), 0)
        FROM SupplierDebt sd
        WHERE sd.supplierId = :sid
        AND sd.status != 'PAID'
        AND sd.purchaseOrderId IN (SELECT po.id FROM PurchaseOrder po WHERE po.warehouseId = :warehouseId)
        """)
    BigDecimal getTotalOutstandingBySupplierAndWarehouse(@Param("sid") UUID sid, @Param("warehouseId") UUID warehouseId);

    // [ĐÃ THÊM] Tính tổng nợ NCC toàn hệ thống (Dành cho Admin Dashboard)
    @Query("""
        SELECT COALESCE(SUM(sd.totalDebt - sd.paidAmount), 0)
        FROM SupplierDebt sd
        WHERE sd.status != 'PAID'
        """)
    BigDecimal getTotalOutstandingDebtAllWarehouses();

    @Query("SELECT sd FROM SupplierDebt sd WHERE sd.status != 'PAID'")
    List<SupplierDebt> findAllOutstandingDebts();

    @Query("SELECT sd FROM SupplierDebt sd WHERE sd.status != 'PAID' " +
           "AND sd.purchaseOrderId IN (SELECT po.id FROM PurchaseOrder po WHERE po.warehouseId = :warehouseId)")
    List<SupplierDebt> findOutstandingDebtsByWarehouse(@Param("warehouseId") UUID warehouseId);

    @Query("""
        SELECT sd FROM SupplierDebt sd
        WHERE sd.status != 'PAID'
        AND sd.dueDate IS NOT NULL
        AND sd.dueDate <= :dueBefore
        """)
    List<SupplierDebt> findAllDueSoon(@Param("dueBefore") java.time.LocalDate dueBefore);

    @Query("""
        SELECT sd FROM SupplierDebt sd
        WHERE sd.status != 'PAID'
        AND sd.dueDate IS NOT NULL
        AND sd.dueDate <= :dueBefore
        AND sd.purchaseOrderId IN (SELECT po.id FROM PurchaseOrder po WHERE po.warehouseId = :warehouseId)
        """)
    List<SupplierDebt> findDueSoonByWarehouse(@Param("warehouseId") UUID warehouseId,
                                              @Param("dueBefore") java.time.LocalDate dueBefore);
}