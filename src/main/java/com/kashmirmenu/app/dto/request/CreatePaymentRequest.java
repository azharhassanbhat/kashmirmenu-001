package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreatePaymentRequest {

    @NotNull
    private PaymentMethod paymentMethod;

}