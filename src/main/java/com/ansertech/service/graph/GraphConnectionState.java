package com.ansertech.service.graph;

import com.ansertech.dto.response.GraphConnectionResponse;
import com.azure.identity.DeviceCodeInfo;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Estado en memoria de la vinculación con Microsoft Graph (Device Code Flow).
 *
 * <p>{@code DeviceCodeCredential} llama a {@link #onChallenge} cuando necesita que un humano abra
 * {@code https://microsoft.com/devicelogin} e ingrese un código. Guardarlo aquí permite que el portal
 * lo muestre sin depender de leer el log. El estado vive en la instancia: en Cloud Run cada réplica
 * tiene el suyo (ver README, sección Cloud Run).</p>
 */
@Component
public class GraphConnectionState {

    public enum Status { NOT_CONNECTED, PENDING, CONNECTED, ERROR }

    private final Clock clock;

    private Status status = Status.NOT_CONNECTED;
    private String url;
    private String code;
    private String rawMessage;
    private OffsetDateTime expiresOn;
    private OffsetDateTime updatedAt;
    private String error;

    public GraphConnectionState(Clock clock) {
        this.clock = clock;
    }

    public synchronized void onChallenge(DeviceCodeInfo info) {
        status = Status.PENDING;
        url = info.getVerificationUrl();
        code = info.getUserCode();
        rawMessage = info.getMessage();
        expiresOn = info.getExpiresOn();
        error = null;
        touch();
    }

    public synchronized void onConnected() {
        status = Status.CONNECTED;
        clearChallenge();
        error = null;
        touch();
    }

    public synchronized void onError(String message) {
        status = Status.ERROR;
        clearChallenge();
        error = message;
        touch();
    }

    /** true si hay un código vigente esperando que alguien lo ingrese. */
    public synchronized boolean hasLiveChallenge() {
        return status == Status.PENDING && !isExpired();
    }

    public synchronized GraphConnectionResponse snapshot() {
        // Un código vencido ya no sirve: se informa como error para que el portal ofrezca uno nuevo.
        if (status == Status.PENDING && isExpired()) {
            onError("El código venció sin que se completara el inicio de sesión. Genera uno nuevo.");
        }
        return GraphConnectionResponse.builder()
                .status(status.name())
                .url(url)
                .code(code)
                .rawMessage(rawMessage)
                .expiresOn(expiresOn)
                .timestamp(updatedAt)
                .error(error)
                .source(status == Status.PENDING ? "MEMORY" : null)
                .build();
    }

    private boolean isExpired() {
        return expiresOn != null && !expiresOn.isAfter(OffsetDateTime.now(clock));
    }

    private void clearChallenge() {
        url = null;
        code = null;
        rawMessage = null;
        expiresOn = null;
    }

    private void touch() {
        updatedAt = OffsetDateTime.now(clock);
    }
}
