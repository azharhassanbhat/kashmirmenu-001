package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage F: why an order was cancelled, a history of every status change, and the kitchen's timing, all
 * through the real controllers, security and database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class OrderHistoryIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, String email, String qr, long dishId) {
    }

    private record Placed(long id, String publicToken) {
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
        String email = "owner-" + id + "@example.com";
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"%s","password":"Password123"}
                """.formatted(id, id, id, email))).get("accessToken").asText();
        long restaurantId = json(as(get("/api/v1/me"), token)).get("restaurantId").asLong();
        long category = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}")).get("data").get("id").asLong();
        long dish = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh\",\"price\":400.00,\"preparationTime\":12,\"categoryId\":" + category + "}"))
                .get("data").get("id").asLong();
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
        return new Shop(token, email, qr, dish);
    }

    private Placed placeOrder(Shop shop) throws Exception {
        String guest = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Rahul");
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                        .header(DinerTestSupport.HEADER, guest), null,
                "{\"menuItemId\":" + shop.dishId() + ",\"quantity\":1}")).andExpect(status().isOk());
        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, guest), null, "{}"));
        return new Placed(order.get("id").asLong(), order.get("publicToken").asText());
    }

    private JsonNode history(Shop shop, long orderId) throws Exception {
        return json(as(get("/api/v1/orders/" + orderId + "/history"), shop.token()));
    }

    /** PUT /orders/{id}/status with the parameters given (reason and note may be null). Returns the HTTP status. */
    private int setStatus(Shop shop, long orderId, String status, String reason, String note) throws Exception {
        MockHttpServletRequestBuilder request = as(put("/api/v1/orders/" + orderId + "/status"), shop.token())
                .param("status", status);
        if (reason != null) {
            request.param("reason", reason);
        }
        if (note != null) {
            request.param("note", note);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int kitchen(Shop shop, long orderId, String step) throws Exception {
        return mockMvc.perform(as(put("/api/v1/kitchen/orders/" + orderId + "/" + step), shop.token()))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode tracking(Placed p) throws Exception {
        return json(get("/api/public/orders/by-token/" + p.publicToken()));
    }

    private String currentStatus(Placed p) throws Exception {
        return tracking(p).get("status").asText();
    }

    // ------------------------------------------------------------------ history

    @Test
    void placingAnOrderIsTheFirstLineOfItsHistoryNamingTheGuest() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);

        JsonNode h = history(shop, p.id());
        assertEquals(1, h.size());
        assertTrue(h.get(0).get("from").isNull());
        assertEquals("PENDING", h.get(0).get("to").asText());
        assertEquals("Rahul", h.get(0).get("actor").asText());
        assertFalse(h.get(0).get("at").isNull());
    }

    @Test
    void everyKitchenStepIsRecordedInOrderWithWhoDidIt() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);

        assertEquals(200, kitchen(shop, p.id(), "confirm"));
        assertEquals(200, kitchen(shop, p.id(), "preparing"));
        assertEquals(200, kitchen(shop, p.id(), "ready"));
        assertEquals(200, kitchen(shop, p.id(), "served"));

        JsonNode h = history(shop, p.id());
        assertEquals(5, h.size());
        String[] expected = {"PENDING", "CONFIRMED", "PREPARING", "READY", "SERVED"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], h.get(i).get("to").asText());
        }
        assertEquals("PENDING", h.get(1).get("from").asText());
        assertEquals(shop.email(), h.get(1).get("actor").asText());
        assertEquals(shop.email(), h.get(4).get("actor").asText());
    }

    @Test
    void aRetriedTapDoesNotAppearTwiceInTheHistory() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(200, kitchen(shop, p.id(), "confirm"));
        assertEquals(200, kitchen(shop, p.id(), "confirm"));   // a double tap, or a retry after a dropped response
        assertEquals(2, history(shop, p.id()).size());
    }

    @Test
    void anIllegalStepIsRefusedAndLeavesNoTrace() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(400, kitchen(shop, p.id(), "served"));   // PENDING cannot jump to SERVED
        assertEquals(1, history(shop, p.id()).size());
        assertEquals("PENDING", currentStatus(p));
    }

    // ------------------------------------------------------------------ cancellation reasons

    @Test
    void aCancellationRecordsTheReasonAndTheGuestSeesItInWordsButNeverTheNote() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);

        assertEquals(200, setStatus(shop, p.id(), "CANCELLED", "OUT_OF_STOCK", "Lamb delivery is late"));

        JsonNode h = history(shop, p.id());
        JsonNode last = h.get(h.size() - 1);
        assertEquals("CANCELLED", last.get("to").asText());
        assertEquals("PENDING", last.get("from").asText());
        assertEquals("OUT_OF_STOCK", last.get("reason").asText());
        assertEquals("A dish is out of stock", last.get("reasonLabel").asText());
        assertEquals("Lamb delivery is late", last.get("note").asText());
        assertEquals(shop.email(), last.get("actor").asText());

        String guestView = mockMvc.perform(get("/api/public/orders/by-token/" + p.publicToken()))
                .andReturn().getResponse().getContentAsString();
        JsonNode t = objectMapper.readTree(guestView);
        assertEquals("CANCELLED", t.get("status").asText());
        assertEquals("A dish is out of stock", t.get("cancelReasonLabel").asText());
        assertFalse(guestView.contains("Lamb delivery"), "the staff note must never reach the guest");
    }

    @Test
    void cancellingWithoutAReasonStillWorksSoOlderCallersAreNotBroken() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);

        assertEquals(200, setStatus(shop, p.id(), "CANCELLED", null, null));

        assertEquals("CANCELLED", currentStatus(p));
        assertTrue(tracking(p).get("cancelReasonLabel").isNull());
        JsonNode h = history(shop, p.id());
        assertTrue(h.get(h.size() - 1).get("reason").isNull());
    }

    @Test
    void aBadReasonIsRefusedBeforeAnythingChanges() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);

        assertEquals(400, setStatus(shop, p.id(), "CANCELLED", "BECAUSE", null));
        assertEquals(400, setStatus(shop, p.id(), "CANCELLED", "OTHER", null));                  // needs a note
        assertEquals(400, setStatus(shop, p.id(), "CANCELLED", "OTHER", "   "));
        assertEquals(400, setStatus(shop, p.id(), "CANCELLED", "OUT_OF_STOCK", "x".repeat(201)));

        assertEquals("PENDING", currentStatus(p), "a refused cancellation must not cancel");
        assertEquals(1, history(shop, p.id()).size());
    }

    @Test
    void otherWithANoteIsAccepted() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(200, setStatus(shop, p.id(), "CANCELLED", "OTHER", "Power cut"));
        assertEquals("Another reason", tracking(p).get("cancelReasonLabel").asText());
    }

    @Test
    void aReasonOnAnythingButACancellationIsRefused() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(400, setStatus(shop, p.id(), "CONFIRMED", "OUT_OF_STOCK", null));
        assertEquals(400, setStatus(shop, p.id(), "CONFIRMED", null, "hello"));
        assertEquals("PENDING", currentStatus(p));
        assertEquals(200, setStatus(shop, p.id(), "CONFIRMED", null, null));
    }

    @Test
    void retryingTheSameCancellationDoesNotDuplicateTheHistory() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(200, setStatus(shop, p.id(), "CANCELLED", "GUEST_REQUEST", null));
        assertEquals(200, setStatus(shop, p.id(), "CANCELLED", "GUEST_REQUEST", null));
        assertEquals(2, history(shop, p.id()).size());
        assertEquals("The guest asked to cancel", tracking(p).get("cancelReasonLabel").asText());
    }

    @Test
    void aCancelledOrderCannotBeRevivedAndTheHistoryKeepsItsReason() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(200, setStatus(shop, p.id(), "CANCELLED", "DUPLICATE_ORDER", null));
        assertEquals(400, setStatus(shop, p.id(), "CONFIRMED", null, null));
        assertEquals(400, kitchen(shop, p.id(), "preparing"));
        assertEquals("CANCELLED", currentStatus(p));
        assertEquals("DUPLICATE_ORDER", history(shop, p.id()).get(1).get("reason").asText());
    }

    // ------------------------------------------------------------------ kitchen timing

    @Test
    void theKitchenBoardCarriesTheTargetFromTheSlowestDishAndAFreshOrderIsNotDelayed() throws Exception {
        Shop shop = shop();
        placeOrder(shop);

        JsonNode board = json(as(get("/api/v1/kitchen/orders/active"), shop.token()));
        assertEquals(1, board.size());
        JsonNode o = board.get(0);
        assertEquals(12, o.get("prepTargetMinutes").asInt());
        assertTrue(o.get("elapsedMinutes").asLong() < 2);
        assertFalse(o.get("delayed").asBoolean());
        assertEquals(0, o.get("overdueMinutes").asLong());
    }

    @Test
    void aDishWithNoPrepTimeFallsBackToTheDefaultTarget() throws Exception {
        Shop shop = shop();
        long category = json(as(get("/api/categories?size=10"), shop.token())).get("data").get("items").get(0).get("id").asLong();
        long plain = json(withBody(post("/api/menu-items"), shop.token(),
                "{\"name\":\"Plain\",\"price\":50.00,\"preparationTime\":0,\"categoryId\":" + category + "}"))
                .get("data").get("id").asLong();
        String guest = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Amit");
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                .header(DinerTestSupport.HEADER, guest), null, "{\"menuItemId\":" + plain + ",\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, guest), null, "{}")).andExpect(status().isCreated());

        JsonNode o = json(as(get("/api/v1/kitchen/orders/active"), shop.token())).get(0);
        assertEquals(15, o.get("prepTargetMinutes").asInt());
    }

    // ------------------------------------------------------------------ tenancy and access

    @Test
    void anotherRestaurantCannotReadAnOrdersHistory() throws Exception {
        Shop mine = shop();
        Shop other = shop();
        Placed p = placeOrder(mine);
        mockMvc.perform(as(get("/api/v1/orders/" + p.id() + "/history"), other.token())).andExpect(status().isNotFound());
        assertEquals(404, setStatus(other, p.id(), "CANCELLED", "OUT_OF_STOCK", null),
                "another restaurant must not be able to cancel it either");
        assertEquals("PENDING", currentStatus(p));
    }

    @Test
    void theHistoryNeedsASignIn() throws Exception {
        mockMvc.perform(get("/api/v1/orders/1/history")).andExpect(status().isUnauthorized());
    }
}
