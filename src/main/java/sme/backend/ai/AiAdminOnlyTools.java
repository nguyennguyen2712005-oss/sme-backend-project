package sme.backend.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import sme.backend.entity.User;
import sme.backend.repository.UserRepository;
import sme.backend.repository.WarehouseRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class AiAdminOnlyTools {

    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;

    @Tool(description = "Lấy danh sách toàn bộ chi nhánh/kho đang hoạt động trong hệ thống kèm tên người quản lý. " +
            "Dùng khi người dùng hỏi tổng quan các chi nhánh, chi nhánh nào chưa có quản lý.")
    public List<Map<String, Object>> getBranchesOverview() {
        return warehouseRepository.findByIsActiveTrueOrderByName().stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", w.getCode());
            m.put("name", w.getName());
            m.put("provinceCode", w.getProvinceCode());
            m.put("managerName", w.getManagerId() != null
                    ? userRepository.findById(w.getManagerId()).map(User::getFullName).orElse("(chưa gán)")
                    : "(chưa gán)");
            return m;
        }).toList();
    }
}