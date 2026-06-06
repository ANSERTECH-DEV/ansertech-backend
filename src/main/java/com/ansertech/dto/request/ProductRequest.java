package com.ansertech.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductRequest {
    @NotBlank private String sku;
    @NotBlank private String name;
    private String category;
    private String description;
    private String unit;
    @NotNull @PositiveOrZero private BigDecimal stockQuantity;
    @PositiveOrZero private BigDecimal minStockThreshold;
    @NotNull @PositiveOrZero private BigDecimal unitPrice;
    private String currency;
    private String supplierName;
    private String brand;
}
