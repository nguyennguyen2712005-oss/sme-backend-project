package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * StockTakeItem — Một dòng trong phiếu kiểm kê
 *
 * Cột discrepancy là GENERATED ALWAYS AS (actual_quantity - system_quantity) STORED
 * trong PostgreSQL. JPA map bằng @Column(insertable=false, updatable=false).
 * Giá trị do DB tính, chỉ đọc từ Java.
 *
 * Giá trị discrepancy:
 *  > 0 : Thừa (actual > system) — có thể do thất thoát ghi chép
 *  < 0 : Thiếu (actual < system) — có thể do mất hàng, hỏng, sai hệ thống
 *  = 0 : Khớp
 */
@Entity
@Table(name = "stock_take_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTakeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stock_take_id", nullable = false)
    private UUID stockTakeId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /**
     * Số lượng tồn kho theo hệ thống tại thời điểm tạo phiếu.
     * Snapshot một lần khi add sản phẩm vào phiếu, không tự động cập nhật.
     */
    @Column(name = "system_quantity", nullable = false)
    private Integer systemQuantity;

    /**
     * Số lượng thực tế đếm được trong kho.
     * Null khi mới tạo, phải nhập trước khi complete phiếu.
     */
    @Column(name = "actual_quantity")
    private Integer actualQuantity;

    /**
     * Chênh lệch = actual - system. Do PostgreSQL tự tính (GENERATED ALWAYS AS).
     * insertable=false, updatable=false để JPA không cố ghi vào cột này.
     */
    @Column(name = "discrepancy",
            insertable = false,
            updatable = false)
    private Integer discrepancy;

    // ─── Helper ─────────────────────────────────────────────

    public boolean hasDiscrepancy() {
        return discrepancy != null && discrepancy != 0;
    }

    public boolean isOverage() {
        return discrepancy != null && discrepancy > 0;
    }

    public boolean isShortage() {
        return discrepancy != null && discrepancy < 0;
    }

    /**
     * Tính discrepancy tạm thời ở Java-side (trước khi DB tính lại).
     * Dùng trong service trước khi flush để check logic.
     */
    public int computeDiscrepancy() {
        if (actualQuantity == null || systemQuantity == null) return 0;
        return actualQuantity - systemQuantity;
    }
}
