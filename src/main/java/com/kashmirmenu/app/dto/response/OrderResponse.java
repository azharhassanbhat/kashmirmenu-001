package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderResponse {

    private Long id;

    private String orderNumber;

    private Long restaurantId;

    private Long restaurantTableId;

    /** Who placed the order. Null for orders staff placed themselves and for older orders. */
    private Long dinerSessionId;

    private String dinerName;

    private OrderStatus status;

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

    /**
     * Key for the diner's public tracking URL. Random, because that endpoint is unauthenticated
     * and the order number is deliberately short and sequential.
     */
    private String publicToken;

    /** The coupon used, if any; kept even if the coupon is later deleted. */
    private String couponCode;

    private String notes;

    /**
     * Payment state, included so a list of orders does not need one follow-up request per row.
     * Derived from every payment on the order, as the order summary endpoint does.
     */
    private PaymentStatus paymentStatus;

    private BigDecimal amountPaid;

    private BigDecimal balanceDue;

    private List<OrderItemResponse> items;
}