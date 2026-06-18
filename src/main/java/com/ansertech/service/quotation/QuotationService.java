package com.ansertech.service.quotation;

import com.ansertech.domain.entity.*;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.domain.enums.QuotationStatus;
import com.ansertech.dto.response.QuotationResponse;
import com.ansertech.exception.BusinessException;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.service.ai.GeminiService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationService {

    private static final BigDecimal IGV_RATE = new BigDecimal("0.18");

    private final QuotationRepository quotationRepository;
    private final ProductRepository productRepository;
    private final GeminiService geminiService;
    private final QuotationPdfService pdfService;
    private final ObjectMapper objectMapper;

    public Quotation generateFromRfq(Rfq rfq) {
        try {
            String rfqJson = objectMapper.writeValueAsString(rfqToMap(rfq));
            String inventoryJson = buildInventoryContext(rfq);

            JsonNode aiResult = geminiService.analyzeAndGenerateQuotation(rfqJson, inventoryJson);

            Quotation quotation = buildQuotation(rfq, aiResult);
            quotationRepository.save(quotation);

            pdfService.generateAndStore(quotation);
            log.info("Cotización {} generada para RFQ {}", quotation.getQuotationNumber(), rfq.getId());
            return quotation;
        } catch (Exception e) {
            log.error("Error generando cotización para RFQ {}: {}", rfq.getId(), e.getMessage());
            return buildMinimalQuotation(rfq);
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
            throw new BusinessException("La cotización ya fue enviada");
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

    private Quotation buildQuotation(Rfq rfq, JsonNode ai) {
        String number = generateQuotationNumber();
        List<QuotationItem> items = new ArrayList<>();

        Quotation quotation = Quotation.builder()
                .rfq(rfq)
                .quotationNumber(number)
                .status(QuotationStatus.DRAFT)
                .currency("PEN")
                .validUntil(LocalDate.now().plusDays(15))
                .conversionProbability(ai.path("conversion_probability").asDouble(0.5))
                .aiSummary(ai.path("ai_summary").asText(""))
                .items(items)
                .build();

        try {
            quotation.setAiAlertsJson(objectMapper.writeValueAsString(ai.path("alerts")));
        } catch (Exception ignored) {}

        JsonNode aiItems = ai.path("quotation_items");
        if (aiItems.isArray()) {
            for (JsonNode itemNode : aiItems) {
                BigDecimal qty = BigDecimal.valueOf(itemNode.path("quantity").asDouble(1.0));
                BigDecimal price = BigDecimal.valueOf(itemNode.path("unit_price").asDouble(0.0));
                BigDecimal sub = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);

                String availStr = itemNode.path("availability_status").asText("IN_STOCK");
                AvailabilityStatus avail;
                try { avail = AvailabilityStatus.valueOf(availStr); }
                catch (Exception e) { avail = AvailabilityStatus.IN_STOCK; }

                QuotationItem qi = QuotationItem.builder()
                        .quotation(quotation)
                        .description(itemNode.path("product_description").asText(""))
                        .quantity(qty).unit(itemNode.path("unit").asText("unidad"))
                        .unitPrice(price).subtotal(sub)
                        .availabilityStatus(avail)
                        .build();
                items.add(qi);
            }
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
                .validUntil(LocalDate.now().plusDays(15))
                .subtotal(BigDecimal.ZERO).igv(BigDecimal.ZERO).total(BigDecimal.ZERO)
                .aiSummary("Generación automática fallida — completar manualmente")
                .items(new ArrayList<>()).build();
        return quotationRepository.save(q);
    }

    private String buildInventoryContext(Rfq rfq) {
        try {
            List<java.util.Map<String, Object>> results = rfq.getItems().stream().map(item -> {
                String desc = item.getProductDescription() != null ? item.getProductDescription() : "";
                double qty = item.getQuantity() != null ? item.getQuantity().doubleValue() : 1.0;
                List<Product> matches = findBestProductMatch(desc);
                if (matches.isEmpty()) {
                    return java.util.Map.<String, Object>of("description", desc, "found", false);
                }
                Product p = matches.get(0);
                BigDecimal price = p.getUnitPrice() != null ? p.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal stock = p.getStockQuantity() != null ? p.getStockQuantity() : BigDecimal.ZERO;
                return java.util.Map.<String, Object>of(
                        "description", desc, "found", true,
                        "sku", p.getSku() != null ? p.getSku() : "",
                        "name", p.getName() != null ? p.getName() : "",
                        "stock", stock, "price", price,
                        "available", stock.doubleValue() >= qty
                );
            }).collect(Collectors.toList());
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "de", "del", "el", "la", "los", "las", "con", "para", "por", "en",
            "un", "una", "y", "o", "a", "al", "suministro", "provision", "provisión",
            "compra", "adquisición", "adquisicion", "instalacion", "instalación"
    );

    private List<Product> findBestProductMatch(String desc) {
        List<Product> matches = productRepository.findByDescriptionLike(desc);
        if (!matches.isEmpty()) return matches;

        String[] words = desc.split("[\\s,./()]+");
        List<String> keywords = Arrays.stream(words)
                .map(w -> w.toLowerCase().replaceAll("[^a-záéíóúüñ]", ""))
                .filter(w -> w.length() > 3 && !STOP_WORDS.contains(w) && !w.matches("\\d+"))
                .sorted((a, b) -> b.length() - a.length())
                .distinct()
                .collect(Collectors.toList());

        for (String keyword : keywords) {
            matches = productRepository.findByDescriptionLike(keyword);
            if (!matches.isEmpty()) return matches;
            // Prueba forma singular en español (quitar -es o -s)
            if (keyword.endsWith("es") && keyword.length() > 5) {
                matches = productRepository.findByDescriptionLike(keyword.substring(0, keyword.length() - 2));
                if (!matches.isEmpty()) return matches;
            } else if (keyword.endsWith("s") && keyword.length() > 4) {
                matches = productRepository.findByDescriptionLike(keyword.substring(0, keyword.length() - 1));
                if (!matches.isEmpty()) return matches;
            }
        }
        return List.of();
    }

    private String generateQuotationNumber() {
        int year = LocalDate.now().getYear();
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
