package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.StockTakeItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockTakeItemRepository extends JpaRepository<StockTakeItem, UUID> {

    List<StockTakeItem> findByStockTakeId(UUID stockTakeId);

    Optional<StockTakeItem> findByStockTakeIdAndProductId(UUID stockTakeId, UUID productId);

    boolean existsByStockTakeIdAndProductId(UUID stockTakeId, UUID productId);

    /** Xóa toàn bộ items của một phiếu kiểm kê */
    @Modifying
    @Query("DELETE FROM StockTakeItem i WHERE i.stockTakeId = :stockTakeId")
    void deleteAllByStockTakeId(@Param("stockTakeId") UUID stockTakeId);

    /** Số lượng items chưa nhập actual_quantity */
    @Query("""
        SELECT COUNT(i) FROM StockTakeItem i
        WHERE i.stockTakeId = :stockTakeId
        AND i.actualQuantity IS NULL
        """)
    long countUnfilledItems(@Param("stockTakeId") UUID stockTakeId);

    /** Items có chênh lệch (dùng Java-side vì GENERATED column chưa flush) */
    @Query("""
        SELECT i FROM StockTakeItem i
        WHERE i.stockTakeId = :stockTakeId
        AND i.actualQuantity IS NOT NULL
        AND i.actualQuantity != i.systemQuantity
        """)
    List<StockTakeItem> findItemsWithDiscrepancy(@Param("stockTakeId") UUID stockTakeId);
}
