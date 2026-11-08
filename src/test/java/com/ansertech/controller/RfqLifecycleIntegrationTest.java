package com.ansertech.controller;

import com.ansertech.AbstractIntegrationTest;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.repository.RfqRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ciclo de vida del RFQ vía HTTP real (MockMvc), con Gemini/PDF/Email mockeados
 * (heredados de AbstractIntegrationTest) para no llamar servicios externos.
 */
class RfqLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RfqRepository rfqRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_transitionsPendingReviewToQuoted_endToEnd() throws Exception {
        JsonNode aiResult = objectMapper.createObjectNode()
                .put("conversion_probability", 0.6)
                .put("ai_summary", "Cliente recurrente, alta probabilidad de cierre");
        when(geminiService.analyzeRfq(any(), any())).thenReturn(aiResult);

        Rfq rfq = rfqRepository.save(Rfq.builder()
                .clientName("Cliente Lifecycle").clientEmail("cliente@lifecycle.pe")
                .status(RfqStatus.PENDING_REVIEW).build());

        mockMvc.perform(post("/api/rfqs/{id}/confirm", rfq.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Rfq reloaded = rfqRepository.findById(rfq.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RfqStatus.QUOTED);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_onAlreadyQuotedRfq_isRejectedWithBadRequest() throws Exception {
        Rfq rfq = rfqRepository.save(Rfq.builder()
                .clientName("Cliente Ya Cotizado").status(RfqStatus.QUOTED).build());

        mockMvc.perform(post("/api/rfqs/{id}/confirm", rfq.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // no debe haber tocado la IA en absoluto: el guard corta antes
        assertThat(rfqRepository.findById(rfq.getId()).orElseThrow().getStatus())
                .isEqualTo(RfqStatus.QUOTED);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void reject_onAlreadyRejectedRfq_isRejectedWithBadRequest() throws Exception {
        Rfq rfq = rfqRepository.save(Rfq.builder()
                .clientName("Cliente Ya Rechazado").status(RfqStatus.REJECTED).build());

        mockMvc.perform(post("/api/rfqs/{id}/reject", rfq.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void reject_onPendingReview_transitionsToRejected() throws Exception {
        Rfq rfq = rfqRepository.save(Rfq.builder()
                .clientName("Cliente Rechazable").status(RfqStatus.PENDING_REVIEW).build());

        mockMvc.perform(post("/api/rfqs/{id}/reject", rfq.getId()))
                .andExpect(status().isOk());

        assertThat(rfqRepository.findById(rfq.getId()).orElseThrow().getStatus())
                .isEqualTo(RfqStatus.REJECTED);
    }
}
