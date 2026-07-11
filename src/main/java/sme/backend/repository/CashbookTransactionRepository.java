
package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.CashbookTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface CashbookTransactionRepository extends JpaRepository<CashbookTransaction, UUID> {

    List<CashbookTransaction> findByShiftIdOrderByCreatedAtAsc(UUID shiftId);

    Page<CashbookTransaction> findByWarehouseIdAndFundTypeOrderByCreatedAtDesc(
            UUID warehouseId,
            CashbookTransaction.FundType fundType,
            Pageable pageable);

    @Query("""
        SELECT SUM(
            CASE WHEN ct.transactionType = 'IN' THEN ct.amount
                 ELSE -ct.amount END
        )
        FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.fundType = :fundType
        """)
    BigDecimal getCurrentBalanceByWarehouse(@Param("wid") UUID warehouseId,
                                            @Param("fundType") CashbookTransaction.FundType fundType);

    @Query("""
        SELECT SUM(
            CASE WHEN ct.transactionType = 'IN' THEN ct.amount
                 ELSE -ct.amount END
        )
        FROM CashbookTransaction ct
        WHERE ct.fundType = :fundType
        """)
    BigDecimal getCurrentBalanceAll(@Param("fundType") CashbookTransaction.FundType fundType);

    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.createdAt BETWEEN :from AND :to
        ORDER BY ct.createdAt DESC
        """)
    List<CashbookTransaction> findByWarehouseAndDateRange(
            @Param("wid")  UUID warehouseId,
            @Param("from") Instant from,
            @Param("to")   Instant to);

    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.createdAt BETWEEN :from AND :to
        ORDER BY ct.createdAt DESC
        """)
    List<CashbookTransaction> findAllByDateRange(
            @Param("from") Instant from,
            @Param("to")   Instant to);

    // =========================================================================
    // HÀM TÌM KIẾM CÓ PHÂN TRANG (Đã sửa lỗi PostgreSQL Null Parameter)
    // =========================================================================
    
    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.createdAt BETWEEN :from AND :to
        AND ct.fundType IN :fundTypes
        AND ct.transactionType IN :txnTypes
        AND (:keyword = '' OR LOWER(ct.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(ct.referenceType) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<CashbookTransaction> searchCashbookAll(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("fundTypes") List<CashbookTransaction.FundType> fundTypes,
            @Param("txnTypes") List<CashbookTransaction.TransactionType> txnTypes,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.createdAt BETWEEN :from AND :to
        AND ct.fundType IN :fundTypes
        AND ct.transactionType IN :txnTypes
        AND (:keyword = '' OR LOWER(ct.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(ct.referenceType) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<CashbookTransaction> searchCashbookByWarehouse(
            @Param("wid") UUID warehouseId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("fundTypes") List<CashbookTransaction.FundType> fundTypes,
            @Param("txnTypes") List<CashbookTransaction.TransactionType> txnTypes,
            @Param("keyword") String keyword,
            Pageable pageable);
}
