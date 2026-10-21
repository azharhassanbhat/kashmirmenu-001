package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;

/** One choice in an option group. {@code priceDelta} is the change from the dish's base price. */
public record OptionResponse(
        Long id,
        String name,
        BigDecimal priceDelta,
        boolean available,
        boolean defaultOption) {
}
