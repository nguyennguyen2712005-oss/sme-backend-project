package sme.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Gửi email sử dụng Brevo HTTP REST API (Bypass lỗi chặn port SMTP 587/465 trên Cloud)
 */
@Service
@Slf4j
public class EmailService {

    @Value("${app.brevo.api-key}")
    private String apiKey;

    @Value("${app.brevo.from-email}")
    private String fromEmail;

    @Value("${app.brevo.from-name}")
    private String fromName;

    // Dùng RestTemplate để gọi HTTP API
    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    public void sendPasswordResetEmail(String toEmail, String fullName, String resetLink) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Chưa cấu hình BREVO_API_KEY. Hủy gửi mail tới: {}", toEmail);
            return;
        }

        try {
            String url = "https://api.brevo.com/v3/smtp/email";

            // 1. Setup Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("api-key", apiKey);

            // 2. Setup Body theo chuẩn JSON của Brevo v3
            Map<String, Object> body = Map.of(
                    "sender", Map.of("name", fromName, "email", fromEmail),
                    "to", List.of(Map.of("email", toEmail, "name", fullName)),
                    "subject", "Yêu cầu đặt lại mật khẩu - SME ERP & POS",
                    "htmlContent", buildHtmlBody(fullName, resetLink)
            );

            // 3. Bắn HTTP POST Request
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, request, String.class);

            log.info("Đã gửi email thành công qua Brevo API tới: {}", toEmail);
        } catch (Exception e) {
            log.error("Gửi email qua Brevo thất bại cho {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildHtmlBody(String fullName, String resetLink) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 16px;">
                    <h2 style="color:#4f46e5; text-align: center;">Đặt lại mật khẩu</h2>
                    <p>Xin chào <b>%s</b>,</p>
                    <p>Hệ thống SME ERP &amp; POS vừa nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.
                       Nhấn vào nút bên dưới để tiến hành thiết lập mật khẩu mới. Liên kết này chỉ có hiệu lực trong <b>15 phút</b>.</p>
                    <p style="text-align:center; margin: 32px 0;">
                        <a href="%s" style="background:#4f46e5; color:#ffffff; padding:14px 28px;
                           border-radius:12px; text-decoration:none; font-weight:bold; display:inline-block;">
                           Đặt lại mật khẩu ngay
                        </a>
                    </p>
                    <p style="font-size:13px; color:#64748b; border-top: 1px solid #e2e8f0; padding-top: 16px;">
                        Nếu bạn không yêu cầu thao tác này, vui lòng bỏ qua email này. Mật khẩu của bạn vẫn an toàn.
                    </p>
                </div>
                """.formatted(fullName, resetLink);
    }
}