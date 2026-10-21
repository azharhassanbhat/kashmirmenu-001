package com.kashmirmenu.app.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ApplyCouponResponse {

    private String couponCode;

    private BigDecimal orderAmount;

    private BigDecimal discountAmount;

    private BigDecimal finalAmount;
}