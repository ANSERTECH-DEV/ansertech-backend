package com.ansertech.service.rfq;

import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqStockCheckResult;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.StockCheckStatus;
import com.ansertech.repository.RfqRepository;
import com.ansertech.repository.RfqStockCheckResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Al arrancar el backend, vuelve a encolar el análisis de stock de los RFQ que quedaron en "Procesando":
 * la cola es en memoria y se pierde al reiniciar o cuando Cloud Run apaga la instancia (#18 b).
 * No toca los que terminaron en FAILED: esos los reintenta el operador.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RfqRecoveryService {

    private final RfqRepository rfqRepository;
    private final RfqStockCheckResultRepository stockCheckResultRepository;
    private final RfqStockCheckQueueService stockCheckQueueService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            int n = requeueStuckStockChecks();
            if (n > 0) log.warn("[Recovery] {} RFQ en 'Procesando' reencolados para el análisis de stock", n);
        } catch (Exception e) {
            log.error("[Recovery] No se pudieron recuperar los RFQ pendientes: {}", e.getMessage());
        }
    }

    public int requeueStuckStockChecks() {
        List<Rfq> processing = rfqRepository.findAllByStatus(RfqStatus.PROCESSING);
        int count = 0;
        for (Rfq rfq : processing) {
            Optional<RfqStockCheckResult> result = stockCheckResultRepository.findByRfqId(rfq.getId());
            boolean stuck = result.isEmpty() || result.get().getStatus() == StockCheckStatus.PROCESSING;
            if (stuck) {
                stockCheckQueueService.processRfq(rfq.getId());
                count++;
            }
        }
        return count;
    }
}
