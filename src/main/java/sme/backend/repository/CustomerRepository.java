
package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Customer;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    // POS: Định danh khách hàng qua SĐT (F3)
    Optional<Customer> findByPhoneNumberAndIsActiveTrue(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    // ĐÃ NÂNG CẤP: Gộp cả tìm kiếm keyword và lọc theo hạng thẻ (tier) vào một Query linh hoạt
    @Query("SELECT c FROM Customer c WHERE c.isActive = true " +
           "AND (:tier IS NULL OR CAST(c.customerTier AS string) = :tier) " +
           "AND (:kw IS NULL OR :kw = '' " +
           "OR LOWER(c.fullName) LIKE LOWER(CONCAT('%', :kw, '%')) " +
           "OR c.phoneNumber LIKE CONCAT('%', REPLACE(:kw, ' ', ''), '%') " +
           "OR LOWER(c.email) LIKE LOWER(CONCAT('%', :kw, '%')))")
    Page<Customer> searchWithFilters(@Param("kw") String keyword, 
                                     @Param("tier") String tier, 
                                     Pageable pageable);

    // Top khách hàng theo tổng chi tiêu
    @Query("""
        SELECT c FROM Customer c
        WHERE c.isActive = true
        ORDER BY c.totalSpent DESC
        """)
    Page<Customer> findTopCustomers(Pageable pageable);
}
