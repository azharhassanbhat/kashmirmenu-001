package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.RecordCashCountRequest;
import com.kashmirmenu.app.dto.response.ReconciliationResponse;

import java.time.LocalDate;

public interface ReconciliationService {

    ReconciliationResponse forDate(LocalDate date);

    /** Records a physical cash count; the expected amount is computed here, never taken from the client. */
    ReconciliationResponse recordCount(RecordCashCountRequest request);

    /** Marks the day's collected digital payments as settled into the bank (manual: there is no bank feed). */
    ReconciliationResponse markDigitalSettled(LocalDate date);
}
