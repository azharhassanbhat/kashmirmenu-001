package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderRequest {

    @NotNull(message = "Restaurant table id is required")
    private Long restaurantTableId;

    private String notes;
}