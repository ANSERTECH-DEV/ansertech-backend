package com.ansertech.controller;

import com.ansertech.domain.enums.EmailStatus;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.dto.response.ApiResponse;
import com.ansertech.dto.response.DashboardStatsResponse;
import com.ansertech.dto.response.EmailResponse;
import com.ansertech.dto.response.RfqResponse;
import com.ansertech.repository.EmailRepository;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "KPIs y métricas del sistema")
public class DashboardController {

    private final EmailRepository emailRepository;
    private final RfqRepository rfqRepository;
    private final QuotationRepository quotationRepository;
    private final ProductRepository productRepository;

    @GetMapping("/stats")
    @Operation(summary = "KPIs generales del sistema")
    public ResponseEntity<ApiResponse<DashboardStatsResponse>> stats() {
        long totalEmails = emailRepository.count();
        long spam = emailRepository.countByStatus(EmailStatus.SPAM);
        long valid = emailRepository.countByStatus(EmailStatus.VALID);
        long uncertain = emailRepository.countByStatus(EmailStatus.UNCERTAIN);

        long totalRfqs = rfqRepository.count();
        long pendingRfqs = rfqRepository.countByStatus(RfqStatus.PENDING_REVIEW);
        long quotedRfqs = rfqRepository.countByStatus(RfqStatus.QUOTED);

        long totalQuotations = quotationRepository.count();
        long draft = quotationRepository.countByStatus(QuotationStatus.DRAFT);
        long sent = quotationRepository.countByStatus(QuotationStatus.SENT);
        long accepted = quotationRepository.countByStatus(QuotationStatus.ACCEPTED);

        long lowStock = productRepository.countLowStock();
        double conversionRate = sent > 0 ? (double) accepted / sent * 100 : 0.0;

        return ResponseEntity.ok(ApiResponse.ok(DashboardStatsResponse.builder()
                .totalEmails(totalEmails).spamEmails(spam).validEmails(valid).uncertainEmails(uncertain)
                .totalRfqs(totalRfqs).pendingRfqs(pendingRfqs).quotedRfqs(quotedRfqs)
                .totalQuotations(totalQuotations).draftQuotations(draft)
                .sentQuotations(sent).acceptedQuotations(accepted)
                .lowStockProducts(lowStock).conversionRate(conversionRate)
                .build()));
    }

    @GetMapping("/recent-emails")
    @Operation(summary = "Últimos 10 correos procesados")
    public ResponseEntity<ApiResponse<List<EmailResponse>>> recentEmails() {
        var emails = emailRepository.findTop10ByOrderByCreatedAtDesc(PageRequest.of(0, 10));
        var response = emails.stream().map(e -> EmailResponse.builder()
                .id(e.getId()).senderEmail(e.getSenderEmail()).subject(e.getSubject())
                .status(e.getStatus()).spamConfidence(e.getSpamConfidence())
                .hasAttachments(e.getHasAttachments()).createdAt(e.getCreatedAt()).build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/recent-rfqs")
    @Operation(summary = "Últimos 10 RFQs procesados")
    public ResponseEntity<ApiResponse<List<RfqResponse>>> recentRfqs() {
        var rfqs = rfqRepository.findRecentRfqs(PageRequest.of(0, 10));
        var response = rfqs.stream().map(r -> RfqResponse.builder()
                .id(r.getId()).clientName(r.getClientName()).clientCompany(r.getClientCompany())
                .rfqType(r.getRfqType()).status(r.getStatus())
                .conversionProbability(r.getConversionProbability()).createdAt(r.getCreatedAt()).build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
