package com.ansertech.domain.enums;

/**
 * Acciones que quedan registradas en la tabla {@code audit_logs}.
 * Se guardan por nombre (columna {@code action}, VARCHAR 100).
 */
public enum AuditAction {

    // ── Autenticación ────────────────────────────────────────────────────
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    USER_REGISTERED,

    // ── Correos (pipeline y operador) ────────────────────────────────────
    EMAIL_POLLING_TRIGGERED,
    EMAIL_CLASSIFIED,
    EMAIL_PROCESSING_FAILED,
    EMAIL_RECLASSIFIED,
    EMAIL_REPORT_EXPORTED,

    // ── Solicitudes de cotización (RFQ) ──────────────────────────────────
    RFQ_CREATED,
    RFQ_STOCK_CHECK_COMPLETED,
    RFQ_STOCK_CHECK_FAILED,
    RFQ_UPDATED,
    RFQ_APPROVED,
    RFQ_REJECTED,

    // ── Cotizaciones ─────────────────────────────────────────────────────
    QUOTATION_GENERATED,
    QUOTATION_GENERATION_FAILED,
    QUOTATION_SENT,
    QUOTATION_SEND_FAILED,
    QUOTATION_RESENT,
    QUOTATION_PDF_DOWNLOADED,

    // ── Inventario ───────────────────────────────────────────────────────
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_STOCK_UPDATED,
    INVENTORY_IMPORTED,
    INVENTORY_EXPORTED,

    // ── Microsoft Graph y administración ─────────────────────────────────
    GRAPH_CONNECT_REQUESTED,
    GRAPH_CONNECTED,
    GRAPH_CONNECT_FAILED,
    SERVER_LOGS_VIEWED
}
