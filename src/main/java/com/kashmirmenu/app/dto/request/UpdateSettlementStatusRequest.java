package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Staff confirming that a settlement's money arrived, or that it did not. Applies to every payment in it. */
public record UpdateSettlementStatusRequest(
        /** SUCCESS, FAILED or CANCELLED. */
        @NotNull PaymentStatus paymentStatus,
        /** One reference for the whole settlement (a UPI or card receipt), copied onto each payment. */
        @Size(max = 100) String transactionId) {
}
