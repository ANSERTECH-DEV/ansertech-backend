package com.ansertech.service.quotation;

import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqItem;
import com.ansertech.domain.entity.RfqStockCheckResult;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.domain.enums.StockCheckStatus;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.QuotationRepository;
import com.ansertech.repository.RfqStockCheckResultRepository;
import com.ansertech.service.ai.GeminiService;
import com.ansertech.service.email.EmailSenderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regla de oro: nunca se llama a Vertex AI, Microsoft Graph ni Azure Blob real.
 * GeminiService, QuotationPdfService y EmailSenderService van mockeados con Mockito.
 */
@ExtendWith(MockitoExtension.class)
class QuotationServiceTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Mock private QuotationRepository quotationRepository;
    @Mock private ProductRepository productRepository;
    @Mock private RfqStockCheckResultRepository stockCheckResultRepository;
    @Mock private GeminiService geminiService;
    @Mock private QuotationPdfService pdfService;
    @Mock private EmailSenderService emailSenderService;

    // StockCheckItemResponse no tiene @NoArgsConstructor: Jackson solo puede construirlo
    // vía el constructor package-private que genera @Builder, usando nombres de parámetro.
    // Spring Boot registra jackson-module-parameter-names automáticamente en el ObjectMapper
    // real de la app; replicamos eso aquí para que el test refleje el comportamiento real.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new ParameterNamesModule());
    private QuotationService quotationService;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        // Reloj fijo en America/Lima: 2026-07-03 (los tests de fecha no dependen del reloj real)
        fixedClock = Clock.fixed(Instant.parse("2026-07-03T15:00:00Z"), LIMA);
        quotationService = new QuotationService(
                quotationRepository, productRepository, stockCheckResultRepository,
                geminiService, pdfService, emailSenderService, objectMapper, fixedClock);

        lenient().when(quotationRepository.save(any(Quotation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void calculatesIgv18PercentWithHalfUpRounding() throws Exception {
        RfqItem item1 = RfqItem.builder().id(1L).productDescription("Sensor HANNA")
                .quantity(new BigDecimal("2")).unit("unidad").build();
        RfqItem item2 = RfqItem.builder().id(2L).productDescription("Relé industrial")
                .quantity(new BigDecimal("1")).unit("unidad").build();
        Rfq rfq = baseRfq(List.of(item1, item2));

        mockStockCheck(rfq.getId(), List.of(
                matchedItem(1L, 10L, new BigDecimal("25.10"), new BigDecimal("50")),
                matchedItem(2L, 11L, new BigDecimal("10.125"), new BigDecimal("10"))));
        mockGeminiAnalysis(0.7, "resumen de prueba");
        mockNextSequence(0);

        Quotation quotation = quotationService.generateFromRfq(rfq);

        // item2: 1 * 10.125 -> HALF_UP a 2 decimales = 10.13
        assertThat(quotation.getItems().get(1).getSubtotal()).isEqualByComparingTo("10.13");
        // subtotal = 50.20 + 10.13 = 60.33
        assertThat(quotation.getSubtotal()).isEqualByComparingTo("60.33");
        // igv = 60.33 * 0.18 = 10.8594 -> HALF_UP = 10.86
        assertThat(quotation.getIgv()).isEqualByComparingTo("10.86");
        // total = 60.33 + 10.86 = 71.19
        assertThat(quotation.getTotal()).isEqualByComparingTo("71.19");
    }

    @Test
    void generatesSequentialQuotationNumber_whenNoPreviousQuotationThisYear() throws Exception {
        Rfq rfq = baseRfq(List.of());
        mockStockCheck(rfq.getId(), List.of());
        mockGeminiAnalysis(0.5, "");
        when(quotationRepository.findMaxSequenceForYear(anyInt())).thenReturn(null);

        Quotation quotation = quotationService.generateFromRfq(rfq);

        int year = LocalDate.now(fixedClock).getYear();
        assertThat(quotation.getQuotationNumber()).isEqualTo(String.format("COT-%d-0001", year));
    }

    @Test
    void generatesSequentialQuotationNumber_incrementsFromMaxSequence() throws Exception {
        Rfq rfq = baseRfq(List.of());
        mockStockCheck(rfq.getId(), List.of());
        mockGeminiAnalysis(0.5, "");
        mockNextSequence(7);

        Quotation quotation = quotationService.generateFromRfq(rfq);

        int year = LocalDate.now(fixedClock).getYear();
        assertThat(quotation.getQuotationNumber()).isEqualTo(String.format("COT-%d-0008", year));
    }

    @Test
    void validUntilIs15DaysFromInjectedClock() throws Exception {
        Rfq rfq = baseRfq(List.of());
        mockStockCheck(rfq.getId(), List.of());
        mockGeminiAnalysis(0.5, "");
        mockNextSequence(0);

        Quotation quotation = quotationService.generateFromRfq(rfq);

        assertThat(quotation.getValidUntil()).isEqualTo(LocalDate.now(fixedClock).plusDays(15));
    }

    @Test
    void unmatchedItem_getsZeroPriceAndOnOrderStatus() throws Exception {
        RfqItem unmatched = RfqItem.builder().id(3L).productDescription("Repuesto no encontrado")
                .quantity(new BigDecimal("5")).unit("unidad").build();
        Rfq rfq = baseRfq(List.of(unmatched));

        mockStockCheck(rfq.getId(), List.of(unmatchedItem(3L, "Repuesto no encontrado")));
        mockGeminiAnalysis(0.4, "");
        mockNextSequence(0);

        Quotation quotation = quotationService.generateFromRfq(rfq);

        var item = quotation.getItems().get(0);
        assertThat(item.getUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(item.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.ON_ORDER);
        assertThat(quotation.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fallsBackToMinimalQuotation_whenGeminiThrows() {
        Rfq rfq = baseRfq(List.of());
        lenient().when(stockCheckResultRepository.findByRfqId(rfq.getId())).thenReturn(Optional.empty());
        when(geminiService.analyzeRfq(any(), any())).thenThrow(new RuntimeException("Vertex AI no disponible"));
        mockNextSequence(0);

        Quotation quotation = quotationService.generateFromRfq(rfq);

        assertThat(quotation.getStatus().name()).isEqualTo("DRAFT");
        assertThat(quotation.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quotation.getIgv()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quotation.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quotation.getAiSummary()).containsIgnoringCase("fallida");
        verify(pdfService, never()).generateAndStore(any());
        verifyNoInteractions(emailSenderService);
    }

    @Test
    void skipsEmail_whenRfqHasNoClientEmail() throws Exception {
        Rfq rfq = baseRfq(List.of());
        rfq.setClientEmail(null);
        mockStockCheck(rfq.getId(), List.of());
        mockGeminiAnalysis(0.5, "");
        mockNextSequence(0);

        quotationService.generateFromRfq(rfq);

        verifyNoInteractions(emailSenderService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Rfq baseRfq(List<RfqItem> items) {
        Rfq rfq = Rfq.builder()
                .id(100L)
                .clientName("Cliente de Prueba")
                .clientCompany("Minera de Prueba S.A.C.")
                .clientEmail("cliente@mineraprueba.pe")
                .items(new java.util.ArrayList<>(items))
                .build();
        items.forEach(i -> i.setRfq(rfq));
        return rfq;
    }

    private void mockStockCheck(Long rfqId, List<StockCheckItemResponse> items) throws Exception {
        RfqStockCheckResult result = RfqStockCheckResult.builder()
                .rfqId(rfqId)
                .status(StockCheckStatus.DONE)
                .itemsJson(objectMapper.writeValueAsString(items))
                .build();
        lenient().when(stockCheckResultRepository.findByRfqId(rfqId)).thenReturn(Optional.of(result));
    }

    private StockCheckItemResponse matchedItem(Long rfqItemId, Long productId, BigDecimal price, BigDecimal stock) {
        return StockCheckItemResponse.builder()
                .rfqItemId(rfqItemId).matched(true)
                .productId(productId).productSku("SKU-" + productId).productName("Producto " + productId)
                .unitPrice(price).stockQuantity(stock).stockSufficient(true)
                .build();
    }

    private StockCheckItemResponse unmatchedItem(Long rfqItemId, String description) {
        return StockCheckItemResponse.builder()
                .rfqItemId(rfqItemId).rfqDescription(description).matched(false)
                .build();
    }

    private void mockGeminiAnalysis(double conversionProbability, String summary) {
        JsonNode node = objectMapper.createObjectNode()
                .put("conversion_probability", conversionProbability)
                .put("ai_summary", summary);
        lenient().when(geminiService.analyzeRfq(any(), any())).thenReturn(node);
    }

    private void mockNextSequence(Integer currentMax) {
        when(quotationRepository.findMaxSequenceForYear(anyInt())).thenReturn(currentMax);
    }
}
