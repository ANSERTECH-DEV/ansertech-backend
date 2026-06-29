package com.ansertech.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 100)
    private String sku;

    @Column(nullable = false, length = 500)
    private String name;

    @Column
    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String unit;

    @Column(name = "stock_quantity")
    @Builder.Default
    private BigDecimal stockQuantity = BigDecimal.ZERO;

    @Column(name = "min_stock_threshold")
    @Builder.Default
    private BigDecimal minStockThreshold = BigDecimal.valueOf(5);

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(length = 10)
    @Builder.Default
    private String currency = "PEN";

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "brand")
    private String brand;

    @Builder.Default
    private Boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
