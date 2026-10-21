package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.platform.PlatformEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A restaurant registering is announced to the platform owner's live feed (and only that feed), once, after the
 * registration has committed; a registration that is refused announces nothing.
 */
@SpringBootTest(properties = "app.restaurant-approval.required=true")
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class PlatformEventIT {

    @Autowired private MockMvc mockMvc;

    @MockitoSpyBean private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void clear() {
        Mockito.clearInvocations(messagingTemplate);
    }

    private int register(String restaurantName, String ownerEmail) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return mockMvc.perform(post("/api/v1/auth/register-owner").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantName":"%s","restaurantEmail":"r-%s@example.com",
                                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                                 "ownerEmail":"%s","password":"Password123"}
                                """.formatted(restaurantName, id, id, ownerEmail)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aRegistrationAwaitingApprovalIsAnnouncedToThePlatformFeedOnly() throws Exception {
        String name = "Announced " + UUID.randomUUID().toString().substring(0, 6);

        assertEquals(200, register(name, "owner-" + UUID.randomUUID() + "@example.com"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(messagingTemplate, Mockito.times(1))
                .convertAndSend(eq(PlatformEventPublisher.TOPIC), payload.capture());

        PlatformEventPublisher.Event event = (PlatformEventPublisher.Event) payload.getValue();
        assertEquals("PLATFORM_RESTAURANT_REGISTERED", event.type());
        assertEquals(name, event.restaurantName());
        assertNotNull(event.eventId());
        assertNotNull(event.restaurantId());

        // No restaurant feed heard about it: the event is for the platform owner only.
        Mockito.verify(messagingTemplate, Mockito.never())
                .convertAndSend(Mockito.startsWith("/topic/restaurants/"), any(Object.class));
    }

    @Test
    void aRefusedRegistrationAnnouncesNothing() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        assertEquals(200, register("First " + UUID.randomUUID().toString().substring(0, 6), email));
        Mockito.clearInvocations(messagingTemplate);

        // The same owner email again: refused, rolled back, and so not announced.
        int status = register("Second " + UUID.randomUUID().toString().substring(0, 6), email);

        assertEquals(409, status);
        Mockito.verify(messagingTemplate, Mockito.never())
                .convertAndSend(eq(PlatformEventPublisher.TOPIC), any(Object.class));
    }
}
