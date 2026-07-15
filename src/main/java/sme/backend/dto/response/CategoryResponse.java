
package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor // <--- THÊM DÒNG NÀY ĐỂ JACKSON CÓ THỂ KHỞI TẠO OBJECT
@AllArgsConstructor // <--- THÊM DÒNG NÀY (Bắt buộc phải có khi dùng chung @Builder và @NoArgsConstructor)
public class CategoryResponse {
    private UUID id;
    private UUID parentId;
    private String name;
    private String slug;
    private String description;
    private Integer sortOrder;
    private Boolean isActive;
}
