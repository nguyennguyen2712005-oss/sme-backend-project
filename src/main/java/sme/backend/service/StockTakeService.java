package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.StockTakeRequest;
import sme.backend.dto.response.StockTakeResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;
import sme.backend.security.UserPrincipal;

import java.util.*;

/**
 * StockTakeService — Nghiệp vụ Kiểm kê tồn kho
 *
 * Vòng đời phiếu:
 *   DRAFT → (startCounting) → IN_PROGRESS → (complete) → COMPLETED → (approve) → APPROVED
 *                                                                ↘ (cancel)
 *   DRAFT / IN_PROGRESS → (cancel) → CANCELLED
 *
 * Khi APPROVED:
 *   Với mỗi item có discrepancy != 0:
 *   → InventoryService.adjustInventory() để set tồn kho = actualQuantity
 *   → Tạo InventoryTransaction type=ADJUSTMENT, referenceId = stockTakeId
 *
 * Ràng buộc:
 *   - Mỗi kho chỉ được có 1 phiếu đang DRAFT hoặc IN_PROGRESS cùng lúc.
 *   - Chỉ MANAGER và ADMIN mới tạo/thao tác được.
 *   - CASHIER không được phép truy cập module này từ cả Frontend lẫn API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockTakeService {

    private final StockTakeRepository stockTakeRepository;
    private final StockTakeItemRepository stockTakeItemRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final CodeGeneratorService codeGeneratorService;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────────────────
    // TẠO PHIẾU KIỂM KÊ (DRAFT)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse createStockTake(StockTakeRequest.Create req, UserPrincipal principal) {
        UUID warehouseId = resolveWarehouseId(req.getWarehouseId(), principal);

        // Ràng buộc: không có phiếu đang dở
        long activeCount = stockTakeRepository.countActiveByWarehouse(warehouseId);
        if (activeCount > 0) {
            throw new BusinessException("ACTIVE_STOCK_TAKE_EXISTS",
                    "Chi nhánh này đang có phiếu kiểm kê chưa hoàn thành. "
                            + "Hãy hoàn tất hoặc hủy phiếu cũ trước khi tạo mới.");
        }

        String code = codeGeneratorService.nextStockTakeCode();

        StockTake stockTake = StockTake.builder()
                .code(code)
                .warehouseId(warehouseId)
                .createdBy(principal.getId())
                .status(StockTake.StockTakeStatus.DRAFT)
                .note(req.getNote())
                .build();

        stockTake = stockTakeRepository.save(stockTake);

        // Thêm sản phẩm vào phiếu kèm system_quantity tại thời điểm này
        if (req.getProductIds() != null && !req.getProductIds().isEmpty()) {
            stockTake = addItemsToStockTake(stockTake, req.getProductIds(), warehouseId);
        } else {
            // Nếu không chỉ định sản phẩm → kiểm kê toàn bộ kho
            List<Inventory> allInventories =
                    inventoryRepository.findByWarehouseId(warehouseId);
            List<UUID> allProductIds = allInventories.stream()
                    .map(Inventory::getProductId).toList();
            if (!allProductIds.isEmpty()) {
                stockTake = addItemsToStockTake(stockTake, allProductIds, warehouseId);
            }
        }

        log.info("StockTake created: {} for warehouse={} by user={}",
                code, warehouseId, principal.getId());
        return mapToResponse(stockTake, principal);
    }

    // ─────────────────────────────────────────────────────────
    // THÊM SẢN PHẨM VÀO PHIẾU
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse addProducts(UUID stockTakeId,
                                          List<UUID> productIds,
                                          UserPrincipal principal) {
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);

        if (stockTake.getStatus() != StockTake.StockTakeStatus.DRAFT) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Chỉ thêm sản phẩm khi phiếu ở trạng thái DRAFT.");
        }

        // Lọc sản phẩm chưa có trong phiếu
        List<UUID> newProductIds = productIds.stream()
                .filter(pid -> !stockTakeItemRepository
                        .existsByStockTakeIdAndProductId(stockTakeId, pid))
                .toList();

        if (!newProductIds.isEmpty()) {
            stockTake = addItemsToStockTake(stockTake, newProductIds, stockTake.getWarehouseId());
        }

        return mapToResponse(stockTake, principal);
    }

    // ─────────────────────────────────────────────────────────
    // XÓA SẢN PHẨM KHỎI PHIẾU
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse removeProduct(UUID stockTakeId, UUID productId,
                                            UserPrincipal principal) {
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);

        if (stockTake.getStatus() != StockTake.StockTakeStatus.DRAFT) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Chỉ xóa sản phẩm khi phiếu ở trạng thái DRAFT.");
        }

        StockTakeItem item = stockTakeItemRepository
                .findByStockTakeIdAndProductId(stockTakeId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy sản phẩm trong phiếu: " + productId));

        stockTakeItemRepository.delete(item);
        stockTake.getItems().remove(item);

        return mapToResponse(stockTakeRepository.findByIdWithItems(stockTakeId).orElseThrow(),
                principal);
    }

    // ─────────────────────────────────────────────────────────
    // BẮT ĐẦU ĐẾM (DRAFT → IN_PROGRESS)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse startCounting(UUID stockTakeId, UserPrincipal principal) {
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);

        if (stockTake.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_STOCK_TAKE",
                    "Phiếu kiểm kê chưa có sản phẩm nào. Thêm sản phẩm trước.");
        }

        stockTake.startCounting();
        stockTake = stockTakeRepository.save(stockTake);

        log.info("StockTake started: {}", stockTakeId);
        return mapToResponse(stockTake, principal);
    }

    // ─────────────────────────────────────────────────────────
    // CẬP NHẬT SỐ LƯỢNG THỰC TẾ
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse updateActualQuantity(UUID stockTakeId,
                                                   List<StockTakeRequest.ItemCount> counts,
                                                   UserPrincipal principal) {
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);

        if (stockTake.getStatus() != StockTake.StockTakeStatus.IN_PROGRESS) {
            throw new BusinessException("INVALID_STATE",
                    "Chỉ cập nhật số lượng thực tế khi phiếu ở trạng thái IN_PROGRESS.");
        }

        for (StockTakeRequest.ItemCount count : counts) {
            if (count.actualQuantity() < 0) {
                throw new BusinessException("INVALID_QUANTITY",
                        "Số lượng thực tế không thể âm: " + count.productId());
            }

            StockTakeItem item = stockTakeItemRepository
                    .findByStockTakeIdAndProductId(stockTakeId, count.productId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Sản phẩm không có trong phiếu: " + count.productId()));

            item.setActualQuantity(count.actualQuantity());
            stockTakeItemRepository.save(item);
        }

        return mapToResponse(
                stockTakeRepository.findByIdWithItems(stockTakeId).orElseThrow(), principal);
    }

    // ─────────────────────────────────────────────────────────
    // HOÀN THÀNH KIỂM KÊ (IN_PROGRESS → COMPLETED)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse completeStockTake(UUID stockTakeId, UserPrincipal principal) {
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);

        long unfilled = stockTakeItemRepository.countUnfilledItems(stockTakeId);
        if (unfilled > 0) {
            throw new BusinessException("INCOMPLETE_COUNT",
                    "Còn " + unfilled + " sản phẩm chưa nhập số lượng thực tế.");
        }

        stockTake.complete();
        stockTake = stockTakeRepository.save(stockTake);

        log.info("StockTake completed: {} | {} discrepancy items",
                stockTakeId, stockTake.countDiscrepancyItems());
        return mapToResponse(stockTake, principal);
    }

    // ─────────────────────────────────────────────────────────
    // DUYỆT & ÁP DỤNG ĐIỀU CHỈNH KHO (COMPLETED → APPROVED)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse approveStockTake(UUID stockTakeId, String approvalNote,
                                               UserPrincipal principal) {
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);

        // Reload items với discrepancy từ DB (GENERATED column đã tính)
        List<StockTakeItem> items = stockTakeItemRepository.findByStockTakeId(stockTakeId);

        int adjustedCount = 0;
        for (StockTakeItem item : items) {
            int computedDiscrepancy = item.computeDiscrepancy();
            if (computedDiscrepancy != 0) {
                // [FIX] Dùng AdjustInventoryRequest theo đúng signature của InventoryService
                sme.backend.dto.request.AdjustInventoryRequest adjReq =
                        new sme.backend.dto.request.AdjustInventoryRequest();
                adjReq.setProductId(item.getProductId());
                adjReq.setWarehouseId(stockTake.getWarehouseId());
                adjReq.setActualQuantity(item.getActualQuantity());
                adjReq.setReason("Kiểm kê phiếu #" + stockTake.getCode()
                        + (approvalNote != null ? " - " + approvalNote : ""));
                inventoryService.adjustInventory(adjReq, stockTakeId,
                        principal.getId().toString());
                adjustedCount++;
            }
        }

        stockTake.approve(principal.getId());
        stockTake = stockTakeRepository.save(stockTake);

        auditLogService.logAction(principal, "APPROVE_STOCK_TAKE", "STOCK_TAKE",
                stockTakeId.toString(),
                Map.of("status", "COMPLETED"),
                Map.of("status", "APPROVED", "adjustedItems", adjustedCount));

        log.info("StockTake approved: {} | {} items adjusted", stockTakeId, adjustedCount);
        return mapToResponse(stockTake, principal);
    }

    // ─────────────────────────────────────────────────────────
    // HỦY PHIẾU
    // ─────────────────────────────────────────────────────────
    @Transactional
    public StockTakeResponse cancelStockTake(UUID stockTakeId, String reason,
                                              UserPrincipal principal) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Phải nhập lý do hủy phiếu.");
        }
        StockTake stockTake = getStockTakeForUpdate(stockTakeId, principal);
        stockTake.cancel();
        if (stockTake.getNote() == null) stockTake.setNote("");
        stockTake.setNote(stockTake.getNote() + " | [HỦY] " + reason);
        stockTake = stockTakeRepository.save(stockTake);

        log.info("StockTake cancelled: {} reason={}", stockTakeId, reason);
        return mapToResponse(stockTake, principal);
    }

    // ─────────────────────────────────────────────────────────
    // QUERIES
    // ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public StockTakeResponse getStockTake(UUID stockTakeId, UserPrincipal principal) {
        StockTake stockTake = stockTakeRepository.findByIdWithItems(stockTakeId)
                .orElseThrow(() -> new ResourceNotFoundException("StockTake", stockTakeId));

        // Warehouse scoping: Manager chỉ xem của chi nhánh mình
        if (principal.getRole() == User.UserRole.ROLE_MANAGER
                && !stockTake.getWarehouseId().equals(principal.getWarehouseId())) {
            throw new BusinessException("ACCESS_DENIED",
                    "Bạn chỉ được xem phiếu kiểm kê của chi nhánh mình.");
        }
        return mapToResponse(stockTake, principal);
    }

    @Transactional(readOnly = true)
    public Page<StockTakeResponse> getStockTakes(UUID warehouseId,
                                                  UserPrincipal principal,
                                                  Pageable pageable) {
        if (principal.getRole() == User.UserRole.ROLE_ADMIN && warehouseId == null) {
            return stockTakeRepository.findAllByOrderByCreatedAtDesc(pageable)
                    .map(st -> mapToSimpleResponse(st, principal));
        }
        UUID wid = (principal.getRole() == User.UserRole.ROLE_ADMIN)
                ? warehouseId : principal.getWarehouseId();
        return stockTakeRepository.findByWarehouseIdOrderByCreatedAtDesc(wid, pageable)
                .map(st -> mapToSimpleResponse(st, principal));
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────
    private StockTake getStockTakeForUpdate(UUID stockTakeId, UserPrincipal principal) {
        StockTake stockTake = stockTakeRepository.findByIdWithItems(stockTakeId)
                .orElseThrow(() -> new ResourceNotFoundException("StockTake", stockTakeId));

        if (principal.getRole() == User.UserRole.ROLE_MANAGER
                && !stockTake.getWarehouseId().equals(principal.getWarehouseId())) {
            throw new BusinessException("ACCESS_DENIED",
                    "Bạn chỉ được thao tác phiếu kiểm kê của chi nhánh mình.");
        }
        return stockTake;
    }

    private UUID resolveWarehouseId(UUID requestedId, UserPrincipal principal) {
        if (principal.getRole() == User.UserRole.ROLE_ADMIN) {
            if (requestedId == null) {
                throw new BusinessException("WAREHOUSE_REQUIRED",
                        "Admin phải chỉ định warehouseId khi tạo phiếu kiểm kê.");
            }
            return requestedId;
        }
        return principal.getWarehouseId();
    }

    private StockTake addItemsToStockTake(StockTake stockTake,
                                           List<UUID> productIds, UUID warehouseId) {
        for (UUID productId : productIds) {
            int systemQty = inventoryRepository
                    .findByProductIdAndWarehouseId(productId, warehouseId)
                    .map(inv -> inv.getQuantity() != null ? inv.getQuantity() : 0)
                    .orElse(0);

            StockTakeItem item = StockTakeItem.builder()
                    .stockTakeId(stockTake.getId())
                    .productId(productId)
                    .systemQuantity(systemQty)
                    .build();
            stockTakeItemRepository.save(item);
        }
        return stockTakeRepository.findByIdWithItems(stockTake.getId()).orElse(stockTake);
    }

    // ─────────────────────────────────────────────────────────
    // MAPPER
    // ─────────────────────────────────────────────────────────
    private StockTakeResponse mapToResponse(StockTake st, UserPrincipal principal) {
        List<StockTakeResponse.ItemResponse> items = st.getItems().stream()
                .map(i -> {
                    Product product = productRepository.findById(i.getProductId()).orElse(null);
                    return StockTakeResponse.ItemResponse.builder()
                            .id(i.getId())
                            .productId(i.getProductId())
                            .productName(product != null ? product.getName() : null)
                            .isbnBarcode(product != null ? product.getIsbnBarcode() : null)
                            .systemQuantity(i.getSystemQuantity())
                            .actualQuantity(i.getActualQuantity())
                            .discrepancy(i.getActualQuantity() != null
                                    ? i.computeDiscrepancy() : null)
                            .build();
                }).toList();

        String createdByName = userRepository.findById(st.getCreatedBy())
                .map(User::getFullName).orElse(null);
        String approvedByName = (st.getApprovedBy() != null)
                ? userRepository.findById(st.getApprovedBy())
                        .map(User::getFullName).orElse(null)
                : null;

        return StockTakeResponse.builder()
                .id(st.getId())
                .code(st.getCode())
                .warehouseId(st.getWarehouseId())
                .createdBy(st.getCreatedBy())
                .createdByName(createdByName)
                .approvedBy(st.getApprovedBy())
                .approvedByName(approvedByName)
                .status(st.getStatus().name())
                .note(st.getNote())
                .createdAt(st.getCreatedAt())
                .completedAt(st.getCompletedAt())
                .totalItems(items.size())
                .discrepancyItems(st.countDiscrepancyItems())
                .hasDiscrepancy(st.hasDiscrepancy())
                .items(items)
                .build();
    }

    private StockTakeResponse mapToSimpleResponse(StockTake st, UserPrincipal principal) {
        return StockTakeResponse.builder()
                .id(st.getId())
                .code(st.getCode())
                .warehouseId(st.getWarehouseId())
                .createdBy(st.getCreatedBy())
                .approvedBy(st.getApprovedBy())
                .status(st.getStatus().name())
                .note(st.getNote())
                .createdAt(st.getCreatedAt())
                .completedAt(st.getCompletedAt())
                .totalItems(st.getItems() != null ? st.getItems().size() : 0)
                .items(List.of())
                .build();
    }
}
