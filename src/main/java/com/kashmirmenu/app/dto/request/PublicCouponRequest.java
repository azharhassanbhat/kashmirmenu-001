package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicCouponRequest(
        @NotBlank @Size(max = 50) String couponCode) {
}
