package com.ansertech.domain.enums;

public enum RfqStatus {
    PROCESSING,      // RFQ creado, stock-check automático en cola
    PENDING_REVIEW,  // Stock-check completado, esperando aprobación del operador
    QUOTING,         // Operador aprobó, generando cotización y PDF
    QUOTED,          // PDF enviado al cliente, proceso finalizado
    REJECTED         // RFQ rechazado por el operador
}
