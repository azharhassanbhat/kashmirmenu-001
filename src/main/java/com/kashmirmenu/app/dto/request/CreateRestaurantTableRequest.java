package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRestaurantTableRequest {

    @NotBlank(message = "Table code is required")
    private String tableCode;

    @NotBlank(message = "Table name is required")
    private String tableName;

    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer capacity;
}