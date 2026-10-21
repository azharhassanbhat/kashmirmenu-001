package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;

/** What a settlement takes for one guest's orders. A name and an amount, never their items. */
public record SettlementGuestLine(String displayName, boolean you, BigDecimal amount) {
}
