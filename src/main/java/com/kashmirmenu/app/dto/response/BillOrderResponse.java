package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One of the calling guest's own orders on their bill. {@code due} is what can be paid now;
 * {@code payable} says whether anything on this order can be paid now at all.
 */
public record BillOrderResponse(
        String orderNumber,
        String publicToken,
        OrderStatus status,
        LocalDateTime placedAt,
        /* How the total was worked out, all computed by the server when the order was placed. */
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal serviceCharge,
        BigDecimal discount,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal inProgress,
        BigDecimal due,
        BigDecimal awaitingConfirmation,
        PaymentStatus paymentStatus,
        boolean payable,
        List<BillItemResponse> items) {
}
