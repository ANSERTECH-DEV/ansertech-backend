package com.ansertech.service.ai;

import com.ansertech.domain.entity.Product;
import com.ansertech.domain.entity.RfqItem;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Matching de inventario con Gemini simulado: cada ítem se evalúa aparte y un error ya no "borra" las coincidencias. */
class GeminiServiceStockMatchTest {

    /** GeminiService con la llamada a Vertex AI y las esperas simuladas. */
    static class FakeGemini extends GeminiService {
        final Deque<Object> responses = new ArrayDeque<>();   // String = respuesta, RuntimeException = error
        final List<Long> sleeps = new ArrayList<>();
        int calls;

        FakeGemini() { super(null, new ObjectMapper()); }

        @Override
        protected String generateJson(String prompt) throws IOException {
            calls++;
            Object next = responses.poll();
            if (next instanceof RuntimeException e) throw e;
            return (String) next;
        }

        @Override
        protected void sleep(long millis) { sleeps.add(millis); }
    }

    private static final RuntimeException QUOTA = new RuntimeException(
            "io.grpc.StatusRuntimeException: RESOURCE_EXHAUSTED: Resource exhausted. Please try again later.");

    private final FakeGemini gemini = new FakeGemini();

    private final List<RfqItem> items = List.of(
            item(11L, "Agitador magnético con calefacción", "2"),
            item(12L, "Reactor nuclear portátil", "1"),
            item(13L, "Balanza analítica 0.1 mg", "5"));

    private final List<Product> catalog = List.of(
            product(100L, "AGI-100", "Agitador magnético con placa calefactora", "10", "850"),
            product(200L, "BAL-200", "Balanza analítica 220 g x 0.1 mg", "3", "4556"));

    @Test
    void unItemSinCoincidenciaNoAfectaALosDemas() {
        gemini.responses.add("""
                [
                  {"rfq_item_id": 11, "matched": true, "product_id": 100, "similarity_score": 0.93, "match_reason": "agitador"},
                  {"rfq_item_id": 12, "matched": false},
                  {"rfq_item_id": 13, "matched": true, "product_id": 200, "similarity_score": 0.88}
                ]""");

        List<StockCheckItemResponse> result = gemini.matchRfqItemsToProducts(items, catalog);

        assertThat(result).extracting(StockCheckItemResponse::isMatched).containsExactly(true, false, true);
        assertThat(result.get(0).getProductSku()).isEqualTo("AGI-100");
        assertThat(result.get(0).getUnitPrice()).isEqualByComparingTo("850");
        assertThat(result.get(1).getRfqDescription()).isEqualTo("Reactor nuclear portátil");
    }

    @Test
    void usaElStockYPrecioRealesDeLaBdNoLosDeGemini() {
        gemini.responses.add("""
                [
                  {"rfq_item_id": 11, "matched": true, "product_id": 100, "similarity_score": 0.9,
                   "product_sku": "INVENTADO", "stock_quantity": 9999, "stock_sufficient": true},
                  {"rfq_item_id": 12, "matched": false},
                  {"rfq_item_id": 13, "matched": true, "product_id": 200, "similarity_score": 0.9, "stock_sufficient": true}
                ]""");

        List<StockCheckItemResponse> result = gemini.matchRfqItemsToProducts(items, catalog);

        assertThat(result.get(0).getProductSku()).isEqualTo("AGI-100");
        assertThat(result.get(0).getStockQuantity()).isEqualByComparingTo("10");
        assertThat(result.get(0).isStockSufficient()).isTrue();            // 10 >= 2
        assertThat(result.get(2).getStockQuantity()).isEqualByComparingTo("3");
        assertThat(result.get(2).isStockSufficient()).isFalse();           // 3 < 5 aunque Gemini diga true
    }

    @Test
    void itemOmitidoProductoInexistenteOSimilitudBajaQuedanSinCoincidenciaSoloEllos() {
        gemini.responses.add("""
                {"items": [
                  {"rfq_item_id": 11, "matched": true, "product_id": 100, "similarity_score": 0.35},
                  {"rfq_item_id": 13, "matched": true, "product_id": 999, "similarity_score": 0.9}
                ]}""");

        List<StockCheckItemResponse> result = gemini.matchRfqItemsToProducts(items, catalog);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(StockCheckItemResponse::getRfqItemId).containsExactly(11L, 12L, 13L);
        assertThat(result).extracting(StockCheckItemResponse::isMatched).containsExactly(false, false, false);
    }

    @Test
    void reintentaCuandoSeAgotaLaCuotaYLuegoResponde() {
        gemini.responses.add(QUOTA);
        gemini.responses.add("""
                [{"rfq_item_id": 11, "matched": true, "product_id": 100, "similarity_score": 0.9},
                 {"rfq_item_id": 12, "matched": false},
                 {"rfq_item_id": 13, "matched": false}]""");

        List<StockCheckItemResponse> result = gemini.matchRfqItemsToProducts(items, catalog);

        assertThat(gemini.calls).isEqualTo(2);
        assertThat(gemini.sleeps).containsExactly(GeminiService.STOCK_MATCH_BACKOFF_MS[0]);
        assertThat(result.get(0).isMatched()).isTrue();
    }

    @Test
    void siLaCuotaSigueAgotadaFallaEnVezDeDecirQueNingunProductoExiste() {
        for (int i = 0; i <= GeminiService.STOCK_MATCH_BACKOFF_MS.length; i++) gemini.responses.add(QUOTA);

        assertThatThrownBy(() -> gemini.matchRfqItemsToProducts(items, catalog))
                .isInstanceOf(StockMatchException.class)
                .hasMessageContaining("límite de uso");
        assertThat(gemini.calls).isEqualTo(GeminiService.STOCK_MATCH_BACKOFF_MS.length + 1);
    }

    @Test
    void errorNoRecuperableNoSeReintentaYFalla() {
        gemini.responses.add(new IllegalArgumentException("Response has been blocked by safety filters"));

        assertThatThrownBy(() -> gemini.matchRfqItemsToProducts(items, catalog))
                .isInstanceOf(StockMatchException.class);
        assertThat(gemini.calls).isEqualTo(1);
        assertThat(gemini.sleeps).isEmpty();
    }

    @Test
    void respuestaSinFormatoEsperadoFalla() {
        gemini.responses.add("lo siento, no puedo ayudar");

        assertThatThrownBy(() -> gemini.matchRfqItemsToProducts(items, catalog))
                .isInstanceOf(StockMatchException.class)
                .hasMessageContaining("formato esperado");
    }

    private static RfqItem item(Long id, String description, String qty) {
        RfqItem i = new RfqItem();
        i.setId(id);
        i.setProductDescription(description);
        i.setQuantity(new BigDecimal(qty));
        i.setUnit("unidad");
        return i;
    }

    private static Product product(Long id, String sku, String name, String stock, String price) {
        Product p = new Product();
        p.setId(id);
        p.setSku(sku);
        p.setName(name);
        p.setStockQuantity(new BigDecimal(stock));
        p.setUnitPrice(new BigDecimal(price));
        p.setUnit("unidad");
        return p;
    }
}
