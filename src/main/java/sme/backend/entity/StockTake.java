package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * StockTake — Phiếu Kiểm kê tồn kho
 *
 * Vòng đời: DRAFT → IN_PROGRESS → COMPLETED → APPROVED
 *                                            ↘ CANCELLED (từ DRAFT/IN_PROGRESS)
 *
 * DRAFT:       Manager tạo phiếu, chọn sản phẩm cần kiểm, hệ thống tự điền
 *              system_quantity = số tồn kho hiện tại.
 * IN_PROGRESS: Đội kho bắt đầu đếm thực tế, nhập actual_quantity từng dòng.
 * COMPLETED:   Đã nhập xong tất cả actual_quantity, hệ thống tính discrepancy
 *              = actual - system. Chờ Manager/Admin xét duyệt.
 * APPROVED:    Manager/Admin duyệt → InventoryService.adjustInventory() chạy
 *              để cập nhật tồn kho thực tế theo kết quả kiểm.
 *              Mỗi dòng chênh lệch tạo 1 InventoryTransaction type=ADJUSTMENT.
 * CANCELLED:   Phiếu bị hủy trước khi APPROVED, không ảnh hưởng tồn kho.
 */
@Entity
@Table(name = "stock_takes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTake {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private StockTakeStatus status = StockTakeStatus.DRAFT;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "stockTakeId",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<StockTakeItem> items = new ArrayList<>();

    public void addItem(StockTakeItem item) {
        item.setStockTakeId(this.id);
        this.items.add(item);
    }

    // ─── Domain logic ───────────────────────────────────────

    public void startCounting() {
        if (this.status != StockTakeStatus.DRAFT) {
            throw new sme.backend.exception.BusinessException("INVALID_TRANSITION",
                    "Chỉ chuyển sang IN_PROGRESS từ trạng thái DRAFT.");
        }
        this.status = StockTakeStatus.IN_PROGRESS;
    }

    public void complete() {
        if (this.status != StockTakeStatus.IN_PROGRESS) {
            throw new sme.backend.exception.BusinessException("INVALID_TRANSITION",
                    "Chỉ hoàn thành (COMPLETED) từ trạng thái IN_PROGRESS.");
        }
        boolean allFilled = this.items.stream()
                .allMatch(i -> i.getActualQuantity() != null);
        if (!allFilled) {
            throw new sme.backend.exception.BusinessException("INCOMPLETE_COUNT",
                    "Vẫn còn sản phẩm chưa nhập số lượng thực tế. Kiểm tra lại.");
        }
        this.status = StockTakeStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void approve(UUID approverId) {
        if (this.status != StockTakeStatus.COMPLETED) {
            throw new sme.backend.exception.BusinessException("INVALID_TRANSITION",
                    "Chỉ duyệt phiếu ở trạng thái COMPLETED.");
        }
        this.status = StockTakeStatus.APPROVED;
        this.approvedBy = approverId;
    }

    public void cancel() {
        if (this.status == StockTakeStatus.APPROVED) {
            throw new sme.backend.exception.BusinessException("INVALID_TRANSITION",
                    "Không thể hủy phiếu kiểm kê đã được duyệt.");
        }
        this.status = StockTakeStatus.CANCELLED;
    }

    public boolean hasDiscrepancy() {
        return this.items.stream()
                .anyMatch(i -> i.getDiscrepancy() != null && i.getDiscrepancy() != 0);
    }

    public int countDiscrepancyItems() {
        return (int) this.items.stream()
                .filter(i -> i.getDiscrepancy() != null && i.getDiscrepancy() != 0)
                .count();
    }

    public enum StockTakeStatus {
        DRAFT,
        IN_PROGRESS,
        COMPLETED,
        APPROVED,
        CANCELLED
    }
}
