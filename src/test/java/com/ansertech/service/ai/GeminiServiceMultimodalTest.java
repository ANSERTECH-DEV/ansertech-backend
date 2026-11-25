package com.ansertech.service.ai;

import com.ansertech.service.ai.GeminiService.FilePart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Clasificación y extracción con adjuntos, con Vertex AI simulado. */
class GeminiServiceMultimodalTest {

    static class FakeGemini extends GeminiService {
        final Deque<Object> responses = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();       // "text" o "files:<n>"
        final List<String> prompts = new ArrayList<>();
        final List<Long> sleeps = new ArrayList<>();

        FakeGemini() { super(null, new ObjectMapper()); }

        private String next() {
            Object r = responses.poll();
            if (r instanceof RuntimeException e) throw e;
            return (String) r;
        }

        @Override protected String generateJson(String prompt) { calls.add("text"); prompts.add(prompt); return next(); }

        @Override protected String generateJsonWithFiles(List<FilePart> files, String prompt) throws IOException {
            calls.add("files:" + files.size()); prompts.add(prompt); return next();
        }

        @Override protected void sleep(long millis) { sleeps.add(millis); }
    }

    private static final RuntimeException QUOTA = new RuntimeException("io.grpc.StatusRuntimeException: RESOURCE_EXHAUSTED");
    private static final FilePart PDF = new FilePart("SC_02.pdf", "application/pdf", new byte[]{37, 80, 68, 70});
    private static final FilePart IMG = new FilePart("foto.jpg", "image/jpeg", new byte[]{1});

    private final FakeGemini gemini = new FakeGemini();

    @Test
    void laClasificacionEnviaLosAdjuntosYLosNombraEnElPrompt() {
        gemini.responses.add("{\"classification\":\"VALID_RFQ\",\"confidence\":0.96,\"reason\":\"PDF con productos\"}");

        JsonNode r = gemini.classifyEmail("cliente@x.pe", "Pedido de cotización", "Ayuda con la solicitud", List.of(PDF));

        assertThat(gemini.calls).containsExactly("files:1");
        assertThat(gemini.prompts.get(0)).contains("Adjuntos: SC_02.pdf (application/pdf)");
        assertThat(r.path("classification").asText()).isEqualTo("VALID_RFQ");
    }

    @Test
    void laClasificacionSinAdjuntosUsaSoloTexto() {
        gemini.responses.add("{\"classification\":\"SPAM\",\"confidence\":0.99}");

        gemini.classifyEmail("a@b.pe", "Oferta", "Compre ya", List.of());

        assertThat(gemini.calls).containsExactly("text");
        assertThat(gemini.prompts.get(0)).contains("Adjuntos: ninguno");
    }

    @Test
    void laClasificacionReintentaAnteCuotaYSiSigueFallandoDevuelveError() {
        for (int i = 0; i <= GeminiService.STOCK_MATCH_BACKOFF_MS.length; i++) gemini.responses.add(QUOTA);

        JsonNode r = gemini.classifyEmail("a@b.pe", "x", "y", List.of(PDF));

        assertThat(r.has("error")).isTrue();
        assertThat(gemini.calls).hasSize(GeminiService.STOCK_MATCH_BACKOFF_MS.length + 1);
    }

    @Test
    void laExtraccionEnviaTodosLosArchivosEnUnaSolaPeticion() {
        gemini.responses.add("{\"items\":[{\"product_description\":\"Agitador\",\"quantity\":2}],\"overall_confidence\":0.9}");

        JsonNode r = gemini.extractRfqFromFiles(List.of(PDF, IMG), "De: cliente\nAsunto: Pedido");

        assertThat(gemini.calls).containsExactly("files:2");
        assertThat(gemini.prompts.get(0)).contains("SC_02.pdf").contains("foto.jpg");
        assertThat(r.path("items").size()).isEqualTo(1);
    }

    @Test
    void siGeminiRechazaElArchivoSeExtraeSoloDelTexto() {
        gemini.responses.add(new IllegalArgumentException("INVALID_ARGUMENT: unsupported file"));
        gemini.responses.add("{\"items\":[],\"overall_confidence\":0.4}");

        JsonNode r = gemini.extractRfqFromFiles(List.of(PDF), "texto del correo");

        assertThat(gemini.calls).containsExactly("files:1", "text");
        assertThat(r.has("error")).isFalse();
    }

    @Test
    void siLaCuotaSigueAgotadaNoInsisteConTextoYDevuelveError() {
        for (int i = 0; i <= GeminiService.STOCK_MATCH_BACKOFF_MS.length; i++) gemini.responses.add(QUOTA);

        JsonNode r = gemini.extractRfqFromFiles(List.of(PDF), "texto");

        assertThat(r.has("error")).isTrue();
        assertThat(gemini.calls).doesNotContain("text");
    }
}
