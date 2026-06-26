package com.ansertech.controller;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.EmailResponse;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.EmailRepository;
import com.ansertech.repository.RfqRepository;
import com.ansertech.service.email.EmailPipelineScheduler;
import com.ansertech.service.email.EmailPollingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
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
    private final EmailPipelineScheduler scheduler;

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

    @GetMapping("/export")
    @Operation(summary = "Exportar reporte de correos procesados a Excel")
    public ResponseEntity<byte[]> exportExcel() throws Exception {
        List<Email> emails = emailRepository.findAll(
                org.springframework.data.domain.Sort.by("createdAt").ascending());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Correos Procesados");

            // Estilos de cabecera
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // Estilo filas alternas
            CellStyle grayStyle = workbook.createCellStyle();
            grayStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            grayStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Cabeceras
            String[] headers = {"N°", "Correo", "Asunto", "Fecha recibido",
                    "Spam", "Confianza clasif.", "Extracción %", "Cantidad de productos"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Datos
            int rowNum = 1;
            for (Email email : emails) {
                Optional<Rfq> rfqOpt = rfqRepository.findByEmailId(email.getId());
                boolean isSpam = email.getStatus() == EmailStatus.SPAM;

                Row row = sheet.createRow(rowNum);
                CellStyle rowStyle = (rowNum % 2 == 0) ? grayStyle : null;

                createCell(row, 0, rowNum, rowStyle);
                createCell(row, 1, email.getSenderEmail() != null ? email.getSenderEmail() : "-", rowStyle);

                String subject = email.getSubject() != null
                        ? (email.getSubject().length() > 60
                            ? email.getSubject().substring(0, 60) + "..."
                            : email.getSubject())
                        : "-";
                createCell(row, 2, subject, rowStyle);
                createCell(row, 3,
                        email.getReceivedAt() != null ? email.getReceivedAt().format(DATE_FMT) : "-",
                        rowStyle);
                createCell(row, 4, isSpam ? "Sí" : "No", rowStyle);

                if (email.getSpamConfidence() != null) {
                    createCell(row, 5, String.format("%.0f%%", email.getSpamConfidence() * 100), rowStyle);
                } else {
                    createCell(row, 5, "-", rowStyle);
                }

                if (rfqOpt.isPresent()) {
                    Rfq rfq = rfqOpt.get();
                    double extPct = rfq.getExtractionConfidence() != null
                            ? rfq.getExtractionConfidence() * 100 : 0.0;
                    createCell(row, 6, String.format("%.0f%%", extPct), rowStyle);
                    createCell(row, 7, rfq.getItems() != null ? rfq.getItems().size() : 0, rowStyle);
                } else {
                    createCell(row, 6, "-", rowStyle);
                    createCell(row, 7, "-", rowStyle);
                }
                rowNum++;
            }

            // Ancho de columnas
            int[] widths = {8, 38, 55, 22, 8, 20, 16, 22};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"reporte-correos.xlsx\"")
                    .body(baos.toByteArray());
        }
    }

    private void createCell(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value instanceof Integer i) cell.setCellValue(i);
        else if (value instanceof Double d) cell.setCellValue(d);
        else cell.setCellValue(value != null ? value.toString() : "-");
        if (style != null) cell.setCellStyle(style);
    }

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
