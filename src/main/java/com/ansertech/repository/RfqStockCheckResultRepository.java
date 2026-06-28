package com.ansertech.repository;

import com.ansertech.domain.entity.RfqStockCheckResult;
import com.ansertech.domain.enums.StockCheckStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RfqStockCheckResultRepository extends JpaRepository<RfqStockCheckResult, Long> {

    Optional<RfqStockCheckResult> findByRfqId(Long rfqId);

    boolean existsByRfqIdAndStatus(Long rfqId, StockCheckStatus status);
}
