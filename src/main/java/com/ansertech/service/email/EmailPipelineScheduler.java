package com.ansertech.service.email;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.repository.EmailRepository;
import com.ansertech.service.quotation.QuotationService;
import com.ansertech.service.rfq.RfqExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailPipelineScheduler {

    private final EmailPollingService pollingService;
    private final EmailClassificationService classificationService;
    private final RfqExtractionService rfqExtractionService;
    private final QuotationService quotationService;
    private final EmailRepository emailRepository;
    private final ObjectMapper objectMapper;

    @Value("${microsoft.polling.enabled:false}")
    private boolean pollingEnabled;

    @Value("${microsoft.polling.max-messages-per-poll:50}")
    private int maxResults;

    @Scheduled(fixedDelayString = "${microsoft.polling.fixed-delay-ms:120000}")
    public void runPipeline() {
        if (!pollingEnabled) {
            log.debug("Polling de correos deshabilitado (microsoft.polling.enabled=false)");
            return;
        }
        log.info("Iniciando polling de correos — {}", LocalDateTime.now());
        try {
            List<EmailPollingService.RawEmailData> rawEmails = pollingService.fetchUnreadEmails(maxResults);
            log.info("Correos nuevos encontrados: {}", rawEmails.size());

            for (EmailPollingService.RawEmailData raw : rawEmails) {
                processEmail(raw);
            }
        } catch (Exception e) {
            log.error("Error en ciclo de polling: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void processEmail(EmailPollingService.RawEmailData raw) {
        if (emailRepository.existsByExternalMessageId(raw.externalId())) {
            log.debug("Email {} ya procesado, omitiendo", raw.externalId());
            return;
        }

        String attachmentsJson = "[]";
        try {
            attachmentsJson = objectMapper.writeValueAsString(raw.attachments());
        } catch (Exception ignored) {}

        Email email = Email.builder()
                .externalMessageId(raw.externalId())
                .senderEmail(raw.senderEmail())
                .senderName(raw.senderName())
                .subject(raw.subject())
                .body(raw.body())
                .receivedAt(raw.receivedAt())
                .hasAttachments(raw.hasAttachments())
                .attachmentsJson(attachmentsJson)
                .emailProvider(pollingService.getClass().getSimpleName()
                        .replace("PollingService", "").toLowerCase())
                .status(EmailStatus.PROCESSING)
                .build();
        emailRepository.save(email);

        try {
            EmailStatus status = classificationService.classify(email);

            if (status == EmailStatus.VALID || status == EmailStatus.UNCERTAIN) {
                var rfq = rfqExtractionService.extractAndSave(email, raw.attachments());
                log.info("RFQ {} creado desde email {}", rfq.getId(), email.getId());

                if (status == EmailStatus.VALID) {
                    quotationService.generateFromRfq(rfq);
                }
            }

            pollingService.markAsRead(raw.externalId());
        } catch (Exception e) {
            log.error("Error procesando email {}: {}", email.getId(), e.getMessage(), e);
            email.setStatus(EmailStatus.ERROR);
            emailRepository.save(email);
        }
    }
}
