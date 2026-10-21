package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.SettlementKind;
import jakarta.validation.constraints.NotNull;

/**
 * A guest choosing how to pay. Both fields are explicit choices: nothing is combined just because
 * guests share a table, and the guest names no amounts, orders or other guests. The server works out
 * what is owed.
 */
public record CreateSettlementRequest(
        /** SEPARATE: my own orders. COMBINED: the whole table's outstanding orders. */
        @NotNull(message = "Choose Pay My Bill or Pay Entire Table Bill.") SettlementKind kind,
        @NotNull(message = "Choose a payment method.") PaymentMethod paymentMethod) {
}
