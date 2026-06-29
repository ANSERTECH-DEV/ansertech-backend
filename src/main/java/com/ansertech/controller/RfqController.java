package com.ansertech.controller;

import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqStockCheckResult;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.StockCheckStatus;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.RfqResponse;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.ansertech.dto.response.StockCheckResultResponse;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.RfqRepository;
import com.ansertech.repository.RfqStockCheckResultRepository;
import com.ansertech.service.quotation.QuotationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rfqs")
@RequiredArgsConstructor
@Tag(name = "RFQs", description = "Solicitudes de cotización extraídas")
public class RfqController {

    private final RfqRepository rfqRepository;
    private final RfqStockCheckResultRepository stockCheckResultRepository;
    private final QuotationService quotationService;
    private final ObjectMapper objectMapper;

    @GetMapping
    @Operation(summary = "Listar RFQs con filtro por estado")
    public ResponseEntity<ApiResponse<Page<RfqResponse>>> list(
            @RequestParam(required = false) RfqStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Rfq> page;
        if (status != null) {
            page = rfqRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            page = rfqRepository.findByStatusInOrderByCreatedAtDesc(
                    List.of(RfqStatus.PROCESSING, RfqStatus.PENDING_REVIEW, RfqStatus.QUOTING), pageable);
        }
        return ResponseEntity.ok(ApiResponse.ok(page.map(this::toResponse)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener RFQ por ID")
    public ResponseEntity<ApiResponse<RfqResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(toResponse(findOrThrow(id))));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Actualizar notas/operador del RFQ")
    public ResponseEntity<ApiResponse<RfqResponse>> update(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        Rfq rfq = findOrThrow(id);
        if (body.containsKey("notes")) rfq.setNotes(body.get("notes"));
        rfqRepository.save(rfq);
        return ResponseEntity.ok(ApiResponse.ok(toResponse(rfq)));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirmar RFQ, generar cotización, PDF y enviar email al cliente")
    public ResponseEntity<ApiResponse<Long>> confirm(@PathVariable Long id) {
        Rfq rfq = findOrThrow(id);
        rfq.setStatus(RfqStatus.QUOTING);
        rfqRepository.save(rfq);

        var quotation = quotationService.generateFromRfq(rfq);

        rfq.setStatus(RfqStatus.QUOTED);
        rfqRepository.save(rfq);

        return ResponseEntity.ok(ApiResponse.ok(quotation.getId(),
                "Cotización generada y enviada: " + quotation.getQuotationNumber()));
    }

    @GetMapping("/{id}/stock-check")
    @Operation(summary = "Consulta el resultado del stock-check automático del RFQ (read-only)")
    public ResponseEntity<ApiResponse<StockCheckResultResponse>> stockCheck(@PathVariable Long id) {
        findOrThrow(id);

        RfqStockCheckResult dbResult = stockCheckResultRepository.findByRfqId(id)
                .orElse(null);

        if (dbResult == null || dbResult.getStatus() == StockCheckStatus.PROCESSING) {
            return ResponseEntity.ok(ApiResponse.ok(
                    StockCheckResultResponse.builder()
                            .rfqId(id)
                            .status(StockCheckStatus.PROCESSING)
                            .build()));
        }

        if (dbResult.getStatus() == StockCheckStatus.FAILED) {
            return ResponseEntity.ok(ApiResponse.ok(
                    StockCheckResultResponse.builder()
                            .rfqId(id)
                            .status(StockCheckStatus.FAILED)
                            .processedAt(dbResult.getProcessedAt())
                            .errorMessage(dbResult.getErrorMessage())
                            .build()));
        }

        // DONE: deserializar ítems desde JSON persistido
        try {
            List<StockCheckItemResponse> items = objectMapper.readValue(
                    dbResult.getItemsJson(), new TypeReference<List<StockCheckItemResponse>>() {});
            return ResponseEntity.ok(ApiResponse.ok(
                    StockCheckResultResponse.builder()
                            .rfqId(id)
                            .status(StockCheckStatus.DONE)
                            .items(items)
                            .processedAt(dbResult.getProcessedAt())
                            .build()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(
                    StockCheckResultResponse.builder()
                            .rfqId(id)
                            .status(StockCheckStatus.FAILED)
                            .errorMessage("Error deserializando resultado: " + e.getMessage())
                            .build()));
        }
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Rechazar RFQ sin generar cotización")
    public ResponseEntity<ApiResponse<String>> reject(@PathVariable Long id) {
        Rfq rfq = findOrThrow(id);
        rfq.setStatus(RfqStatus.REJECTED);
        rfqRepository.save(rfq);
        return ResponseEntity.ok(ApiResponse.ok("REJECTED", "RFQ #" + id + " rechazado"));
    }

    private Rfq findOrThrow(Long id) {
        return rfqRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RFQ", id));
    }

    private RfqResponse toResponse(Rfq r) {
        return RfqResponse.builder()
                .id(r.getId()).emailId(r.getEmail() != null ? r.getEmail().getId() : null)
                .rfqType(r.getRfqType()).clientName(r.getClientName())
                .clientCompany(r.getClientCompany()).clientEmail(r.getClientEmail())
                .clientPhone(r.getClientPhone()).urgency(r.getUrgency())
                .extractionConfidence(r.getExtractionConfidence()).status(r.getStatus())
                .notes(r.getNotes()).conversionProbability(r.getConversionProbability())
                .items(r.getItems().stream().map(i -> RfqResponse.RfqItemResponse.builder()
                        .id(i.getId()).productCode(i.getProductCode())
                        .productDescription(i.getProductDescription())
                        .quantity(i.getQuantity() != null ? i.getQuantity().doubleValue() : null)
                        .unit(i.getUnit()).fieldConfidence(i.getFieldConfidence()).build())
                        .collect(Collectors.toList()))
                .createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt()).build();
    }
}
