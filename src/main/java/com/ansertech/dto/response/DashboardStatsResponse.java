package com.ansertech.dto.response;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class DashboardStatsResponse {
    private long totalEmails;
    private long spamEmails;
    private long validEmails;
    private long uncertainEmails;
    private long totalRfqs;
    private long processingRfqs;
    private long pendingRfqs;
    private long quotingRfqs;
    private long quotedRfqs;
    private long totalQuotations;
    private long draftQuotations;
    private long sentQuotations;
    private long acceptedQuotations;
    private long lowStockProducts;
    private double conversionRate;
}
