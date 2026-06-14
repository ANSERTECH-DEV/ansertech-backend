package com.ansertech.domain.entity;

import com.ansertech.domain.enums.QuotationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "quotations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id")
    private Rfq rfq;

    @Column(name = "quotation_number", unique = true, nullable = false, length = 50)
    private String quotationNumber;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private QuotationStatus status = QuotationStatus.DRAFT;

    private BigDecimal subtotal;

    private BigDecimal igv;

    private BigDecimal total;

    @Builder.Default
    private String currency = "PEN";

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by")
    private User generatedBy;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "conversion_probability")
    private Double conversionProbability;

    @Column(name = "ai_alerts_json", columnDefinition = "TEXT")
    private String aiAlertsJson;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<QuotationItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
