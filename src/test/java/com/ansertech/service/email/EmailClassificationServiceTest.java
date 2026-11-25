package com.ansertech.service.email;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.repository.EmailRepository;
import com.ansertech.service.ai.GeminiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regla de oro: GeminiService (Vertex AI) va mockeado, ninguna llamada real.
 * confidenceThreshold se inyecta por reflexión porque en un test sin contexto
 * Spring el @Value de EmailClassificationService no se resuelve solo.
 */
@ExtendWith(MockitoExtension.class)
class EmailClassificationServiceTest {

    @Mock private GeminiService geminiService;
    @Mock private EmailRepository emailRepository;

    private EmailClassificationService classificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        classificationService = new EmailClassificationService(geminiService, emailRepository);
        ReflectionTestUtils.setField(classificationService, "confidenceThreshold", 0.85);
    }

    @Test
    void highConfidenceSpam_classifiesAsSpam() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("SPAM", 0.92, "promoción no solicitada"));

        EmailStatus status = classificationService.classify(email);

        assertThat(status).isEqualTo(EmailStatus.SPAM);
        assertThat(email.getStatus()).isEqualTo(EmailStatus.SPAM);
    }

    @Test
    void highConfidenceNonSpam_classifiesAsValid() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("VALID", 0.95, "RFQ de cotización de sensores"));

        EmailStatus status = classificationService.classify(email);

        assertThat(status).isEqualTo(EmailStatus.VALID);
    }

    @Test
    void belowThreshold_classifiesAsUncertainRegardlessOfLabel() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("SPAM", 0.50, "no está claro"));

        EmailStatus status = classificationService.classify(email);

        assertThat(status).isEqualTo(EmailStatus.UNCERTAIN);
    }

    @Test
    void exactlyAtThreshold_0_85_isTreatedAsAutomatic() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("VALID", 0.85, "límite exacto"));

        EmailStatus status = classificationService.classify(email);

        // el código usa "confidence < threshold" -> 0.85 NO es < 0.85, por lo tanto es automático
        assertThat(status).isEqualTo(EmailStatus.VALID);
    }

    @Test
    void geminiReturnsErrorNode_classifiesAsUncertain() {
        Email email = email();
        ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("error", "modelo no disponible");
        when(geminiService.classifyEmail(any(), any(), any(), anyList())).thenReturn(errorNode);

        EmailStatus status = classificationService.classify(email);

        assertThat(status).isEqualTo(EmailStatus.UNCERTAIN);
        assertThat(email.getClassificationReason()).isEqualTo("Error en modelo IA");
    }

    @Test
    void geminiThrowsException_classifiesAsUncertain() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenThrow(new RuntimeException("Vertex AI timeout"));

        EmailStatus status = classificationService.classify(email);

        assertThat(status).isEqualTo(EmailStatus.UNCERTAIN);
    }

    @Test
    void uncertainWithHighConfidence_staysUncertain_notValid() {
        // Caso real (email 498): Gemini dijo UNCERTAIN con 0.95 y el código lo guardaba como VALID.
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("UNCERTAIN", 0.95, "el cuerpo es vago y no especifica productos"));

        EmailStatus status = classificationService.classify(email);

        assertThat(status).isEqualTo(EmailStatus.UNCERTAIN);
        assertThat(email.getStatus()).isEqualTo(EmailStatus.UNCERTAIN);
    }

    @Test
    void validRfqLabel_fromPrompt_classifiesAsValid() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("VALID_RFQ", 0.97, "lista de productos en el PDF adjunto"));

        assertThat(classificationService.classify(email)).isEqualTo(EmailStatus.VALID);
    }

    @Test
    void unknownLabel_classifiesAsUncertain() {
        Email email = email();
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("OTRO", 0.99, "?"));

        assertThat(classificationService.classify(email)).isEqualTo(EmailStatus.UNCERTAIN);
    }

    @Test
    void attachmentsArePassedToGemini() {
        Email email = email();
        var pdf = new GeminiService.FilePart("SC_02.pdf", "application/pdf", new byte[]{1, 2, 3});
        when(geminiService.classifyEmail(any(), any(), any(), anyList()))
                .thenReturn(geminiResult("VALID_RFQ", 0.96, "RFQ en el adjunto"));

        classificationService.classify(email, java.util.List.of(pdf));

        verify(geminiService).classifyEmail(eq("comprador@mineraejemplo.pe"), any(), any(), eq(java.util.List.of(pdf)));
    }

    private Email email() {
        return Email.builder()
                .id(1L)
                .senderEmail("comprador@mineraejemplo.pe")
                .subject("Solicitud de cotización")
                .body("Necesitamos cotizar 10 sensores HANNA")
                .build();
    }

    private JsonNode geminiResult(String classification, double confidence, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("classification", classification);
        node.put("confidence", confidence);
        node.put("reason", reason);
        return node;
    }
}
