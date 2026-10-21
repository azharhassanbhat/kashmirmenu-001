package com.kashmirmenu.app.dto.websocket;

import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    private Long notificationId;

    private Long restaurantId;

    private Long orderId;

    /** Short order number (ORD-12) when the event is about an order; lets the dashboard name it without a lookup. */
    private String orderNumber;

    /** The order's total as plain decimal text ("450.00"), when the event is about an order. */
    private String orderTotal;

    private String title;

    private String message;

    private NotificationType notificationType;

    /** What the event is; clients choose the sound from this, never from the text. */
    private NotificationEventKind eventKind;

    private String createdAt;
}