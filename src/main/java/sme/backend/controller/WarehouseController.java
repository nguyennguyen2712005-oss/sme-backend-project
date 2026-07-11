
package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.UpdateWarehouseRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.entity.Warehouse;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.WarehouseRepository;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseRepository warehouseRepository;

    /** GET /warehouses */
    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Warehouse>>> getAll() {
        // Lấy toàn bộ kho (kể cả đã khóa) để các phiếu giao dịch cũ vẫn map được tên
        return ResponseEntity.ok(ApiResponse.ok(
                warehouseRepository.findAll(Sort.by("name"))));
    }

    /** GET /warehouses/{id} */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Warehouse>> getOne(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(
                warehouseRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id))));
    }

    /** POST /warehouses — SYS-01 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Warehouse>> create(
            @RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (warehouseRepository.existsByCode(code)) {
            throw new BusinessException("DUPLICATE_CODE",
                    "Mã chi nhánh '" + code + "' đã tồn tại");
        }
        Warehouse warehouse = Warehouse.builder()
                .code(code)
                .name(body.get("name"))
                .provinceCode(body.get("provinceCode"))
                .address(body.get("address"))
                .phone(body.get("phone"))
                .isActive(true)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(warehouseRepository.save(warehouse)));
    }

    /** PUT /warehouses/{id} - ĐÃ SỬA: Sử dụng DTO để xử lý logic update Manager */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Warehouse>> update(
            @PathVariable UUID id,
            @RequestBody UpdateWarehouseRequest request) {
            
        Warehouse w = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));

        // Cập nhật thông tin cơ bản nếu có truyền
        if (request.getName() != null)         w.setName(request.getName());
        if (request.getProvinceCode() != null) w.setProvinceCode(request.getProvinceCode());
        if (request.getAddress() != null)      w.setAddress(request.getAddress());
        if (request.getPhone() != null)        w.setPhone(request.getPhone());

        // Cập nhật ManagerId (xử lý cả trường hợp gỡ Manager bằng null)
        if (request.getHasManagerId() != null && request.getHasManagerId()) {
            w.setManagerId(request.getManagerId());
        }

        return ResponseEntity.ok(ApiResponse.ok(warehouseRepository.save(w)));
    }

    /** PATCH /warehouses/{id}/deactivate */
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Warehouse>> deactivate(@PathVariable UUID id) {
        Warehouse w = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));
        w.setIsActive(false);
        return ResponseEntity.ok(ApiResponse.ok(warehouseRepository.save(w)));
    }

    /** PATCH /warehouses/{id}/activate */
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Warehouse>> activate(@PathVariable UUID id) {
        Warehouse w = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));
        w.setIsActive(true);
        return ResponseEntity.ok(ApiResponse.ok(warehouseRepository.save(w)));
    }
}
