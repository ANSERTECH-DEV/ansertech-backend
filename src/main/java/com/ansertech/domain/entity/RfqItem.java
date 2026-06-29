package com.ansertech.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "rfq_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RfqItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", nullable = false)
    private Rfq rfq;

    @Column(name = "product_code", length = 100)
    private String productCode;

    @Column(name = "product_description", columnDefinition = "TEXT")
    private String productDescription;

    private BigDecimal quantity;

    @Column(length = 50)
    private String unit;

    @Column(name = "service_type")
    private String serviceType;

    @Column(name = "field_confidence")
    private Double fieldConfidence;

    @Column(columnDefinition = "TEXT")
    private String notes;

}
