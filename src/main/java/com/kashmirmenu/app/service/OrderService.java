package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.CreateOrderRequest;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.response.OrderResponse;
import com.kashmirmenu.app.entity.DinerSession;

import java.util.List;

public interface OrderService {

    /** Staff path: places an order from the table's staff cart. */
    OrderResponse placeOrder(CreateOrderRequest request);

    /**
     * Guest path: places an order from this guest's own cart, and records who placed it. The guest
     * is the one resolved from their token, never named by the caller.
     */
    OrderResponse placeOrderForDiner(DinerSession diner, String tableQrIdentifier, String notes);

    OrderResponse getOrder(Long orderId);

    /**
     * A page of the restaurant's orders, newest first, each carrying its payment state.
     *
     * <p>Was an unbounded List with a lazy items collection, so it returned every order the
     * restaurant had ever taken and then issued one more query per order for its items.
     */
    PageResponse<OrderResponse> getOrders(int page, int size);

    PageResponse<OrderResponse> getOrdersByStatus(String status, int page, int size);

    OrderResponse updateOrderStatus(
            Long orderId,
            String status);

    /**
     * As above; when the status is CANCELLED, records why. {@code reason} is one of the CancelReason
     * codes and is optional (older callers send none); {@code note} is the staff's own words.
     */
    OrderResponse updateOrderStatus(
            Long orderId,
            String status,
            String reason,
            String note);

    /** Every recorded status change of one of the caller's own orders, oldest first. */
    java.util.List<com.kashmirmenu.app.dto.response.OrderHistoryEntryResponse> getOrderHistory(Long orderId);
}