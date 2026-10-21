package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.service.OrderStatusPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-003 (any transition accepted) and BUG-004 (cancellation abandons payments).
 *
 * <p>The transition table always existed in {@code KitchenServiceImpl}, but
 * {@code PUT /api/v1/orders/{id}/status} wrote to the same field without consulting it. These tests
 * drive the REST endpoint specifically, because that is the path that was unguarded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class OrderCancellationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, long restaurantId, long menuItemId, String qr, String diner) {
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response =
                mockMvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString();

        // A setup call that fails used to surface as a NullPointerException several lines later,
        // when the test dereferenced a field the error body does not have - which says nothing
        // about WHY. Failing here, with the status and body, names the actual cause.
        if (response.getStatus() >= 400) {
            throw new AssertionError("Unexpected " + response.getStatus() + " from "
                    + request.buildRequest(new org.springframework.mock.web.MockServletContext())
                    .getMethod() + " "
                    + request.buildRequest(new org.springframework.mock.web.MockServletContext())
                    .getRequestURI() + ": " + body);
        }
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token, String body) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Shop setupShop() throws Exception {
        String id = unique();
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();

        long restaurantId = json(authed(get("/api/v1/me"), token)).get("restaurantId").asLong();
        long categoryId = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long menuItemId = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Dish " + id + "\",\"price\":600.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}")).get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"shop@upi\"}")).andExpect(status().isCreated());

        return new Shop(token, restaurantId, menuItemId, qr,
                DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest"));
    }

    private JsonNode placeOrder(Shop shop) throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":1}")).andExpect(status().isOk());
        return json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"));
    }

    private int setStatus(Shop shop, long orderId, String status) throws Exception {
        return mockMvc.perform(authed(
                        put("/api/v1/orders/" + orderId + "/status?status=" + status), shop.token()))
                .andReturn().getResponse().getStatus();
    }

    private String statusOf(Shop shop, long orderId) throws Exception {
        // The listing is a page ({items, totalItems, ...}), not a bare array.
        for (JsonNode o : json(authed(get("/api/v1/orders"), shop.token())).get("items")) {
            if (o.get("id").asLong() == orderId) {
                return o.get("status").asText();
            }
        }
        throw new AssertionError("order " + orderId + " not found");
    }

    // ------------------------------------------------------------------ BUG-003

    @Test
    void theRestEndpointRefusesIllegalTransitions() throws Exception {
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        // PENDING -> READY skips the kitchen entirely.
        assertEquals(400, setStatus(shop, orderId, "READY"));
        assertEquals("PENDING", statusOf(shop, orderId));

        // Walk it forward legitimately.
        assertEquals(200, setStatus(shop, orderId, "CONFIRMED"));
        assertEquals(200, setStatus(shop, orderId, "PREPARING"));

        // ...and backwards, which used to be accepted.
        assertEquals(400, setStatus(shop, orderId, "PENDING"));
        assertEquals("PREPARING", statusOf(shop, orderId));
    }

    @Test
    void aServedOrderIsTerminal() throws Exception {
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        setStatus(shop, orderId, "CONFIRMED");
        setStatus(shop, orderId, "PREPARING");
        setStatus(shop, orderId, "READY");
        assertEquals(200, setStatus(shop, orderId, "SERVED"));

        // Cancelling served food used to restore stock and release the coupon for food that had
        // already left the kitchen.
        assertEquals(400, setStatus(shop, orderId, "CANCELLED"));
        assertEquals(400, setStatus(shop, orderId, "PENDING"));
        assertEquals("SERVED", statusOf(shop, orderId));
    }

    @Test
    void repeatingTheSameStatusIsANoOp() throws Exception {
        // A retry after a dropped response must not read as a failure.
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        assertEquals(200, setStatus(shop, orderId, "CONFIRMED"));
        assertEquals(200, setStatus(shop, orderId, "CONFIRMED"));
        assertEquals("CONFIRMED", statusOf(shop, orderId));
    }

    @Test
    void theTransitionTableIsTheOneTheKitchenUses() {
        // Guards against the two paths drifting apart again, which is what caused BUG-003.
        assertTrue(OrderStatusPolicy.isAllowed(OrderStatus.PENDING, OrderStatus.CONFIRMED));
        assertTrue(OrderStatusPolicy.isAllowed(OrderStatus.PENDING, OrderStatus.CANCELLED));
        assertTrue(OrderStatusPolicy.isAllowed(OrderStatus.CONFIRMED, OrderStatus.CANCELLED));
        assertFalse(OrderStatusPolicy.isAllowed(OrderStatus.PREPARING, OrderStatus.CANCELLED));
        assertFalse(OrderStatusPolicy.isAllowed(OrderStatus.SERVED, OrderStatus.CANCELLED));
        assertFalse(OrderStatusPolicy.isAllowed(OrderStatus.CANCELLED, OrderStatus.PREPARING));
        for (OrderStatus s : OrderStatus.values()) {
            assertTrue(OrderStatusPolicy.isAllowed(s, s), s + " -> itself must be a no-op");
        }
    }

    // ------------------------------------------------------------------ BUG-004

    @Test
    void cancellingAnOrderCancelsItsPendingPayment() throws Exception {
        Shop shop = setupShop();
        JsonNode order = placeOrder(shop);
        long orderId = order.get("id").asLong();

        // The diner picks a method on their phone, creating a PENDING payment.
        mockMvc.perform(withBody(
                        post("/api/public/orders/by-token/" + order.get("publicToken").asText() + "/payments"),
                        null, "{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated());

        JsonNode beforeCancel = json(authed(
                get("/api/v1/payment-analytics/reconciliation?date=" + LocalDate.now()), shop.token()));
        assertEquals(1, beforeCancel.get("pending").get("count").asInt());

        assertEquals(200, setStatus(shop, orderId, "CANCELLED"));

        // The payment is cancelled, not abandoned.
        JsonNode summary = json(authed(get("/api/v1/payments/orders/" + orderId + "/summary"), shop.token()));
        assertEquals("CANCELLED", summary.get("payments").get(0).get("paymentStatus").asText());

        // And it has left the reconciliation panel's awaiting-confirmation bucket, where it used to
        // sit for ever, overstating the money expected at the close.
        JsonNode afterCancel = json(authed(
                get("/api/v1/payment-analytics/reconciliation?date=" + LocalDate.now()), shop.token()));
        assertEquals(0, afterCancel.get("pending").get("count").asInt());
    }

    @Test
    void anOrderThatHasBeenPaidCannotSimplyBeCancelled() throws Exception {
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + orderId + "/collect"), shop.token(),
                        "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":600}]}"))
                .andExpect(status().isCreated());

        // Refusing is the point: a status change must not quietly strand money that was taken, and
        // net collections must not count a payment whose order no longer exists.
        assertEquals(400, setStatus(shop, orderId, "CANCELLED"));
        assertEquals("PENDING", statusOf(shop, orderId));

        // The payment is untouched and still counted.
        JsonNode overview = json(authed(
                get("/api/v1/payment-analytics/overview?period=TODAY"), shop.token()));
        assertEquals(0, new java.math.BigDecimal("600")
                .compareTo(overview.get("totals").get("gross").decimalValue()));
    }

    @Test
    void cancellingStillReleasesTheCouponAndRestoresStock() throws Exception {
        // The behaviour added in earlier phases must survive this change.
        Shop shop = setupShop();

        long inventoryId = json(withBody(post("/api/v1/inventory"), shop.token(),
                "{\"restaurantId\":" + shop.restaurantId() + ",\"menuItemId\":" + shop.menuItemId()
                        + ",\"availableQuantity\":10,\"minimumQuantity\":1}"))
                .get("data").get("id").asLong();

        String code = "CANC" + unique().substring(0, 4);
        long couponId = json(withBody(post("/api/v1/coupons"), shop.token(), """
                {"restaurantId":%d,"code":"%s","name":"Cancel test","discountType":"FIXED",
                 "discountValue":50,"minimumOrderAmount":0,"minimumItems":0,"usageLimit":5,
                 "startDate":"%s","expiryDate":"%s","active":true}
                """.formatted(shop.restaurantId(), code,
                java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now().plusDays(1))))
                .get("id").asLong();

        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":2}")).andExpect(status().isOk());
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/coupon").header(DinerTestSupport.HEADER, shop.diner()), null,
                "{\"couponCode\":\"" + code + "\"}")).andExpect(status().isOk());

        long orderId = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"))
                .get("id").asLong();

        assertEquals(1, json(authed(get("/api/v1/coupons/" + couponId), shop.token()))
                .get("usedCount").asInt());
        assertEquals(8, json(authed(get("/api/v1/inventory/" + inventoryId), shop.token()))
                .get("data").get("availableQuantity").asInt());

        assertEquals(200, setStatus(shop, orderId, "CANCELLED"));

        assertEquals(0, json(authed(get("/api/v1/coupons/" + couponId), shop.token()))
                .get("usedCount").asInt());
        assertEquals(10, json(authed(get("/api/v1/inventory/" + inventoryId), shop.token()))
                .get("data").get("availableQuantity").asInt());
    }

    // ------------------------------------------------------------------ BUG-002

    @Test
    void cancellingDoesNotCreditStockThatWasNeverTaken() throws Exception {
        // The asymmetry: the order is placed while the dish is UNTRACKED, so nothing is deducted.
        // Tracking is then switched on. Cancelling used to add the quantity back anyway, inflating
        // the count above what the restaurant actually has.
        Shop shop = setupShop();

        long orderId = placeOrder(shop).get("id").asLong();   // no inventory row yet

        long inventoryId = json(withBody(post("/api/v1/inventory"), shop.token(),
                "{\"restaurantId\":" + shop.restaurantId() + ",\"menuItemId\":" + shop.menuItemId()
                        + ",\"availableQuantity\":10,\"minimumQuantity\":1}"))
                .get("data").get("id").asLong();

        assertEquals(200, setStatus(shop, orderId, "CANCELLED"));

        assertEquals(10, json(authed(get("/api/v1/inventory/" + inventoryId), shop.token()))
                        .get("data").get("availableQuantity").asInt(),
                "stock must be unchanged: this order never deducted any");
    }

    @Test
    void stockIsRestoredOnlyOnceEvenIfCancellationIsRepeated() throws Exception {
        Shop shop = setupShop();

        long inventoryId = json(withBody(post("/api/v1/inventory"), shop.token(),
                "{\"restaurantId\":" + shop.restaurantId() + ",\"menuItemId\":" + shop.menuItemId()
                        + ",\"availableQuantity\":10,\"minimumQuantity\":1}"))
                .get("data").get("id").asLong();

        long orderId = placeOrder(shop).get("id").asLong();
        assertEquals(9, json(authed(get("/api/v1/inventory/" + inventoryId), shop.token()))
                .get("data").get("availableQuantity").asInt());

        assertEquals(200, setStatus(shop, orderId, "CANCELLED"));
        // Already CANCELLED, so the transition is a no-op and must not credit again.
        assertEquals(200, setStatus(shop, orderId, "CANCELLED"));

        assertEquals(10, json(authed(get("/api/v1/inventory/" + inventoryId), shop.token()))
                        .get("data").get("availableQuantity").asInt(),
                "a repeated cancellation must not credit stock twice");
    }

    @Test
    void theKitchenPathAndTheRestPathAgree() throws Exception {
        // Both write Order.status. They must refuse the same things, or the guard can be bypassed
        // by choosing the other endpoint - which is exactly what BUG-003 was.
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        for (String kitchenStep : List.of("ready", "served")) {
            mockMvc.perform(authed(put("/api/v1/kitchen/orders/" + orderId + "/" + kitchenStep),
                            shop.token()))
                    .andExpect(status().is4xxClientError());
        }
        assertEquals("PENDING", statusOf(shop, orderId));
    }
}
