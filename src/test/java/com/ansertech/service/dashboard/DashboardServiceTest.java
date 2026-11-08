package com.ansertech.service.dashboard;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.dto.response.DashboardKpisResponse;
import com.ansertech.exception.BusinessException;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock QuotationRepository quotationRepository;
    @Mock RfqRepository rfqRepository;
    @InjectMocks DashboardService service;

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    private Quotation quotation(String total, QuotationStatus status, LocalDateTime received, LocalDateTime created) {
        Email email = Email.builder().receivedAt(received).build();
        Rfq rfq = Rfq.builder().email(email).build();
        return Quotation.builder().rfq(rfq).total(new BigDecimal(total)).status(status).createdAt(created).build();
    }

    @Test
    void calculaKpisDelPeriodoYDelPeriodoAnterior() {
        LocalDateTime start = FROM.atStartOfDay();
        LocalDateTime end = TO.plusDays(1).atStartOfDay();
        LocalDateTime t = LocalDateTime.of(2026, 10, 10, 10, 0);
        when(quotationRepository.findFirstPerRfqCreatedBetween(start, end)).thenReturn(List.of(
                quotation("1000.50", QuotationStatus.SENT, t, t.plusHours(2)),
                quotation("500.00", QuotationStatus.DRAFT, t, t.plusHours(4))));
        when(rfqRepository.countByStatusAndUpdatedAtBetween(RfqStatus.REJECTED, start, end)).thenReturn(2L);
        // Periodo anterior: 31 días previos (31-ago a 30-sep), sin datos
        when(quotationRepository.findFirstPerRfqCreatedBetween(
                eq(LocalDate.of(2026, 8, 31).atStartOfDay()), eq(LocalDate.of(2026, 10, 1).atStartOfDay())))
                .thenReturn(List.of());
        when(rfqRepository.countByStatusAndUpdatedAtBetween(eq(RfqStatus.REJECTED),
                eq(LocalDate.of(2026, 8, 31).atStartOfDay()), any())).thenReturn(0L);

        DashboardKpisResponse r = service.kpis(FROM, TO);

        assertThat(r.getCurrent().getTotalQuoted()).isEqualByComparingTo("1500.50");
        assertThat(r.getCurrent().getApproved()).isEqualTo(2);
        assertThat(r.getCurrent().getSent()).isEqualTo(1);
        assertThat(r.getCurrent().getRejected()).isEqualTo(2);
        assertThat(r.getCurrent().getAvgHoursToQuote()).isEqualTo(3.0);
        assertThat(r.getCurrent().getApprovalRate()).isEqualTo(50.0);

        assertThat(r.getPreviousFrom()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(r.getPreviousTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(r.getPrevious().getApproved()).isZero();
        assertThat(r.getPrevious().getTotalQuoted()).isEqualByComparingTo("0");
        assertThat(r.getPrevious().getAvgHoursToQuote()).isNull();
        assertThat(r.getPrevious().getApprovalRate()).isNull();
    }

    @Test
    void usaCreatedAtDelCorreoSiNoHayFechaDeRecepcion() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 5, 8, 0);
        Quotation q = quotation("100", QuotationStatus.SENT, null, t.plusHours(6));
        q.getRfq().getEmail().setCreatedAt(t);
        when(quotationRepository.findFirstPerRfqCreatedBetween(any(), any())).thenReturn(List.of(q), List.of());

        DashboardKpisResponse r = service.kpis(FROM, TO);

        assertThat(r.getCurrent().getAvgHoursToQuote()).isEqualTo(6.0);
        assertThat(r.getCurrent().getApprovalRate()).isEqualTo(100.0);
    }

    @Test
    void rechazaRangoInvertido() {
        assertThatThrownBy(() -> service.kpis(TO, FROM))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("posterior");
    }
}
