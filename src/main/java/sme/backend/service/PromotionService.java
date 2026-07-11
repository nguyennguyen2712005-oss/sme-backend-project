package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.PromotionRequest;
import sme.backend.dto.response.PromotionResponse;
import sme.backend.entity.Promotion;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.PromotionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    private final PromotionRepository promotionRepository;

    // ─────────────────────────────────────────────────────────
    // CRUD (Admin / Manager)
    // ─────────────────────────────────────────────────────────

    @Transactional
    public PromotionResponse create(PromotionRequest.Create req) {
        if (promotionRepository.existsByCodeIgnoreCase(req.getCode())) {
            throw new BusinessException("DUPLICATE_CODE",
                    "Mã khuyến mãi '" + req.getCode().toUpperCase() + "' đã tồn tại.");
        }
        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "Ngày kết thúc phải sau ngày bắt đầu.");
        }
        if (req.getType() == Promotion.PromotionType.PERCENT
                && req.getValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("INVALID_PERCENT",
                    "Phần trăm giảm không thể vượt quá 100%.");
        }

        Promotion p = Promotion.builder()
                .code(req.getCode().toUpperCase().trim())
                .name(req.getName())
                .type(req.getType())
                .value(req.getValue())
                .minOrderValue(req.getMinOrderValue() != null
                        ? req.getMinOrderValue() : BigDecimal.ZERO)
                .maxDiscount(req.getMaxDiscount())
                .usageLimit(req.getUsageLimit())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .applicableTo(req.getApplicableTo() != null
                        ? req.getApplicableTo() : Promotion.ApplicableTo.ALL)
                .isActive(true)
                .build();

        p = promotionRepository.save(p);
        log.info("Promotion created: {} ({})", p.getCode(), p.getType());
        return mapToResponse(p, null);
    }

    @Transactional
    public PromotionResponse update(UUID id, PromotionRequest.Update req) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));

        if (req.getName() != null)          p.setName(req.getName());
        if (req.getValue() != null)         p.setValue(req.getValue());
        if (req.getMinOrderValue() != null) p.setMinOrderValue(req.getMinOrderValue());
        if (req.getMaxDiscount() != null)   p.setMaxDiscount(req.getMaxDiscount());
        if (req.getUsageLimit() != null)    p.setUsageLimit(req.getUsageLimit());
        if (req.getStartDate() != null)     p.setStartDate(req.getStartDate());
        if (req.getEndDate() != null)       p.setEndDate(req.getEndDate());
        if (req.getApplicableTo() != null)  p.setApplicableTo(req.getApplicableTo());
        if (req.getIsActive() != null)      p.setIsActive(req.getIsActive());

        p = promotionRepository.save(p);
        return mapToResponse(p, null);
    }

    @Transactional
    public void deactivate(UUID id) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));
        p.setIsActive(false);
        promotionRepository.save(p);
        log.info("Promotion deactivated: {}", p.getCode());
    }

    @Transactional(readOnly = true)
    public Page<PromotionResponse> list(String keyword, Pageable pageable) {
        String kw = (keyword == null || keyword.isBlank()) ? "" : keyword.trim();
        return promotionRepository.search(kw, pageable)
                .map(p -> mapToResponse(p, null));
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> listActive() {
        return promotionRepository.findAllActive(Instant.now())
                .stream().map(p -> mapToResponse(p, null)).toList();
    }

    @Transactional(readOnly = true)
    public PromotionResponse getById(UUID id) {
        return mapToResponse(
                promotionRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Promotion", id)),
                null);
    }

    // ─────────────────────────────────────────────────────────
    // VALIDATE & APPLY (dùng trong POS / Checkout)
    // ─────────────────────────────────────────────────────────

    /**
     * Validate mã khuyến mãi và tính số tiền giảm — KHÔNG tăng usedCount.
     * Chỉ để hiện preview trên UI.
     */
    @Transactional(readOnly = true)
    public PromotionResponse validateCode(String code, BigDecimal orderTotal, String channel) {
        Promotion p = findValidPromotion(code, orderTotal, channel);
        BigDecimal discount = p.calculateDiscount(orderTotal);
        return mapToResponse(p, discount);
    }

    /**
     * Apply mã khuyến mãi và tính discount — TĂNG usedCount (gọi khi checkout thực sự).
     * Dùng @Modifying trực tiếp để tránh lost update trong môi trường concurrent.
     *
     * @return số tiền giảm thực tế
     */
    @Transactional
    public BigDecimal applyPromotion(String code, BigDecimal orderTotal, String channel) {
        Promotion p = findValidPromotion(code, orderTotal, channel);
        BigDecimal discount = p.calculateDiscount(orderTotal);
        int updated = promotionRepository.incrementUsedCount(p.getId());
        if (updated == 0) {
            throw new BusinessException("PROMOTION_APPLY_FAILED",
                    "Không thể áp dụng mã khuyến mãi. Vui lòng thử lại.");
        }
        log.info("Promotion applied: code={} discount={} channel={}", code, discount, channel);
        return discount;
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE
    // ─────────────────────────────────────────────────────────

    private Promotion findValidPromotion(String code, BigDecimal orderTotal, String channel) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("INVALID_CODE", "Mã khuyến mãi không được để trống.");
        }

        Promotion p = promotionRepository
                .findActiveByCode(code.trim(), Instant.now())
                .orElseThrow(() -> new BusinessException("INVALID_CODE",
                        "Mã '" + code.toUpperCase() + "' không hợp lệ, đã hết hạn hoặc đã dùng hết lượt."));

        // Validate phạm vi kênh
        if (p.getApplicableTo() != Promotion.ApplicableTo.ALL) {
            boolean isPOS    = "POS".equalsIgnoreCase(channel);
            boolean isOnline = "ONLINE".equalsIgnoreCase(channel);
            if (p.getApplicableTo() == Promotion.ApplicableTo.POS && !isPOS) {
                throw new BusinessException("WRONG_CHANNEL",
                        "Mã này chỉ áp dụng cho bán hàng tại quầy POS.");
            }
            if (p.getApplicableTo() == Promotion.ApplicableTo.ONLINE && !isOnline) {
                throw new BusinessException("WRONG_CHANNEL",
                        "Mã này chỉ áp dụng cho đơn hàng online.");
            }
        }

        // Validate giá trị đơn tối thiểu
        if (orderTotal.compareTo(p.getMinOrderValue()) < 0) {
            throw new BusinessException("MIN_ORDER_NOT_MET",
                    String.format("Đơn hàng tối thiểu %,.0f₫ để dùng mã này. Hiện tại: %,.0f₫",
                            p.getMinOrderValue(), orderTotal));
        }

        return p;
    }

    public PromotionResponse mapToResponse(Promotion p, BigDecimal discountAmount) {
        Instant now = Instant.now();
        boolean expired = now.isAfter(p.getEndDate());
        boolean valid   = p.isValidNow(now);
        int remaining   = (p.getUsageLimit() != null)
                ? Math.max(0, p.getUsageLimit() - p.getUsedCount())
                : Integer.MAX_VALUE;

        return PromotionResponse.builder()
                .id(p.getId())
                .code(p.getCode())
                .name(p.getName())
                .type(p.getType().name())
                .value(p.getValue())
                .minOrderValue(p.getMinOrderValue())
                .maxDiscount(p.getMaxDiscount())
                .usageLimit(p.getUsageLimit())
                .usedCount(p.getUsedCount())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .applicableTo(p.getApplicableTo().name())
                .isActive(p.getIsActive())
                .createdAt(p.getCreatedAt())
                .isExpired(expired)
                .isValid(valid)
                .remainingUses(remaining == Integer.MAX_VALUE ? -1 : remaining)
                .discountAmount(discountAmount)
                .build();
    }
}
