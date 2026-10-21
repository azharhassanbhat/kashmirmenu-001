package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.MarkNotificationReadRequest;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.response.NotificationResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;

import java.util.List;

public interface NotificationService {

    /** As below, with the kind that the type implies. */
    default NotificationResponse createNotification(
            Restaurant restaurant,
            Order order,
            String title,
            String message,
            NotificationType notificationType) {
        return createNotification(restaurant, order, title, message, notificationType,
                NotificationEventKind.defaultFor(notificationType));
    }

    NotificationResponse createNotification(
            Restaurant restaurant,
            Order order,
            String title,
            String message,
            NotificationType notificationType,
            NotificationEventKind eventKind);

    PageResponse<NotificationResponse> getAllNotifications(Long restaurantId, int page, int size);

    List<NotificationResponse> getUnreadNotifications(Long restaurantId);

    NotificationResponse markAsRead(
            Long notificationId,
            MarkNotificationReadRequest request);

    long getUnreadCount(Long restaurantId);

    List<NotificationResponse> getOrderNotifications(Long orderId);
}