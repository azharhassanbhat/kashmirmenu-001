package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.CartStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartResponse {

    private Long id;

    private Long restaurantId;

    private Long restaurantTableId;

    private CartStatus status;

    private BigDecimal subtotal;

    private BigDecimal tax;
    private BigDecimal serviceCharge;
    private BigDecimal taxIncluded;
    private String taxName;
    private BigDecimal taxRate;
    private BigDecimal serviceChargeRate;
    private Boolean pricesIncludeTax;

    private BigDecimal discount;

    private BigDecimal total;

    private List<CartItemResponse> items;

    /** The code currently applied, or null. The diner-facing name and saving go with it. */
    private String couponCode;

    private String couponName;
}