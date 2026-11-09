package com.ansertech.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Estado de la vinculación con Outlook (Microsoft Graph, Device Code Flow).
 *
 * <ul>
 *   <li>{@code status}: NOT_CONNECTED, PENDING (hay código por ingresar), CONNECTED o ERROR.</li>
 *   <li>{@code url} / {@code code}: solo con PENDING; el usuario abre la URL e ingresa el código.</li>
 *   <li>{@code source}: MEMORY si el código lo capturó esta instancia, LOG si se recuperó del archivo de log.</li>
 * </ul>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphConnectionResponse {
    private final String status;
    private final String url;
    private final String code;
    private final String rawMessage;
    private final OffsetDateTime expiresOn;
    private final OffsetDateTime timestamp;
    private final String error;
    private final String source;
}
