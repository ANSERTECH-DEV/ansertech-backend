package com.ansertech.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class StockCheckItemResponse {
    private Long rfqItemId;
    private String rfqDescription;
    private Double quantityRequested;
    private String unitRequested;
    private boolean matched;
    private Long productId;
    private String productSku;
    private String productName;
    private Double similarityScore;
    private String matchReason;
    private BigDecimal stockQuantity;
    private String stockUnit;
    private boolean stockSufficient;
}
