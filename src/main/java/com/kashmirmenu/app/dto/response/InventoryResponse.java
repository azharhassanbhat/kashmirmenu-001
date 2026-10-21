package com.kashmirmenu.app.dto.response;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryResponse {

    private Long id;

    private Long restaurantId;

    private String restaurantName;

    private Long menuItemId;

    private String menuItemName;

    private Integer availableQuantity;

    private Integer minimumQuantity;

    private Boolean inStock;
}