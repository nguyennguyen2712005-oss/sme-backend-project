package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.ChangePasswordRequest;
import sme.backend.dto.request.CreateUserRequest;
import sme.backend.dto.request.ForgotPasswordRequest;
import sme.backend.dto.request.LoginRequest;
import sme.backend.dto.request.ResetPasswordRequest;
import sme.backend.dto.response.AuthResponse;
import sme.backend.dto.response.UserResponse;
import sme.backend.entity.User;
import sme.backend.entity.Warehouse;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.UserRepository;
import sme.backend.repository.WarehouseRepository;
import sme.backend.security.UserPrincipal;
import sme.backend.security.jwt.JwtTokenProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final WarehouseRepository warehouseRepository;
    private final PasswordEncoder passwordEncoder;
    
    private final EmailService emailService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private static final String RESET_TOKEN_PREFIX = "pwd-reset:";
    private static final String RESET_COOLDOWN_PREFIX = "pwd-reset-cooldown:";

    // ─────────────────────────────────────────────────────────
    // LOGIN & REFRESH TOKEN
    // ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(), request.getPassword()
                )
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        userRepository.findByUsernameAndIsActiveTrue(principal.getUsername())
                .ifPresent(u -> {
                    u.setLastLoginAt(Instant.now());
                    userRepository.save(u);
                });

        String accessToken  = jwtTokenProvider.generateAccessToken(principal);
        String refreshToken = jwtTokenProvider.generateRefreshToken(principal.getUsername());

        String warehouseName = null;
        if (principal.getWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(principal.getWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(86400000L)
                .user(toUserResponse(principal, warehouseName))
                .build();
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException("INVALID_TOKEN", "Refresh token không hợp lệ hoặc đã hết hạn");
        }
        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);

        User user = userRepository.findByUsernameAndIsActiveTrue(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));

        UserPrincipal principal = UserPrincipal.build(user);
        String newAccessToken  = jwtTokenProvider.generateAccessToken(principal);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(username);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(86400000L)
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // USER MANAGEMENT (ADMIN & MANAGER)
    // ─────────────────────────────────────────────────────────
    
    private void verifyManagerAccess(UserPrincipal principal, User targetUser) {
        if (principal.getRole() == User.UserRole.ROLE_MANAGER) {
            if (!principal.getWarehouseId().equals(targetUser.getWarehouseId())) {
                throw new BusinessException("ACCESS_DENIED", "Bạn chỉ được thao tác trên nhân sự thuộc chi nhánh của mình.");
            }
        }
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest req, UserPrincipal principal) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException("DUPLICATE_USERNAME", "Tên đăng nhập '" + req.getUsername() + "' đã tồn tại");
        }

        User.UserRole role;
        try {
            role = User.UserRole.valueOf(req.getRole());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ROLE", "Role không hợp lệ: " + req.getRole());
        }

        UUID effectiveWarehouseId = req.getWarehouseId();

        if (principal.getRole() == User.UserRole.ROLE_MANAGER) {
            if (role != User.UserRole.ROLE_CASHIER) {
                throw new BusinessException("ACCESS_DENIED", "Quản lý chi nhánh chỉ được phép tạo tài khoản Thu ngân.");
            }
            effectiveWarehouseId = principal.getWarehouseId();
        }

        if (role != User.UserRole.ROLE_ADMIN && effectiveWarehouseId == null) {
            throw new BusinessException("WAREHOUSE_REQUIRED", "Manager và Cashier phải được gán vào một chi nhánh");
        }

        User user = User.builder()
                .username(req.getUsername())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .fullName(req.getFullName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .role(role)
                .warehouseId(effectiveWarehouseId)
                .isActive(true)
                .build();

        user = userRepository.save(user);
        log.info("Created user: {} with role: {} by {}", user.getUsername(), user.getRole(), principal.getUsername());
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> searchUsers(String keyword, String roleStr, UUID warehouseId, UserPrincipal principal) {
        UUID effectiveWarehouseId = warehouseId;
        if (principal.getRole() == User.UserRole.ROLE_MANAGER) {
            effectiveWarehouseId = principal.getWarehouseId();
        }
        final UUID finalWid = effectiveWarehouseId;

        return userRepository.findAll(Sort.by("fullName")).stream()
                .filter(u -> keyword == null || keyword.isBlank() ||
                        u.getFullName().toLowerCase().contains(keyword.toLowerCase()) ||
                        u.getUsername().toLowerCase().contains(keyword.toLowerCase()) ||
                        (u.getEmail() != null && u.getEmail().toLowerCase().contains(keyword.toLowerCase())))
                .filter(u -> roleStr == null || roleStr.isBlank() || u.getRole().name().equals(roleStr))
                .filter(u -> finalWid == null || finalWid.equals(u.getWarehouseId()))
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public UserResponse updateUser(UUID id, CreateUserRequest req, UserPrincipal principal) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        verifyManagerAccess(principal, user);

        if (req.getFullName() != null) user.setFullName(req.getFullName());
        if (req.getEmail() != null) user.setEmail(req.getEmail());
        if (req.getPhone() != null) user.setPhone(req.getPhone());
        if (req.getPosSettings() != null) user.setPosSettings(req.getPosSettings());

        if (req.getRole() != null) {
            User.UserRole newRole = User.UserRole.valueOf(req.getRole());
            if (principal.getRole() == User.UserRole.ROLE_MANAGER) {
                if (user.getId().equals(principal.getId()) && newRole != User.UserRole.ROLE_MANAGER) {
                    throw new BusinessException("ACCESS_DENIED", "Bạn không thể tự thay đổi chức vụ của chính mình.");
                }
                if (!user.getId().equals(principal.getId()) && newRole != User.UserRole.ROLE_CASHIER) {
                    throw new BusinessException("ACCESS_DENIED", "Bạn chỉ được phép cấp quyền Thu ngân cho nhân viên.");
                }
            }
            user.setRole(newRole);
        }

        if (req.getWarehouseId() != null) {
            if (principal.getRole() == User.UserRole.ROLE_MANAGER && !req.getWarehouseId().equals(principal.getWarehouseId())) {
                throw new BusinessException("ACCESS_DENIED", "Bạn không thể chuyển nhân sự sang chi nhánh khác.");
            }
            user.setWarehouseId(req.getWarehouseId());
        }

        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }
        
        return mapToResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse toggleUserActive(UUID userId, boolean active, UserPrincipal principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        verifyManagerAccess(principal, user);

        if (principal.getRole() == User.UserRole.ROLE_MANAGER && user.getId().equals(principal.getId()) && !active) {
            throw new BusinessException("ACCESS_DENIED", "Bạn không thể tự khóa tài khoản của chính mình.");
        }

        user.setIsActive(active);
        return mapToResponse(userRepository.save(user));
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("WRONG_PASSWORD", "Mật khẩu hiện tại không đúng");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAllActive().stream()
                .map(this::mapToResponse).toList();
    }

    // ─────────────────────────────────────────────────────────
    // QUÊN MẬT KHẨU
    // ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public void forgotPassword(String email) {
        userRepository.findFirstByEmailIgnoreCaseAndIsActiveTrue(email).ifPresentOrElse(user -> {
            String cooldownKey = RESET_COOLDOWN_PREFIX + user.getId();
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(cooldownKey))) {
                log.info("Bỏ qua yêu cầu quên mật khẩu (đang trong cooldown) cho user: {}", user.getId());
                return; 
            }

            String token = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "");

            stringRedisTemplate.opsForValue()
                    .set(RESET_TOKEN_PREFIX + token, user.getId().toString(), Duration.ofMinutes(15));
            stringRedisTemplate.opsForValue()
                    .set(cooldownKey, "1", Duration.ofSeconds(60));

            String resetLink = frontendUrl + "/reset-password?token=" + token;
            emailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), resetLink);

            log.info("Đã tạo token đặt lại mật khẩu cho user: {}", user.getId());
        }, () -> log.info("Yêu cầu quên mật khẩu cho email không tồn tại/không active: {}", email));
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        String redisKey = RESET_TOKEN_PREFIX + req.getToken();
        String userIdStr = stringRedisTemplate.opsForValue().get(redisKey);

        if (userIdStr == null) {
            throw new BusinessException("INVALID_OR_EXPIRED_TOKEN",
                    "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn");
        }

        User user = userRepository.findById(UUID.fromString(userIdStr))
                .orElseThrow(() -> new ResourceNotFoundException("User", userIdStr));

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        stringRedisTemplate.delete(redisKey); 
        log.info("Đặt lại mật khẩu thành công cho user: {}", user.getId());
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────
    private UserResponse toUserResponse(UserPrincipal p, String warehouseName) {
        return UserResponse.builder()
                .id(p.getId())
                .username(p.getUsername())
                .fullName(p.getFullName())
                .role(p.getRole().name())
                .warehouseId(p.getWarehouseId())
                .warehouseName(warehouseName)
                .isActive(p.isEnabled())
                .build();
    }

    public UserResponse mapToResponse(User user) {
        String warehouseName = null;
        if (user.getWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(user.getWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole().name())
                .warehouseId(user.getWarehouseId())
                .warehouseName(warehouseName)
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .posSettings(user.getPosSettings())
                .build();
    }
}