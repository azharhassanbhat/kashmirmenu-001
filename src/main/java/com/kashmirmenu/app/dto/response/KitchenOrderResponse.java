package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.OrderStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class KitchenOrderResponse {

    private Long orderId;

    private String orderNumber;

    private Long restaurantTableId;

    private String tableName;

    private OrderStatus status;

    private BigDecimal total;

    private String notes;

    private LocalDateTime createdAt;

    private java.util.List<OrderItemResponse> items;

    /** How long the kitchen is expected to need: the slowest dish's preparation time. */
    private Integer prepTargetMinutes;

    /** Minutes since the order was placed, by the SERVER's clock. */
    private Long elapsedMinutes;

    /** Open longer than the target and not yet ready. */
    private Boolean delayed;

    /** Minutes beyond the target; 0 unless delayed. */
    private Long overdueMinutes;
}