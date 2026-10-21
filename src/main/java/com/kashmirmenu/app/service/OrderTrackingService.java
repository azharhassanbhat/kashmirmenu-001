package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.response.OrderTrackingResponse;

public interface OrderTrackingService {

    /** Staff lookup by the readable order number. */
    OrderTrackingResponse trackOrder(String orderNumber);

    /** Diner lookup by the order's unguessable public token. */
    OrderTrackingResponse trackOrderByToken(String publicToken);
}