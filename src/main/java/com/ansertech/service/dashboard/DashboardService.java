package com.ansertech.service.dashboard;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.dto.response.DashboardKpisResponse;
import com.ansertech.exception.BusinessException;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final QuotationRepository quotationRepository;
    private final RfqRepository rfqRepository;

    /**
     * KPIs entre {@code from} y {@code to} (ambos días inclusive) y del periodo inmediatamente
     * anterior con la misma cantidad de días.
     */
    @Transactional(readOnly = true)
    public DashboardKpisResponse kpis(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha 'desde' no puede ser posterior a la fecha 'hasta'");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);

        return DashboardKpisResponse.builder()
                .from(from).to(to)
                .current(period(from, to))
                .previousFrom(prevFrom).previousTo(prevTo)
                .previous(period(prevFrom, prevTo))
                .build();
    }

    private DashboardKpisResponse.Period period(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay(); // exclusivo

        // Solo la primera cotización de cada RFQ: excluye las cotizaciones vacías duplicadas
        // que se crean cuando falla la generación del PDF (problema #16).
        List<Quotation> quotations = quotationRepository.findFirstPerRfqCreatedBetween(start, end);

        BigDecimal total = quotations.stream()
                .map(Quotation::getTotal).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long approved = quotations.size();
        long sent = quotations.stream().filter(q -> q.getStatus() == QuotationStatus.SENT).count();
        long rejected = rfqRepository.countByStatusAndUpdatedAtBetween(RfqStatus.REJECTED, start, end);

        Double avgHours = quotations.stream()
                .map(this::hoursToQuote).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average()
                .stream().boxed().findFirst().orElse(null);

        long decided = approved + rejected;
        Double rate = decided > 0 ? approved * 100.0 / decided : null;

        return DashboardKpisResponse.Period.builder()
                .totalQuoted(total).approved(approved).rejected(rejected).sent(sent)
                .avgHoursToQuote(avgHours).approvalRate(rate)
                .build();
    }

    /** Horas desde que llegó el correo (o se registró, o se creó el RFQ) hasta que se generó la cotización. */
    private Double hoursToQuote(Quotation q) {
        if (q.getCreatedAt() == null || q.getRfq() == null) return null;
        Email email = q.getRfq().getEmail();
        LocalDateTime start = email != null && email.getReceivedAt() != null ? email.getReceivedAt()
                : email != null && email.getCreatedAt() != null ? email.getCreatedAt()
                : q.getRfq().getCreatedAt();
        if (start == null || start.isAfter(q.getCreatedAt())) return null;
        return Duration.between(start, q.getCreatedAt()).toMinutes() / 60.0;
    }
}
