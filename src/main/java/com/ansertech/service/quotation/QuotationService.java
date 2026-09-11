package com.ansertech.service.quotation;

import com.ansertech.domain.entity.*;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.domain.enums.StockCheckStatus;
import com.ansertech.dto.response.QuotationResponse;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqStockCheckResultRepository;
import com.ansertech.service.ai.GeminiService;
import com.ansertech.service.email.EmailSenderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationService {

    private static final BigDecimal IGV_RATE = new BigDecimal("0.18");

    private final QuotationRepository quotationRepository;
    private final ProductRepository productRepository;
    private final RfqStockCheckResultRepository stockCheckResultRepository;
    private final GeminiService geminiService;
    private final QuotationPdfService pdfService;
    private final EmailSenderService emailSenderService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public Quotation generateFromRfq(Rfq rfq) {
        try {
            List<StockCheckItemResponse> stockCheckItems = loadStockCheckItems(rfq.getId());

            String rfqJson = objectMapper.writeValueAsString(rfqToMap(rfq));
            String inventoryJson = buildInventoryContextFromStockCheck(stockCheckItems);

            JsonNode aiResult = geminiService.analyzeRfq(rfqJson, inventoryJson);

            Quotation quotation = buildQuotation(rfq, aiResult, stockCheckItems);
            quotationRepository.save(quotation);

            pdfService.generateAndStore(quotation);
            log.info("Cotización {} generada para RFQ {}", quotation.getQuotationNumber(), rfq.getId());

            sendEmailToClient(quotation, rfq);

            return quotation;
        } catch (Exception e) {
            log.error("Error generando cotización para RFQ {}: {}", rfq.getId(), e.getMessage());
            return buildMinimalQuotation(rfq);
        }
    }

    private List<StockCheckItemResponse> loadStockCheckItems(Long rfqId) {
        return stockCheckResultRepository.findByRfqId(rfqId)
                .filter(r -> r.getStatus() == StockCheckStatus.DONE && r.getItemsJson() != null)
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.getItemsJson(),
                                new TypeReference<List<StockCheckItemResponse>>() {});
                    } catch (Exception e) {
                        log.warn("No se pudo deserializar stock-check para RFQ {}: {}", rfqId, e.getMessage());
                        return List.<StockCheckItemResponse>of();
                    }
                })
                .orElse(List.of());
    }

    private String buildInventoryContextFromStockCheck(List<StockCheckItemResponse> items) {
        try {
            List<Map<String, Object>> results = items.stream().map(item -> {
                if (!item.isMatched()) {
                    return Map.<String, Object>of(
                            "description", item.getRfqDescription() != null ? item.getRfqDescription() : "",
                            "found", false);
                }
                BigDecimal price = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal stock = item.getStockQuantity() != null ? item.getStockQuantity() : BigDecimal.ZERO;
                return Map.<String, Object>of(
                        "description", item.getRfqDescription() != null ? item.getRfqDescription() : "",
                        "found", true,
                        "sku",       item.getProductSku()  != null ? item.getProductSku()  : "",
                        "name",      item.getProductName() != null ? item.getProductName() : "",
                        "stock",     stock,
                        "price",     price,
                        "available", item.isStockSufficient());
            }).collect(Collectors.toList());
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> extractCcAddresses(Rfq rfq) {
        try {
            if (rfq.getEmail() != null && rfq.getEmail().getCcAddresses() != null
                    && !rfq.getEmail().getCcAddresses().isBlank()) {
                return Arrays.stream(rfq.getEmail().getCcAddresses().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private void sendEmailToClient(Quotation quotation, Rfq rfq) {
        String clientEmail = rfq.getClientEmail();
        if (clientEmail == null || clientEmail.isBlank()) {
            log.warn("RFQ {} sin correo de cliente — cotización {} no enviada por email",
                    rfq.getId(), quotation.getQuotationNumber());
            return;
        }
        if (quotation.getPdfPath() == null) {
            log.warn("Cotización {} sin PDF — no se puede enviar email", quotation.getQuotationNumber());
            return;
        }
        try {
            byte[] pdfBytes = pdfService.downloadPdf(quotation.getPdfPath());
            emailSenderService.sendQuotationEmail(
                    clientEmail, rfq.getClientName(), quotation.getQuotationNumber(),
                    quotation.getSubtotal(), quotation.getIgv(), quotation.getTotal(),
                    pdfBytes, extractCcAddresses(rfq)
            );
            quotation.setStatus(QuotationStatus.SENT);
            quotation.setSentAt(LocalDateTime.now());
            quotationRepository.save(quotation);
            log.info("Email enviado a {} — cotización {}", clientEmail, quotation.getQuotationNumber());
        } catch (Exception e) {
            log.error("Error enviando email para cotización {} — queda en DRAFT: {}",
                    quotation.getQuotationNumber(), e.getMessage());
        }
    }

    public Page<QuotationResponse> findAll(QuotationStatus status, Pageable pageable) {
        Page<Quotation> page = status != null
                ? quotationRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : quotationRepository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(this::toResponse);
    }

    public QuotationResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public QuotationResponse markAsSent(Long id) {
        Quotation q = findOrThrow(id);
        if (q.getStatus() == QuotationStatus.SENT) {
            log.info("Reenvío manual de cotización {} a {}", q.getQuotationNumber(),
                    q.getRfq() != null ? q.getRfq().getClientEmail() : "desconocido");
        }
        if (q.getRfq() != null && q.getRfq().getClientEmail() != null && q.getPdfPath() != null) {
            byte[] pdfBytes = pdfService.downloadPdf(q.getPdfPath());
            emailSenderService.sendQuotationEmail(
                    q.getRfq().getClientEmail(), q.getRfq().getClientName(),
                    q.getQuotationNumber(),
                    q.getSubtotal(), q.getIgv(), q.getTotal(),
                    pdfBytes, extractCcAddresses(q.getRfq())
            );
        } else {
            log.warn("Cotización {} sin correo de cliente o PDF", q.getQuotationNumber());
        }
        q.setStatus(QuotationStatus.SENT);
        q.setSentAt(LocalDateTime.now());
        return toResponse(quotationRepository.save(q));
    }

    public String getPdfPath(Long id) {
        Quotation q = findOrThrow(id);
        if (q.getPdfPath() == null) throw new ResourceNotFoundException("PDF no generado aún");
        return q.getPdfPath();
    }

    private Quotation buildQuotation(Rfq rfq, JsonNode ai, List<StockCheckItemResponse> stockCheckItems) {
        // Índice por rfqItemId para lookup O(1)
        Map<Long, StockCheckItemResponse> byItemId = stockCheckItems.stream()
                .filter(s -> s.getRfqItemId() != null)
                .collect(Collectors.toMap(StockCheckItemResponse::getRfqItemId, s -> s, (a, b) -> a));

        String number = generateQuotationNumber();
        List<QuotationItem> items = new ArrayList<>();

        Quotation quotation = Quotation.builder()
                .rfq(rfq)
                .quotationNumber(number)
                .status(QuotationStatus.DRAFT)
                .currency("PEN")
                .validUntil(LocalDate.now(clock).plusDays(15))
                .conversionProbability(ai.path("conversion_probability").asDouble(0.5))
                .aiSummary(ai.path("ai_summary").asText(""))
                .items(items)
                .build();

        for (RfqItem rfqItem : rfq.getItems()) {
            StockCheckItemResponse match = byItemId.get(rfqItem.getId());
            boolean found = match != null && match.isMatched();

            BigDecimal qty   = rfqItem.getQuantity() != null ? rfqItem.getQuantity() : BigDecimal.ONE;
            String desc      = rfqItem.getProductDescription() != null ? rfqItem.getProductDescription() : "";
            String unit      = rfqItem.getUnit() != null ? rfqItem.getUnit() : "unidad";
            BigDecimal price = found && match.getUnitPrice() != null ? match.getUnitPrice() : BigDecimal.ZERO;
            AvailabilityStatus avail = found ? AvailabilityStatus.IN_STOCK : AvailabilityStatus.ON_ORDER;

            Product product = found
                    ? productRepository.findById(match.getProductId()).orElse(null)
                    : null;

            BigDecimal sub = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);

            items.add(QuotationItem.builder()
                    .quotation(quotation)
                    .product(product)
                    .description(desc)
                    .quantity(qty).unit(unit)
                    .unitPrice(price).subtotal(sub)
                    .availabilityStatus(avail)
                    .build());
        }

        BigDecimal subtotal = items.stream()
                .map(QuotationItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal igv = subtotal.multiply(IGV_RATE).setScale(2, RoundingMode.HALF_UP);

        quotation.setSubtotal(subtotal);
        quotation.setIgv(igv);
        quotation.setTotal(subtotal.add(igv));
        return quotation;
    }

    private Quotation buildMinimalQuotation(Rfq rfq) {
        Quotation q = Quotation.builder()
                .rfq(rfq).quotationNumber(generateQuotationNumber())
                .status(QuotationStatus.DRAFT).currency("PEN")
                .validUntil(LocalDate.now(clock).plusDays(15))
                .subtotal(BigDecimal.ZERO).igv(BigDecimal.ZERO).total(BigDecimal.ZERO)
                .aiSummary("Generación automática fallida — completar manualmente")
                .items(new ArrayList<>()).build();
        return quotationRepository.save(q);
    }



    private String generateQuotationNumber() {
        int year = LocalDate.now(clock).getYear();
        Integer maxSeq = quotationRepository.findMaxSequenceForYear(year);
        int next = (maxSeq != null ? maxSeq : 0) + 1;
        return String.format("COT-%d-%04d", year, next);
    }

    private Quotation findOrThrow(Long id) {
        return quotationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cotización", id));
    }

    private QuotationResponse toResponse(Quotation q) {
        List<QuotationResponse.QuotationItemResponse> itemResponses = q.getItems().stream()
                .map(i -> QuotationResponse.QuotationItemResponse.builder()
                        .id(i.getId()).description(i.getDescription())
                        .quantity(i.getQuantity()).unit(i.getUnit())
                        .unitPrice(i.getUnitPrice()).subtotal(i.getSubtotal())
                        .availabilityStatus(i.getAvailabilityStatus()).build())
                .collect(Collectors.toList());

        return QuotationResponse.builder()
                .id(q.getId()).rfqId(q.getRfq() != null ? q.getRfq().getId() : null)
                .quotationNumber(q.getQuotationNumber()).status(q.getStatus())
                .subtotal(q.getSubtotal()).igv(q.getIgv()).total(q.getTotal())
                .currency(q.getCurrency()).validUntil(q.getValidUntil())
                .pdfPath(q.getPdfPath()).aiSummary(q.getAiSummary())
                .conversionProbability(q.getConversionProbability())
                .items(itemResponses).createdAt(q.getCreatedAt()).sentAt(q.getSentAt())
                .build();
    }

    private java.util.Map<String, Object> rfqToMap(Rfq rfq) {
        List<java.util.Map<String, Object>> itemMaps = rfq.getItems().stream()
                .map(i -> java.util.Map.<String, Object>of(
                        "description", i.getProductDescription() != null ? i.getProductDescription() : "",
                        "quantity", i.getQuantity() != null ? i.getQuantity().doubleValue() : 1.0,
                        "unit", i.getUnit() != null ? i.getUnit() : "unidad"
                ))
                .collect(Collectors.toList());

        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", rfq.getId() != null ? rfq.getId() : 0L);
        map.put("rfq_type", rfq.getRfqType() != null ? rfq.getRfqType().name() : "PRODUCT_SALE");
        map.put("client_name", rfq.getClientName() != null ? rfq.getClientName() : "");
        map.put("client_company", rfq.getClientCompany() != null ? rfq.getClientCompany() : "");
        map.put("urgency", rfq.getUrgency() != null ? rfq.getUrgency() : "MEDIUM");
        map.put("items", itemMaps);
        return map;
    }
}
