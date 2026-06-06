package com.ansertech.dto.response;

import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.domain.enums.QuotationStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class QuotationResponse {
    private Long id;
    private Long rfqId;
    private String quotationNumber;
    private QuotationStatus status;
    private BigDecimal subtotal;
    private BigDecimal igv;
    private BigDecimal total;
    private String currency;
    private LocalDate validUntil;
    private String pdfPath;
    private String aiSummary;
    private Double conversionProbability;
    private List<QuotationItemResponse> items;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;

    @Data @Builder
    public static class QuotationItemResponse {
        private Long id;
        private String description;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
        private AvailabilityStatus availabilityStatus;
    }
}
