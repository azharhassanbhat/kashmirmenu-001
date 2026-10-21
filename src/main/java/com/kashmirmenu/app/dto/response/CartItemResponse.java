package com.kashmirmenu.app.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemResponse {

    private Long id;

    private Long menuItemId;

    private String menuItemName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal lineTotal;
    private java.util.List<SelectedOptionResponse> options = new java.util.ArrayList<>();
}