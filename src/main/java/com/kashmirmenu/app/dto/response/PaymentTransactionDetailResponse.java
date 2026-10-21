package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Everything the transaction drawer shows: the payment, its refunds, and the rest of the same order. */
public record PaymentTransactionDetailResponse(
        PaymentTransactionResponse transaction,
        BigDecimal orderTotal,
        String tableName,
        List<Refund> refunds,
        List<PaymentTransactionResponse> otherPaymentsOnOrder) {

    public record Refund(Long id, BigDecimal amount, String reason, LocalDateTime refundedAt, String refundedBy) {
    }
}
