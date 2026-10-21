package com.kashmirmenu.app.service;

import com.kashmirmenu.app.analytics.AnalyticsOverview;
import com.kashmirmenu.app.analytics.AnalyticsQuery;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.response.PaymentMetaResponse;
import com.kashmirmenu.app.dto.response.PaymentTransactionDetailResponse;
import com.kashmirmenu.app.dto.response.PaymentTransactionResponse;

import java.io.IOException;
import java.io.Writer;

public interface PaymentAnalyticsService {

    PaymentMetaResponse meta();

    AnalyticsOverview overview(AnalyticsQuery query);

    PageResponse<PaymentTransactionResponse> transactions(
            AnalyticsQuery query, String search, int page, int size, String sortBy, String direction);

    PaymentTransactionDetailResponse transaction(Long paymentId);

    /** Writes the filtered transactions as CSV (header row included). Returns the number of data rows. */
    int exportCsv(AnalyticsQuery query, String search, Writer out) throws IOException;
}
