package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Live events for the platform owner, on {@value #TOPIC}. Only a PLATFORM_OWNER can subscribe (see
 * StompAuthChannelInterceptor). Nothing is stored: the platform dashboard's own queue (the pending list and
 * its count) is the durable record, this only tells an open dashboard that something arrived.
 */
@Component
@RequiredArgsConstructor
public class PlatformEventPublisher {

    public static final String TOPIC = "/topic/platform";

    private final SimpMessagingTemplate messagingTemplate;

    public record Event(
            String eventId,
            String type,
            String title,
            String message,
            Long restaurantId,
            String restaurantName,
            String createdAt) {
    }

    /** A restaurant registered and is waiting for approval. Sent after the registration commits. */
    public void restaurantRegistered(Restaurant restaurant) {
        Event event = new Event(
                UUID.randomUUID().toString(),
                "PLATFORM_RESTAURANT_REGISTERED",
                "New restaurant awaiting approval",
                restaurant.getName() + " has registered and is waiting for approval.",
                restaurant.getId(),
                restaurant.getName(),
                Instant.now().toString());

        AfterCommit.run(() -> messagingTemplate.convertAndSend(TOPIC, event));
    }
}
