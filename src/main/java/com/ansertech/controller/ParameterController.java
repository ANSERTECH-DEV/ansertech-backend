package com.ansertech.controller;

import com.ansertech.domain.entity.Parameter;
import com.ansertech.domain.enums.AuditAction;
import com.ansertech.dto.request.ParameterValueRequest;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.ParameterResponse;
import com.ansertech.service.audit.AuditService;
import com.ansertech.service.parameter.ParameterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/parameters")
@RequiredArgsConstructor
@Tag(name = "Parámetros", description = "Parámetros configurables de la aplicación")
public class ParameterController {

    private final ParameterService parameterService;
    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "Listar todos los parámetros")
    public ResponseEntity<ApiResponse<List<ParameterResponse>>> list() {
        List<ParameterResponse> parameters = parameterService.findAll().stream()
                .map(ParameterResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(parameters));
    }

    @GetMapping("/email-count")
    @Operation(summary = "Cantidad de correos que leerá el polling manual",
            description = "Valor efectivo de 'Cantidad de correos'; si falta o no es válido, el de microsoft.polling.max-messages-per-poll.")
    public ResponseEntity<ApiResponse<Integer>> emailCount() {
        return ResponseEntity.ok(ApiResponse.ok(parameterService.getEmailCount()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Editar el valor de un parámetro")
    public ResponseEntity<ApiResponse<ParameterResponse>> updateValue(
            @PathVariable Long id, @Valid @RequestBody ParameterValueRequest request) {
        String previous = parameterService.findAll().stream()
                .filter(p -> p.getId().equals(id))
                .map(Parameter::getValue)
                .findFirst()
                .orElse(null);
        Parameter updated = parameterService.updateValue(id, request.getValue());
        auditService.record(AuditAction.PARAMETER_UPDATED, "PARAMETER", id,
                AuditService.details("nombre", updated.getName(), "valorAnterior", previous,
                        "valorNuevo", updated.getValue()));
        return ResponseEntity.ok(ApiResponse.ok(ParameterResponse.from(updated), "Parámetro actualizado"));
    }
}
