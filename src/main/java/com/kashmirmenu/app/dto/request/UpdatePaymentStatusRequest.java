package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.CardType;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdatePaymentStatusRequest {

    /** SUCCESS, FAILED or CANCELLED. Refunds go through the refund endpoint. */
    @NotNull
    private PaymentStatus paymentStatus;

    @Size(max = 100)
    private String transactionId;

    /** Captured when confirming the payment, so the UPI app / wallet is on record from the start. */
    private PaymentProvider paymentProvider;

    private CardType cardType;
}
