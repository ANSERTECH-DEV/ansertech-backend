package com.ansertech.service.email;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.repository.EmailRepository;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.ai.GeminiService.FilePart;
import com.ansertech.service.audit.AuditService;
import com.ansertech.service.email.EmailPollingService.AttachmentInfo;
import com.ansertech.service.email.EmailPollingService.RawEmailData;
import com.ansertech.service.parameter.ParameterService;
import com.ansertech.service.quotation.QuotationService;
import com.ansertech.service.rfq.RfqExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pipeline de correos: adjuntos para la IA, recuperación de correos a medias y marcado como leído. */
class EmailPipelineSchedulerTest {

    private final EmailPollingService polling = mock(EmailPollingService.class);
    private final EmailClassificationService classification = mock(EmailClassificationService.class);
    private final RfqExtractionService extraction = mock(RfqExtractionService.class);
    private final EmailRepository emailRepository = mock(EmailRepository.class);
    private final RfqRepository rfqRepository = mock(RfqRepository.class);
    private final AttachmentLoader attachmentLoader = mock(AttachmentLoader.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private EmailPipelineScheduler scheduler;

    private static final AttachmentInfo LOGO = new AttachmentInfo("a1", "image001.png", "image/png", 4000, true);
    private static final AttachmentInfo PDF_INFO = new AttachmentInfo("a2", "SC_02.pdf", "application/pdf", 2559, false);
    private static final FilePart PDF = new FilePart("SC_02.pdf", "application/pdf", new byte[]{1});

    @BeforeEach
    void setUp() {
        scheduler = new EmailPipelineScheduler(polling, classification, extraction, mock(QuotationService.class),
                emailRepository, objectMapper, mock(AuditService.class), mock(ParameterService.class),
                rfqRepository, attachmentLoader);
        when(emailRepository.save(any(Email.class))).thenAnswer(inv -> {
            Email e = inv.getArgument(0);
            if (e.getId() == null) e.setId(498L);
            return e;
        });
        when(extraction.extractAndSave(any(), anyList())).thenReturn(Rfq.builder().id(900L).items(new ArrayList<>()).build());
    }

    private RawEmailData raw(List<AttachmentInfo> attachments) {
        return new RawEmailData("msg-1", "cliente@x.pe", "Cliente", "Pedido de cotización",
                "Ayuda con la solicitud de cotización", LocalDateTime.now(), true, attachments, List.of());
    }

    @Test
    void correoNuevoConPdfLoClasificaYExtraeConElAdjuntoYLoMarcaComoLeido() throws Exception {
        when(emailRepository.findByExternalMessageId("msg-1")).thenReturn(Optional.empty());
        when(attachmentLoader.load(eq("msg-1"), anyList())).thenReturn(List.of(PDF));
        when(classification.classify(any(), anyList())).thenReturn(EmailStatus.VALID);
        when(rfqRepository.existsByEmailId(498L)).thenReturn(false);

        scheduler.processEmail(raw(List.of(LOGO, PDF_INFO)));

        verify(attachmentLoader).load("msg-1", List.of(LOGO, PDF_INFO));
        verify(classification).classify(any(Email.class), eq(List.of(PDF)));
        verify(extraction).extractAndSave(any(Email.class), eq(List.of(PDF)));
        verify(polling).markAsRead("msg-1");
    }

    @Test
    void correoInciertoNoCreaRfq() throws Exception {
        when(emailRepository.findByExternalMessageId("msg-1")).thenReturn(Optional.empty());
        when(attachmentLoader.load(anyString(), anyList())).thenReturn(List.of());
        when(classification.classify(any(), anyList())).thenReturn(EmailStatus.UNCERTAIN);

        scheduler.processEmail(raw(List.of()));

        verify(extraction, never()).extractAndSave(any(), anyList());
        verify(polling).markAsRead("msg-1");
    }

    @Test
    void correoYaProcesadoNoSeReprocesaPeroSeMarcaComoLeido() throws Exception {
        Email done = Email.builder().id(10L).externalMessageId("msg-1").status(EmailStatus.SPAM).build();
        when(emailRepository.findByExternalMessageId("msg-1")).thenReturn(Optional.of(done));

        scheduler.processEmail(raw(List.of()));

        verify(classification, never()).classify(any(), anyList());
        verify(polling).markAsRead("msg-1");   // antes se omitía y la lectura se quedaba atascada (#22)
    }

    @Test
    void correoValidoSinRfqSeReprocesaAlVolverALeerlo() throws Exception {
        Email stuck = Email.builder().id(498L).externalMessageId("msg-1").status(EmailStatus.VALID).build();
        when(emailRepository.findByExternalMessageId("msg-1")).thenReturn(Optional.of(stuck));
        when(rfqRepository.existsByEmailId(498L)).thenReturn(false);
        when(attachmentLoader.load(anyString(), anyList())).thenReturn(List.of(PDF));
        when(classification.classify(any(), anyList())).thenReturn(EmailStatus.VALID);

        scheduler.processEmail(raw(List.of(PDF_INFO)));

        verify(extraction).extractAndSave(stuck, List.of(PDF));
    }

    @Test
    void recuperaDesdeLaBdLosCorreosAMediasUsandoSuAttachmentsJson() throws Exception {
        Email stuck = Email.builder().id(498L).externalMessageId("msg-498").status(EmailStatus.VALID)
                .attachmentsJson("[{\"id\":\"a2\",\"name\":\"SC_02.pdf\",\"mimeType\":\"application/pdf\",\"size\":2559}]")
                .build();
        when(emailRepository.findIncomplete(any(), any(), any())).thenReturn(List.of(stuck));
        when(rfqRepository.existsByEmailId(498L)).thenReturn(false);
        when(attachmentLoader.load(anyString(), anyList())).thenReturn(List.of(PDF));
        when(classification.classify(any(), anyList())).thenReturn(EmailStatus.VALID);

        scheduler.recoverIncompleteEmails();

        // attachments_json antiguo, sin "inline": se lee como false
        verify(attachmentLoader).load("msg-498", List.of(new AttachmentInfo("a2", "SC_02.pdf", "application/pdf", 2559, false)));
        verify(extraction).extractAndSave(stuck, List.of(PDF));
        verify(polling).markAsRead("msg-498");
    }

    @Test
    void siFallaElProcesoElCorreoQuedaEnError() {
        when(emailRepository.findByExternalMessageId("msg-1")).thenReturn(Optional.empty());
        when(attachmentLoader.load(anyString(), anyList())).thenReturn(List.of());
        when(classification.classify(any(), anyList())).thenThrow(new IllegalStateException("BD caída"));

        scheduler.processEmail(raw(List.of()));

        var captor = org.mockito.ArgumentCaptor.forClass(Email.class);
        verify(emailRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(EmailStatus.ERROR);
    }
}
