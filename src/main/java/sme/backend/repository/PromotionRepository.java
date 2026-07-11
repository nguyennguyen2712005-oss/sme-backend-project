package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Promotion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    Optional<Promotion> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Tìm khuyến mãi đang hoạt động (dùng trong POS để validate) */
    @Query("""
        SELECT p FROM Promotion p
        WHERE UPPER(p.code) = UPPER(:code)
          AND p.isActive = true
          AND :now BETWEEN p.startDate AND p.endDate
          AND (p.usageLimit IS NULL OR p.usedCount < p.usageLimit)
        """)
    Optional<Promotion> findActiveByCode(@Param("code") String code, @Param("now") Instant now);

    /** Tìm tất cả khuyến mãi đang hoạt động (cho dropdown POS) */
    @Query("""
        SELECT p FROM Promotion p
        WHERE p.isActive = true
          AND :now BETWEEN p.startDate AND p.endDate
          AND (p.usageLimit IS NULL OR p.usedCount < p.usageLimit)
        ORDER BY p.endDate ASC
        """)
    List<Promotion> findAllActive(@Param("now") Instant now);

    Page<Promotion> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT p FROM Promotion p
        WHERE (:keyword = '' OR LOWER(p.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY p.createdAt DESC
        """)
    Page<Promotion> search(@Param("keyword") String keyword, Pageable pageable);

    /** Tăng usedCount an toàn (Optimistic approach — tránh race condition khi 2 người dùng cùng lúc) */
    @Modifying
    @Query("UPDATE Promotion p SET p.usedCount = p.usedCount + 1 WHERE p.id = :id")
    int incrementUsedCount(@Param("id") UUID id);
}
