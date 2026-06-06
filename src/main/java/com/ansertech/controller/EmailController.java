package com.ansertech.controller;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.EmailResponse;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.EmailRepository;
import com.ansertech.service.email.EmailPipelineScheduler;
import com.ansertech.service.email.EmailPollingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
@Tag(name = "Emails", description = "Gestión de correos clasificados")
public class EmailController {

    private final EmailRepository emailRepository;
    private final EmailPipelineScheduler scheduler;

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
