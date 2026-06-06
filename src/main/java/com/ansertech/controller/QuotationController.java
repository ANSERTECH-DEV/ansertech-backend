package com.ansertech.controller;

import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.QuotationResponse;
import com.ansertech.service.quotation.QuotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
@Tag(name = "Cotizaciones", description = "Gestión de cotizaciones generadas")
public class QuotationController {

    private final QuotationService quotationService;

    @GetMapping
    @Operation(summary = "Listar cotizaciones con filtro por estado")
    public ResponseEntity<ApiResponse<Page<QuotationResponse>>> list(
            @RequestParam(required = false) QuotationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(quotationService.findAll(status, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener cotización por ID")
    public ResponseEntity<ApiResponse<QuotationResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(quotationService.getById(id)));
    }

    @PostMapping("/{id}/send")
    @Operation(summary = "Marcar cotización como enviada")
    public ResponseEntity<ApiResponse<QuotationResponse>> send(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(quotationService.markAsSent(id), "Cotización marcada como enviada"));
    }

    @GetMapping("/{id}/pdf")
    @Operation(summary = "Descargar PDF de la cotización")
    public ResponseEntity<Resource> downloadPdf(@PathVariable Long id) {
        String path = quotationService.getPdfPath(id);
        File file = new File(path);
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }
}
