package com.kashmirmenu.app.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OrderTrackingItemResponse {

    private String itemName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal lineTotal;
    private java.util.List<SelectedOptionResponse> options = new java.util.ArrayList<>();
}