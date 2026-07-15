package sme.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import sme.backend.security.filter.JwtAuthEntryPoint;
import sme.backend.security.filter.JwtAuthenticationFilter;

/**
 * SecurityConfig — Phân quyền API tầng HTTP (RBAC).
 *
 * QUAN TRỌNG — Quy tắc thứ tự:
 *   Spring Security xét requestMatchers THEO THỨ TỰ khai báo, match đầu tiên thắng.
 *   Các matcher cụ thể hơn (có HttpMethod hoặc path cụ thể) PHẢI đứng TRƯỚC
 *   matcher tổng quát hơn của cùng một path.
 *
 * PHÂN QUYỀN 3 ROLE:
 *   ROLE_ADMIN   — Giám sát toàn chuỗi. Không can thiệp vận hành vật lý.
 *   ROLE_MANAGER — Toàn quyền chi nhánh mình. Duyệt ca, xử lý đơn, v.v.
 *   ROLE_CASHIER — Chỉ POS, đóng gói, hủy PENDING order.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final UserDetailsService userDetailsService;

    // 1. Bean PasswordEncoder (Đã thêm ở bước trước)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2. ĐÃ THÊM: Bean AuthenticationManager để AuthService gọi hàm login
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex ->
                    ex.authenticationEntryPoint(jwtAuthEntryPoint))

            .authorizeHttpRequests(auth -> auth

                // ── PUBLIC ──────────────────────────────────────────────
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/ws/**", "/ws/**").permitAll()
                // [MỚI] payOS gọi vào endpoint này khi có biến động số dư — không có JWT.
                // BẮT BUỘC đứng TRƯỚC rule "/pos/**" bên dưới
                .requestMatchers(HttpMethod.POST, "/pos/payments/webhook").permitAll()

                // ── MODULE 0: POS & SHIFTS ───────────────────────────────
                .requestMatchers("/pos/**")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")

                // ── MODULE 1: INVENTORY ──────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/inventory/**")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers("/inventory/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 2: PURCHASE ORDERS ────────────────────────────
                .requestMatchers("/purchase-orders/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 3: TRANSFERS ──────────────────────────────────
                .requestMatchers("/transfers/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 4: ORDERS ─────────────────────────────────────
                .requestMatchers(HttpMethod.GET,   "/orders/**")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/orders/**")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers("/orders/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 5: CRM ────────────────────────────────────────
                .requestMatchers(HttpMethod.GET,  "/customers/**")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/customers")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers("/customers/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 6: FINANCE ────────────────────────────────────
                .requestMatchers("/finance/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 7: REPORTS ────────────────────────────────────
                .requestMatchers("/reports/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── MODULE 7B: DASHBOARD ─────────────────────────────────
                .requestMatchers("/dashboard/cashier").hasRole("CASHIER")
                .requestMatchers("/dashboard/manager").hasRole("MANAGER")
                .requestMatchers("/dashboard/admin").hasRole("ADMIN")

                // ── MODULE 8: ADMIN SETTINGS ─────────────────────────────
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/warehouses/**")
                    .hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers("/warehouses/**").hasRole("ADMIN")

                // ── [NEW - Step 3] MODULE: KIỂM KÊ (STOCK TAKE) ────────
                .requestMatchers("/stock-takes/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── [NEW - Step 5] MODULE: KHUYẾN MÃI (PROMOTIONS) ──────
                .requestMatchers(HttpMethod.GET,  "/promotions/active")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/promotions/validate")
                    .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/promotions/**")
                    .hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST,   "/promotions/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/promotions/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH,  "/promotions/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/promotions/**").hasRole("ADMIN")

                // ── AI MODULE ────────────────────────────────────────────
                .requestMatchers("/ai/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── NOTIFICATIONS ────────────────────────────────────────
                .requestMatchers("/notifications/**")
                    .hasAnyRole("MANAGER", "ADMIN")

                // ── SWAGGER / DOCS ───────────────────────────────────────
                .requestMatchers(
                    "/v3/api-docs/**", "/v3/api-docs.yaml",
                    "/swagger-ui/**", "/swagger-ui.html",
                    "/swagger-resources/**", "/webjars/**"
                ).permitAll()

                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder()); 
        return provider;
    }
}