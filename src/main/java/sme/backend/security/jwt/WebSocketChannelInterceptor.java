package sme.backend.security.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import sme.backend.entity.User;
import sme.backend.security.UserPrincipal;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            // 1. KHI KẾT NỐI (CONNECT) -> LƯU THÔNG TIN USER
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    
                    if (jwtTokenProvider.validateToken(token)) {
                        String username = jwtTokenProvider.getUsernameFromToken(token);
                        UUID userId = jwtTokenProvider.getUserIdFromToken(token);
                        String roleStr = jwtTokenProvider.getRoleFromToken(token);
                        UUID warehouseId = jwtTokenProvider.getWarehouseIdFromToken(token);

                        User.UserRole role = User.UserRole.valueOf(roleStr);
                        
                        UserPrincipal principal = new UserPrincipal(
                                userId, username, "", username, warehouseId, role, true,
                                List.of(new SimpleGrantedAuthority(roleStr))
                        );

                        UsernamePasswordAuthenticationToken authentication = 
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        
                        accessor.setUser(authentication);
                        log.info("WebSocket connected successfully for user: {} (Role: {})", username, roleStr);
                    }
                }
            } 
            // 2. ĐÃ THÊM: KHI BẮT ĐẦU NGHE KÊNH (SUBSCRIBE) -> KIỂM TRA QUYỀN TRUY CẬP
            else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                String destination = accessor.getDestination();
                Authentication auth = (Authentication) accessor.getUser();
                
                if (destination != null && auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
                    
                    // Kênh Admin thì chỉ có ROLE_ADMIN mới được theo dõi
                    if (destination.startsWith("/topic/admin/") && principal.getRole() != User.UserRole.ROLE_ADMIN) {
                        throw new IllegalArgumentException("Không có quyền theo dõi kênh này");
                    }
                    
                    // Phân quyền cho kênh Chi nhánh
                    if (destination.startsWith("/topic/warehouse/")) {
                        // Thu ngân không cần theo dõi cảnh báo (Giảm thiểu traffic)
                        if (principal.getRole() == User.UserRole.ROLE_CASHIER) {
                            throw new IllegalArgumentException("Không có quyền theo dõi kênh này");
                        }
                        // Quản lý không được "nghe lén" kênh của kho khác
                        if (principal.getRole() == User.UserRole.ROLE_MANAGER
                                && !destination.startsWith("/topic/warehouse/" + principal.getWarehouseId() + "/")) {
                            throw new IllegalArgumentException("Không có quyền theo dõi chi nhánh khác");
                        }
                    }
                }
            }
        }
        return message;
    }
}