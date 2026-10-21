package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PublicQuantityRequest(
        @NotNull @Min(1) @Max(50) Integer quantity) {
}
