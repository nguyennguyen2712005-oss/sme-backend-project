package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.StockTake;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockTakeRepository extends JpaRepository<StockTake, UUID> {

    boolean existsByCode(String code);

    /** Fetch kèm items để tránh N+1 khi xem chi tiết */
    @Query("""
        SELECT DISTINCT st FROM StockTake st
        LEFT JOIN FETCH st.items
        WHERE st.id = :id
        """)
    Optional<StockTake> findByIdWithItems(@Param("id") UUID id);

    /** Danh sách phiếu theo kho, sắp xếp mới nhất trước */
    Page<StockTake> findByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId, Pageable pageable);

    /** Admin xem tất cả phiếu trong hệ thống */
    Page<StockTake> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Phiếu đang DRAFT hoặc IN_PROGRESS của kho — không cho tạo thêm nếu có */
    @Query("""
        SELECT COUNT(st) FROM StockTake st
        WHERE st.warehouseId = :warehouseId
        AND st.status IN ('DRAFT', 'IN_PROGRESS')
        """)
    long countActiveByWarehouse(@Param("warehouseId") UUID warehouseId);

    List<StockTake> findByWarehouseIdAndStatus(UUID warehouseId, StockTake.StockTakeStatus status);
}
