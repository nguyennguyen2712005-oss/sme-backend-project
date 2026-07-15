package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class PaymentQrResponse {
    private String code;         
    private String qrCode;        
    private String checkoutUrl;   
    private BigDecimal amount;
    private Instant expiredAt;
    private String status;        
    private String reference;     
}