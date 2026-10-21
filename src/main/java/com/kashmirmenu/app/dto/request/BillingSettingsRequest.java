package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A restaurant's service charge and tax. Rates are percentages with at most two decimals. There are
 * no defaults here on purpose: nothing is assumed about which taxes apply to a restaurant.
 */
public record BillingSettingsRequest(
        @NotBlank(message = "Give the tax a name, for example GST.")
        @Size(max = 30, message = "The tax name can be up to 30 characters.") String taxName,
        @NotNull(message = "Enter a tax rate (0 for none).")
        @DecimalMin(value = "0", message = "The tax rate cannot be negative.")
        @DecimalMax(value = "100", message = "The tax rate cannot be more than 100.")
        @Digits(integer = 3, fraction = 2, message = "Use at most two decimal places.") BigDecimal taxRate,
        @NotNull(message = "Enter a service charge rate (0 for none).")
        @DecimalMin(value = "0", message = "The service charge cannot be negative.")
        @DecimalMax(value = "100", message = "The service charge cannot be more than 100.")
        @Digits(integer = 3, fraction = 2, message = "Use at most two decimal places.") BigDecimal serviceChargeRate,
        @NotNull(message = "Say whether your menu prices already include tax.") Boolean pricesIncludeTax,
        @Size(max = 60, message = "The tax registration number is too long.") String taxRegistrationNumber) {
}
