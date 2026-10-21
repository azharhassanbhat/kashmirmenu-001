package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ApplyCouponRequest {

    @NotNull
    private Long restaurantId;

    @NotBlank
    private String couponCode;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal orderAmount;
}