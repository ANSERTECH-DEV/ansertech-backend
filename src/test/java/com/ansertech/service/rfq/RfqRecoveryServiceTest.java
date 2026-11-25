package com.ansertech.service.rfq;

import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqStockCheckResult;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.enums.StockCheckStatus;
import com.ansertech.repository.RfqRepository;
import com.ansertech.repository.RfqStockCheckResultRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RfqRecoveryServiceTest {

    private final RfqRepository rfqRepository = mock(RfqRepository.class);
    private final RfqStockCheckResultRepository results = mock(RfqStockCheckResultRepository.class);
    private final RfqStockCheckQueueService queue = mock(RfqStockCheckQueueService.class);
    private final RfqRecoveryService service = new RfqRecoveryService(rfqRepository, results, queue);

    @Test
    void reencolaLosRfqEnProcesandoSinResultadoOConResultadoEnCursoPeroNoLosFallidos() {
        when(rfqRepository.findAllByStatus(RfqStatus.PROCESSING)).thenReturn(List.of(
                Rfq.builder().id(1L).build(), Rfq.builder().id(2L).build(), Rfq.builder().id(3L).build()));
        when(results.findByRfqId(1L)).thenReturn(Optional.empty());
        when(results.findByRfqId(2L)).thenReturn(Optional.of(RfqStockCheckResult.builder().status(StockCheckStatus.PROCESSING).build()));
        when(results.findByRfqId(3L)).thenReturn(Optional.of(RfqStockCheckResult.builder().status(StockCheckStatus.FAILED).build()));

        int n = service.requeueStuckStockChecks();

        assertThat(n).isEqualTo(2);
        verify(queue).processRfq(1L);
        verify(queue).processRfq(2L);
        verify(queue, never()).processRfq(3L);
    }
}
