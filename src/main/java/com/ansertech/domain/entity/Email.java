package com.ansertech.domain.entity;

import com.ansertech.domain.enums.EmailStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "emails")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_message_id", unique = true, nullable = false, length = 500)
    private String externalMessageId;

    @Column(name = "sender_email")
    private String senderEmail;

    @Column(name = "sender_name")
    private String senderName;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EmailStatus status = EmailStatus.PENDING;

    @Column(name = "spam_confidence")
    private Double spamConfidence;

    @Column(name = "classification_reason", columnDefinition = "TEXT")
    private String classificationReason;

    @Column(name = "has_attachments")
    @Builder.Default
    private Boolean hasAttachments = false;

    @Column(name = "attachments_json", columnDefinition = "TEXT")
    private String attachmentsJson;

    @Column(name = "email_provider", length = 20)
    private String emailProvider;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
