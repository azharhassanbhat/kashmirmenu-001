package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PublicCartItemRequest(
        @NotNull Long menuItemId,
        @NotNull @Min(1) @Max(50) Integer quantity,
        /** Ids of the chosen options. Names and prices are always taken from the server's copy. */
        @jakarta.validation.constraints.Size(max = 30) java.util.List<Long> optionIds) {
}
