package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Money and ordering are the parts of this application an attacker profits from, so the rules are tested at the
 * HTTP boundary: a diner can neither set a price nor mark anything paid, only authorised staff can confirm money,
 * the same payment cannot be taken twice, and everything a person with authority does with money is recorded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class PaymentAndOrderSecurityIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuditRepository auditRepository;

    private record Shop(String token, long restaurantId, long menuItemId, String qr, String diner) {
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();
        if (response.getStatus() >= 400) {
            throw new AssertionError("Setup call failed with " + response.getStatus() + ": " + response.getContentAsString());
        }
        String body = response.getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private static long idOf(JsonNode n) {
        JsonNode id = n.at("/data/id");
        return (id.isMissingNode() ? n.at("/id") : id).asLong();
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, String token, String json) {
        b.contentType(MediaType.APPLICATION_JSON).content(json);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(b).andReturn().getResponse().getStatus();
    }

    private Shop shop() throws Exception {
        String id = unique();
        String token = json(body(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(as(get("/api/v1/me"), token)).get("restaurantId").asLong();
        long category = idOf(json(body(post("/api/categories"), token, "{\"name\":\"Mains\"}")));
        long item = idOf(json(body(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh " + id + "\",\"price\":400.00,\"categoryId\":" + category + "}")));
        mockMvc.perform(body(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"shop@upi\"}"));
        String qr = json(body(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}")).get("qrIdentifier").asText();
        return new Shop(token, restaurantId, item, qr, DinerTestSupport.join(mockMvc, objectMapper, qr, "Asha"));
    }

    private MockHttpServletRequestBuilder asDiner(MockHttpServletRequestBuilder b, Shop s) {
        return b.header(DinerTestSupport.HEADER, s.diner());
    }

    private JsonNode placeOrder(Shop s, int quantity) throws Exception {
        mockMvc.perform(body(asDiner(post("/api/public/tables/" + s.qr() + "/cart/items"), s), null,
                "{\"menuItemId\":" + s.menuItemId() + ",\"quantity\":" + quantity + "}")).andReturn();
        return json(body(asDiner(post("/api/public/tables/" + s.qr() + "/orders"), s), null, "{}"));
    }

    // ------------------------------------------------------------------ ordering

    @Test
    void theServerPricesTheOrderWhateverTheBrowserSends() throws Exception {
        Shop s = shop();
        // Extra fields a forged client might add are not part of the contract and change nothing.
        mockMvc.perform(body(asDiner(post("/api/public/tables/" + s.qr() + "/cart/items"), s), null,
                "{\"menuItemId\":" + s.menuItemId() + ",\"quantity\":2,\"price\":1,\"unitPrice\":1,\"total\":1,\"lineTotal\":1}"));
        JsonNode order = json(body(asDiner(post("/api/public/tables/" + s.qr() + "/orders"), s), null,
                "{\"total\":1,\"subtotal\":1,\"discount\":9999,\"status\":\"SERVED\",\"paymentStatus\":\"SUCCESS\"}"));
        assertEquals(0, new BigDecimal("800").compareTo(new BigDecimal(order.get("total").asText())),
                "2 x 400.00 is 800, not what the browser claimed");
        assertEquals("PENDING", order.get("status").asText(), "a diner cannot choose the order's status");
    }

    @Test
    void absurdQuantitiesAreRefused() throws Exception {
        Shop s = shop();
        for (String quantity : new String[]{"0", "-1", "-100", "100000", "2147483648", "1.5", "\"abc\"", "null"}) {
            int code = status(body(asDiner(post("/api/public/tables/" + s.qr() + "/cart/items"), s), null,
                    "{\"menuItemId\":" + s.menuItemId() + ",\"quantity\":" + quantity + "}"));
            assertTrue(code == 400 || code == 422, "quantity " + quantity + " answered " + code);
        }
    }

    @Test
    void aDishFromAnotherRestaurantCannotBeOrdered() throws Exception {
        Shop a = shop();
        Shop b = shop();
        int code = status(body(asDiner(post("/api/public/tables/" + a.qr() + "/cart/items"), a), null,
                "{\"menuItemId\":" + b.menuItemId() + ",\"quantity\":1}"));
        assertTrue(code == 404 || code == 400, "B's dish put in A's cart answered " + code);
    }

    @Test
    void anotherGuestsTokenOrAnotherTablesTokenCannotReadOrChangeMyCart() throws Exception {
        Shop a = shop();
        Shop b = shop();
        assertTrue(status(get("/api/public/tables/" + a.qr() + "/cart").header(DinerTestSupport.HEADER, b.diner())) >= 400,
                "B's guest token must not open A's table");
        assertTrue(status(get("/api/public/tables/" + a.qr() + "/cart").header(DinerTestSupport.HEADER, "x".repeat(40))) >= 400,
                "a made-up token must not open a cart");
        assertTrue(status(get("/api/public/tables/" + a.qr() + "/cart")) >= 400, "no token, no cart");
    }

    // ------------------------------------------------------------------ marking things paid

    @Test
    void aDinerCannotForgeAPaymentStatusOrAmount() throws Exception {
        Shop s = shop();
        JsonNode order = placeOrder(s, 1);
        String token = order.get("publicToken").asText();

        // Choosing "pay by UPI" only opens a PENDING payment for the full, server-computed amount.
        JsonNode payment = json(body(post("/api/public/orders/by-token/" + token + "/payments"), null,
                "{\"paymentMethod\":\"UPI\",\"amount\":1,\"paymentStatus\":\"SUCCESS\",\"status\":\"SUCCESS\",\"transactionId\":\"FORGED\"}"));
        assertEquals("PENDING", payment.get("paymentStatus").asText(), "the diner's request must never produce a verified payment");
        assertEquals(0, new BigDecimal("400").compareTo(new BigDecimal(payment.get("amount").asText())));
        assertTrue(payment.get("transactionId") == null || payment.get("transactionId").isNull(), "no forged reference is stored");

        // And the staff-only endpoint that does confirm money refuses every non-staff caller.
        long paymentId = payment.get("id").asLong();
        String confirm = "{\"paymentStatus\":\"SUCCESS\"}";
        assertTrue(status(body(put("/api/v1/payments/" + paymentId + "/status"), null, confirm)) == 401
                || status(body(put("/api/v1/payments/" + paymentId + "/status"), null, confirm)) == 403, "anonymous");
        String customer = json(body(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"c-" + unique() + "@example.com\",\"password\":\"Password123\"}")).get("accessToken").asText();
        assertEquals(403, status(body(put("/api/v1/payments/" + paymentId + "/status"), customer, confirm)), "a registered customer");

        // Nothing changed.
        JsonNode after = json(as(get("/api/v1/payments/" + paymentId), s.token()));
        assertEquals("PENDING", (after.has("data") ? after.get("data") : after).get("paymentStatus").asText());
    }

    @Test
    void aKitchenAccountCannotConfirmMoney() throws Exception {
        Shop s = shop();
        String chefEmail = "chef-" + unique() + "@example.com";
        json(body(post("/api/v1/staff"), s.token(),
                "{\"name\":\"Chef\",\"email\":\"" + chefEmail + "\",\"role\":\"CHEF\",\"password\":\"Chef2026pw\"}"));
        String chef = json(body(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + chefEmail + "\",\"password\":\"Chef2026pw\"}")).get("accessToken").asText();
        JsonNode order = placeOrder(s, 1);
        long orderId = order.get("id").asLong();
        assertEquals(403, status(body(post("/api/v1/payments/orders/" + orderId + "/collect"), chef,
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":400}]}")));
        assertEquals(403, status(body(post("/api/v1/payments/orders/" + orderId), chef, "{\"paymentMethod\":\"CASH\"}")));
        assertEquals(403, status(body(put("/api/v1/payment-analytics/x"), chef, "{}")));
        assertEquals(403, status(as(get("/api/v1/payment-analytics/overview?period=TODAY"), chef)), "a cook cannot read revenue");
        assertEquals(403, status(body(put("/api/v1/restaurants/" + s.restaurantId() + "/payment-settings"), chef, "{\"cashEnabled\":false}")),
                "a cook cannot change where the money goes");
    }

    // ------------------------------------------------------------------ duplicates and over-payment

    @Test
    void theSamePaymentCannotBeTakenTwiceOrOverpaid() throws Exception {
        Shop s = shop();
        JsonNode order = placeOrder(s, 1);
        long orderId = order.get("id").asLong();
        String key = "key-" + unique();
        String once = "{\"idempotencyKey\":\"" + key + "\",\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":400}]}";

        // A double tap with the same key is one payment.
        assertEquals(201, status(body(post("/api/v1/payments/orders/" + orderId + "/collect"), s.token(), once)));
        assertEquals(201, status(body(post("/api/v1/payments/orders/" + orderId + "/collect"), s.token(), once)));
        // A second payment without a key cannot pay an order that is already settled.
        int overpay = status(body(post("/api/v1/payments/orders/" + orderId + "/collect"), s.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":400}]}"));
        assertTrue(overpay >= 400 && overpay < 500, "paying a settled order again answered " + overpay);

        JsonNode summary = json(as(get("/api/v1/payments/orders/" + orderId + "/summary"), s.token()));
        JsonNode s0 = summary.has("data") ? summary.get("data") : summary;
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(s0.get("outstanding").asText())), "settled exactly once");
        assertEquals(0, new BigDecimal("400").compareTo(new BigDecimal(s0.get("settled").asText())), "and not twice");

        // Reusing the key for a different order is refused, not silently honoured.
        JsonNode other = placeOrder(s, 1);
        int reused = status(body(post("/api/v1/payments/orders/" + other.get("id").asLong() + "/collect"), s.token(), once));
        assertTrue(reused >= 400 && reused < 500, "key reuse across orders answered " + reused);
    }

    @Test
    void aDinerCannotOpenTwoPaymentsForOneOrder() throws Exception {
        Shop s = shop();
        String token = placeOrder(s, 1).get("publicToken").asText();
        assertEquals(201, status(body(post("/api/public/orders/by-token/" + token + "/payments"), null, "{\"paymentMethod\":\"UPI\"}")));
        int second = status(body(post("/api/public/orders/by-token/" + token + "/payments"), null, "{\"paymentMethod\":\"UPI\"}"));
        assertTrue(second >= 400 && second < 500, "a second pending payment answered " + second);
    }

    // ------------------------------------------------------------------ payment settings and the audit trail

    @Test
    void upiIdsMustLookLikeUpiIds() throws Exception {
        Shop s = shop();
        String url = "/api/v1/restaurants/" + s.restaurantId() + "/payment-settings";
        for (String bad : new String[]{"a&am=1@upi", "shop@upi?pn=x", "javascript:alert(1)", "two words@upi", "shop", "@upi",
                "shop@", "shop@upi/../x", "<script>@upi", "shop@up i", "a@b@c"}) {
            int code = status(body(put(url), s.token(), "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"" + bad + "\"}"));
            assertEquals(400, code, "'" + bad + "' must be refused");
        }
        for (String good : new String[]{"shop@upi", "my.shop-1@okhdfcbank", "name_9@ybl"}) {
            assertEquals(200, status(body(put(url), s.token(), "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"" + good + "\"}")), good);
        }
        // The saved value is still the last good one.
        assertTrue(json(as(get(url), s.token())).toString().contains("name_9@ybl"));
    }

    @Test
    void moneyEventsAndPaymentSettingChangesAreAudited() throws Exception {
        Shop s = shop();
        long settingsBefore = auditRepository.findAll().stream().filter(e -> e.getAction() == AuditAction.PAYMENT_SETTINGS_CHANGED).count();
        long collectedBefore = auditRepository.findAll().stream().filter(e -> e.getAction() == AuditAction.PAYMENT_COLLECTED).count();
        long refundBefore = auditRepository.findAll().stream().filter(e -> e.getAction() == AuditAction.PAYMENT_REFUNDED).count();

        assertEquals(200, status(body(put("/api/v1/restaurants/" + s.restaurantId() + "/payment-settings"), s.token(),
                "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"newshop@upi\"}")));
        JsonNode order = placeOrder(s, 1);
        JsonNode paid = json(body(post("/api/v1/payments/orders/" + order.get("id").asLong() + "/collect"), s.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":400}]}"));
        long paymentId = paid.get("payments").get(0).get("id").asLong();
        assertEquals(201, status(body(post("/api/v1/payments/" + paymentId + "/refunds"), s.token(), "{\"amount\":50.00,\"reason\":\"cold\"}")));

        var all = auditRepository.findAll();
        assertEquals(settingsBefore + 1, all.stream().filter(e -> e.getAction() == AuditAction.PAYMENT_SETTINGS_CHANGED).count());
        assertEquals(collectedBefore + 1, all.stream().filter(e -> e.getAction() == AuditAction.PAYMENT_COLLECTED).count());
        assertEquals(refundBefore + 1, all.stream().filter(e -> e.getAction() == AuditAction.PAYMENT_REFUNDED).count());

        var settingsEntry = all.stream().filter(e -> e.getAction() == AuditAction.PAYMENT_SETTINGS_CHANGED)
                .reduce((first, second) -> second).orElseThrow();
        assertNotNull(settingsEntry.getActorEmail(), "the audit line says who");
        assertTrue(settingsEntry.getDetail().contains("ne***@upi"), "the new UPI id is recorded masked: " + settingsEntry.getDetail());
        assertFalse(settingsEntry.getDetail().contains("newshop@upi"), "the full UPI id is never written to the audit trail");
        assertFalse(settingsEntry.getDetail().contains("Password"), "no secrets");
    }

    @Test
    void aStaffMemberConfirmingAPendingPaymentIsAudited() throws Exception {
        Shop s = shop();
        String token = placeOrder(s, 1).get("publicToken").asText();
        long paymentId = json(body(post("/api/public/orders/by-token/" + token + "/payments"), null, "{\"paymentMethod\":\"UPI\"}")).get("id").asLong();
        long before = auditRepository.findAll().stream().filter(e -> e.getAction() == AuditAction.PAYMENT_STATUS_CHANGED).count();
        assertEquals(200, status(body(put("/api/v1/payments/" + paymentId + "/status"), s.token(),
                "{\"paymentStatus\":\"SUCCESS\",\"transactionId\":\"UTR123456\"}")));
        // Confirming it again changes nothing and records nothing more.
        assertEquals(200, status(body(put("/api/v1/payments/" + paymentId + "/status"), s.token(), "{\"paymentStatus\":\"SUCCESS\"}")));
        long after = auditRepository.findAll().stream().filter(e -> e.getAction() == AuditAction.PAYMENT_STATUS_CHANGED).count();
        assertEquals(before + 1, after);
    }
}
