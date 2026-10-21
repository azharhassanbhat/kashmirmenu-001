package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.dto.websocket.NotificationMessage;
import com.kashmirmenu.app.dto.websocket.NotificationPublisher;
import com.kashmirmenu.app.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the dashboards are told, through the real controllers, services and database: each event a
 * restaurant cares about is published once, to that restaurant's feed only, with enough detail for the
 * dashboard to name the order without another request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class RealtimeNotificationIT {

    private record Shop(String token, long restaurantId, String qr, long dishId) {
    }

    private record Placed(long id, String publicToken, String total, String number) {
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoSpyBean private NotificationPublisher publisher;

    @BeforeEach
    void forgetEarlierTests() {
        Mockito.clearInvocations(publisher);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String bearer, String body) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return b;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String bearer) {
        return b.header("Authorization", "Bearer " + bearer);
    }

    private Shop shop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me"), token))
                .get("restaurantId").asLong();
        long category = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}")).get("data").get("id").asLong();
        long dish = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh\",\"price\":400.00,\"categoryId\":" + category + "}")).get("data").get("id").asLong();
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
        return new Shop(token, restaurantId, qr, dish);
    }

    private Placed placeOrder(Shop shop) throws Exception {
        String guest = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Asha");
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                        .header(DinerTestSupport.HEADER, guest), null,
                "{\"menuItemId\":" + shop.dishId() + ",\"quantity\":1}")).andExpect(status().isOk());
        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, guest), null, "{}"));
        return new Placed(order.get("id").asLong(), order.get("publicToken").asText(),
                order.get("total").asText(), order.get("orderNumber").asText());
    }

    /** Every message handed to the websocket publisher since the test started. */
    private List<NotificationMessage> published() {
        return Mockito.mockingDetails(publisher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("publish"))
                .map(i -> (NotificationMessage) i.getArgument(0))
                .toList();
    }

    private List<NotificationMessage> published(NotificationType type) {
        return published().stream().filter(m -> m.getNotificationType() == type).toList();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aNewOrderIsPublishedOnceToItsOwnRestaurantWithTheNumberAndTotal() throws Exception {
        Shop a = shop();
        Shop b = shop();
        Mockito.clearInvocations(publisher);

        Placed order = placeOrder(a);

        List<NotificationMessage> placed = published(NotificationType.ORDER_PLACED);
        assertEquals(1, placed.size(), "one order, one notification");
        NotificationMessage m = placed.get(0);
        assertEquals(a.restaurantId(), m.getRestaurantId());
        assertEquals(order.id(), m.getOrderId());
        assertEquals(order.number(), m.getOrderNumber());
        assertNotNull(m.getOrderTotal());
        assertEquals(0, new BigDecimal(order.total()).compareTo(new BigDecimal(m.getOrderTotal())),
                "the total in the event is the order's total");
        assertNotNull(m.getNotificationId());

        // Nothing about this order, or anything else, went to the other restaurant's feed.
        assertTrue(published().stream().noneMatch(x -> x.getRestaurantId().equals(b.restaurantId())),
                "restaurant B must hear nothing about restaurant A's order");
    }

    @Test
    void notificationIdsGrowSoTheDashboardCanTellNewFromOld() throws Exception {
        Shop a = shop();
        Mockito.clearInvocations(publisher);

        placeOrder(a);
        placeOrder(a);

        List<NotificationMessage> placed = published(NotificationType.ORDER_PLACED);
        assertEquals(2, placed.size());
        assertTrue(placed.get(1).getNotificationId() > placed.get(0).getNotificationId());
    }

    @Test
    void cancellingAnOrderIsPublishedOnce() throws Exception {
        Shop a = shop();
        Placed order = placeOrder(a);
        Mockito.clearInvocations(publisher);

        mockMvc.perform(as(put("/api/v1/orders/" + order.id() + "/status").param("status", "CANCELLED"), a.token()))
                .andExpect(status().isOk());

        List<NotificationMessage> cancelled = published(NotificationType.ORDER_CANCELLED);
        assertEquals(1, cancelled.size());
        assertEquals(a.restaurantId(), cancelled.get(0).getRestaurantId());
        assertEquals(order.number(), cancelled.get(0).getOrderNumber());

        // Cancelling again changes nothing, so it must not announce anything again.
        mockMvc.perform(as(put("/api/v1/orders/" + order.id() + "/status").param("status", "CANCELLED"), a.token()));
        assertEquals(1, published(NotificationType.ORDER_CANCELLED).size());
    }

    @Test
    void aPaymentAndALaterRefundAreEachPublishedOnce() throws Exception {
        Shop a = shop();
        Placed order = placeOrder(a);
        Mockito.clearInvocations(publisher);

        JsonNode result = json(withBody(post("/api/v1/payments/orders/" + order.id() + "/collect"), a.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":" + order.total() + "}]}"));
        long paymentId = result.get("payments").get(0).get("id").asLong();

        assertEquals(1, published(NotificationType.PAYMENT_SUCCESS).size());
        assertEquals(0, published(NotificationType.PAYMENT_REFUNDED).size());

        mockMvc.perform(withBody(post("/api/v1/payments/" + paymentId + "/refunds"), a.token(),
                "{\"amount\":100.00,\"reason\":\"Cold food\"}")).andExpect(status().isCreated());

        List<NotificationMessage> refunded = published(NotificationType.PAYMENT_REFUNDED);
        assertEquals(1, refunded.size());
        assertEquals(a.restaurantId(), refunded.get(0).getRestaurantId());
        assertEquals(order.number(), refunded.get(0).getOrderNumber());
    }

    @Test
    void aGuestRatingIsPublishedTheFirstTimeButNotWhenTheyChangeIt() throws Exception {
        Shop a = shop();
        Placed order = placeOrder(a);
        for (String step : new String[]{"confirm", "preparing", "ready", "served"}) {
            mockMvc.perform(as(put("/api/v1/kitchen/orders/" + order.id() + "/" + step), a.token()))
                    .andExpect(status().isOk());
        }
        Mockito.clearInvocations(publisher);

        mockMvc.perform(withBody(put("/api/public/orders/by-token/" + order.publicToken() + "/feedback"), null,
                "{\"rating\":5,\"comment\":\"Lovely\"}")).andExpect(status().isOk());
        assertEquals(1, published(NotificationType.FEEDBACK_RECEIVED).size());
        NotificationMessage m = published(NotificationType.FEEDBACK_RECEIVED).get(0);
        assertEquals(a.restaurantId(), m.getRestaurantId());
        assertTrue(!m.getMessage().contains("Lovely"), "the guest's own words are not broadcast");

        mockMvc.perform(withBody(put("/api/public/orders/by-token/" + order.publicToken() + "/feedback"), null,
                "{\"rating\":4}")).andExpect(status().isOk());
        assertEquals(1, published(NotificationType.FEEDBACK_RECEIVED).size(), "an edit is not a new review");
    }
}
