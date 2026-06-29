package com.ansertech.service.rfq;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqItem;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.RfqType;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.ai.GeminiService;
import com.ansertech.service.email.EmailPollingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RfqExtractionService {

    private final GeminiService geminiService;
    private final RfqRepository rfqRepository;
    private final EmailPollingService pollingService;
    private final ObjectMapper objectMapper;
    private final RfqStockCheckQueueService stockCheckQueueService;

    @Transactional
    public Rfq extractAndSave(Email email, List<EmailPollingService.AttachmentInfo> attachments) {
        try {
            JsonNode extracted;
            String bodyWithContext = buildBodyWithContext(email);

            if (!attachments.isEmpty()) {
                EmailPollingService.AttachmentInfo first = attachments.get(0);
                boolean isSupportedMime = first.mimeType().equals("application/pdf")
                        || first.mimeType().startsWith("image/");

                if (isSupportedMime) {
                    byte[] bytes = pollingService.fetchAttachment(email.getExternalMessageId(), first.id());
                    extracted = geminiService.extractRfqFromBytes(bytes, first.mimeType(), bodyWithContext);
                    log.info("RFQ extraído desde adjunto {} ({})", first.name(), first.mimeType());
                } else {
                    extracted = geminiService.extractRfq(bodyWithContext);
                }
            } else {
                extracted = geminiService.extractRfq(bodyWithContext);
            }

            if (extracted.has("error")) {
                log.warn("Error extrayendo RFQ del email {}", email.getId());
                return buildFallbackRfq(email);
            }

            Rfq saved = buildAndSaveRfq(email, extracted);
            scheduleStockCheck(saved.getId());
            return saved;

        } catch (Exception e) {
            log.error("Excepción extrayendo RFQ para email {}: {}", email.getId(), e.getMessage());
            Rfq fallback = buildFallbackRfq(email);
            scheduleStockCheck(fallback.getId());
            return fallback;
        }
    }

    private void scheduleStockCheck(Long rfqId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("[Extraction] Encolando stock-check automático para RFQ {}", rfqId);
                stockCheckQueueService.processRfq(rfqId);
            }
        });
    }

    private Rfq buildAndSaveRfq(Email email, JsonNode data) {
        RfqType rfqType = parseEnum(data.path("rfq_type").asText("PRODUCT_SALE"), RfqType.class);

        String clientEmail = nullIfEmpty(data.path("client_email").asText());
        if (clientEmail == null) {
            clientEmail = email.getSenderEmail();
        }

        String clientName = nullIfEmpty(data.path("client_name").asText());
        if (clientName == null) {
            clientName = email.getSenderName();
        }

        Rfq rfq = Rfq.builder()
                .email(email)
                .rfqType(rfqType)
                .clientName(clientName)
                .clientCompany(nullIfEmpty(data.path("client_company").asText()))
                .clientEmail(clientEmail)
                .clientPhone(nullIfEmpty(data.path("client_phone").asText()))
                .urgency(data.path("urgency").asText("MEDIUM"))
                .extractionConfidence(data.path("overall_confidence").asDouble(0.0))
                .status(RfqStatus.PROCESSING)
                .items(new ArrayList<>())
                .build();

        try {
            rfq.setRawExtractedJson(objectMapper.writeValueAsString(data));
        } catch (Exception ignored) {}

        JsonNode items = data.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                RfqItem rfqItem = RfqItem.builder()
                        .rfq(rfq)
                        .productCode(nullIfEmpty(item.path("product_code").asText()))
                        .productDescription(item.path("product_description").asText(""))
                        .quantity(BigDecimal.valueOf(item.path("quantity").asDouble(1.0)))
                        .unit(item.path("unit").asText("unidad"))
                        .serviceType(nullIfEmpty(item.path("service_type").asText()))
                        .fieldConfidence(item.path("confidence").asDouble(0.0))
                        .build();
                rfq.getItems().add(rfqItem);
            }
        }

        return rfqRepository.save(rfq);
    }

    private Rfq buildFallbackRfq(Email email) {
        Rfq rfq = Rfq.builder()
                .email(email)
                .rfqType(RfqType.PRODUCT_SALE)
                .clientEmail(email.getSenderEmail())
                .clientName(email.getSenderName())
                .extractionConfidence(0.0)
                .status(RfqStatus.PROCESSING)
                .notes("Extracción automática fallida — requiere revisión manual")
                .items(new ArrayList<>())
                .build();
        return rfqRepository.save(rfq);
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> enumClass) {
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (Exception e) {
            return enumClass.getEnumConstants()[0];
        }
    }

    private String nullIfEmpty(String value) {
        return (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) ? null : value;
    }

    private String buildBodyWithContext(Email email) {
        StringBuilder sb = new StringBuilder();
        sb.append("De: ").append(email.getSenderName()).append(" <").append(email.getSenderEmail()).append(">\n");
        if (email.getCcAddresses() != null && !email.getCcAddresses().isBlank()) {
            sb.append("CC: ").append(email.getCcAddresses()).append("\n");
        }
        sb.append("Asunto: ").append(email.getSubject()).append("\n");
        sb.append("---\n");
        if (email.getBody() != null) {
            sb.append(email.getBody());
        }
        return sb.toString();
    }
}
