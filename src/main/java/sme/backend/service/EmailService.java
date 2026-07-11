package sme.backend.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Gửi email nghiệp vụ. Hiện chỉ dùng cho luồng quên mật khẩu.
 * @Async chạy nền — không cần thêm @EnableAsync vì BackendApplication đã khai báo sẵn.
 */
@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.mail.sender-name:SME ERP & POS}")
    private String senderName;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String fullName, String resetLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress, senderName);
            helper.setTo(toEmail);
            helper.setSubject("Yêu cầu đặt lại mật khẩu - SME ERP & POS");
            helper.setText(buildHtmlBody(fullName, resetLink), true);
            mailSender.send(message);
            log.info("Đã gửi email đặt lại mật khẩu tới: {}", toEmail);
        } catch (Exception e) {
            log.error("Gửi email đặt lại mật khẩu thất bại cho {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildHtmlBody(String fullName, String resetLink) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto; padding: 24px;">
                    <h2 style="color:#4f46e5;">Đặt lại mật khẩu</h2>
                    <p>Xin chào <b>%s</b>,</p>
                    <p>Có yêu cầu đặt lại mật khẩu cho tài khoản SME ERP &amp; POS gắn với email này.
                       Nhấn vào nút bên dưới để đặt mật khẩu mới. Liên kết có hiệu lực trong <b>15 phút</b>
                       và chỉ dùng được một lần.</p>
                    <p style="text-align:center; margin: 32px 0;">
                        <a href="%s" style="background:#4f46e5; color:#ffffff; padding:14px 28px;
                           border-radius:12px; text-decoration:none; font-weight:bold; display:inline-block;">
                           Đặt lại mật khẩu
                        </a>
                    </p>
                    <p style="font-size:13px; color:#64748b;">
                        Nếu bạn không yêu cầu thao tác này, vui lòng bỏ qua email —
                        mật khẩu của bạn sẽ không bị thay đổi.
                    </p>
                </div>
                """.formatted(fullName, resetLink);
    }
}