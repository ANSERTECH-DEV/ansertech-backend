package com.ansertech.domain.enums;

public enum RfqStatus {
    PROCESSING,      // RFQ creado, stock-check automático en cola
    PENDING_REVIEW,  // Stock-check completado, esperando aprobación del operador
    QUOTING,         // Operador aprobó, generando cotización y PDF
    QUOTED,          // PDF enviado al cliente, proceso finalizado
    REJECTED;        // RFQ rechazado por el operador

    /**
     * Gobierna solo las transiciones disparadas por el operador vía HTTP
     * (RfqController.confirm()/reject()). La transición automática
     * PROCESSING → PENDING_REVIEW la hace el sistema (RfqStockCheckQueueService)
     * y no pasa por este guard.
     */
    public boolean canTransitionTo(RfqStatus target) {
        return switch (this) {
            case PENDING_REVIEW -> target == QUOTING || target == REJECTED;
            case QUOTING -> target == QUOTED || target == REJECTED;
            case PROCESSING, QUOTED, REJECTED -> false;
        };
    }
}
