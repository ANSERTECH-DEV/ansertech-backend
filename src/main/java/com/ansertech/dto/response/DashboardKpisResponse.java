package com.ansertech.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** KPIs del dashboard para un rango de fechas, con el periodo anterior de igual duración para comparar. */
@Data @Builder
public class DashboardKpisResponse {
    private LocalDate from;
    private LocalDate to;
    private Period current;
    private Period previous;
    private LocalDate previousFrom;
    private LocalDate previousTo;

    @Data @Builder
    public static class Period {
        /** Suma de totales (con IGV) de la primera cotización de cada RFQ generada en el periodo. */
        private BigDecimal totalQuoted;
        /** RFQs aprobados (con cotización generada) en el periodo. */
        private long approved;
        /** RFQs rechazados en el periodo. */
        private long rejected;
        /** Cotizaciones del periodo que llegaron a enviarse al cliente (status SENT). */
        private long sent;
        /** Promedio de horas entre la recepción del correo y la generación de la cotización; null si no hay datos. */
        private Double avgHoursToQuote;
        /** aprobadas / (aprobadas + rechazadas) * 100; null si no hubo decisiones. */
        private Double approvalRate;
    }
}
