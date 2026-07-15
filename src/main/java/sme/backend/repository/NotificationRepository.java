package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Notification;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.isRead = false ORDER BY n.createdAt DESC")
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(@Param("userId") UUID userId);

    @Query("SELECT n FROM Notification n WHERE n.userId IS NULL AND n.isRead = false ORDER BY n.createdAt DESC")
    List<Notification> findByUserIdIsNullAndIsReadFalseOrderByCreatedAtDesc();

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.isRead = false")
    long countByUserIdAndIsReadFalse(@Param("userId") UUID userId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId IS NULL AND n.isRead = false")
    long countByUserIdIsNullAndIsReadFalse();

    // =========================================================================
    // ĐÃ THÊM: QUERIES DÀNH RIÊNG CHO MANAGER
    // Manager thấy thông báo cá nhân LẪN thông báo chung của đúng chi nhánh mình
    // =========================================================================

    @Query("SELECT n FROM Notification n WHERE n.isRead = false AND " +
           "(n.userId = :userId OR (n.userId IS NULL AND n.warehouseId = :warehouseId)) " +
           "ORDER BY n.createdAt DESC")
    List<Notification> findUnreadForManager(@Param("userId") UUID userId, @Param("warehouseId") UUID warehouseId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.isRead = false AND " +
           "(n.userId = :userId OR (n.userId IS NULL AND n.warehouseId = :warehouseId))")
    long countUnreadForManager(@Param("userId") UUID userId, @Param("warehouseId") UUID warehouseId);
}