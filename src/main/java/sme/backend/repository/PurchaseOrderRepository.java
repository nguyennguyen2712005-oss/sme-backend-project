
package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.PurchaseOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Optional<PurchaseOrder> findByCode(String code);
    boolean existsByCode(String code);

    @Query("""
        SELECT po FROM PurchaseOrder po
        WHERE po.status IN :statuses
        AND (:keyword = '' OR LOWER(po.code) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY po.createdAt DESC
        """)
    Page<PurchaseOrder> searchAllPurchaseOrders(
            @Param("statuses") List<PurchaseOrder.PurchaseStatus> statuses,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
        SELECT po FROM PurchaseOrder po
        WHERE po.warehouseId = :warehouseId
        AND po.status IN :statuses
        AND (:keyword = '' OR LOWER(po.code) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY po.createdAt DESC
        """)
    Page<PurchaseOrder> searchPurchaseOrdersByWarehouse(
            @Param("warehouseId") UUID warehouseId,
            @Param("statuses") List<PurchaseOrder.PurchaseStatus> statuses,
            @Param("keyword") String keyword,
            Pageable pageable);

    Page<PurchaseOrder> findBySupplierIdOrderByCreatedAtDesc(UUID supplierId, Pageable pageable);

    @Query("""
        SELECT po FROM PurchaseOrder po
        LEFT JOIN FETCH po.items
        WHERE po.id = :id
        """)
    Optional<PurchaseOrder> findByIdWithItems(@Param("id") UUID id);
}
