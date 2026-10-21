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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Negative tests for the authorization boundaries, covering the audit's two Critical findings:
 * admin APIs that accepted any authenticated user (SEC-001) and services that took a restaurant id
 * from the request without checking it (SEC-002).
 *
 * <p>These are regression tests in the strict sense: each one failed before the corresponding fix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class AuthorizationBoundaryIT {

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
                "{\"name\":\"Rogan Josh " + id + "\",\"price\":600.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}")).get("qrIdentifier").asText();

        return new Shop(token, restaurantId, menuItemId, qr,
                DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest"));
    }

    /** A self-registered consumer account: the lowest privilege any attacker can obtain for free. */
    private String registerCustomer() throws Exception {
        String email = "cust-" + unique() + "@example.com";
        return json(withBody(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .get("accessToken").asText();
    }

    // ------------------------------------------------------------------ SEC-001

    @Test
    void anonymousCallersCannotReachAnyBusinessEndpoint() throws Exception {
        for (String path : List.of(
                "/api/v1/dashboard/summary", "/api/v1/inventory", "/api/categories", "/api/menu-items",
                "/api/v1/tables", "/api/v1/orders", "/api/v1/notifications",
                "/api/v1/payment-analytics/overview", "/api/v1/coupons", "/api/v1/kitchen/orders/active")) {

            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aSelfRegisteredCustomerCannotReachStaffOrAdminEndpoints() throws Exception {
        String customer = registerCustomer();

        // Anyone can register in one unauthenticated request, so this is the baseline attacker.
        // Every one of these was reachable before the deny-by-default rule was added.
        for (String path : List.of(
                "/api/v1/dashboard/summary", "/api/v1/inventory", "/api/categories", "/api/menu-items",
                "/api/v1/tables", "/api/v1/orders", "/api/v1/notifications",
                "/api/v1/payment-analytics/overview", "/api/v1/coupons",
                "/api/v1/kitchen/orders/active", "/api/v1/kitchen/orders/today")) {

            mockMvc.perform(authed(get(path), customer))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aCustomerCannotWriteOrDestroyRestaurantData() throws Exception {
        Shop shop = setupShop();
        String customer = registerCustomer();

        mockMvc.perform(authed(delete("/api/menu-items/" + shop.menuItemId()), customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(withBody(post("/api/categories"), customer, "{\"name\":\"Injected\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withBody(post("/api/v1/inventory"), customer,
                        "{\"restaurantId\":" + shop.restaurantId() + ",\"menuItemId\":" + shop.menuItemId()
                                + ",\"availableQuantity\":1,\"minimumQuantity\":1}"))
                .andExpect(status().isForbidden());

        // The owner's data is untouched.
        mockMvc.perform(authed(get("/api/menu-items/" + shop.menuItemId()), shop.token()))
                .andExpect(status().isOk());
    }

    @Test
    void anEndpointWithNoExplicitGrantIsDeniedRatherThanOpen() throws Exception {
        // The fallback rule is denyAll, so a path that no matcher grants must not fall through to
        // "authenticated". This is what stops a newly added controller from shipping unprotected.
        String customer = registerCustomer();

        mockMvc.perform(authed(get("/api/v1/does-not-exist-yet"), customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/actuator/info"), customer))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ SEC-002

    @Test
    void oneRestaurantCannotReadOrRewriteAnothersPaymentSettings() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();

        // This helper does not create payment settings, so A's are created here. UPI is enabled, so
        // a UPI id is required - the service rejects UPI-enabled settings without one.
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"),
                        a.token(), "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"shop@upi\"}"))
                .andExpect(status().isCreated());

        // B must not be able to read A's settings...
        mockMvc.perform(authed(get("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"), b.token()))
                .andExpect(status().isNotFound());

        // ...nor disable every payment method A's diners can choose, which was a one-request
        // denial of service against a competitor's checkout.
        mockMvc.perform(withBody(put("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"),
                        b.token(), """
                        {"cashEnabled":false,"upiEnabled":false,"cardEnabled":false,"walletEnabled":false}
                        """))
                .andExpect(status().isNotFound());

        // A's diners can still pay.
        JsonNode table = json(get("/api/public/tables/" + a.qr()));
        assertFalse(table.get("paymentMethods").isEmpty(), "A's payment methods must be unchanged");
    }

    @Test
    void inventoryIsScopedToTheCallersRestaurant() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();

        long inventoryId = json(withBody(post("/api/v1/inventory"), a.token(),
                "{\"restaurantId\":" + a.restaurantId() + ",\"menuItemId\":" + a.menuItemId()
                        + ",\"availableQuantity\":42,\"minimumQuantity\":5}"))
                .get("data").get("id").asLong();

        // Listing used to be findAll(), returning every restaurant's stock to any caller.
        JsonNode bList = json(authed(get("/api/v1/inventory"), b.token()));
        assertFalse(bList.get("data").toString().contains("\"id\":" + inventoryId),
                "B must not see A's stock rows");

        // Nor read, change or delete a row by its id.
        mockMvc.perform(authed(get("/api/v1/inventory/" + inventoryId), b.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(withBody(patch("/api/v1/inventory/" + inventoryId + "/stock"), b.token(),
                        "{\"availableQuantity\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/inventory/" + inventoryId), b.token()))
                .andExpect(status().isNotFound());

        // A still has its stock, at the original quantity.
        mockMvc.perform(authed(get("/api/v1/inventory/" + inventoryId), a.token()))
                .andExpect(status().isOk());
    }

    @Test
    void aRestaurantIdInTheRequestCannotRedirectWhoseDataIsReturned() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();

        // The notification endpoints took restaurantId as a query parameter with no ownership check.
        // The parameter is still accepted for compatibility but must now be ignored entirely.
        JsonNode asB = json(authed(
                get("/api/v1/notifications/unread/count?restaurantId=" + a.restaurantId()), b.token()));
        JsonNode bOwn = json(authed(get("/api/v1/notifications/unread/count"), b.token()));

        org.junit.jupiter.api.Assertions.assertEquals(bOwn.asLong(), asB.asLong(),
                "passing another restaurant's id must not change the answer");

        // Creating inventory must likewise ignore the restaurantId in the body.
        long inventoryId = json(withBody(post("/api/v1/inventory"), b.token(),
                "{\"restaurantId\":" + a.restaurantId() + ",\"menuItemId\":" + b.menuItemId()
                        + ",\"availableQuantity\":7,\"minimumQuantity\":1}"))
                .get("data").get("id").asLong();

        // It belongs to B (the caller), not to A (the id in the body).
        mockMvc.perform(authed(get("/api/v1/inventory/" + inventoryId), b.token()))
                .andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/inventory/" + inventoryId), a.token()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ SEC-004

    @Test
    void anotherTablesOrderCannotBeReadByGuessingTheOrderNumber() throws Exception {
        Shop shop = setupShop();

        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":1}")).andExpect(status().isOk());
        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"));

        String number = order.get("orderNumber").asText();   // ORD-1, ORD-2, ...
        String token = order.get("publicToken").asText();

        // The readable number is not a lookup key: it is sequential, so it could be counted through.
        mockMvc.perform(get("/api/public/orders/by-token/" + number))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/orders/by-token/ORD-1"))
                .andExpect(status().isNotFound());

        // The token is, and it is long enough not to be guessed.
        mockMvc.perform(get("/api/public/orders/by-token/" + token))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(token.length() >= 32,
                "the public token must be long enough to resist guessing");
    }

    // ------------------------------------------------------------------ token handling

    @Test
    void malformedForgedAndMissingTokensAreAllRejected() throws Exception {
        // A token signed with a different key must not be accepted. This is the condition that the
        // dev-profile placeholder secret made exploitable against real accounts.
        String foreign = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJzdWIiOiJvd25lckBleGFtcGxlLmNvbSIsImV4cCI6NDEwMjQ0NDgwMH0."
                + "ZmFrZS1zaWduYXR1cmUtbm90LXZhbGlk";

        for (String bad : List.of("not-a-jwt", "Bearer", foreign)) {
            mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
}
