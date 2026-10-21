package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.service.OrderTiming;
import com.kashmirmenu.app.service.OrderHistory;
import com.kashmirmenu.app.dto.response.KitchenOrderResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.mapper.KitchenMapper;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.service.KitchenService;
import com.kashmirmenu.app.service.OrderStatusPolicy;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.service.NotificationService;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.security.service.CurrentUserService;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class KitchenServiceImpl implements KitchenService {

    private final OrderRepository orderRepository;
    private final KitchenMapper kitchenMapper;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;
    private final OrderHistory orderHistory;

    @Override
    @Transactional(readOnly = true)
    public List<KitchenOrderResponse> getPendingOrders() {

        return withItems(orderRepository
                .findByRestaurantAndStatusOrderByCreatedAtAsc(
                        currentUserService.getCurrentRestaurant(),
                        OrderStatus.PENDING));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KitchenOrderResponse> getActiveOrders() {

        return withItems(orderRepository
                .findByRestaurantAndStatusInOrderByCreatedAtAsc(
                        currentUserService.getCurrentRestaurant(),
                        List.of(
                                OrderStatus.PENDING,
                                OrderStatus.CONFIRMED,
                                OrderStatus.PREPARING,
                                OrderStatus.READY)));
    }

    /**
     * Today's orders, newest first, whatever their status. The board only shows what is still in
     * progress, so once an order is served or cancelled it disappears - leaving the kitchen no way
     * to check what went out, or to look up an order a diner is asking about.
     *
     * <p>Scoped to the current business day: a kitchen cares about this service, not all history,
     * and it keeps the response small enough to poll.
     */
    @Override
    @Transactional(readOnly = true)
    public List<KitchenOrderResponse> getTodaysOrders() {

        return withItems(orderRepository
                .findSince(
                        currentUserService.getCurrentRestaurantId(),
                        businessTime.today().atStartOfDay()));
    }

    @Override
    public KitchenOrderResponse acceptOrder(Long orderId) {

        Order order = getOrder(orderId);

        validateTransition(order, OrderStatus.CONFIRMED);

        OrderStatus before = order.getStatus();

        order.setStatus(OrderStatus.CONFIRMED);

        order = orderRepository.save(order);

        orderHistory.changed(order, before, OrderStatus.CONFIRMED);

        notificationService.createNotification(
                order.getRestaurant(),
                order,
                "Order Confirmed",
                "Order " + order.getOrderNumber() + " has been confirmed.",
                NotificationType.ORDER_CONFIRMED
        );

        return toResponse(order);
    }

    @Override
    public KitchenOrderResponse startPreparing(Long orderId) {

        Order order = getOrder(orderId);

        validateTransition(order, OrderStatus.PREPARING);

        OrderStatus before = order.getStatus();

        order.setStatus(OrderStatus.PREPARING);

        order = orderRepository.save(order);

        orderHistory.changed(order, before, OrderStatus.PREPARING);

        notificationService.createNotification(
                order.getRestaurant(),
                order,
                "Order Preparing",
                "Order " + order.getOrderNumber() + " is being prepared.",
                NotificationType.ORDER_PREPARING
        );

        return toResponse(order);
    }

    @Override
    public KitchenOrderResponse markReady(Long orderId) {

        Order order = getOrder(orderId);

        validateTransition(order, OrderStatus.READY);

        OrderStatus before = order.getStatus();

        order.setStatus(OrderStatus.READY);

        order = orderRepository.save(order);

        orderHistory.changed(order, before, OrderStatus.READY);

        notificationService.createNotification(
                order.getRestaurant(),
                order,
                "Order Ready",
                "Order " + order.getOrderNumber() + " is ready.",
                NotificationType.ORDER_READY
        );

        return toResponse(order);
    }

    @Override
    public KitchenOrderResponse markServed(Long orderId) {

        Order order = getOrder(orderId);

        validateTransition(order, OrderStatus.SERVED);

        OrderStatus before = order.getStatus();

        order.setStatus(OrderStatus.SERVED);

        order = orderRepository.save(order);

        orderHistory.changed(order, before, OrderStatus.SERVED);

        notificationService.createNotification(
                order.getRestaurant(),
                order,
                "Order Served",
                "Order " + order.getOrderNumber() + " has been served.",
                NotificationType.ORDER_SERVED
        );

        return toResponse(order);
    }

    /**
     * Maps orders to responses after loading their items in one query.
     *
     * <p>KitchenOrderResponse includes the line items, so mapping a list straight from a query that
     * does not fetch them triggers one SELECT per order. These two endpoints are polled every 20 and
     * 30 seconds by every open kitchen display, so that was a sustained multiplier rather than a
     * one-off page cost.
     *
     * <p>Two queries, not a fetch join on the original: the orders are already ordered and a
     * collection fetch join would have to be de-duplicated, and on any paged query it would force
     * Hibernate to page in memory (see docs/paging_check.py).
     */
    private List<KitchenOrderResponse> withItems(List<Order> orders) {

        if (orders.isEmpty()) {
            return List.of();
        }

        // Populates the persistence context for this transaction; the mapper below then reads the
        // items from managed entities rather than going back to the database per order.
        orderRepository.findWithItemsByIdIn(
                orders.stream().map(Order::getId).toList());

        return orders.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The kitchen's view of an order, with how long it has been open against how long it should take.
     * Worked out here, on the server's clock and from the dishes' own preparation times, so a kitchen
     * tablet with the wrong time cannot show a wrong "delayed".
     */
    private KitchenOrderResponse toResponse(Order order) {

        KitchenOrderResponse response = kitchenMapper.toResponse(order);

        int target = OrderTiming.targetMinutes(order.getItems().stream()
                .map(i -> i.getMenuItem() == null ? null : i.getMenuItem().getPreparationTime())
                .toList());
        OrderTiming.Timing timing = OrderTiming.of(
                order.getStatus(), order.getCreatedAt(), businessTime.now(), target);

        response.setPrepTargetMinutes(timing.targetMinutes());
        response.setElapsedMinutes(timing.elapsedMinutes());
        response.setDelayed(timing.delayed());
        response.setOverdueMinutes(timing.overdueMinutes());
        return response;
    }

    private Order getOrder(Long orderId) {

        // Locked: see OrderRepository.findByIdAndRestaurantForUpdate.
        return orderRepository
                .findByIdAndRestaurantForUpdate(
                        orderId,
                        currentUserService.getCurrentRestaurant())
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Order not found with id: " + orderId));
    }

    /**
     * Delegates to {@link OrderStatusPolicy}. The table used to live here privately, which is why
     * PUT /api/v1/orders/{id}/status - writing to the same field - could bypass it entirely.
     */
    private void validateTransition(Order order,
                                    OrderStatus nextStatus) {

        if (!OrderStatusPolicy.isAllowed(order.getStatus(), nextStatus)) {
            throw new IllegalStateException(
                    OrderStatusPolicy.refusalMessage(order.getStatus(), nextStatus));
        }
    }
}