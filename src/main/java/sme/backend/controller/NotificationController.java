package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.entity.Notification;
import sme.backend.entity.User;
import sme.backend.security.UserPrincipal;
import sme.backend.service.NotificationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // ĐÃ SỬA: Bỏ Role CASHIER khỏi @PreAuthorize
    @GetMapping("/unread")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Notification>>> getUnread(
            @AuthenticationPrincipal UserPrincipal principal) {
        
        List<Notification> result = (principal.getRole() == User.UserRole.ROLE_ADMIN)
                ? notificationService.getUnread(null)
                : notificationService.getUnreadForManager(principal.getId(), principal.getWarehouseId());
                
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ĐÃ SỬA: Bỏ Role CASHIER khỏi @PreAuthorize
    @GetMapping("/count-unread")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Long>> countUnread(
            @AuthenticationPrincipal UserPrincipal principal) {
            
        long count = (principal.getRole() == User.UserRole.ROLE_ADMIN)
                ? notificationService.countUnread(null)
                : notificationService.countUnreadForManager(principal.getId(), principal.getWarehouseId());
                
        return ResponseEntity.ok(ApiResponse.ok(count));
    }

    // ĐÃ SỬA: Bỏ Role CASHIER khỏi @PreAuthorize
    @PatchMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã đọc thông báo", null));
    }

    // ĐÃ SỬA: Bỏ Role CASHIER khỏi @PreAuthorize
    @PatchMapping("/read-all")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal.getRole() == User.UserRole.ROLE_ADMIN) {
            notificationService.markAllAsRead(null);
        } else {
            notificationService.markAllAsReadForManager(principal.getId(), principal.getWarehouseId());
        }
        return ResponseEntity.ok(ApiResponse.ok("Đã đánh dấu đọc tất cả", null));
    }
}