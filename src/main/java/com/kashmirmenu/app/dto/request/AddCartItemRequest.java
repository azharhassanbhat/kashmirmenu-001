package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddCartItemRequest {

    @NotBlank(message = "Table QR identifier is required")
    private String tableQrIdentifier;

    @NotNull(message = "Menu item is required")
    private Long menuItemId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    /** Chosen option ids; null or empty when the dish has no options. */
    private java.util.List<Long> optionIds;
}