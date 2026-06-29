package com.ansertech.domain.entity;

import com.ansertech.domain.enums.StockCheckStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pending_review_rfqs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RfqStockCheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Un resultado por RFQ — constraint UNIQUE garantiza idempotencia
    @Column(name = "rfq_id", nullable = false, unique = true)
    private Long rfqId;

    // JSON serializado de List<StockCheckItemResponse> — un elemento por ítem del RFQ
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StockCheckStatus status = StockCheckStatus.PROCESSING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
