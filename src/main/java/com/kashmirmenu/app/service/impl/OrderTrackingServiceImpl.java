package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.repository.OrderFeedbackRepository;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.dto.response.OrderTrackingResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.OrderTrackingMapper;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.PaymentService;
import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.service.OrderTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderTrackingServiceImpl implements OrderTrackingService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderTrackingMapper orderTrackingMapper;
    private final OrderFeedbackRepository feedbackRepository;
    private final CurrentUserService currentUserService;

    /**
     * Diner lookup. Keyed by the order's random public token rather than its number: the endpoint is
     * unauthenticated, and order numbers are short and sequential, so using the number as the key
     * would let anyone count through other tables' orders.
     */
    @Override
    public OrderTrackingResponse trackOrderByToken(String publicToken) {

        Order order = orderRepository.findByPublicToken(publicToken)
                // Deliberately bare: a message naming the token would confirm which values exist.
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));

        return build(order);
    }

    /**
     * Staff lookup by the readable order number; this path is behind authentication. Scoped to the
     * caller's restaurant: numbers repeat across restaurants, and without the scope this would
     * return (or fail on) another restaurant's order.
     */
    @Override
    public OrderTrackingResponse trackOrder(String orderNumber) {

        Order order = orderRepository
                .findByOrderNumberAndRestaurant(orderNumber, currentUserService.getCurrentRestaurant())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found with number: " + orderNumber));

        return build(order);
    }

    private OrderTrackingResponse build(Order order) {

        OrderTrackingResponse response = orderTrackingMapper.toResponse(order);

        // Derived from every payment on the order (it may be split or retried), not from a single row.
        PaymentBalance.Result balance = paymentService.getBalance(order);
        response.setPaymentStatus(balance.status());
        response.setAmountPaid(balance.settled());
        response.setBalanceDue(balance.outstanding());
        // The reason in words only. The staff note stays internal.
        response.setCancelReasonLabel(order.getCancelReason() == null ? null : order.getCancelReason().label());

        // Only a served order can have been rated, so the poll that runs while food is still cooking does not
        // pay for the extra lookup.
        if (order.getStatus() == OrderStatus.SERVED) {
            feedbackRepository.findByOrderId(order.getId()).ifPresent(f -> {
                response.setFeedbackRating(f.getRating());
                response.setFeedbackComment(f.getComment());
            });
        }

        return response;
    }
}