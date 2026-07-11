
package sme.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateWarehouseRequest {
    private String name;
    private String provinceCode;
    private String address;
    private String phone;

    // Cho phép gán hoặc xóa quản lý chi nhánh
    // null = không thay đổi, nếu gửi null (với hasManagerId = true) = xóa
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private UUID managerId;

    private Boolean hasManagerId = false; // flag để phân biệt null vs không gửi

    // Custom setter để tự động bật cờ hasManagerId khi Jackson parse JSON từ Frontend
    public void setManagerId(UUID managerId) {
        this.managerId = managerId;
        this.hasManagerId = true;
    }
}
