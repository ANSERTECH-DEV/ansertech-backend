package com.ansertech.domain.entity;

import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.RfqType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rfqs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Rfq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id")
    private Email email;

    @Enumerated(EnumType.STRING)
    @Column(name = "rfq_type")
    private RfqType rfqType;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "client_company")
    private String clientCompany;

    @Column(name = "client_email")
    private String clientEmail;

    @Column(name = "client_phone")
    private String clientPhone;

    @Column(name = "urgency", length = 10)
    private String urgency;

    @Column(name = "extraction_confidence")
    private Double extractionConfidence;

    @Column(name = "raw_extracted_json", columnDefinition = "TEXT")
    private String rawExtractedJson;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RfqStatus status = RfqStatus.PENDING_REVIEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    private User operator;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "conversion_probability")
    private Double conversionProbability;

    @OneToMany(mappedBy = "rfq", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RfqItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
