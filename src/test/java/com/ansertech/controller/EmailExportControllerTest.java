package com.ansertech.controller;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.entity.QuotationItem;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.repository.EmailRepository;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.audit.AuditService;
import com.ansertech.service.email.ManualPollingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exportación del reporte de correos (H7): nombre del archivo y carga en lote, sin N+1. */
class EmailExportControllerTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), LIMA);

    private final EmailRepository emailRepository = mock(EmailRepository.class);
    private final RfqRepository rfqRepository = mock(RfqRepository.class);
    private final QuotationRepository quotationRepository = mock(QuotationRepository.class);
    private EmailController controller;

    @BeforeEach
    void setUp() {
        controller = new EmailController(emailRepository, rfqRepository, quotationRepository,
                mock(ManualPollingService.class), new ObjectMapper(), mock(AuditService.class), CLOCK);
    }

    @Test
    void sinFechasElNombreEsInicioAHoy() {
        assertThat(controller.reportFilename(null, null)).isEqualTo("reporte-correos_inicio_a_2026-11-17.xlsx");
    }

    @Test
    void conRangoUsaLasFechasDelRango() {
        assertThat(controller.reportFilename(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 6)))
                .isEqualTo("reporte-correos_2026-11-01_a_2026-11-06.xlsx");
        assertThat(controller.reportFilename(LocalDate.of(2026, 11, 1), null))
                .isEqualTo("reporte-correos_2026-11-01_a_2026-11-17.xlsx");
        assertThat(controller.reportFilename(null, LocalDate.of(2026, 11, 6)))
                .isEqualTo("reporte-correos_inicio_a_2026-11-06.xlsx");
    }

    @Test
    void exportaTodosLosCorreosConDosConsultasEnLoteYElNombreCorrecto() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 11, 5, 10, 0);
        List<Email> emails = new ArrayList<>();
        for (long id = 1; id <= 300; id++) {
            emails.add(Email.builder().id(id).senderEmail("c" + id + "@x.pe").subject("Correo " + id)
                    .status(EmailStatus.SPAM).receivedAt(LocalDateTime.of(2019, 11, 27, 8, 0)).createdAt(t).build());
        }
        Email valid = emails.get(0);
        valid.setStatus(EmailStatus.VALID);
        Rfq rfq = Rfq.builder().id(50L).email(valid).createdAt(t.plusMinutes(1)).build();
        Quotation first = quotation(70L, rfq, t.plusMinutes(5), "1180.00");
        Quotation sameTimeHigherId = quotation(71L, rfq, t.plusMinutes(5), "0.00");

        when(emailRepository.findForReport(any(), any())).thenReturn(emails);
        when(rfqRepository.findWithItemsByEmailIdIn(any())).thenReturn(List.of(rfq));
        when(quotationRepository.findFirstWithItemsByRfqIdIn(any())).thenReturn(List.of(sameTimeHigherId, first));

        ResponseEntity<byte[]> res = controller.exportExcel(null, null);

        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("reporte-correos_inicio_a_2026-11-17.xlsx");
        verify(rfqRepository, times(1)).findWithItemsByEmailIdIn(any());
        verify(quotationRepository, times(1)).findFirstWithItemsByRfqIdIn(any());
        verify(rfqRepository, never()).findByEmailId(anyLong());
        verify(quotationRepository, never()).findFirstByRfqIdOrderByCreatedAtAsc(anyLong());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1 + 300);          // 2 filas de cabecera + 300 correos
            assertThat(sheet.getRow(1).getLastCellNum()).isEqualTo((short) 23);
            assertThat(sheet.getRow(2).getCell(19).getStringCellValue()).isEqualTo("Sí");     // cotización enviada
            assertThat(sheet.getRow(2).getCell(20).getStringCellValue()).isEqualTo("1180.00"); // la de menor id
            assertThat(sheet.getRow(3).getCell(18).getStringCellValue()).isEqualTo("-");      // SPAM sin RFQ
        }
    }

    private static Quotation quotation(long id, Rfq rfq, LocalDateTime createdAt, String total) {
        Quotation q = Quotation.builder().id(id).rfq(rfq).createdAt(createdAt)
                .status(QuotationStatus.SENT).total(new BigDecimal(total)).build();
        q.getItems().add(QuotationItem.builder().availabilityStatus(AvailabilityStatus.IN_STOCK).build());
        return q;
    }
}
