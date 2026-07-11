
package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sme.backend.entity.InventoryTransaction;

import java.util.List;
import java.util.UUID;

@Repository
public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, UUID> {
    
    // ĐÃ SỬA: Thay List thành Page và thêm Pageable để phân trang
    Page<InventoryTransaction> findByInventoryIdOrderByCreatedAtDesc(UUID inventoryId, Pageable pageable);
    
    List<InventoryTransaction> findByReferenceId(UUID referenceId);
}
