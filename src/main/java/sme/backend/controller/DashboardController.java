
package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sme.backend.dto.response.ApiResponse;
import sme.backend.security.UserPrincipal;
import sme.backend.service.DashboardService;

import java.util.Map;

/**
 * 3 endpoint riêng theo role - KHÔNG dùng 1 endpoint /dashboard chung rồi
 * service tự if/else theo role bên trong, để tránh tình huống một field nhạy
 * cảm bị lọt qua do quên nhánh điều kiện. SecurityConfig đã khoá thêm 1 lớp
 * nữa ở filter chain (xem /dashboard/cashier|manager|admin).
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/cashier")
    @PreAuthorize("hasRole('CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCashierDashboard(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getCashierDashboard(principal)));
    }

    @GetMapping("/manager")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getManagerDashboard(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getManagerDashboard(principal)));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAdminDashboard() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getAdminDashboard()));
    }
}
