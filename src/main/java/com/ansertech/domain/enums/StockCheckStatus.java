package com.ansertech.domain.enums;

public enum StockCheckStatus {
    PROCESSING,  // En cola o ejecutándose
    DONE,        // Completado con éxito
    FAILED       // Error durante el procesamiento
}
