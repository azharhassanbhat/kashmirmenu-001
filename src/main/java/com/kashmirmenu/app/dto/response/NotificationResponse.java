package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class NotificationResponse {

    private Long id;

    private Long restaurantId;

    private String restaurantName;

    private Long orderId;

    private String orderNumber;

    private String title;

    private String message;

    private NotificationType notificationType;

    /** What the event is (WAITER_CALL, BILL_REQUEST...). Rows without a stored kind get their type's default. */
    private NotificationEventKind eventKind;

    private Boolean read;

    private LocalDateTime createdAt;
}