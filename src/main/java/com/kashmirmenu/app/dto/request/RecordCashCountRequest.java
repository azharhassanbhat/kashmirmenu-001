package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordCashCountRequest(
        @NotNull LocalDate date,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal countedCash,
        @Size(max = 500) String note) {
}
