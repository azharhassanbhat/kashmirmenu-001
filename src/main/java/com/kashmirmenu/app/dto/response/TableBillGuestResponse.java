package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;

/**
 * One guest's line on the table bill. Name and amounts only: no items, no order numbers, and nothing
 * about how or when anyone paid.
 */
public record TableBillGuestResponse(
        String displayName,
        boolean you,
        BigDecimal due,
        BigDecimal inProgress,
        BigDecimal awaitingConfirmation) {
}
