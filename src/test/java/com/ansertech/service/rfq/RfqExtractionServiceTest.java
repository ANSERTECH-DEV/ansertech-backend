package com.ansertech.service.rfq;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.ai.GeminiService;
import com.ansertech.service.ai.GeminiService.FilePart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RfqExtractionServiceTest {

    private final GeminiService gemini = mock(GeminiService.class);
    private final RfqRepository rfqRepository = mock(RfqRepository.class);
    private final RfqStockCheckQueueService queue = mock(RfqStockCheckQueueService.class);
    private final ObjectMapper om = new ObjectMapper();
    private final RfqExtractionService service = new RfqExtractionService(gemini, rfqRepository, om, queue);

    private final Email email = Email.builder().id(498L).senderEmail("cliente@x.pe").senderName("Cliente")
            .subject("Pedido de cotización").body("Ayuda con la solicitud").build();

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();   // scheduleStockCheck registra un afterCommit
        when(rfqRepository.save(any(Rfq.class))).thenAnswer(inv -> { Rfq r = inv.getArgument(0); r.setId(900L); return r; });
    }

    @AfterEach
    void tearDown() { TransactionSynchronizationManager.clearSynchronization(); }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void conAdjuntosUsaLaExtraccionMultimodalConTodosLosArchivos() throws Exception {
        var files = List.of(new FilePart("SC_02.pdf", "application/pdf", new byte[]{1}));
        when(gemini.extractRfqFromFiles(eq(files), anyString())).thenReturn(om.readTree(
                "{\"client_company\":\"Minera X\",\"items\":[{\"product_description\":\"Agitador\",\"quantity\":2,\"unit\":\"unidad\"}],\"overall_confidence\":0.9}"));

        Rfq rfq = service.extractAndSave(email, files);

        assertThat(rfq.getItems()).hasSize(1);
        assertThat(rfq.getClientCompany()).isEqualTo("Minera X");
        verify(gemini, never()).extractRfq(anyString());
        commit();
        verify(queue).processRfq(900L);
    }

    @Test
    void sinAdjuntosUsaSoloElTexto() throws Exception {
        when(gemini.extractRfq(anyString())).thenReturn(om.readTree("{\"items\":[],\"overall_confidence\":0.5}"));

        service.extractAndSave(email, List.of());

        verify(gemini).extractRfq(anyString());
    }

    @Test
    void siGeminiDevuelveErrorElRfqDeRespaldoTambienSeEncolaYNoQuedaEnProcesandoParaSiempre() throws Exception {
        when(gemini.extractRfq(anyString())).thenReturn(om.readTree("{\"error\":\"límite de uso\"}"));

        Rfq rfq = service.extractAndSave(email, List.of());

        assertThat(rfq.getNotes()).contains("Extracción automática fallida");
        commit();
        verify(queue).processRfq(900L);
    }
}
