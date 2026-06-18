package com.ansertech.service.ai;

import com.ansertech.domain.entity.Product;
import com.ansertech.domain.entity.RfqItem;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.ansertech.service.inventory.InventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.*;
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
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
    private final InventoryService inventoryService;
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

    private static final String ANALYSIS_PROMPT = """
            Eres un asistente de cotización experto en el sector minero-industrial peruano.

            Basándote en los datos del RFQ y los resultados de inventario, genera:
            1. Ítems de cotización con precios sugeridos
            2. Predicción de probabilidad de conversión (0.0 a 1.0)
            3. Alertas si hay productos sin stock o stock bajo
            4. Recomendaciones comerciales

            INSTRUCCIONES IMPORTANTES SOBRE PRECIOS:
            - Para cada ítem del RFQ, DEBES llamar a check_inventory con el nombre probable del producto.
            - Usa el campo "unit_price" retornado por check_inventory como el "unit_price" del ítem.
            - Calcula "subtotal" = quantity * unit_price.
            - Si check_inventory devuelve found=false, usa availability_status "ON_ORDER" y unit_price 0.
            - NUNCA pongas unit_price en 0 si check_inventory encontró el producto con precio.
            - Usa palabras clave cortas al llamar check_inventory (ej: "cámara IP", no la frase completa).

            RFQ:
            %s

            Resultados de inventario (pre-búsqueda, puede estar incompleto):
            %s

            Responde ÚNICAMENTE en formato JSON:
            {
              "quotation_items": [
                {
                  "product_description": "descripción",
                  "quantity": 1.0,
                  "unit": "unidad",
                  "unit_price": 0.0,
                  "subtotal": 0.0,
                  "availability_status": "IN_STOCK | OUT_OF_STOCK | ON_ORDER"
                }
              ],
              "conversion_probability": 0.75,
              "conversion_factors": ["factor1"],
              "alerts": [{"type": "LOW_STOCK", "product": "...", "current_stock": 2}],
              "recommendations": ["recomendación 1"],
              "ai_summary": "Resumen ejecutivo en español"
            }
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

    public JsonNode analyzeAndGenerateQuotation(String rfqJson, String inventoryResultsJson) {
        String prompt = String.format(ANALYSIS_PROMPT, rfqJson, inventoryResultsJson);
        return callGeminiWithToolCalling(prompt);
    }

    // Tool Calling loop: Gemini puede llamar check_inventory hasta 5 veces
    private JsonNode callGeminiWithToolCalling(String prompt) {
        try {
            FunctionDeclaration checkInventoryTool = FunctionDeclaration.newBuilder()
                    .setName("check_inventory")
                    .setDescription("Verifica disponibilidad y stock de un producto en el inventario de Ansertech")
                    .setParameters(Schema.newBuilder()
                            .setType(Type.OBJECT)
                            .putProperties("product_description", Schema.newBuilder()
                                    .setType(Type.STRING)
                                    .setDescription("Descripción o nombre del producto")
                                    .build())
                            .putProperties("quantity_needed", Schema.newBuilder()
                                    .setType(Type.NUMBER)
                                    .setDescription("Cantidad requerida")
                                    .build())
                            .addRequired("product_description")
                            .build())
                    .build();

            Tool tool = Tool.newBuilder().addFunctionDeclarations(checkInventoryTool).build();

            GenerativeModel model = new GenerativeModel(modelName, vertexAI)
                    .withTools(List.of(tool));

            List<Content> history = new ArrayList<>();
            history.add(ContentMaker.fromString(prompt));

            int maxIterations = 5;
            for (int i = 0; i < maxIterations; i++) {
                GenerateContentResponse response = model.generateContent(history);
                Candidate candidate = response.getCandidates(0);
                Content modelContent = candidate.getContent();
                history.add(modelContent);

                boolean hasFunctionCall = modelContent.getPartsList().stream()
                        .anyMatch(Part::hasFunctionCall);

                if (!hasFunctionCall) {
                    String text = ResponseHandler.getText(response);
                    return parseJson(text);
                }

                List<Part> toolResults = new ArrayList<>();
                for (Part part : modelContent.getPartsList()) {
                    if (part.hasFunctionCall()) {
                        FunctionCall fc = part.getFunctionCall();
                        String result = executeToolCall(fc);
                        toolResults.add(Part.newBuilder()
                                .setFunctionResponse(FunctionResponse.newBuilder()
                                        .setName(fc.getName())
                                        .setResponse(Struct.newBuilder()
                                                .putFields("result", Value.newBuilder()
                                                        .setStringValue(result).build())
                                                .build())
                                        .build())
                                .build());
                    }
                }
                history.add(Content.newBuilder().setRole("tool").addAllParts(toolResults).build());
            }

            return callGemini(prompt);
        } catch (Exception e) {
            log.error("Error en tool calling: {}", e.getMessage());
            return callGemini(prompt);
        }
    }

    private String executeToolCall(FunctionCall fc) {
        try {
            if ("check_inventory".equals(fc.getName())) {
                Map<String, Value> args = fc.getArgs().getFieldsMap();
                String description = args.getOrDefault("product_description",
                        Value.newBuilder().setStringValue("").build()).getStringValue();
                double qty = args.getOrDefault("quantity_needed",
                        Value.newBuilder().setNumberValue(1).build()).getNumberValue();
                return inventoryService.checkAvailabilityJson(description, qty);
            }
        } catch (Exception e) {
            log.warn("Error ejecutando tool {}: {}", fc.getName(), e.getMessage());
        }
        return "{\"error\": \"tool_execution_failed\"}";
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
            - Si un ítem no tiene coincidencia clara (similitud < 0.4), usa matched=false y omite campos de producto.
            - stock_sufficient = true cuando stock_quantity >= quantity_requested.
            - similarity_score entre 0.0 y 1.0.
            - Incluye TODOS los ítems de la RFQ en el array de respuesta.
            """;

    public List<StockCheckItemResponse> matchRfqItemsToProducts(List<RfqItem> rfqItems, List<Product> products) {
        try {
            String rfqItemsJson = buildRfqItemsJson(rfqItems);
            String catalogJson  = buildProductCatalogJson(products);
            String prompt = String.format(STOCK_MATCH_PROMPT, rfqItemsJson, catalogJson);

            JsonNode result = callGemini(prompt);
            return parseStockCheckResult(result, rfqItems);
        } catch (Exception e) {
            log.error("Error en matching de stock: {}", e.getMessage());
            return buildFallbackStockCheck(rfqItems);
        }
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

    private List<StockCheckItemResponse> parseStockCheckResult(JsonNode root, List<RfqItem> rfqItems) {
        List<StockCheckItemResponse> result = new ArrayList<>();
        if (!root.isArray()) return buildFallbackStockCheck(rfqItems);
        for (JsonNode node : root) {
            boolean matched = node.path("matched").asBoolean(false);
            StockCheckItemResponse.StockCheckItemResponseBuilder builder = StockCheckItemResponse.builder()
                    .rfqItemId(node.path("rfq_item_id").asLong(0))
                    .rfqDescription(node.path("rfq_description").asText(""))
                    .quantityRequested(node.path("quantity_requested").asDouble(1.0))
                    .unitRequested(node.path("unit_requested").asText("unidad"))
                    .matched(matched);
            if (matched) {
                builder
                    .productId(node.path("product_id").asLong(0))
                    .productSku(node.path("product_sku").asText(""))
                    .productName(node.path("product_name").asText(""))
                    .similarityScore(node.path("similarity_score").asDouble(0.0))
                    .matchReason(node.path("match_reason").asText(""))
                    .stockQuantity(BigDecimal.valueOf(node.path("stock_quantity").asDouble(0.0)))
                    .stockUnit(node.path("stock_unit").asText("unidad"))
                    .stockSufficient(node.path("stock_sufficient").asBoolean(false));
            }
            result.add(builder.build());
        }
        return result;
    }

    private List<StockCheckItemResponse> buildFallbackStockCheck(List<RfqItem> rfqItems) {
        List<StockCheckItemResponse> result = new ArrayList<>();
        for (RfqItem item : rfqItems) {
            result.add(StockCheckItemResponse.builder()
                    .rfqItemId(item.getId())
                    .rfqDescription(item.getProductDescription() != null ? item.getProductDescription() : "")
                    .quantityRequested(item.getQuantity() != null ? item.getQuantity().doubleValue() : 1.0)
                    .unitRequested(item.getUnit() != null ? item.getUnit() : "unidad")
                    .matched(false)
                    .build());
        }
        return result;
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
