
package sme.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.UUID;

@Data
public class CategoryRequest {
    @NotBlank(message = "Tên danh mục không được để trống")
    private String name;
    private UUID parentId;
    private String description;
    private Integer sortOrder;
    private Boolean isActive;
}
