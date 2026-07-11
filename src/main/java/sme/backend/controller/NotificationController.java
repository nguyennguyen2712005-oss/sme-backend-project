
package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.entity.Notification;
import sme.backend.entity.User; // <- Bổ sung import này
import sme.backend.security.UserPrincipal;
import sme.backend.service.NotificationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/unread")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Notification>>> getUnread(
            @AuthenticationPrincipal UserPrincipal principal) {
        // Sử dụng Enum so sánh an toàn hơn String
        UUID userId = (principal.getRole() == User.UserRole.ROLE_ADMIN) ? null : principal.getId();
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnread(userId)));
    }

    @GetMapping("/count-unread")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Long>> countUnread(
            @AuthenticationPrincipal UserPrincipal principal) {
        // Sử dụng Enum so sánh an toàn hơn String
        UUID userId = (principal.getRole() == User.UserRole.ROLE_ADMIN) ? null : principal.getId();
        return ResponseEntity.ok(ApiResponse.ok(notificationService.countUnread(userId)));
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã đọc thông báo", null));
    }

    @PatchMapping("/read-all")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(@AuthenticationPrincipal UserPrincipal principal) {
        // Sử dụng Enum so sánh an toàn hơn String
        UUID userId = (principal.getRole() == User.UserRole.ROLE_ADMIN) ? null : principal.getId();
        List<Notification> unread = notificationService.getUnread(userId);
        for (Notification n : unread) {
            notificationService.markAsRead(n.getId());
        }
        return ResponseEntity.ok(ApiResponse.ok("Đã đánh dấu đọc tất cả", null));
    }
}
