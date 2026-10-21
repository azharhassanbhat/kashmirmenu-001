package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.PaymentStatus;

import java.math.BigDecimal;

/** One order a settlement covers, for staff: which order, whose, how much, and where its payment stands. */
public record SettlementOrderLine(
        String orderNumber,
        String guestName,
        BigDecimal amount,
        PaymentStatus paymentStatus) {
}
