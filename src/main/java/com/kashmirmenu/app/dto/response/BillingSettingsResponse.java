package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;

public record BillingSettingsResponse(
        String taxName,
        BigDecimal taxRate,
        BigDecimal serviceChargeRate,
        boolean pricesIncludeTax,
        String taxRegistrationNumber) {
}
