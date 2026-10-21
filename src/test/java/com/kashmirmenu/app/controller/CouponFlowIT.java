package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The diner side of coupons: before this feature a coupon could be created in the admin console but
 * never reached a bill. Each dish is 600.00, so a cart of two is 1,200.00 and the arithmetic below can
 * be checked by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class CouponFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, long restaurantId, String qr, long menuItemId, String diner) {
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

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual);
    }

    private Shop setupShop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(authed(get("/api/v1/me"), token)).get("restaurantId").asLong();

        long categoryId = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long menuItemId = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh\",\"price\":600.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}")).get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());

        return new Shop(token, restaurantId, qr, menuItemId,
                DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest"));
    }

    /** Creates a coupon owned by the shop. Dates span now unless overridden. */
    private long createCoupon(Shop shop, String code, String type, String value, String minimum,
                              String maximumDiscount, int usageLimit) throws Exception {
        return createCoupon(shop, code, type, value, minimum, maximumDiscount, usageLimit, 0);
    }

    /** @param minimumItems fewest dishes the order must contain; 0 for no requirement */
    private long createCoupon(Shop shop, String code, String type, String value, String minimum,
                              String maximumDiscount, int usageLimit, int minimumItems) throws Exception {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        String body = """
                {"restaurantId":%d,"code":"%s","name":"%s offer","discountType":"%s","discountValue":%s,
                 "minimumOrderAmount":%s,%s"minimumItems":%d,"usageLimit":%d,
                 "startDate":"%s","expiryDate":"%s","active":true}
                """.formatted(shop.restaurantId(), code, code, type, value, minimum,
                maximumDiscount == null ? "" : "\"maximumDiscount\":" + maximumDiscount + ",",
                minimumItems, usageLimit, start, end);
        return json(withBody(post("/api/v1/coupons"), shop.token(), body)).get("id").asLong();
    }

    private void addTwoDishes(Shop shop) throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                        "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":2}"))
                .andExpect(status().isOk());
    }

    private JsonNode applyCoupon(Shop shop, String code, int expectedStatus) throws Exception {
        String body = mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/coupon").header(DinerTestSupport.HEADER, shop.diner()),
                        null, "{\"couponCode\":\"" + code + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private JsonNode cart(Shop shop) throws Exception {
        return json(get("/api/public/tables/" + shop.qr() + "/cart").header(DinerTestSupport.HEADER, shop.diner()));
    }

    private JsonNode coupon(Shop shop, long couponId) throws Exception {
        return json(authed(get("/api/v1/coupons/" + couponId), shop.token()));
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void aDinerAppliesACoupon_andTheDiscountReachesTheOrder() throws Exception {
        Shop shop = setupShop();
        long couponId = createCoupon(shop, "WELCOME10", "PERCENTAGE", "10", "0", null, 0);
        addTwoDishes(shop);

        JsonNode applied = applyCoupon(shop, "WELCOME10", 200);
        assertMoney("1200", applied.get("subtotal"));
        assertMoney("120", applied.get("discount"));
        assertMoney("1080", applied.get("total"));
        assertEquals("WELCOME10", applied.get("couponCode").asText());

        // Checking a code must not consume it.
        assertEquals(0, coupon(shop, couponId).get("usedCount").asInt());

        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"));
        assertMoney("1200", order.get("subtotal"));
        assertMoney("120", order.get("discount"));
        assertMoney("1080", order.get("total"));
        assertEquals("WELCOME10", order.get("couponCode").asText());

        // Exactly one use is claimed, at the point the order is placed.
        assertEquals(1, coupon(shop, couponId).get("usedCount").asInt());

        // The diner's tracking page shows the saving, and payment is due on the discounted total.
        JsonNode tracking = json(get("/api/public/orders/by-token/" + order.get("publicToken").asText()));
        assertMoney("120", tracking.get("discount"));
        assertMoney("1080", tracking.get("balanceDue"));
        assertEquals("WELCOME10", tracking.get("couponCode").asText());
    }

    @Test
    void acodeIsMatchedCaseInsensitively_andCanBeRemoved() throws Exception {
        Shop shop = setupShop();
        createCoupon(shop, "WELCOME10", "FIXED", "150", "0", null, 0);
        addTwoDishes(shop);

        assertMoney("150", applyCoupon(shop, "welcome10", 200).get("discount"));

        JsonNode removed = json(delete("/api/public/tables/" + shop.qr() + "/cart/coupon").header(DinerTestSupport.HEADER, shop.diner()));
        assertMoney("0", removed.get("discount"));
        assertMoney("1200", removed.get("total"));
        assertNull(removed.get("couponCode").textValue());
    }

    // ------------------------------------------------------------------ the rules

    @Test
    void aCouponIsRefusedWithAReasonTheDinerCanAct_on() throws Exception {
        Shop shop = setupShop();
        createCoupon(shop, "BIGSPEND", "PERCENTAGE", "20", "5000", null, 0);
        createCoupon(shop, "GONE", "FIXED", "100", "0", null, 1);
        addTwoDishes(shop);

        // Below the minimum: refused, and nothing is discounted.
        applyCoupon(shop, "BIGSPEND", 400);
        assertMoney("0", cart(shop).get("discount"));

        // Unknown code.
        applyCoupon(shop, "NOPE", 400);

        // An empty cart cannot carry a coupon.
        Shop other = setupShop();
        createCoupon(other, "ANY", "FIXED", "50", "0", null, 0);
        applyCoupon(other, "ANY", 400);
    }

    @Test
    void aCouponFromAnotherRestaurantCannotBeUsed() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();
        createCoupon(a, "ASHARED", "FIXED", "100", "0", null, 0);
        addTwoDishes(b);

        applyCoupon(b, "ASHARED", 400);
        assertMoney("0", cart(b).get("discount"));
    }

    @Test
    void aCouponIsDroppedIfTheCartStopsQualifying() throws Exception {
        Shop shop = setupShop();
        createCoupon(shop, "OVER1000", "PERCENTAGE", "10", "1000", null, 0);
        addTwoDishes(shop);
        assertMoney("120", applyCoupon(shop, "OVER1000", 200).get("discount"));

        // Drop to one dish (600) and the coupon no longer qualifies, so it comes off rather than
        // quietly discounting a bill below its minimum.
        long cartItemId = cart(shop).get("items").get(0).get("id").asLong();
        JsonNode reduced = json(withBody(put("/api/public/tables/" + shop.qr() + "/cart/items/" + cartItemId).header(DinerTestSupport.HEADER, shop.diner()),
                null, "{\"quantity\":1}"));
        assertMoney("600", reduced.get("subtotal"));
        assertMoney("0", reduced.get("discount"));
        assertMoney("600", reduced.get("total"));
        assertNull(reduced.get("couponCode").textValue());
    }

    @Test
    void aCouponCanAlsoRequireANumberOfDishes() throws Exception {
        Shop shop = setupShop();
        // Spend 200 and order at least 3 dishes. Two dishes at 600 clear the money, not the count.
        createCoupon(shop, "THREEDISH", "FIXED", "100", "200", null, 0, 3);
        addTwoDishes(shop);

        applyCoupon(shop, "THREEDISH", 400);
        assertMoney("0", cart(shop).get("discount"));

        // A third dish satisfies it.
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                        "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":1}"))
                .andExpect(status().isOk());
        assertMoney("100", applyCoupon(shop, "THREEDISH", 200).get("discount"));
    }

    @Test
    void aFixedCouponNeverMakesTheBillNegative() throws Exception {
        Shop shop = setupShop();
        createCoupon(shop, "HUGE", "FIXED", "5000", "0", null, 0);
        addTwoDishes(shop);

        JsonNode applied = applyCoupon(shop, "HUGE", 200);
        assertMoney("1200", applied.get("discount"));
        assertMoney("0", applied.get("total"));
    }

    @Test
    void theUsageLimitHolds_andACancelledOrderGivesTheUseBack() throws Exception {
        Shop shop = setupShop();
        long couponId = createCoupon(shop, "ONLYONE", "FIXED", "100", "0", null, 1);

        addTwoDishes(shop);
        applyCoupon(shop, "ONLYONE", 200);
        JsonNode first = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"));
        assertMoney("100", first.get("discount"));
        assertEquals(1, coupon(shop, couponId).get("usedCount").asInt());

        // The single use is gone, so it cannot be applied again.
        addTwoDishes(shop);
        applyCoupon(shop, "ONLYONE", 400);

        // Cancelling the order releases the use...
        mockMvc.perform(authed(put("/api/v1/orders/" + first.get("id").asLong() + "/status?status=CANCELLED"),
                shop.token())).andExpect(status().isOk());
        assertEquals(0, coupon(shop, couponId).get("usedCount").asInt());

        // ... and it can be used again.
        assertMoney("100", applyCoupon(shop, "ONLYONE", 200).get("discount"));
    }

    @Test
    void aPercentageIsCappedByTheMaximumDiscount() throws Exception {
        Shop shop = setupShop();
        createCoupon(shop, "HALF", "PERCENTAGE", "50", "0", "150", 0);
        addTwoDishes(shop);

        assertMoney("150", applyCoupon(shop, "HALF", 200).get("discount"));
    }

    // ------------------------------------------------------------------ admin side

    @Test
    void couponManagementIsRoleProtectedAndTenantScoped() throws Exception {
        Shop shop = setupShop();
        long couponId = createCoupon(shop, "MINE", "FIXED", "100", "0", null, 0);

        // A customer account could previously create and delete any restaurant's coupons.
        String email = "cust-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String customer = json(withBody(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .get("accessToken").asText();

        mockMvc.perform(authed(get("/api/v1/coupons?restaurantId=" + shop.restaurantId()), customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/coupons/" + couponId), customer))
                .andExpect(status().isForbidden());

        // Another restaurant's owner cannot read, edit or delete it either, and cannot list
        // someone else's coupons by passing their restaurant id.
        Shop other = setupShop();
        mockMvc.perform(authed(get("/api/v1/coupons/" + couponId), other.token()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(authed(delete("/api/v1/coupons/" + couponId), other.token()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(authed(get("/api/v1/coupons?restaurantId=" + shop.restaurantId()), other.token()))
                .andExpect(status().is4xxClientError());

        // The owner still can.
        assertEquals("MINE", coupon(shop, couponId).get("code").asText());
    }

    @Test
    void previewingACouponDoesNotConsumeIt() throws Exception {
        Shop shop = setupShop();
        long couponId = createCoupon(shop, "PREVIEW", "PERCENTAGE", "10", "0", null, 5);

        for (int i = 0; i < 3; i++) {
            JsonNode quote = json(withBody(post("/api/v1/coupons/apply"), shop.token(),
                    "{\"restaurantId\":" + shop.restaurantId() + ",\"couponCode\":\"PREVIEW\",\"orderAmount\":1000.00}"));
            assertMoney("100", quote.get("discountAmount"));
            assertMoney("900", quote.get("finalAmount"));
        }
        // The old implementation incremented usedCount on every check, so this was 3.
        assertEquals(0, coupon(shop, couponId).get("usedCount").asInt());
    }
}
