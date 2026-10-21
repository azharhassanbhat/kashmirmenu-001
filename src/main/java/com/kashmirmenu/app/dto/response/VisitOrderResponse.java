package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.TablePayState;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** One order within a visit, with how far it has been paid. A cancelled order is shown but owes nothing. */
public record VisitOrderResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        LocalDateTime placedAt,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal inProgress,
        BigDecimal due,
        BigDecimal awaitingConfirmation,
        TablePayState payState,
        List<BillItemResponse> items) {
}
