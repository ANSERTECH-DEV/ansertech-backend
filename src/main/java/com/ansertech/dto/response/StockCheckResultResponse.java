package com.ansertech.dto.response;

import com.ansertech.domain.enums.StockCheckStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class StockCheckResultResponse {

    private Long rfqId;
    private StockCheckStatus status;       // PROCESSING | DONE | FAILED
    private List<StockCheckItemResponse> items;  // null si aún está PROCESSING
    private LocalDateTime processedAt;
    private String errorMessage;
}
