package com.ansertech.controller;

import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.GraphConnectionResponse;
import com.ansertech.service.graph.GraphAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Vinculación de la cuenta de Outlook (Microsoft Graph, Device Code Flow). Solo ADMIN. */
@RestController
@RequestMapping("/api/auth/graph")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Microsoft Graph", description = "Vinculación de la cuenta de Outlook")
public class GraphAuthController {

    private final GraphAuthService graphAuthService;

    @GetMapping("/device-code")
    @Operation(summary = "URL y código de Microsoft vigentes, leídos de las últimas líneas del log")
    public ResponseEntity<ApiResponse<GraphConnectionResponse>> deviceCode() {
        return ResponseEntity.ok(ApiResponse.ok(graphAuthService.status()));
    }

    @PostMapping("/connect")
    @Operation(summary = "Inicia la vinculación en segundo plano (o comprueba que el token en caché sirve)")
    public ResponseEntity<ApiResponse<GraphConnectionResponse>> connect() {
        return ResponseEntity.ok(ApiResponse.ok(graphAuthService.connect()));
    }
}
