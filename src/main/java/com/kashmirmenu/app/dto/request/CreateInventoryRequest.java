package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateInventoryRequest {

    /**
     * Ignored by the server, which uses the signed-in user's restaurant. Kept so existing clients
     * keep working; it is no longer trusted because the body must not choose the tenant.
     */
    @NotNull
    private Long restaurantId;

    @NotNull
    private Long menuItemId;

    @NotNull
    @Min(0)
    private Integer availableQuantity;

    @NotNull
    @Min(0)
    private Integer minimumQuantity;

}