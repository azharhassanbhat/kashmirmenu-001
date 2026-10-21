package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class OrderTrackingResponse {

    private String orderNumber;

    private OrderStatus status;

    private PaymentStatus paymentStatus;

    /** Received so far, net of refunds. */
    private BigDecimal amountPaid;

    /** Still due; the diner can pay this. Zero once the order is fully paid. */
    private BigDecimal balanceDue;

    private String tableName;

    private BigDecimal subtotal;

    private BigDecimal discount;
    private BigDecimal tax;
    private BigDecimal serviceCharge;
    private BigDecimal taxIncluded;
    private String taxName;
    private BigDecimal taxRate;
    private BigDecimal serviceChargeRate;
    private Boolean pricesIncludeTax;

    private String couponCode;

    private BigDecimal total;

    /** Why the restaurant cancelled this order, in words. Never the staff's own note. Null if not cancelled. */
    private String cancelReasonLabel;

    /** What this guest already rated the order, so the page can show it and let them change it. Null if not rated. */
    private Integer feedbackRating;

    private String feedbackComment;

    private LocalDateTime createdAt;

    private List<OrderTrackingItemResponse> items;
}
