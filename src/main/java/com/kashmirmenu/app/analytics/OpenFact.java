package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;

import java.math.BigDecimal;

/** Payments that did not (yet) bring money in: pending, failed, cancelled. Never counted as revenue. */
public record OpenFact(
        PaymentStatus status,
        PaymentMethod method,
        PaymentProvider provider,
        Long count,
        BigDecimal amount) {
}
