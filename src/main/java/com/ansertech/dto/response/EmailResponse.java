package com.ansertech.dto.response;

import com.ansertech.domain.enums.EmailStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data @Builder
public class EmailResponse {
    private Long id;
    private String senderEmail;
    private String senderName;
    private String subject;
    private String body;
    private LocalDateTime receivedAt;
    private EmailStatus status;
    private Double spamConfidence;
    private String classificationReason;
    private Boolean hasAttachments;
    private String emailProvider;
    private LocalDateTime createdAt;
}
