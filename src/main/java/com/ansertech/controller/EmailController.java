package com.ansertech.controller;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.EmailResponse;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.EmailRepository;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.email.EmailPipelineScheduler;
import com.ansertech.service.email.EmailPollingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
@Tag(name = "Emails", description = "Gestión de correos clasificados")
public class EmailController {

    private final EmailRepository emailRepository;
    private final RfqRepository rfqRepository;
    private final QuotationRepository quotationRepository;
    private final EmailPipelineScheduler scheduler;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @GetMapping
    @Operation(summary = "Listar correos con filtro por estado")
    public ResponseEntity<ApiResponse<Page<EmailResponse>>> list(
            @RequestParam(required = false) EmailStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Email> page = status != null
                ? emailRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : emailRepository.findAllByOrderByCreatedAtDesc(pageable);
        return ResponseEntity.ok(ApiResponse.ok(page.map(this::toResponse)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener email por ID")
    public ResponseEntity<ApiResponse<EmailResponse>> getById(@PathVariable Long id) {
        Email email = emailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email", id));
        return ResponseEntity.ok(ApiResponse.ok(toResponse(email)));
    }

    @PatchMapping("/{id}/reclassify")
    @Operation(summary = "Reclasificar manualmente un email")
    public ResponseEntity<ApiResponse<EmailResponse>> reclassify(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Email email = emailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email", id));
        EmailStatus newStatus = EmailStatus.valueOf(body.get("status").toUpperCase());
        email.setStatus(newStatus);
        emailRepository.save(email);
        return ResponseEntity.ok(ApiResponse.ok(toResponse(email), "Email reclasificado"));
    }

    @PostMapping("/trigger-polling")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Forzar polling manual de correos")
    public ResponseEntity<ApiResponse<String>> triggerPolling() {
        scheduler.runPipeline();
        return ResponseEntity.ok(ApiResponse.ok("Polling ejecutado", "Pipeline iniciado"));
    }

    // ── Excel Export ──────────────────────────────────────────────────────────

    @GetMapping("/export")
    @Operation(summary = "Exportar reporte de correos procesados a Excel")
    public ResponseEntity<byte[]> exportExcel() throws Exception {
        List<Email> emails = emailRepository.findAll(
                org.springframework.data.domain.Sort.by("createdAt").ascending());

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Reporte Correos");

            // ── Estilos ───────────────────────────────────────────────────
            CellStyle groupStyle = groupHeaderStyle(wb, IndexedColors.DARK_BLUE);
            CellStyle classifStyle = groupHeaderStyle(wb, IndexedColors.DARK_TEAL);
            CellStyle extractStyle = groupHeaderStyle(wb, IndexedColors.DARK_GREEN);
            CellStyle inventoryStyle = groupHeaderStyle(wb, IndexedColors.DARK_RED);
            CellStyle resultStyle = groupHeaderStyle(wb, IndexedColors.VIOLET);

            CellStyle colHeaderStyle = colHeaderStyle(wb);
            CellStyle grayStyle = grayRowStyle(wb);
            CellStyle numberStyle = numberCellStyle(wb);
            CellStyle grayNumberStyle = grayNumberCellStyle(wb);

            // ── Fila 0: Grupos de columnas (merged) ───────────────────────
            Row groupRow = sheet.createRow(0);
            groupRow.setHeightInPoints(16);

            mergedHeader(sheet, groupRow, groupStyle,  "GENERAL",            0,  8);
            mergedHeader(sheet, groupRow, classifStyle,"CLASIFICACIÓN",       9, 11);
            mergedHeader(sheet, groupRow, extractStyle,"EXTRACCIÓN",         12, 13);
            mergedHeader(sheet, groupRow, inventoryStyle,"INVENTARIO",       14, 16);
            mergedHeader(sheet, groupRow, resultStyle, "RESULTADO PIPELINE", 17, 21);

            // ── Fila 1: Cabeceras de columna ──────────────────────────────
            String[] headers = {
                // GENERAL (0-8)
                "N°", "Correo", "Asunto", "Fecha recibido",
                "Spam", "Confianza clasif.", "Extracción %",
                "Cant. productos", "Tiempo pipeline (IA)",
                // CLASIFICACIÓN (9-11)
                "Estado clasif.", "Tiene adjunto", "Tipo adjunto",
                // EXTRACCIÓN (12-13)
                "Campos extraídos (N/4)", "Email origen",
                // INVENTARIO (14-16)
                "Ítems encontrados", "Ítems no encontrados", "% Ítems encontrados",
                // RESULTADO (17-21)
                "Estado RFQ", "Cotización enviada",
                "Valor total (S/)", "Prob. conversión (%)", "Tiempo revisión operador"
            };
            Row headerRow = sheet.createRow(1);
            headerRow.setHeightInPoints(28);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(colHeaderStyle);
            }

            // ── Filas de datos ────────────────────────────────────────────
            int rowNum = 2;
            for (Email email : emails) {
                Optional<Rfq> rfqOpt = rfqRepository.findByEmailId(email.getId());
                Optional<Quotation> quotOpt = rfqOpt.isPresent()
                        ? quotationRepository.findFirstByRfqIdOrderByCreatedAtAsc(rfqOpt.get().getId())
                        : Optional.empty();

                Row row = sheet.createRow(rowNum);
                boolean isGray = (rowNum % 2 == 0);
                CellStyle base   = isGray ? grayStyle        : null;
                CellStyle numSt  = isGray ? grayNumberStyle  : numberStyle;

                // ── GENERAL ───────────────────────────────────────────────
                setCell(row, 0, rowNum - 1, numSt);
                setCell(row, 1, nvl(email.getSenderEmail()), base);
                setCell(row, 2, truncate(email.getSubject(), 60), base);
                setCell(row, 3, email.getReceivedAt() != null
                        ? email.getReceivedAt().format(DATE_FMT) : "-", base);
                setCell(row, 4, email.getStatus() == EmailStatus.SPAM ? "Sí" : "No", base);
                setCell(row, 5, email.getSpamConfidence() != null
                        ? String.format("%.1f%%", email.getSpamConfidence() * 100) : "-", base);

                LocalDateTime pipelineEnd = null;
                if (rfqOpt.isPresent()) {
                    Rfq rfq = rfqOpt.get();
                    setCell(row, 6, rfq.getExtractionConfidence() != null
                            ? String.format("%.1f%%", rfq.getExtractionConfidence() * 100) : "-", base);
                    setCell(row, 7, rfq.getItems() != null ? rfq.getItems().size() : 0, numSt);
                    pipelineEnd = rfq.getCreatedAt();
                } else {
                    setCell(row, 6, "-", base);
                    setCell(row, 7, "-", base);
                    pipelineEnd = email.getProcessedAt();
                }
                setCell(row, 8, formatDuration(email.getCreatedAt(), pipelineEnd), base);

                // ── CLASIFICACIÓN ─────────────────────────────────────────
                setCell(row, 9, statusLabel(email.getStatus()), base);
                setCell(row, 10, Boolean.TRUE.equals(email.getHasAttachments()) ? "Sí" : "No", base);
                setCell(row, 11, attachmentType(email.getAttachmentsJson()), base);

                // ── EXTRACCIÓN ────────────────────────────────────────────
                if (rfqOpt.isPresent()) {
                    Rfq rfq = rfqOpt.get();
                    setCell(row, 12, countExtractedFields(rfq) + "/4", base);
                    setCell(row, 13, emailOrigen(rfq, email), base);
                } else {
                    setCell(row, 12, "-", base);
                    setCell(row, 13, "-", base);
                }

                // ── INVENTARIO ────────────────────────────────────────────
                if (quotOpt.isPresent()) {
                    Quotation q = quotOpt.get();
                    long found    = q.getItems().stream()
                            .filter(i -> i.getAvailabilityStatus() != AvailabilityStatus.ON_ORDER)
                            .count();
                    long notFound = q.getItems().stream()
                            .filter(i -> i.getAvailabilityStatus() == AvailabilityStatus.ON_ORDER)
                            .count();
                    int total     = q.getItems().size();
                    double pct    = total > 0 ? (found * 100.0 / total) : 0.0;
                    setCell(row, 14, (int) found, numSt);
                    setCell(row, 15, (int) notFound, numSt);
                    setCell(row, 16, String.format("%.1f%%", pct), base);
                } else {
                    setCell(row, 14, "-", base);
                    setCell(row, 15, "-", base);
                    setCell(row, 16, "-", base);
                }

                // ── RESULTADO ─────────────────────────────────────────────
                if (rfqOpt.isPresent()) {
                    setCell(row, 17, rfqStatusLabel(rfqOpt.get()), base);
                } else {
                    setCell(row, 17, "-", base);
                }
                if (quotOpt.isPresent()) {
                    Quotation q = quotOpt.get();
                    setCell(row, 18, q.getStatus() == QuotationStatus.SENT ? "Sí" : "No", base);
                    setCell(row, 19, q.getTotal() != null
                            ? String.format("%.2f", q.getTotal()) : "-", base);
                    setCell(row, 20, q.getConversionProbability() != null
                            ? String.format("%.0f%%", q.getConversionProbability() * 100) : "-", base);
                    // Tiempo revisión = desde que el RFQ quedó listo hasta que el operador aprobó
                    LocalDateTime reviewStart = rfqOpt.map(Rfq::getCreatedAt).orElse(null);
                    setCell(row, 21, formatDuration(reviewStart, q.getCreatedAt()), base);
                } else {
                    setCell(row, 18, "-", base);
                    setCell(row, 19, "-", base);
                    setCell(row, 20, "-", base);
                    setCell(row, 21, "-", base);
                }

                rowNum++;
            }

            // ── Anchos de columna ─────────────────────────────────────────
            int[] widths = {
                6, 36, 52, 20, 8, 18, 15, 16, 22,   // GENERAL
                18, 14, 14,                            // CLASIFICACIÓN
                22, 26,                                // EXTRACCIÓN
                18, 20, 20,                            // INVENTARIO
                16, 18, 16, 20, 26                     // RESULTADO
            };
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }

            // Freeze header rows
            sheet.createFreezePane(0, 2);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"reporte-correos.xlsx\"")
                    .body(baos.toByteArray());
        }
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private CellStyle groupHeaderStyle(XSSFWorkbook wb, IndexedColors color) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        f.setFontHeightInPoints((short) 9);
        s.setFont(f);
        return s;
    }

    private CellStyle colHeaderStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        s.setBorderBottom(BorderStyle.MEDIUM);
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        f.setFontHeightInPoints((short) 9);
        s.setFont(f);
        return s;
    }

    private CellStyle grayRowStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle numberCellStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private CellStyle grayNumberCellStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private void mergedHeader(Sheet sheet, Row row, CellStyle style,
                               String label, int colFrom, int colTo) {
        Cell c = row.createCell(colFrom);
        c.setCellValue(label);
        c.setCellStyle(style);
        for (int i = colFrom + 1; i <= colTo; i++) {
            row.createCell(i).setCellStyle(style);
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, colFrom, colTo));
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    private void setCell(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value instanceof Integer i)    cell.setCellValue(i);
        else if (value instanceof Double d) cell.setCellValue(d);
        else cell.setCellValue(value != null ? value.toString() : "-");
        if (style != null) cell.setCellStyle(style);
    }

    private String formatDuration(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return "-";
        Duration d = Duration.between(from, to);
        if (d.isNegative()) return "-";
        long hours   = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours   > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    private String statusLabel(EmailStatus status) {
        if (status == null) return "-";
        return switch (status) {
            case SPAM        -> "SPAM";
            case VALID       -> "VÁLIDO";
            case UNCERTAIN   -> "INCIERTO";
            case PROCESSING  -> "EN PROCESO";
            case PENDING     -> "PENDIENTE";
            default          -> status.name();
        };
    }

    private String rfqStatusLabel(Rfq rfq) {
        if (rfq.getStatus() == null) return "-";
        return switch (rfq.getStatus()) {
            case PROCESSING     -> "PROCESANDO";
            case PENDING_REVIEW -> "PENDIENTE REVISIÓN";
            case QUOTING        -> "COTIZANDO";
            case QUOTED         -> "COTIZADO";
            case REJECTED       -> "RECHAZADO";
        };
    }

    private String attachmentType(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()
                || attachmentsJson.equals("[]")) return "Ninguno";
        try {
            JsonNode arr = objectMapper.readTree(attachmentsJson);
            if (arr.isArray() && arr.size() > 0) {
                String mime = arr.get(0).path("mimeType").asText("");
                if (mime.equals("application/pdf"))  return "PDF";
                if (mime.startsWith("image/"))       return "Imagen";
                if (mime.isEmpty())                  return "Ninguno";
                return "Otro";
            }
        } catch (Exception ignored) {}
        return "Ninguno";
    }

    private int countExtractedFields(Rfq rfq) {
        int count = 0;
        if (rfq.getClientName()    != null && !rfq.getClientName().isBlank())    count++;
        if (rfq.getClientCompany() != null && !rfq.getClientCompany().isBlank()) count++;
        if (rfq.getClientPhone()   != null && !rfq.getClientPhone().isBlank())   count++;
        // Email: solo cuenta si lo extrajo la IA (viene del rawExtractedJson, no del fallback)
        if (aiExtractedEmail(rfq))                                               count++;
        return count;
    }

    private boolean aiExtractedEmail(Rfq rfq) {
        try {
            if (rfq.getRawExtractedJson() == null) return false;
            JsonNode node = objectMapper.readTree(rfq.getRawExtractedJson());
            String aiEmail = node.path("client_email").asText(null);
            return aiEmail != null && !aiEmail.isBlank() && !aiEmail.equalsIgnoreCase("null");
        } catch (Exception e) {
            return false;
        }
    }

    private String emailOrigen(Rfq rfq, Email email) {
        return aiExtractedEmail(rfq) ? "Extraído por IA" : "Tomado del remitente";
    }

    private String truncate(String s, int max) {
        if (s == null) return "-";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String nvl(String s) {
        return s != null ? s : "-";
    }

    // ── REST helpers ──────────────────────────────────────────────────────────

    private EmailResponse toResponse(Email e) {
        return EmailResponse.builder()
                .id(e.getId()).senderEmail(e.getSenderEmail()).senderName(e.getSenderName())
                .subject(e.getSubject()).body(e.getBody()).receivedAt(e.getReceivedAt())
                .status(e.getStatus()).spamConfidence(e.getSpamConfidence())
                .classificationReason(e.getClassificationReason())
                .hasAttachments(e.getHasAttachments()).emailProvider(e.getEmailProvider())
                .createdAt(e.getCreatedAt()).build();
    }
}
