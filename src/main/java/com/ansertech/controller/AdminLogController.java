package com.ansertech.controller;

import com.ansertech.domain.enums.AuditAction;
import com.ansertech.service.audit.AuditService;
import com.ansertech.service.logs.LogReaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/** Últimas líneas del log del backend, para diagnosticar sin acceso a la consola. Solo ADMIN. */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administración", description = "Diagnóstico del backend")
public class AdminLogController {

    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    private final LogReaderService logReader;
    private final AuditService auditService;

    @GetMapping(value = "/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Últimas líneas del archivo de log (1 a 500, por defecto 50)")
    public ResponseEntity<String> logs(@RequestParam(defaultValue = "50") int lines) {
        int wanted = Math.max(1, Math.min(lines, LogReaderService.MAX_LINES));
        auditService.record(AuditAction.SERVER_LOGS_VIEWED, "LOG", null,
                AuditService.details("lineas", wanted));

        if (!logReader.exists()) {
            return ResponseEntity.ok().contentType(TEXT_UTF8).body(
                    "El archivo de log " + logReader.getLogFile() + " aún no existe en esta instancia.");
        }
        try {
            List<String> tail = logReader.tail(wanted);
            return ResponseEntity.ok().contentType(TEXT_UTF8).body(String.join("\n", tail));
        } catch (IOException e) {
            log.warn("No se pudo leer {}: {}", logReader.getLogFile(), e.getMessage());
            return ResponseEntity.ok().contentType(TEXT_UTF8).body(
                    "No se pudo leer el archivo de log " + logReader.getLogFile() + ": " + e.getMessage());
        }
    }
}
