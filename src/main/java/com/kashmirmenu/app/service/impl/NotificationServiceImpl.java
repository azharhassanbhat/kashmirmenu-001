package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.dto.request.MarkNotificationReadRequest;
import com.kashmirmenu.app.dto.response.NotificationResponse;
import com.kashmirmenu.app.entity.Notification;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.NotificationMapper;
import com.kashmirmenu.app.repository.NotificationRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.NotificationService;
import lombok.RequiredArgsConstructor;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.util.PageResponseUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kashmirmenu.app.dto.websocket.NotificationMessage;
import com.kashmirmenu.app.dto.websocket.NotificationPublisher;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final RestaurantRepository restaurantRepository;
    private final OrderRepository orderRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserService currentUserService;

    /** Ceiling on page size, so the unbounded history read cannot be asked for again. */
    private static final int MAX_PAGE_SIZE = 100;
    private final NotificationPublisher notificationPublisher;


    /**
     * Notifications and orders are fetched by raw id here, so ownership has to be checked explicitly
     * or one restaurant could read and mark another's notifications. Answers as "not found" on a
     * mismatch, so ids cannot be probed across tenants.
     */
    private void assertOwn(Long restaurantId) {

        Restaurant current = currentUserService.getCurrentRestaurant();

        if (current == null || !current.getId().equals(restaurantId)) {
            throw new ResourceNotFoundException("Notification not found.");
        }
    }

    private Restaurant getRestaurant(Long restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Restaurant not found."));
    }

    private Notification getNotification(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Notification not found."));
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Order not found."));
    }

    @Override
    public NotificationResponse createNotification(
            Restaurant restaurant,
            Order order,
            String title,
            String message,
            NotificationType notificationType,
            NotificationEventKind eventKind) {

        final NotificationEventKind kind =
                eventKind != null ? eventKind : NotificationEventKind.defaultFor(notificationType);
        Notification notification = Notification.builder()
                .restaurant(restaurant)
                .order(order)
                .title(title)
                .message(message)
                .notificationType(notificationType)
                .eventKind(kind)
                .read(false)
                .build();

        notification = notificationRepository.save(notification);

        final NotificationMessage websocketMessage = NotificationMessage.builder()
                .notificationId(notification.getId())
                .restaurantId(restaurant.getId())
                .orderId(order != null ? order.getId() : null)
                .orderNumber(order != null ? order.getOrderNumber() : null)
                .orderTotal(order != null && order.getTotal() != null ? order.getTotal().toPlainString() : null)
                .title(notification.getTitle())
                .message(notification.getMessage())
                .notificationType(notification.getNotificationType())
                .eventKind(kind)
                .createdAt(notification.getCreatedAt().toString())
                .build();

        // Published AFTER the transaction commits, not inside it. Two reasons, and the first is
        // correctness rather than performance:
        //
        //  1. Inside the transaction, a notification was sent for work that might still roll back -
        //     an order whose stock check then failed would have told the kitchen it was coming.
        //  2. It is network I/O holding a database connection. In placeOrder's case it holds one
        //     while the table, coupon, counter and inventory locks are all still taken, so the
        //     narrowest lock in the application is widened by the slowest thing in it. Harmless with
        //     the in-memory broker; not harmless if it is ever replaced with RabbitMQ or Redis.
        //
        // If no transaction is active (a direct call outside one), it is published immediately.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            notificationPublisher.publish(websocketMessage);
                        }
                    });
        } else {
            notificationPublisher.publish(websocketMessage);
        }

        return notificationMapper.toResponse(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getAllNotifications(
            Long restaurantId, int page, int size) {

        Restaurant restaurant = getRestaurant(restaurantId);

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE));

        return PageResponseUtils.fromPage(
                notificationRepository.findByRestaurantOrderByCreatedAtDesc(restaurant, pageable),
                notificationMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getUnreadNotifications(Long restaurantId) {

        Restaurant restaurant = getRestaurant(restaurantId);

        return notificationRepository
                .findByRestaurantAndReadFalseOrderByCreatedAtDesc(restaurant)
                .stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Override
    public NotificationResponse markAsRead(
            Long notificationId,
            MarkNotificationReadRequest request) {

        Notification notification = getNotification(notificationId);

        assertOwn(notification.getRestaurant().getId());

        notification.setRead(request.getRead());

        notification = notificationRepository.save(notification);

        return notificationMapper.toResponse(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(Long restaurantId) {

        Restaurant restaurant = getRestaurant(restaurantId);

        return notificationRepository.countByRestaurantAndReadFalse(restaurant);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getOrderNotifications(Long orderId) {

        Order order = getOrder(orderId);

        assertOwn(order.getRestaurant().getId());

        return notificationRepository
                .findByOrderOrderByCreatedAtAsc(order)
                .stream()
                .map(notificationMapper::toResponse)
                .toList();
    }
}