package com.ansertech.service.email;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.repository.EmailRepository;
import com.ansertech.service.ai.GeminiService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailClassificationService {

    private final GeminiService geminiService;
    private final EmailRepository emailRepository;

    @Value("${email.classification.confidence-threshold:0.85}")
    private double confidenceThreshold;

    @Transactional
    public EmailStatus classify(Email email) {
        try {
            log.info("Clasificando email id={} de {}", email.getId(), email.getSenderEmail());

            JsonNode result = geminiService.classifyEmail(
                    email.getSenderEmail(),
                    email.getSubject(),
                    email.getBody()
            );

            if (result.has("error")) {
                log.warn("Error en clasificación IA para email {}", email.getId());
                email.setStatus(EmailStatus.UNCERTAIN);
                email.setClassificationReason("Error en modelo IA");
                emailRepository.save(email);
                return EmailStatus.UNCERTAIN;
            }

            String classification = result.path("classification").asText("UNCERTAIN");
            double confidence = result.path("confidence").asDouble(0.0);
            String reason = result.path("reason").asText("");

            email.setSpamConfidence(classification.equals("SPAM") ? confidence : 1.0 - confidence);
            email.setClassificationReason(reason);
            email.setProcessedAt(LocalDateTime.now());

            EmailStatus status;
            if (confidence < confidenceThreshold) {
                status = EmailStatus.UNCERTAIN;
                log.info("Email {} → UNCERTAIN (confianza {} < {})",
                        email.getId(), String.format("%.2f", confidence), confidenceThreshold);
            } else if ("SPAM".equals(classification)) {
                status = EmailStatus.SPAM;
                log.info("Email {} → SPAM (confianza {})", email.getId(), String.format("%.2f", confidence));
            } else {
                status = EmailStatus.VALID;
                log.info("Email {} → VALID_RFQ (confianza {})", email.getId(), String.format("%.2f", confidence));
            }

            email.setStatus(status);
            emailRepository.save(email);
            return status;

        } catch (Exception e) {
            log.error("Excepción clasificando email {}: {}", email.getId(), e.getMessage());
            email.setStatus(EmailStatus.UNCERTAIN);
            emailRepository.save(email);
            return EmailStatus.UNCERTAIN;
        }
    }
}
