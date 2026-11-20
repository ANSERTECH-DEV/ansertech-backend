package com.ansertech.service.ai;

import com.ansertech.domain.entity.Product;
import com.ansertech.domain.entity.RfqItem;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.*;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final VertexAI vertexAI;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${vertex-ai.model}")
    private String modelName;

    private static final String CLASSIFICATION_PROMPT = """
            Eres un asistente especializado en clasificar correos electrónicos para una empresa B2B del sector minero llamada Ansertech Perú.

            Analiza el siguiente correo y clasifícalo en una de estas categorías:
            - VALID_RFQ: Es una solicitud de cotización legítima (productos, servicios, reparación, alquiler de equipos mineros/analíticos)
            - SPAM: Correo no deseado, publicidad, phishing, o irrelevante para el negocio
            - UNCERTAIN: No es posible determinar con certeza

            Responde ÚNICAMENTE en formato JSON con esta estructura exacta:
            {
              "classification": "VALID_RFQ | SPAM | UNCERTAIN",
              "confidence": 0.95,
              "reason": "Explicación breve en español",
              "client_name": "nombre detectado o null",
              "client_company": "empresa detectada o null"
            }

            CORREO A ANALIZAR:
            De: %s
            Asunto: %s
            Cuerpo: %s
            """;

    private static final String EXTRACTION_PROMPT = """
            Eres un extractor de información especializado en solicitudes de cotización (RFQ) del sector minero-industrial peruano.

            Extrae TODOS los datos relevantes del siguiente correo/documento. Los campos pueden estar en cualquier formato (tabla, lista, texto libre, español, inglés o mezclado).

            Responde ÚNICAMENTE en formato JSON:
            {
              "rfq_type": "PRODUCT_SALE | REPAIR | RENTAL | MIXED",
              "client_name": "string o null",
              "client_company": "string o null",
              "client_email": "string o null",
              "client_phone": "string o null",
              "urgency": "LOW | MEDIUM | HIGH",
              "items": [
                {
                  "product_code": "string o null",
                  "product_description": "descripción completa",
                  "quantity": 1.0,
                  "unit": "unidad",
                  "service_type": "string o null",
                  "confidence": 0.95
                }
              ],
              "additional_notes": "string o null",
              "overall_confidence": 0.90
            }

            CONTENIDO DEL CORREO/DOCUMENTO:
            %s
            """;


    public JsonNode classifyEmail(String sender, String subject, String body) {
        String prompt = String.format(CLASSIFICATION_PROMPT, sender, subject,
                body != null ? body.substring(0, Math.min(body.length(), 3000)) : "");
        return callGemini(prompt);
    }

    public JsonNode extractRfq(String content) {
        String prompt = String.format(EXTRACTION_PROMPT,
                content.substring(0, Math.min(content.length(), 8000)));
        return callGemini(prompt);
    }

    public JsonNode extractRfqFromBytes(byte[] fileBytes, String mimeType, String additionalText) {
        try {
            GenerativeModel model = new GenerativeModel(modelName, vertexAI);
            String textPrompt = String.format(EXTRACTION_PROMPT,
                    "[Archivo adjunto analizado — ver contenido multimodal]\n" + additionalText);

            List<Part> parts = new ArrayList<>();
            parts.add(Part.newBuilder()
                    .setInlineData(Blob.newBuilder()
                            .setMimeType(mimeType)
                            .setData(ByteString.copyFrom(fileBytes))
                            .build())
                    .build());
            parts.add(Part.newBuilder().setText(textPrompt).build());

            Content content = Content.newBuilder()
                    .setRole("user")
                    .addAllParts(parts)
                    .build();

            GenerateContentResponse response = model.generateContent(List.of(content));
            String rawJson = ResponseHandler.getText(response);
            return parseJson(rawJson);
        } catch (IOException e) {
            log.error("Error en extracción multimodal: {}", e.getMessage());
            return callGemini(String.format(EXTRACTION_PROMPT, additionalText));
        }
    }

    private static final String ANALYSIS_SIMPLE_PROMPT = """
            Eres un asesor comercial de Ansertech Perú S.A.C. especializado en el sector minero-industrial.

            Analiza la siguiente solicitud de cotización junto con los resultados de disponibilidad de inventario
            ya procesados. No necesitas buscar precios — están incluidos en los resultados de inventario.

            RFQ:
            %s

            Resultados de inventario (pre-procesados):
            %s

            Responde ÚNICAMENTE en formato JSON:
            {
              "conversion_probability": 0.75,
              "ai_summary": "Resumen ejecutivo en español (2-3 oraciones sobre el pedido y su viabilidad)"
            }
            """;

    public JsonNode analyzeRfq(String rfqJson, String inventoryJson) {
        String prompt = String.format(ANALYSIS_SIMPLE_PROMPT, rfqJson, inventoryJson);
        return callGemini(prompt);
    }

    private JsonNode callGemini(String prompt) {
        try {
            GenerativeModel model = new GenerativeModel(modelName, vertexAI);
            GenerateContentResponse response = model.generateContent(prompt);
            String rawText = ResponseHandler.getText(response);
            return parseJson(rawText);
        } catch (IOException e) {
            log.error("Error llamando a Gemini: {}", e.getMessage());
            return objectMapper.createObjectNode().put("error", e.getMessage());
        }
    }

    private static final String STOCK_MATCH_PROMPT = """
            Eres un asistente especializado en matching de productos industriales para Ansertech Perú S.A.C.

            Tu tarea: para cada ítem de la RFQ, encuentra el producto del catálogo con mayor similitud semántica.
            Compara la descripción del ítem con el campo "description" (descripción larga) de cada producto del catálogo.
            Entiende sinónimos, abreviaciones, variaciones de redacción técnica en español e inglés, y plurales.

            ÍTEMS SOLICITADOS EN LA RFQ:
            %s

            CATÁLOGO DE PRODUCTOS (id, sku, nombre, descripción completa, stock_actual, unidad):
            %s

            Responde ÚNICAMENTE con un array JSON, un objeto por cada ítem de la RFQ:
            [
              {
                "rfq_item_id": 1,
                "rfq_description": "descripción original del ítem",
                "quantity_requested": 45.0,
                "unit_requested": "unidad",
                "matched": true,
                "product_id": 1,
                "product_sku": "CAM-IP-001",
                "product_name": "Cámara IP industrial",
                "similarity_score": 0.92,
                "match_reason": "Coincidencia por descripción de cámara IP para uso industrial",
                "stock_quantity": 60.0,
                "stock_unit": "unidad",
                "stock_sufficient": true
              }
            ]

            Reglas:
            - Evalúa cada ítem de forma independiente: que un ítem no tenga coincidencia NO afecta a los demás.
            - Si un ítem no tiene coincidencia clara (similitud < 0.4), usa matched=false y omite campos de producto.
            - product_id debe ser el "id" de un producto del catálogo; no inventes productos.
            - stock_sufficient = true cuando stock_quantity >= quantity_requested.
            - similarity_score entre 0.0 y 1.0.
            - Incluye TODOS los ítems de la RFQ en el array de respuesta.
            """;

    /** Esperas antes de cada reintento cuando Vertex AI responde 429/503/timeout (2 reintentos = 3 intentos). */
    static final long[] STOCK_MATCH_BACKOFF_MS = {15_000, 45_000};
    /** Mismo umbral que el prompt: por debajo, el ítem se considera sin coincidencia. */
    static final double MIN_SIMILARITY = 0.4;

    /**
     * Matching semántico de los ítems del RFQ contra el catálogo, en una sola llamada a Gemini.
     * Cada ítem se evalúa por separado: que uno no tenga coincidencia no afecta a los demás.
     * Si Gemini falla (incluso tras reintentar) lanza {@link StockMatchException}; ya NO devuelve
     * "todos sin coincidencia", porque eso se mostraba como si ningún producto existiera en el inventario.
     */
    public List<StockCheckItemResponse> matchRfqItemsToProducts(List<RfqItem> rfqItems, List<Product> products) {
        if (rfqItems == null || rfqItems.isEmpty()) return List.of();
        String prompt;
        try {
            prompt = String.format(STOCK_MATCH_PROMPT, buildRfqItemsJson(rfqItems), buildProductCatalogJson(products));
        } catch (Exception e) {
            throw new StockMatchException("No se pudo preparar la consulta a Gemini: " + e.getMessage(), e);
        }
        String raw = generateStockMatchWithRetry(prompt);
        return parseStockCheckResult(parseJson(raw), rfqItems, products);
    }

    private String generateStockMatchWithRetry(String prompt) {
        for (int attempt = 0; ; attempt++) {
            try {
                return generateJson(prompt);
            } catch (Exception e) {
                boolean retryable = isRetryable(e);
                if (!retryable || attempt >= STOCK_MATCH_BACKOFF_MS.length) {
                    log.error("Error en matching de stock (intento {}): {}", attempt + 1, e.getMessage());
                    throw new StockMatchException(friendlyMessage(e, retryable), e);
                }
                long wait = STOCK_MATCH_BACKOFF_MS[attempt];
                log.warn("Matching de stock: Gemini no disponible ({}). Reintento {} de {} en {} s",
                        e.getMessage(), attempt + 1, STOCK_MATCH_BACKOFF_MS.length, wait / 1000);
                sleep(wait);
            }
        }
    }

    /** Llamada real a Vertex AI pidiendo JSON puro y respuestas deterministas. Protegido para poder simularlo en tests. */
    protected String generateJson(String prompt) throws IOException {
        GenerationConfig config = GenerationConfig.newBuilder()
                .setResponseMimeType("application/json")
                .setTemperature(0f)
                .build();
        GenerativeModel model = new GenerativeModel(modelName, vertexAI).withGenerationConfig(config);
        return ResponseHandler.getText(model.generateContent(prompt));
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new StockMatchException("Matching de stock interrumpido", ie);
        }
    }

    static boolean isRetryable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String text = t.getClass().getSimpleName() + " " + t.getMessage();
            if (text.contains("RESOURCE_EXHAUSTED") || text.contains("ResourceExhausted")
                    || text.contains("UNAVAILABLE") || text.contains("Unavailable")
                    || text.contains("DEADLINE_EXCEEDED") || text.contains("DeadlineExceeded")) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static String friendlyMessage(Throwable e, boolean retryable) {
        String text = String.valueOf(e.getMessage());
        if (text.contains("RESOURCE_EXHAUSTED") || text.contains("ResourceExhausted")) {
            return "Gemini (Vertex AI) alcanzó su límite de uso. Reintenta en unos minutos.";
        }
        if (retryable) {
            return "Gemini (Vertex AI) no está disponible en este momento. Reintenta en unos minutos.";
        }
        return "No se pudo completar el análisis de stock con Gemini: " + text;
    }

    private String buildRfqItemsJson(List<RfqItem> items) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RfqItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          item.getId());
            m.put("description", item.getProductDescription() != null ? item.getProductDescription() : "");
            m.put("quantity",    item.getQuantity() != null ? item.getQuantity().doubleValue() : 1.0);
            m.put("unit",        item.getUnit() != null ? item.getUnit() : "unidad");
            list.add(m);
        }
        return objectMapper.writeValueAsString(list);
    }

    private String buildProductCatalogJson(List<Product> products) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Product p : products) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          p.getId());
            m.put("sku",         p.getSku());
            m.put("name",        p.getName());
            m.put("description", p.getDescription() != null ? p.getDescription() : "");
            m.put("stock",       p.getStockQuantity() != null ? p.getStockQuantity() : BigDecimal.ZERO);
            m.put("unit",        p.getUnit() != null ? p.getUnit() : "unidad");
            list.add(m);
        }
        return objectMapper.writeValueAsString(list);
    }

    /**
     * Arma el resultado ítem por ítem, en el orden del RFQ. Usa el stock, precio, SKU y nombre reales de la BD
     * (no los que escribe Gemini). Un ítem queda sin coincidencia si Gemini no lo devolvió, lo marcó matched=false,
     * su similitud es menor a {@link #MIN_SIMILARITY} o apunta a un product_id que no está en el catálogo.
     */
    List<StockCheckItemResponse> parseStockCheckResult(JsonNode root, List<RfqItem> rfqItems, List<Product> products) {
        JsonNode array = root;
        if (root != null && root.isObject()) {
            for (String field : List.of("items", "results", "matches")) {
                if (root.path(field).isArray()) { array = root.path(field); break; }
            }
        }
        if (array == null || !array.isArray()) {
            throw new StockMatchException("Gemini devolvió una respuesta sin el formato esperado.", null);
        }

        Map<Long, Product> productsById = new java.util.HashMap<>();
        for (Product p : products) productsById.put(p.getId(), p);
        Map<Long, JsonNode> nodesByItemId = new java.util.HashMap<>();
        for (JsonNode node : array) {
            long id = node.path("rfq_item_id").asLong(0);
            if (id > 0) nodesByItemId.putIfAbsent(id, node);
        }

        List<StockCheckItemResponse> result = new ArrayList<>();
        for (int i = 0; i < rfqItems.size(); i++) {
            RfqItem item = rfqItems.get(i);
            JsonNode node = item.getId() != null ? nodesByItemId.get(item.getId()) : null;
            if (node == null && nodesByItemId.isEmpty() && i < array.size()) {
                node = array.get(i);   // Gemini no envió rfq_item_id: se empareja por posición
            }
            result.add(toStockCheckItem(item, node, productsById));
        }
        return result;
    }

    private StockCheckItemResponse toStockCheckItem(RfqItem item, JsonNode node, Map<Long, Product> productsById) {
        double qty = item.getQuantity() != null ? item.getQuantity().doubleValue() : 1.0;
        StockCheckItemResponse.StockCheckItemResponseBuilder builder = StockCheckItemResponse.builder()
                .rfqItemId(item.getId())
                .rfqDescription(item.getProductDescription() != null ? item.getProductDescription() : "")
                .quantityRequested(qty)
                .unitRequested(item.getUnit() != null ? item.getUnit() : "unidad")
                .matched(false);
        if (node == null) {
            log.warn("Matching de stock: Gemini no devolvió el ítem {} ('{}')", item.getId(), item.getProductDescription());
            return builder.build();
        }
        if (!node.path("matched").asBoolean(false)) return builder.build();

        double score = node.path("similarity_score").asDouble(0.0);
        Product product = productsById.get(node.path("product_id").asLong(0));
        if (product == null) {
            log.warn("Matching de stock: Gemini propuso product_id {} para el ítem {}, que no está en el catálogo",
                    node.path("product_id").asText(), item.getId());
            return builder.build();
        }
        if (score < MIN_SIMILARITY) return builder.build();

        BigDecimal stock = product.getStockQuantity() != null ? product.getStockQuantity() : BigDecimal.ZERO;
        return builder
                .matched(true)
                .productId(product.getId())
                .productSku(product.getSku())
                .productName(product.getName())
                .similarityScore(Math.min(score, 1.0))
                .matchReason(node.path("match_reason").asText(""))
                .stockQuantity(stock)
                .stockUnit(product.getUnit() != null ? product.getUnit() : "unidad")
                .stockSufficient(stock.compareTo(BigDecimal.valueOf(qty)) >= 0)
                .unitPrice(product.getUnitPrice())
                .build();
    }

    private JsonNode parseJson(String raw) {
        try {
            // Extraer bloque JSON si Gemini envuelve en markdown ```json ... ```
            Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
            Matcher matcher = pattern.matcher(raw);
            String jsonStr = matcher.find() ? matcher.group(1).trim() : raw.trim();
            return objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            log.warn("JSON malformado de Gemini, retornando nodo de error. Raw: {}", raw);
            return objectMapper.createObjectNode()
                    .put("error", "json_parse_failed")
                    .put("raw", raw);
        }
    }
}
