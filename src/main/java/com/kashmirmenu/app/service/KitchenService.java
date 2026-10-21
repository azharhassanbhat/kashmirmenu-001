package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.response.KitchenOrderResponse;

import java.util.List;

public interface KitchenService {

    List<KitchenOrderResponse> getPendingOrders();

    List<KitchenOrderResponse> getActiveOrders();

    /** Today's orders in every status, including served and cancelled. */
    List<KitchenOrderResponse> getTodaysOrders();

    KitchenOrderResponse acceptOrder(Long orderId);

    KitchenOrderResponse startPreparing(Long orderId);

    KitchenOrderResponse markReady(Long orderId);

    KitchenOrderResponse markServed(Long orderId);
}