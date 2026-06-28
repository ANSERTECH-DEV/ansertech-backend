package com.ansertech.service.rfq;

import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqStockCheckResult;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.StockCheckStatus;
import com.ansertech.dto.response.StockCheckItemResponse;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.RfqRepository;
import com.ansertech.repository.RfqStockCheckResultRepository;
import com.ansertech.service.ai.GeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RfqStockCheckQueueService {

    private final RfqRepository rfqRepository;
    private final ProductRepository productRepository;
    private final GeminiService geminiService;
    private final RfqStockCheckResultRepository stockCheckResultRepository;
    private final ObjectMapper objectMapper;

    /**
     * Envía el RFQ al executor de hilo único para stock-check automático.
     * Debe llamarse desde otra clase para que Spring AOP aplique @Async correctamente.
     */
    @Async("stockCheckExecutor")
    @Transactional
    public void processRfq(Long rfqId) {
        log.info("[StockCheckQueue] Iniciando stock-check automático para RFQ {}", rfqId);

        Rfq rfq = rfqRepository.findById(rfqId).orElse(null);
        if (rfq == null) {
            log.error("[StockCheckQueue] RFQ {} no encontrado — abortando", rfqId);
            return;
        }

        RfqStockCheckResult result = stockCheckResultRepository.findByRfqId(rfqId)
                .orElseGet(() -> RfqStockCheckResult.builder()
                        .rfqId(rfqId)
                        .status(StockCheckStatus.PROCESSING)
                        .build());

        try {
            var products = productRepository.findAllActive();
            List<StockCheckItemResponse> items = geminiService.matchRfqItemsToProducts(rfq.getItems(), products);

            String itemsJson = objectMapper.writeValueAsString(items);

            result.setItemsJson(itemsJson);
            result.setStatus(StockCheckStatus.DONE);
            result.setProcessedAt(LocalDateTime.now());
            result.setErrorMessage(null);
            stockCheckResultRepository.save(result);

            rfq.setStatus(RfqStatus.PENDING_REVIEW);
            rfqRepository.save(rfq);

            log.info("[StockCheckQueue] RFQ {} completado — {} ítems, estado → PENDING_REVIEW", rfqId, items.size());

        } catch (Exception e) {
            log.error("[StockCheckQueue] Error en stock-check de RFQ {}: {}", rfqId, e.getMessage(), e);

            result.setStatus(StockCheckStatus.FAILED);
            result.setProcessedAt(LocalDateTime.now());
            result.setErrorMessage(e.getMessage());
            stockCheckResultRepository.save(result);

            // RFQ permanece en PROCESSING para que el operador pueda reintentar
        }
    }
}
