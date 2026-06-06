package com.ansertech.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder
public class ProductResponse {
    private Long id;
    private String sku;
    private String name;
    private String category;
    private String description;
    private String unit;
    private BigDecimal stockQuantity;
    private BigDecimal minStockThreshold;
    private BigDecimal unitPrice;
    private String currency;
    private String supplierName;
    private String brand;
    private Boolean active;
    private Boolean lowStock;
    private LocalDateTime updatedAt;
}
