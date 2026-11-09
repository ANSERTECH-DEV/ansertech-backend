package com.ansertech.service.graph;

import com.ansertech.config.GraphConfiguration;
import com.ansertech.domain.enums.AuditAction;
import com.ansertech.dto.response.GraphConnectionResponse;
import com.ansertech.service.audit.AuditService;
import com.ansertech.service.logs.LogReaderService;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DeviceCodeCredential;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vincula el backend con la cuenta de Outlook (Device Code Flow) sin bloquear peticiones HTTP.
 *
 * <p>{@link #connect()} pide un token en un hilo aparte: si la caché ya tiene uno válido termina
 * al instante (CONNECTED); si no, Microsoft devuelve un código que {@link GraphConnectionState}
 * captura (PENDING) y el hilo espera hasta que alguien lo ingrese o venza (~15 min).</p>
 */
@Slf4j
@Service
public class GraphAuthService {

    private final DeviceCodeCredential credential;
    private final GraphConnectionState state;
    private final LogReaderService logReader;
    private final AuditService auditService;
    private final boolean connectOnStartup;

    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-device-code");
        t.setDaemon(true);
        return t;
    });

    public GraphAuthService(DeviceCodeCredential credential, GraphConnectionState state,
                            LogReaderService logReader, AuditService auditService,
                            @Value("${microsoft.graph.connect-on-startup:false}") boolean connectOnStartup) {
        this.credential = credential;
        this.state = state;
        this.logReader = logReader;
        this.auditService = auditService;
        this.connectOnStartup = connectOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectOnStartup() {
        if (connectOnStartup) {
            log.info("Vinculación con Microsoft Graph al arrancar (microsoft.graph.connect-on-startup=true)");
            startAuthentication();
        }
    }

    /** Estado actual; si la memoria no tiene un código, intenta recuperarlo del log. */
    public GraphConnectionResponse status() {
        GraphConnectionResponse current = state.snapshot();
        if ("NOT_CONNECTED".equals(current.getStatus())) {
            return logReader.findLatestDeviceCode().orElse(current);
        }
        return current;
    }

    /** Inicia la vinculación (si no hay una en curso) y devuelve el estado sin esperar a que termine. */
    public GraphConnectionResponse connect() {
        boolean started = startAuthentication();
        auditService.record(AuditAction.GRAPH_CONNECT_REQUESTED, "GRAPH", null,
                AuditService.details("nuevaSolicitud", started));
        return state.snapshot();
    }

    boolean startAuthentication() {
        if (!inProgress.compareAndSet(false, true)) {
            return false;  // ya hay un hilo esperando el código: se reutiliza
        }
        CompletableFuture.runAsync(this::authenticate, executor)
                .whenComplete((ok, ex) -> inProgress.set(false));
        // Da un momento para que llegue el código (o el token de la caché) y la respuesta ya lo traiga.
        waitBriefly();
        return true;
    }

    private void authenticate() {
        try {
            credential.getTokenSync(new TokenRequestContext().addScopes(GraphConfiguration.GRAPH_SCOPES));
            state.onConnected();
            log.info("Microsoft Graph vinculado: token obtenido");
            auditService.recordSystem(AuditAction.GRAPH_CONNECTED, "GRAPH", null, null);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            state.onError(message);
            log.error("No se pudo vincular Microsoft Graph: {}", message);
            auditService.recordSystem(AuditAction.GRAPH_CONNECT_FAILED, "GRAPH", null,
                    AuditService.details("error", message));
        }
    }

    private void waitBriefly() {
        for (int i = 0; i < 20 && inProgress.get() && !state.hasLiveChallenge(); i++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
