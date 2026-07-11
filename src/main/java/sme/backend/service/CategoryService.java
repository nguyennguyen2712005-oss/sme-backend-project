
package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CategoryRequest;
import sme.backend.dto.response.CategoryResponse;
import sme.backend.entity.Category;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.CategoryRepository;

import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream()
                .sorted((c1, c2) -> Integer.compare(c1.getSortOrder() != null ? c1.getSortOrder() : 0, 
                                                    c2.getSortOrder() != null ? c2.getSortOrder() : 0))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest req) {
        String slug = generateSlug(req.getName());
        if (categoryRepository.existsBySlug(slug)) {
            slug += "-" + System.currentTimeMillis();
        }

        Category category = Category.builder()
                .name(req.getName())
                .parentId(req.getParentId())
                .description(req.getDescription())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .slug(slug)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();

        return mapToResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse updateCategory(UUID id, CategoryRequest req) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
                
        if (req.getName() != null) {
            category.setName(req.getName());
            // Cập nhật lại slug nếu cần, hoặc giữ nguyên slug cũ để tốt cho SEO
        }
        
        // 🚨 SỬA Ở ĐÂY: Xóa bỏ điều kiện if để cho phép lưu giá trị null (gỡ danh mục cha)
        category.setParentId(req.getParentId());
        
        if (req.getDescription() != null) category.setDescription(req.getDescription());
        if (req.getSortOrder() != null) category.setSortOrder(req.getSortOrder());
        if (req.getIsActive() != null) category.setIsActive(req.getIsActive());

        return mapToResponse(categoryRepository.save(category));
    }

    private CategoryResponse mapToResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .parentId(c.getParentId())
                .name(c.getName())
                .slug(c.getSlug())
                .description(c.getDescription())
                .sortOrder(c.getSortOrder())
                .isActive(c.getIsActive())
                .build();
    }

    private String generateSlug(String input) {
        String nonAscii = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return Pattern.compile("[^\\w\\s-]").matcher(nonAscii).replaceAll("")
                .trim().replaceAll("\\s+", "-").toLowerCase();
    }
}
