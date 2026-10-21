package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateInventoryRequest {

    @NotNull
    @Min(0)
    private Integer minimumQuantity;

}