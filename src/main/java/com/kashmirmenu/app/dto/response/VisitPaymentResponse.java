package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.enums.SettlementKind;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment record against one order of the visit. This is the unit money is counted in: a combined
 * payment appears as one row per order it covered, so adding up the rows never counts anything twice.
 * {@code settlementKind} says whether it was a guest paying their own bill (SEPARATE), one guest paying
 * for the table (COMBINED), or null for a payment staff recorded at the counter.
 */
public record VisitPaymentResponse(
        Long id,
        String orderNumber,
        String guestName,
        PaymentMethod method,
        PaymentProvider provider,
        BigDecimal amount,
        PaymentStatus status,
        SettlementKind settlementKind,
        String settlementCode,
        String transactionId,
        LocalDateTime at) {
}
