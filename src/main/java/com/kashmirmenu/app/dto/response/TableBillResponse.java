package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * "Table bill": what the whole visit still owes, broken down by guest. {@code due} is the amount one
 * guest could pay to cover everyone. {@code paid} is a table-level total only; who paid it, and how,
 * is not shown to other guests.
 */
public record TableBillResponse(
        String visitId,
        String tableName,
        List<TableBillGuestResponse> guests,
        BigDecimal due,
        BigDecimal inProgress,
        BigDecimal paid,
        BigDecimal awaitingConfirmation) {
}
