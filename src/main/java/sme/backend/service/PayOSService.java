package sme.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import sme.backend.exception.BusinessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Service
@Slf4j
public class PayOSService {

    private static final String BASE_URL = "https://api-merchant.payos.vn";

    @Value("${app.payos.client-id}")
    private String clientId;

    @Value("${app.payos.api-key}")
    private String apiKey;

    @Value("${app.payos.checksum-key}")
    private String checksumKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record CreateLinkResult(String checkoutUrl, String qrCode, String paymentLinkId) {}

    public CreateLinkResult createPaymentLink(long orderCode, long amountVnd, String description,
                                               String returnUrl, String cancelUrl) {
        try {
            String signature = hmacSha256(
                    "amount=" + amountVnd +
                    "&cancelUrl=" + cancelUrl +
                    "&description=" + description +
                    "&orderCode=" + orderCode +
                    "&returnUrl=" + returnUrl,
                    checksumKey);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderCode", orderCode);
            body.put("amount", amountVnd);
            body.put("description", description);
            body.put("cancelUrl", cancelUrl);
            body.put("returnUrl", returnUrl);
            body.put("signature", signature);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);

            var response = restTemplate.postForEntity(
                    BASE_URL + "/v2/payment-requests",
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!"00".equals(root.path("code").asText())) {
                throw new BusinessException("PAYOS_ERROR",
                        "payOS từ chối tạo link thanh toán: " + root.path("desc").asText());
            }
            JsonNode data = root.path("data");
            return new CreateLinkResult(
                    data.path("checkoutUrl").asText(null),
                    data.path("qrCode").asText(null),
                    data.path("paymentLinkId").asText(null));

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Gọi payOS createPaymentLink thất bại: {}", e.getMessage());
            throw new BusinessException("PAYOS_UNAVAILABLE",
                    "Không thể kết nối cổng thanh toán payOS. Vui lòng thử lại.");
        }
    }

    public void cancelPaymentLink(String paymentLinkId, String reason) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);
            Map<String, Object> body = Map.of("cancellationReason", reason != null ? reason : "Cashier cancelled");
            restTemplate.postForEntity(
                    BASE_URL + "/v2/payment-requests/" + paymentLinkId + "/cancel",
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.warn("Huỷ payOS link {} thất bại (bỏ qua): {}", paymentLinkId, e.getMessage());
        }
    }

    public boolean verifyWebhookSignature(Map<String, Object> data, String signature) {
        if (signature == null) return false;
        Map<String, Object> sorted = new TreeMap<>(data);
        StringBuilder sb = new StringBuilder();
        for (var entry : sorted.entrySet()) {
            Object v = entry.getValue();
            String value = (v == null) ? "" : String.valueOf(v);
            if (sb.length() > 0) sb.append('&');
            sb.append(entry.getKey()).append('=').append(value);
        }
        String expected = hmacSha256(sb.toString(), checksumKey);
        return MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Không thể tạo chữ ký HMAC_SHA256", e);
        }
    }
}