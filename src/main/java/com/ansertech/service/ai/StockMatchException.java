package com.ansertech.service.ai;

/**
 * El matching de inventario con Gemini no se pudo completar (cuota agotada, servicio caído, respuesta inválida...).
 * Se propaga para que el stock-check quede FAILED y el operador pueda reintentar, en vez de reportar
 * "ningún producto existe" como si fuera un resultado válido.
 */
public class StockMatchException extends RuntimeException {
    public StockMatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
