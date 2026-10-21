package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;

public record BillItemResponse(
        String name,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {
}
