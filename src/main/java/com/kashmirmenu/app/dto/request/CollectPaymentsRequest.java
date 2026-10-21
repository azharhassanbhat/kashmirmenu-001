package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.CardType;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Staff recording money actually received at the counter, as one or more components (a split bill).
 * This is deliberately a separate type from the diner's {@link CreatePaymentRequest}: only staff
 * endpoints accept an amount, a provider or "already paid".
 */
public record CollectPaymentsRequest(
        @NotEmpty @Size(max = 10) List<@Valid Component> payments,
        /** Optional. Sending the same key again returns the original result instead of recording twice. */
        @Size(max = 80) String idempotencyKey) {

    public record Component(
            @NotNull PaymentMethod paymentMethod,
            /** UPI app or wallet; leave empty when it is not known. Must belong to the method. */
            PaymentProvider paymentProvider,
            CardType cardType,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @Size(max = 100) String transactionId) {
    }
}
