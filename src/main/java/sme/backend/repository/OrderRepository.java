
package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByCode(String code);
    boolean existsByCode(String code);

    // =========================================================================
    // QUERIES FOR PENDING & STATUS
    // =========================================================================

    @Query("""
        SELECT o FROM Order o
        WHERE o.assignedWarehouseId = :wid
        AND o.status IN ('PENDING','CONFIRMED')
        ORDER BY o.createdAt ASC
        """)
    List<Order> findPendingOrdersByWarehouse(@Param("wid") UUID warehouseId);

    @Query("""
        SELECT o FROM Order o
        WHERE o.status IN ('PENDING','CONFIRMED')
        ORDER BY o.createdAt ASC
        """)
    List<Order> findAllPendingOrders();

    @Query("""
        SELECT o FROM Order o
        WHERE o.assignedWarehouseId = :wid
        AND o.type = 'BOPIS'
        AND o.status = 'READY_FOR_PICKUP'
        ORDER BY o.createdAt ASC
        """)
    List<Order> findBOPISReadyByWarehouse(@Param("wid") UUID warehouseId);

    long countByAssignedWarehouseIdAndStatus(UUID warehouseId, Order.OrderStatus status);

    // =========================================================================
    // QUERIES FOR DASHBOARD (COUNT)
    // =========================================================================

    @Query("""
        SELECT COUNT(o) FROM Order o
        WHERE o.createdAt BETWEEN :from AND :to
        """)
    long countAllByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
        SELECT COUNT(o) FROM Order o
        WHERE o.assignedWarehouseId = :wid
        AND o.createdAt BETWEEN :from AND :to
        """)
    long countByAssignedWarehouseIdAndCreatedAtBetween(@Param("wid") UUID warehouseId, @Param("from") Instant from, @Param("to") Instant to);

    Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    Page<Order> findByAssignedWarehouseIdAndStatusOrderByCreatedAtDesc(UUID warehouseId, Order.OrderStatus status, Pageable pageable);

    @Query("""
        SELECT o FROM Order o
        WHERE o.paymentMethod = 'COD'
        AND o.status = 'DELIVERED'
        AND o.codReconciled = false
        """)
    List<Order> findUnreconciledCODOrders();

    List<Order> findByAssignedWarehouseIdIsNullAndStatus(Order.OrderStatus status);

    @Query("""
        SELECT DISTINCT o FROM Order o
        LEFT JOIN FETCH o.items
        WHERE o.id = :id
        """)
    Optional<Order> findByIdWithDetails(@Param("id") UUID id);

    // =========================================================================
    // TÌM KIẾM TOÀN HỆ THỐNG (ADMIN) VÀ THEO CHI NHÁNH (MANAGER/CASHIER)
    // Dùng danh sách IN để tránh lỗi PostgreSQL Cast
    // =========================================================================

    @Query("""
        SELECT o FROM Order o
        WHERE o.status IN :statuses
        AND o.type IN :types
        AND (:keyword = ''
             OR LOWER(o.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingName) LIKE LOWER(CONCAT('%', :keyword, '%'))
             )
        ORDER BY o.createdAt DESC
        """)
    Page<Order> searchAllOrders(@Param("statuses") List<Order.OrderStatus> statuses,
                                @Param("types") List<Order.OrderType> types,
                                @Param("keyword") String keyword,
                                Pageable pageable);

    @Query("""
        SELECT o FROM Order o
        WHERE o.assignedWarehouseId = :warehouseId
        AND o.status IN :statuses
        AND o.type IN :types
        AND (:keyword = ''
             OR LOWER(o.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingName) LIKE LOWER(CONCAT('%', :keyword, '%'))
             )
        ORDER BY o.createdAt DESC
        """)
    Page<Order> searchOrdersByWarehouse(@Param("warehouseId") UUID warehouseId,
                                        @Param("statuses") List<Order.OrderStatus> statuses,
                                        @Param("types") List<Order.OrderType> types,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);
}
