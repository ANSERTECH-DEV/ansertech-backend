package com.ansertech.dto.response;

import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.RfqType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class RfqResponse {
    private Long id;
    private Long emailId;
    private RfqType rfqType;
    private String clientName;
    private String clientCompany;
    private String clientEmail;
    private String clientPhone;
    private String urgency;
    private Double extractionConfidence;
    private RfqStatus status;
    private String notes;
    private Double conversionProbability;
    private List<RfqItemResponse> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data @Builder
    public static class RfqItemResponse {
        private Long id;
        private String productCode;
        private String productDescription;
        private Double quantity;
        private String unit;
        private Double fieldConfidence;
    }
}
