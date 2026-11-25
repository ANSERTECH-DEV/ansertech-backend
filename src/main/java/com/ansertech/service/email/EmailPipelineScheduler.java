package com.ansertech.service.email;

import com.ansertech.domain.enums.AuditAction;
import com.ansertech.service.audit.AuditService;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.repository.EmailRepository;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.ai.GeminiService;
import com.ansertech.service.parameter.ParameterService;
import com.ansertech.service.quotation.QuotationService;
import com.ansertech.service.rfq.RfqExtractionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    private final AuditService auditService;
    private final ParameterService parameterService;
    private final RfqRepository rfqRepository;
    private final AttachmentLoader attachmentLoader;

    /** Ventana de recuperación: correos a medias de los últimos 7 días, con al menos 2 min de antigüedad. */
    static final int RECOVERY_DAYS = 7;
    static final int RECOVERY_MIN_AGE_MINUTES = 2;
    static final int RECOVERY_BATCH = 10;

    @Value("${microsoft.polling.enabled:false}")
    private boolean pollingEnabled;

    @Value("${microsoft.polling.max-messages-per-poll:50}")
    private int maxResults;

    public void runPipelineManually() {
        // ejecuta polling
        log.info("Iniciando polling de correos — {}", LocalDateTime.now());
        try {
            recoverIncompleteEmails();
            int emailCount = parameterService.getEmailCount(maxResults);
            log.info("Polling manual: se consultarán hasta {} correos", emailCount);
            List<EmailPollingService.RawEmailData> rawEmails = pollingService.fetchUnreadEmails(emailCount);
            log.info("Correos nuevos encontrados: {}", rawEmails.size());

            for (EmailPollingService.RawEmailData raw : rawEmails) {
                processEmail(raw);
            }
        } catch (Exception e) {
            log.error("Error en ciclo de polling: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${microsoft.polling.fixed-delay-ms:2400000}")
    public void runPipeline() {
        if (!pollingEnabled) {
            log.debug("Polling de correos deshabilitado (microsoft.polling.enabled=false)");
            return;
        }
        log.info("Iniciando polling de correos — {}", LocalDateTime.now());
        try {
            recoverIncompleteEmails();
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
        Optional<Email> existing = emailRepository.findByExternalMessageId(raw.externalId());
        if (existing.isPresent()) {
            Email email = existing.get();
            if (isIncomplete(email)) {
                log.warn("Email {} quedó a medias (estado {}, sin RFQ): se reprocesa", email.getId(), email.getStatus());
                runPipeline(email, raw.attachments());
            } else {
                // Ya procesado: se marca como leído para que la próxima lectura avance a los siguientes (#22).
                log.debug("Email {} ya procesado, se marca como leído", raw.externalId());
                markAsReadQuietly(raw.externalId());
            }
            return;
        }

        String attachmentsJson = "[]";
        try {
            attachmentsJson = objectMapper.writeValueAsString(raw.attachments());
        } catch (Exception ignored) {}

        String ccJson = raw.ccAddresses().isEmpty() ? null : String.join(",", raw.ccAddresses());

        Email email = Email.builder()
                .externalMessageId(raw.externalId())
                .senderEmail(raw.senderEmail())
                .senderName(raw.senderName())
                .subject(raw.subject())
                .body(raw.body())
                .receivedAt(raw.receivedAt())
                .hasAttachments(raw.hasAttachments())
                .attachmentsJson(attachmentsJson)
                .ccAddresses(ccJson)
                .emailProvider(pollingService.getClass().getSimpleName()
                        .replace("PollingService", "").toLowerCase())
                .status(EmailStatus.PROCESSING)
                .build();
        emailRepository.save(email);

        runPipeline(email, raw.attachments());
    }

    /**
     * Reprocesa los correos que quedaron a medias porque el proceso se cortó entre la clasificación y la
     * extracción (caso real: email 498, VALID sin RFQ). Usa los datos guardados en la BD (cuerpo y attachments_json).
     */
    public void recoverIncompleteEmails() {
        LocalDateTime now = LocalDateTime.now();
        List<Email> pending = emailRepository.findIncomplete(now.minusDays(RECOVERY_DAYS),
                now.minusMinutes(RECOVERY_MIN_AGE_MINUTES), PageRequest.of(0, RECOVERY_BATCH));
        if (pending.isEmpty()) return;
        log.warn("Recuperando {} correo(s) que quedaron a medias: {}", pending.size(),
                pending.stream().map(Email::getId).toList());
        for (Email email : pending) {
            runPipeline(email, readAttachments(email));
        }
    }

    private boolean isIncomplete(Email email) {
        EmailStatus st = email.getStatus();
        return st == null || st == EmailStatus.PENDING || st == EmailStatus.PROCESSING
                || (st == EmailStatus.VALID && !rfqRepository.existsByEmailId(email.getId()));
    }

    /** Clasifica (viendo los adjuntos), extrae el RFQ si es válido y marca el correo como leído. */
    private void runPipeline(Email email, List<EmailPollingService.AttachmentInfo> attachments) {
        try {
            List<GeminiService.FilePart> files = attachmentLoader.load(email.getExternalMessageId(), attachments);

            EmailStatus status = classificationService.classify(email, files);
            auditService.recordSystem(AuditAction.EMAIL_CLASSIFIED, "EMAIL", email.getId(),
                    AuditService.details("remitente", email.getSenderEmail(), "asunto", email.getSubject(),
                            "clasificacion", status != null ? status.name() : null,
                            "confianza", email.getSpamConfidence(),
                            "motivo", email.getClassificationReason(),
                            "adjuntosIA", files.size()));

            if (status == EmailStatus.VALID && !rfqRepository.existsByEmailId(email.getId())) {
                var rfq = rfqExtractionService.extractAndSave(email, files);
                log.info("RFQ {} creado desde email {}", rfq.getId(), email.getId());
                auditService.recordSystem(AuditAction.RFQ_CREATED, "RFQ", rfq.getId(),
                        AuditService.details("emailId", email.getId(),
                                "cliente", rfq.getClientCompany() != null ? rfq.getClientCompany() : rfq.getClientEmail(),
                                "items", rfq.getItems() != null ? rfq.getItems().size() : 0,
                                "confianzaExtraccion", rfq.getExtractionConfidence()));
            }

            pollingService.markAsRead(email.getExternalMessageId());
        } catch (Exception e) {
            log.error("Error procesando email {}: {}", email.getId(), e.getMessage(), e);
            email.setStatus(EmailStatus.ERROR);
            emailRepository.save(email);
            auditService.recordSystem(AuditAction.EMAIL_PROCESSING_FAILED, "EMAIL", email.getId(),
                    AuditService.details("remitente", email.getSenderEmail(), "error", e.getMessage()));
        }
    }

    private List<EmailPollingService.AttachmentInfo> readAttachments(Email email) {
        String json = email.getAttachmentsJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<EmailPollingService.AttachmentInfo>>() {});
        } catch (Exception e) {
            log.warn("attachments_json ilegible en email {}: {}", email.getId(), e.getMessage());
            return List.of();
        }
    }

    private void markAsReadQuietly(String externalId) {
        try {
            pollingService.markAsRead(externalId);
        } catch (Exception e) {
            log.warn("No se pudo marcar como leído {}: {}", externalId, e.getMessage());
        }
    }
}
