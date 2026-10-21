package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.TablePayState;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** One guest at a table: when they joined, when they last acted, their orders, and what they owe. */
public record VisitGuestResponse(
        Long id,
        String displayName,
        LocalDateTime joinedAt,
        LocalDateTime lastSeenAt,
        BigDecimal due,
        BigDecimal inProgress,
        BigDecimal paid,
        BigDecimal awaitingConfirmation,
        TablePayState payState,
        List<VisitOrderResponse> orders) {
}
