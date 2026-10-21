package com.kashmirmenu.app.dto.websocket;

import com.kashmirmenu.app.dto.websocket.NotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publish(NotificationMessage message) {
        messagingTemplate.convertAndSend(
                "/topic/restaurants/" + message.getRestaurantId(),
                message
        );

        log.debug("Published {} notification {} to restaurant {}",
                message.getNotificationType(), message.getNotificationId(), message.getRestaurantId());
    }
}