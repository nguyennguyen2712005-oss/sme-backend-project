
package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Warehouse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    List<Warehouse> findByIsActiveTrueOrderByName();

    Optional<Warehouse> findByCode(String code);

    boolean existsByCode(String code);

    List<Warehouse> findByProvinceCodeAndIsActiveTrue(String provinceCode);

    // BỔ SUNG: Tìm tất cả các kho do một người cụ thể làm quản lý
    List<Warehouse> findByManagerIdAndIsActiveTrue(UUID managerId);
}
