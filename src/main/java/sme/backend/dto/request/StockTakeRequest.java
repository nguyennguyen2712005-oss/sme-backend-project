package sme.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

public class StockTakeRequest {

    /** Tạo phiếu kiểm kê mới */
    @Data
    public static class Create {
        /** warehouseId: Admin cần truyền, Manager tự lấy từ JWT */
        private UUID warehouseId;

        /**
         * productIds: null hoặc rỗng = kiểm kê toàn bộ kho.
         * Có giá trị = chỉ kiểm những sản phẩm trong danh sách.
         */
        private List<UUID> productIds;

        private String note;
    }

    /** Nhập số lượng thực tế cho 1 sản phẩm */
    public record ItemCount(
            @NotNull UUID productId,
            @Min(0) int actualQuantity
    ) {}
}
