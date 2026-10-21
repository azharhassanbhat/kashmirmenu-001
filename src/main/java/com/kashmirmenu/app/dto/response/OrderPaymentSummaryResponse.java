package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Where an order stands: what it costs, what was received (net of refunds), what is pending, and what
 * is still due, plus every payment component (a split bill has several).
 */
public record OrderPaymentSummaryResponse(
        Long orderId,
        String orderNumber,
        BigDecimal orderTotal,
        BigDecimal settled,
        BigDecimal pending,
        BigDecimal outstanding,
        PaymentStatus status,
        List<PaymentResponse> payments) {
}
