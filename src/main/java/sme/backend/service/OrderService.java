package sme.backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.config.AppProperties;
import sme.backend.dto.request.CreateOrderRequest;
import sme.backend.dto.response.OrderResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.InsufficientStockException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;
import sme.backend.security.UserPrincipal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final CashbookTransactionRepository cashbookRepository;
    private final InternalTransferRepository transferRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final EntityManager entityManager;
    private final CodeGeneratorService codeGenerator; 
    
    // ĐÃ THÊM: Inject PromotionService và AppProperties
    private final PromotionService promotionService;
    private final AppProperties appProperties;

    private UUID getCurrentUserIdSafe() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException("UNAUTHORIZED", "Không tìm thấy thông tin đăng nhập.");
        }
        try {
            Object principal = auth.getPrincipal();
            java.lang.reflect.Method method = principal.getClass().getMethod("getId");
            Object id = method.invoke(principal);
            if (id instanceof UUID) return (UUID) id;
            if (id != null) return UUID.fromString(id.toString());
        } catch (Exception e) {
            log.warn("Không lấy được ID qua Token, chuyển sang quét DB...");
        }
        try {
            String username = auth.getName();
            if (username != null && !username.isEmpty()) {
                List<?> results = entityManager
                        .createNativeQuery("SELECT id FROM users WHERE username = :username LIMIT 1")
                        .setParameter("username", username)
                        .getResultList();
                if (!results.isEmpty() && results.get(0) != null) {
                    return UUID.fromString(results.get(0).toString());
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi query bảng users: {}", e.getMessage());
        }
        throw new BusinessException("USER_NOT_FOUND",
                "Hệ thống không lấy được ID của bạn.");
    }

    // =====================================================================
    // TẠO ĐƠN
    // =====================================================================
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest req) {
        Customer customer = customerRepository.findById(req.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.getCustomerId()));

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        UUID currentUserId = getCurrentUserIdSafe();

        for (CreateOrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId()).orElseThrow();
            Integer totalAvailObj = inventoryRepository.getTotalAvailableQuantity(product.getId());
            int available = totalAvailObj != null ? totalAvailObj : 0;
            if (available < itemReq.getQuantity()) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Sản phẩm '" + product.getName() + "' không đủ tồn kho.");
            }
            BigDecimal subtotal = product.getRetailPrice()
                    .multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            orderItems.add(OrderItem.builder()
                    .productId(product.getId()).quantity(itemReq.getQuantity())
                    .unitPrice(product.getRetailPrice()).macPrice(product.getMacPrice())
                    .subtotal(subtotal).build());
            totalAmount = totalAmount.add(subtotal);
        }

        // ── TÍNH TOÁN PHÍ SHIP, KHUYẾN MÃI & ĐIỂM THƯỞNG ──────
        BigDecimal shippingFee = req.getShippingFee() != null ? req.getShippingFee() : BigDecimal.ZERO;
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        BigDecimal loyaltyDiscount = BigDecimal.ZERO;
        int pointsToUse = req.getPointsToUse() != null ? req.getPointsToUse() : 0;
        String promotionCode = req.getPromotionCode();

        // 1. Mã khuyến mãi
        if (promotionCode != null && !promotionCode.isBlank()) {
            promotionDiscount = promotionService.applyPromotion(promotionCode.trim(), totalAmount, "ONLINE");
        }

        // 2. Điểm tích lũy (tính trên giá sau mã KM)
        BigDecimal afterPromotion = totalAmount.subtract(promotionDiscount);
        if (pointsToUse > 0) {
            if (pointsToUse % 500 != 0) {
                throw new BusinessException("INVALID_POINTS", "Chỉ có thể quy đổi điểm theo mốc 500 điểm.");
            }
            if (customer.getLoyaltyPoints() < pointsToUse) {
                throw new BusinessException("INSUFFICIENT_POINTS", "Điểm tích lũy không đủ. Hiện có: " + customer.getLoyaltyPoints());
            }
            loyaltyDiscount = BigDecimal.valueOf(pointsToUse)
                    .multiply(BigDecimal.valueOf(appProperties.getBusiness().getLoyaltyPointsRedeemValue()));
            if (loyaltyDiscount.compareTo(afterPromotion) > 0) {
                loyaltyDiscount = afterPromotion;
            }
            // Trừ điểm khách hàng
            customer.deductPoints(pointsToUse);
            customerRepository.save(customer);
        }

        BigDecimal discountAmount = promotionDiscount.add(loyaltyDiscount);
        BigDecimal finalAmount = totalAmount.add(shippingFee).subtract(discountAmount);
        
        // Ghi chú thêm thông tin KM vào note
        String finalNote = req.getNote() != null ? req.getNote() : "";
        if (promotionCode != null && !promotionCode.isBlank()) {
            finalNote = "[Mã KM: " + promotionCode.toUpperCase() + "] " + finalNote;
        }
        if (pointsToUse > 0) {
            finalNote = "[Đổi " + pointsToUse + " điểm] " + finalNote;
        }
        // ─────────────────────────────────────────────────────

        UUID assignedWarehouseId = req.getAssignedWarehouseId();
        Map<String, Object> chosenPlan = null;

        List<Map<String, Object>> plans = suggestBranchesForOrder(
                req.getProvinceCode(), req.getItems());
        if (plans.isEmpty()) {
            throw new BusinessException("NO_WAREHOUSE",
                    "Không có kho nào đáp ứng được đơn hàng này.");
        }

        if (assignedWarehouseId != null) {
            final UUID finalId = assignedWarehouseId;
            chosenPlan = plans.stream()
                    .filter(p -> p.get("warehouseId").equals(finalId))
                    .findFirst().orElse(null);
        } else {
            chosenPlan = plans.get(0);
            assignedWarehouseId = (UUID) chosenPlan.get("warehouseId");
        }
        if (chosenPlan == null) {
            throw new BusinessException("INVALID_WAREHOUSE", "Kho được chọn không hợp lệ.");
        }

        Order.OrderType orderType = "BOPIS".equalsIgnoreCase(req.getType())
                ? Order.OrderType.BOPIS : Order.OrderType.DELIVERY;
        boolean isReadyToShip = (Boolean) chosenPlan.get("isReadyToShip");

        Order order = Order.builder()
                .code(codeGenerator.nextOrderCode())
                .customerId(customer.getId())
                .assignedWarehouseId(assignedWarehouseId)
                .type(orderType)
                .shippingName(req.getShippingName())
                .shippingPhone(req.getShippingPhone())
                .shippingAddress(req.getShippingAddress())
                .provinceCode(req.getProvinceCode())
                .totalAmount(totalAmount)
                .shippingFee(shippingFee) // ĐÃ SỬA
                .discountAmount(discountAmount) // ĐÃ SỬA
                .finalAmount(finalAmount) // ĐÃ SỬA
                .paymentMethod(req.getPaymentMethod())
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .note(finalNote.trim()) // ĐÃ SỬA
                .status(isReadyToShip
                        ? Order.OrderStatus.PENDING
                        : Order.OrderStatus.WAITING_FOR_CONSOLIDATION)
                .build();
        orderItems.forEach(order::addItem);
        order = orderRepository.save(order);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availableItems =
                (List<Map<String, Object>>) chosenPlan.get("availableItems");
        for (Map<String, Object> avItem : availableItems) {
            inventoryService.reserveForOnlineOrder(
                    (UUID) avItem.get("productId"), assignedWarehouseId,
                    (Integer) avItem.get("quantity"), order.getId(), "SYSTEM");
        }

        if (!isReadyToShip) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transferReqs =
                    (List<Map<String, Object>>) chosenPlan.get("transferRequirements");
            Map<UUID, List<Map<String, Object>>> transfersBySource = transferReqs.stream()
                    .collect(Collectors.groupingBy(r -> (UUID) r.get("fromWarehouseId")));

            for (Map.Entry<UUID, List<Map<String, Object>>> entry : transfersBySource.entrySet()) {
                UUID sourceWarehouseId = entry.getKey();
                InternalTransfer transfer = InternalTransfer.builder()
                        .code(codeGenerator.nextAutoTransferCode())
                        .fromWarehouseId(sourceWarehouseId)
                        .toWarehouseId(assignedWarehouseId)
                        .createdByUserId(currentUserId)
                        .status(InternalTransfer.TransferStatus.DRAFT)
                        .referenceOrderId(order.getId())
                        .note("Tự động tạo - Gom hàng cho Đơn #" + order.getCode())
                        .build();
                if (transfer.getItems() == null) transfer.setItems(new ArrayList<>());
                for (Map<String, Object> reqItem : entry.getValue()) {
                    UUID pId = (UUID) reqItem.get("productId");
                    int qty = (Integer) reqItem.get("quantity");
                    transfer.addItem(TransferItem.builder()
                            .productId(pId).quantity(qty).build());
                    inventoryService.reserveForOnlineOrder(
                            pId, sourceWarehouseId, qty, order.getId(), "SYSTEM_CONSOLIDATION");
                }
                transferRepository.save(transfer);
                notificationService.notifyTransferArrived(transfer.getId(), sourceWarehouseId);
            }
        }

        notificationService.notifyNewOrder(order, assignedWarehouseId);
        return mapToResponse(order, null);
    }

    // =====================================================================
    // SUGGEST BRANCHES
    // =====================================================================
    @Transactional(readOnly = true)
    public List<Map<String, Object>> suggestBranchesForOrder(
            String provinceCode, List<CreateOrderRequest.OrderItemRequest> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<Warehouse> activeWarehouses = warehouseRepository.findByIsActiveTrueOrderByName();
        List<UUID> productIds = items.stream()
                .map(CreateOrderRequest.OrderItemRequest::getProductId).toList();
        List<Inventory> allInventories = inventoryRepository.findByProductIdIn(productIds);

        Map<UUID, Map<UUID, Integer>> stockMatrix = new HashMap<>();
        for (Inventory inv : allInventories) {
            stockMatrix.computeIfAbsent(inv.getWarehouseId(), k -> new HashMap<>())
                    .put(inv.getProductId(), inv.getAvailableQuantity());
        }

        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Warehouse targetWarehouse : activeWarehouses) {
            boolean isSameProvince = provinceCode != null
                    && provinceCode.equals(targetWarehouse.getProvinceCode());
            List<Map<String, Object>> availableItems = new ArrayList<>();
            List<Map<String, Object>> transferRequirements = new ArrayList<>();
            boolean isReadyToShip = true;
            Map<UUID, Integer> targetStock =
                    stockMatrix.getOrDefault(targetWarehouse.getId(), Collections.emptyMap());

            for (CreateOrderRequest.OrderItemRequest item : items) {
                int requiredQty = item.getQuantity();
                int currentStock = targetStock.getOrDefault(item.getProductId(), 0);

                if (currentStock >= requiredQty) {
                    availableItems.add(Map.of("productId", item.getProductId(),
                            "quantity", requiredQty));
                } else {
                    isReadyToShip = false;
                    if (currentStock > 0) {
                        availableItems.add(Map.of("productId", item.getProductId(),
                                "quantity", currentStock));
                    }
                    int remainingToFind = requiredQty - currentStock;
                    for (Warehouse sourceWarehouse : activeWarehouses) {
                        if (sourceWarehouse.getId().equals(targetWarehouse.getId())) continue;
                        if (remainingToFind <= 0) break;
                        Map<UUID, Integer> sourceStockMap =
                                stockMatrix.getOrDefault(sourceWarehouse.getId(), Collections.emptyMap());
                        int sourceStock = sourceStockMap.getOrDefault(item.getProductId(), 0);
                        if (sourceStock > 0) {
                            int takeQty = Math.min(sourceStock, remainingToFind);
                            remainingToFind -= takeQty;
                            Product prod = productRepository.findById(item.getProductId()).orElseThrow();
                            transferRequirements.add(Map.of(
                                    "fromWarehouseId", sourceWarehouse.getId(),
                                    "fromWarehouseName", sourceWarehouse.getName(),
                                    "productId", item.getProductId(),
                                    "productName", prod.getName(),
                                    "quantity", takeQty
                            ));
                        }
                    }
                    if (remainingToFind > 0) {
                        isReadyToShip = false;
                        transferRequirements.clear();
                        break;
                    }
                }
            }
            if (!isReadyToShip && transferRequirements.isEmpty()) continue;

            int score = 0;
            if (isReadyToShip) score += 1000;
            if (isSameProvince) score += 500;
            score -= transferRequirements.size() * 10;

            Map<String, Object> plan = new HashMap<>();
            plan.put("warehouseId", targetWarehouse.getId());
            plan.put("warehouseName", targetWarehouse.getName());
            plan.put("isSameProvince", isSameProvince);
            plan.put("isReadyToShip", isReadyToShip);
            plan.put("availableItems", availableItems);
            plan.put("transferRequirements", transferRequirements);
            plan.put("sortScore", score);
            suggestions.add(plan);
        }
        suggestions.sort((a, b) ->
                Integer.compare((Integer) b.get("sortScore"), (Integer) a.get("sortScore")));
        return suggestions;
    }

    // =====================================================================
    // HELPERS PHÂN QUYỀN
    // =====================================================================
    private void requireSameWarehouse(Order order, UserPrincipal principal) {
        if (principal.getWarehouseId() == null
                || order.getAssignedWarehouseId() == null
                || !principal.getWarehouseId().equals(order.getAssignedWarehouseId())) {
            throw new BusinessException("ACCESS_DENIED",
                    "Bạn chỉ được xử lý đơn hàng thuộc chi nhánh của mình.");
        }
    }

    private Order findOrderOrThrow(UUID orderId) {
        return orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    // =====================================================================
    // CONFIRM / PACK / SHIP / MARK_READY / COMPLETE / RETURN / CANCEL / FORCE_CANCEL / REASSIGN
    // =====================================================================
    @Transactional
    public OrderResponse confirmOrder(UUID orderId, String note, UserPrincipal principal) {
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        for (OrderItem item : order.getItems()) {
            Inventory inv = inventoryRepository
                    .findByProductIdAndWarehouseId(item.getProductId(), order.getAssignedWarehouseId())
                    .orElseThrow(() -> new InsufficientStockException(
                            "Không tìm thấy tồn kho cho sản phẩm " + item.getProductId()));
            if (inv.getReservedQuantity() == null || inv.getReservedQuantity() < item.getQuantity()) {
                throw new InsufficientStockException(
                        "Số lượng đã giữ (reserved) cho sản phẩm " + item.getProductId()
                                + " không đủ. Vui lòng kiểm tra lại tồn kho.");
            }
        }
        order.transitionTo(Order.OrderStatus.CONFIRMED, note, principal.getId().toString());
        return mapToResponse(orderRepository.save(order), principal.getRole());
    }

    @Transactional
    public OrderResponse packOrder(UUID orderId, String note, UserPrincipal principal) {
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        order.transitionTo(Order.OrderStatus.PACKED, note, principal.getId().toString());
        order.setPackedBy(principal.getId());
        order.setPackedAt(Instant.now());
        return mapToResponse(orderRepository.save(order), principal.getRole());
    }

    @Transactional
    public OrderResponse shipOrder(UUID orderId, String trackingCode, String shippingProvider,
                                    String note, UserPrincipal principal) {
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        if (order.getType() != Order.OrderType.DELIVERY) {
            throw new BusinessException("INVALID_ORDER_TYPE",
                    "Đơn loại BOPIS không 'giao đi' — dùng chức năng 'Sẵn sàng lấy tại quầy'.");
        }
        order.transitionTo(Order.OrderStatus.SHIPPING, note, principal.getId().toString());
        if (trackingCode != null) order.setTrackingCode(trackingCode);
        if (shippingProvider != null) order.setShippingProvider(shippingProvider);
        order.getItems().forEach(item -> inventoryService.confirmOnlineShipment(
                item.getProductId(), order.getAssignedWarehouseId(),
                item.getQuantity(), orderId, principal.getId().toString()));
        return mapToResponse(orderRepository.save(order), principal.getRole());
    }

    @Transactional
    public OrderResponse markReadyForPickup(UUID orderId, String note, UserPrincipal principal) {
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        if (order.getType() != Order.OrderType.BOPIS) {
            throw new BusinessException("INVALID_ORDER_TYPE",
                    "Chức năng này chỉ áp dụng cho đơn BOPIS.");
        }
        order.transitionTo(Order.OrderStatus.READY_FOR_PICKUP, note, principal.getId().toString());
        order.getItems().forEach(item -> inventoryService.confirmOnlineShipment(
                item.getProductId(), order.getAssignedWarehouseId(),
                item.getQuantity(), orderId, principal.getId().toString()));
        return mapToResponse(orderRepository.save(order), principal.getRole());
    }

    @Transactional
    public OrderResponse completeOrder(UUID orderId, String note, UserPrincipal principal) {
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        order.transitionTo(Order.OrderStatus.DELIVERED, note, principal.getId().toString());
        if ("COD".equals(order.getPaymentMethod())) {
            order.setPaymentStatus(Order.PaymentStatus.PAID);
            recordCODRevenue(order);
        } else if ("BANK_TRANSFER".equals(order.getPaymentMethod())
                && order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
            order.setPaymentStatus(Order.PaymentStatus.PAID);
            recordBankTransferRevenue(order, principal.getId().toString());
        }
        return mapToResponse(orderRepository.save(order), principal.getRole());
    }

    @Transactional
    public OrderResponse returnOrder(UUID orderId, String reason, UserPrincipal principal) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Phải nhập lý do hoàn trả.");
        }
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        order.transitionTo(Order.OrderStatus.RETURNED, reason, principal.getId().toString());
        
        // ĐÃ THÊM: Đánh dấu là đã hoàn tiền nếu đơn đã thanh toán trước đó
        if (order.getPaymentStatus() == Order.PaymentStatus.PAID) {
            order.setPaymentStatus(Order.PaymentStatus.REFUNDED);
        }
        
        if (order.getAssignedWarehouseId() != null) {
            order.getItems().forEach(item -> inventoryService.returnToStock(
                    item.getProductId(), order.getAssignedWarehouseId(), item.getQuantity(),
                    orderId, "RETURNED_ORDER", principal.getId().toString()));
        }
        return mapToResponse(orderRepository.save(order), principal.getRole());
    }

    @Transactional
    public OrderResponse cancelOrder(UUID orderId, String reason, UserPrincipal principal) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Phải nhập lý do hủy đơn.");
        }
        Order order = findOrderOrThrow(orderId);
        requireSameWarehouse(order, principal);
        User.UserRole role = principal.getRole();
        if (role == User.UserRole.ROLE_CASHIER
                && order.getStatus() != Order.OrderStatus.PENDING) {
            throw new BusinessException("ACCESS_DENIED",
                    "Thu ngân chỉ được hủy đơn đang ở trạng thái Chờ xác nhận (PENDING).");
        }
        boolean managerCanCancel =
                order.getStatus() == Order.OrderStatus.WAITING_FOR_CONSOLIDATION
                        || order.getStatus() == Order.OrderStatus.PENDING
                        || order.getStatus() == Order.OrderStatus.CONFIRMED
                        || order.getStatus() == Order.OrderStatus.PACKED;
        if (role == User.UserRole.ROLE_MANAGER && !managerCanCancel) {
            throw new BusinessException("ACCESS_DENIED",
                    "Đơn đã rời kho hoặc hoàn tất. Liên hệ Admin để dùng hủy khẩn cấp.");
        }
        performCancellation(order, reason, principal.getId().toString());
        return mapToResponse(orderRepository.save(order), role);
    }

    @Transactional
    public OrderResponse forceCancelOrder(UUID orderId, String reason, UserPrincipal principal) {
        if (reason == null || reason.trim().length() < 20) {
            throw new BusinessException("REASON_TOO_SHORT",
                    "Lý do hủy khẩn cấp phải có tối thiểu 20 ký tự.");
        }
        Order order = findOrderOrThrow(orderId);
        Set<Order.OrderStatus> blockedStatuses = Set.of(
                Order.OrderStatus.SHIPPING, Order.OrderStatus.DELIVERED,
                Order.OrderStatus.CANCELLED, Order.OrderStatus.RETURNED);
        if (blockedStatuses.contains(order.getStatus())) {
            throw new BusinessException("INVALID_STATE",
                    "Không thể hủy khẩn cấp đơn ở trạng thái " + order.getStatus());
        }
        String oldStatus = order.getStatus().name();
        performCancellation(order, "[HỦY KHẨN CẤP - ADMIN] " + reason,
                principal.getId().toString());
        Order saved = orderRepository.save(order);
        auditLogService.logAction(principal, "FORCE_CANCEL_ORDER", "ORDER", orderId.toString(),
                Map.of("status", oldStatus),
                Map.of("status", "CANCELLED", "reason", reason));
        notificationService.notifyOrderForceCancelled(saved, reason);
        return mapToResponse(saved, principal.getRole());
    }

    private void performCancellation(Order order, String reason, String changedBy) {
        UUID orderId = order.getId();
        order.transitionTo(Order.OrderStatus.CANCELLED, reason, changedBy);
        List<InternalTransfer> transfers = transferRepository.findByReferenceOrderId(orderId);
        Map<UUID, Integer> stuckTransferQtys = new HashMap<>();
        for (InternalTransfer transfer : transfers) {
            if (transfer.getStatus() == InternalTransfer.TransferStatus.DRAFT) {
                transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "")
                        + " | Hủy tự động do Đơn hàng " + order.getCode() + " bị hủy.");
                transferRepository.save(transfer);
                for (TransferItem tItem : transfer.getItems()) {
                    inventoryService.releaseReservation(tItem.getProductId(),
                            transfer.getFromWarehouseId(), tItem.getQuantity(), orderId, changedBy);
                    stuckTransferQtys.merge(tItem.getProductId(), tItem.getQuantity(), Integer::sum);
                }
            } else if (transfer.getStatus() == InternalTransfer.TransferStatus.DISPATCHED) {
                transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "")
                        + " | Hủy tự động do Đơn hàng hủy. Hàng đang đi đường hoàn về kho gốc.");
                transferRepository.save(transfer);
                for (TransferItem tItem : transfer.getItems()) {
                    Inventory srcInv = inventoryRepository.findByProductIdAndWarehouseId(
                            tItem.getProductId(), transfer.getFromWarehouseId()).orElse(null);
                    if (srcInv != null) {
                        int before = srcInv.getQuantity() != null ? srcInv.getQuantity() : 0;
                        srcInv.setInTransit(Math.max(0, srcInv.getInTransit() - tItem.getQuantity()));
                        srcInv.setQuantity(srcInv.getQuantity() + tItem.getQuantity());
                        inventoryRepository.save(srcInv);
                        inventoryService.recordTransaction(srcInv, transfer.getId(),
                                "CANCEL_TRANSFER", tItem.getQuantity(), before,
                                srcInv.getQuantity(), changedBy, "Hủy đơn, quay đầu hàng luân chuyển");
                    }
                    stuckTransferQtys.merge(tItem.getProductId(), tItem.getQuantity(), Integer::sum);
                }
            }
        }
        if (order.getAssignedWarehouseId() != null) {
            for (OrderItem item : order.getItems()) {
                int stuckQty = stuckTransferQtys.getOrDefault(item.getProductId(), 0);
                int releaseAtAssigned = item.getQuantity() - stuckQty;
                if (releaseAtAssigned > 0) {
                    inventoryService.releaseReservation(item.getProductId(),
                            order.getAssignedWarehouseId(), releaseAtAssigned, orderId, changedBy);
                }
            }
        }
        order.setCancelledReason(reason);
    }

    @Transactional
    public OrderResponse reassignOrder(UUID orderId, UUID newWarehouseId, UserPrincipal principal) {
        Order order = findOrderOrThrow(orderId);
        if (order.getStatus() != Order.OrderStatus.PENDING
                && order.getStatus() != Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
            throw new BusinessException("INVALID_STATE",
                    "Chỉ tái phân công được khi đơn PENDING hoặc WAITING_FOR_CONSOLIDATION.");
        }
        UUID oldWarehouseId = order.getAssignedWarehouseId();
        if (newWarehouseId.equals(oldWarehouseId)) {
            throw new BusinessException("INVALID_WAREHOUSE", "Kho mới phải khác kho hiện tại.");
        }
        Warehouse newWarehouse = warehouseRepository.findById(newWarehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", newWarehouseId));
        for (OrderItem item : order.getItems()) {
            Inventory inv = inventoryRepository
                    .findByProductIdAndWarehouseId(item.getProductId(), newWarehouseId)
                    .orElseThrow(() -> new InsufficientStockException(
                            "Kho mới (" + newWarehouse.getName()
                                    + ") không có tồn kho cho sản phẩm " + item.getProductId()));
            if (inv.getAvailableQuantity() < item.getQuantity()) {
                throw new InsufficientStockException(
                        "Kho mới (" + newWarehouse.getName() + ") không đủ hàng.");
            }
        }
        if (oldWarehouseId != null) {
            for (OrderItem item : order.getItems()) {
                inventoryService.releaseReservation(item.getProductId(), oldWarehouseId,
                        item.getQuantity(), orderId, principal.getId().toString());
            }
        }
        for (OrderItem item : order.getItems()) {
            inventoryService.reserveForOnlineOrder(item.getProductId(), newWarehouseId,
                    item.getQuantity(), orderId, principal.getId().toString());
        }
        order.setAssignedWarehouseId(newWarehouseId);
        if (order.getStatus() == Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
            order.transitionTo(Order.OrderStatus.PENDING,
                    "Admin tái phân công sang " + newWarehouse.getName(),
                    principal.getId().toString());
        }
        Order saved = orderRepository.save(order);
        auditLogService.logAction(principal, "REASSIGN_ORDER", "ORDER", orderId.toString(),
                Map.of("warehouseId", oldWarehouseId != null ? oldWarehouseId.toString() : "null"),
                Map.of("warehouseId", newWarehouseId.toString()));
        notificationService.notifyOrderReassigned(saved, oldWarehouseId);
        return mapToResponse(saved, principal.getRole());
    }

    // =====================================================================
    // REVENUE RECORDING
    // =====================================================================
    private void recordCODRevenue(Order order) {
        if (order.getAssignedWarehouseId() == null) return;
        cashbookRepository.save(CashbookTransaction.builder()
                .warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.CASH_111)
                .transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE").referenceId(order.getId())
                .amount(order.getFinalAmount())
                .description("Thu COD đơn hàng #" + order.getCode())
                .createdBy("SYSTEM").build());
    }

    private void recordBankTransferRevenue(Order order, String changedBy) {
        if (order.getAssignedWarehouseId() == null) return;
        cashbookRepository.save(CashbookTransaction.builder()
                .warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.BANK_112)
                .transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE").referenceId(order.getId())
                .amount(order.getFinalAmount())
                .description("Thu chuyển khoản đơn hàng #" + order.getCode())
                .createdBy(changedBy != null ? changedBy : "SYSTEM").build());
    }

    // =====================================================================
    // ĐỌC DỮ LIỆU
    // =====================================================================
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(UUID warehouseId, Order.OrderStatus status,
                                          Order.OrderType type, String keyword,
                                          Pageable pageable, User.UserRole requesterRole) {
        List<Order.OrderStatus> statuses = (status == null)
                ? List.of(Order.OrderStatus.values()) : List.of(status);
        List<Order.OrderType> types = (type == null)
                ? List.of(Order.OrderType.values()) : List.of(type);
        String kw = (keyword == null) ? "" : keyword.trim();

        Page<Order> pageResult;
        if (warehouseId == null) {
            pageResult = orderRepository.searchAllOrders(statuses, types, kw, pageable);
        } else {
            pageResult = orderRepository.searchOrdersByWarehouse(
                    warehouseId, statuses, types, kw, pageable);
        }
        return pageResult.map(o -> mapToSimpleResponse(o, requesterRole));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetail(UUID orderId, User.UserRole requesterRole,
                                         UUID requesterWarehouseId) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (requesterRole != User.UserRole.ROLE_ADMIN && requesterWarehouseId != null) {
            if (!requesterWarehouseId.equals(order.getAssignedWarehouseId())) {
                throw new BusinessException("ACCESS_DENIED",
                        "Bạn không có quyền xem đơn hàng này.");
            }
        }

        return mapToResponse(order, requesterRole);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getPendingOrders(UUID warehouseId, User.UserRole requesterRole) {
        List<Order> orders;
        if (warehouseId == null) {
            orders = orderRepository.findAllPendingOrders();
        } else {
            orders = orderRepository.findPendingOrdersByWarehouse(warehouseId);
        }
        return orders.stream().map(o -> mapToSimpleResponse(o, requesterRole)).toList();
    }

    private void maskFinancialFieldsForCashier(OrderResponse response, User.UserRole requesterRole) {
        if (requesterRole == User.UserRole.ROLE_CASHIER) {
            response.setTotalAmount(null);
            response.setShippingFee(null);
            response.setDiscountAmount(null);
            response.setFinalAmount(null);
            response.setPaymentMethod(null);
            response.setPaymentStatus(null);
        }
    }

    public OrderResponse mapToSimpleResponse(Order order, User.UserRole requesterRole) {
        String custName = "Khách lẻ", custPhone = null;
        if (order.getCustomerId() != null) {
            var c = customerRepository.findById(order.getCustomerId()).orElse(null);
            if (c != null) { custName = c.getFullName(); custPhone = c.getPhoneNumber(); }
        }
        String warehouseName = null;
        if (order.getAssignedWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(order.getAssignedWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }
        OrderResponse response = OrderResponse.builder()
                .id(order.getId()).code(order.getCode())
                .customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId())
                .assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName())
                .shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress())
                .provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount())
                .shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount())
                .finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null
                        ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode())
                .shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled())
                .note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy()).packedAt(order.getPackedAt())
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(List.of()).statusHistory(List.of())
                .build();
        maskFinancialFieldsForCashier(response, requesterRole);
        return response;
    }

    public OrderResponse mapToResponse(Order order, User.UserRole requesterRole) {
        String custName = "Khách lẻ", custPhone = null;
        if (order.getCustomerId() != null) {
            var c = customerRepository.findById(order.getCustomerId()).orElse(null);
            if (c != null) { custName = c.getFullName(); custPhone = c.getPhoneNumber(); }
        }
        String warehouseName = null;
        if (order.getAssignedWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(order.getAssignedWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }
        String packedByName = null;
        if (order.getPackedBy() != null) {
            packedByName = userRepository.findById(order.getPackedBy())
                    .map(User::getFullName).orElse(null);
        }
        List<OrderResponse.ItemResponse> items = order.getItems() == null ? List.of()
                : order.getItems().stream().map(i -> {
                    var product = productRepository.findById(i.getProductId()).orElse(null);
                    return OrderResponse.ItemResponse.builder()
                            .productId(i.getProductId())
                            .productName(product != null ? product.getName() : null)
                            .isbnBarcode(product != null ? product.getIsbnBarcode() : null)
                            .quantity(i.getQuantity())
                            .unitPrice(i.getUnitPrice())
                            .subtotal(i.getSubtotal())
                            .build();
                }).toList();

        List<OrderResponse.StatusHistoryResponse> history = order.getStatusHistory() == null
                ? List.of()
                : order.getStatusHistory().stream().map(h -> {
                    String changedByName = "Hệ thống";
                    if (h.getChangedBy() != null && !h.getChangedBy().equals("SYSTEM")) {
                        try {
                            UUID uid = UUID.fromString(h.getChangedBy());
                            changedByName = userRepository.findById(uid)
                                    .map(User::getFullName).orElse(h.getChangedBy());
                        } catch (Exception e) {
                            changedByName = h.getChangedBy();
                        }
                    }
                    return OrderResponse.StatusHistoryResponse.builder()
                            .oldStatus(h.getOldStatus()).newStatus(h.getNewStatus())
                            .note(h.getNote()).changedBy(h.getChangedBy())
                            .changedByName(changedByName).createdAt(h.getCreatedAt())
                            .build();
                }).toList();

        OrderResponse response = OrderResponse.builder()
                .id(order.getId()).code(order.getCode())
                .customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId())
                .assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName())
                .shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress())
                .provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount())
                .shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount())
                .finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null
                        ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode())
                .shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled())
                .note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy())
                .packedByName(packedByName)
                .packedAt(order.getPackedAt())
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(items).statusHistory(history)
                .build();
        maskFinancialFieldsForCashier(response, requesterRole);
        return response;
    }
}