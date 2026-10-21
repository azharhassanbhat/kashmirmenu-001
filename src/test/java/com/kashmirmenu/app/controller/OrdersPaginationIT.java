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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-005 (unbounded order listing) and BUG-009 (one request per row from the browser).
 *
 * <p>The listing returned every order a restaurant had ever taken, with a lazy items collection the
 * mapper then touched per row. The browser compounded it by fetching each order's payment summary
 * separately, so the two multiplied with trading volume.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class OrdersPaginationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, long restaurantId, long menuItemId, String qr, String diner) {
    }

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
                "{\"name\":\"Dish " + id + "\",\"price\":600.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}")).get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());

        return new Shop(token, restaurantId, menuItemId, qr,
                DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest"));
    }

    private long placeOrder(Shop shop) throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":1}")).andExpect(status().isOk());
        return json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"))
                .get("id").asLong();
    }

    @Test
    void theListingIsPagedAndNewestFirst() throws Exception {
        Shop shop = setupShop();
        long first = placeOrder(shop);
        for (int i = 0; i < 4; i++) {
            placeOrder(shop);
        }
        long last = placeOrder(shop);

        JsonNode page = json(authed(get("/api/v1/orders?page=0&size=3"), shop.token()));

        assertEquals(3, page.get("items").size(), "the page size must be respected");
        assertEquals(6, page.get("totalItems").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        assertTrue(page.get("hasNext").asBoolean());
        assertFalse(page.get("hasPrevious").asBoolean());

        assertEquals(last, page.get("items").get(0).get("id").asLong(), "newest first");

        JsonNode second = json(authed(get("/api/v1/orders?page=1&size=3"), shop.token()));
        assertEquals(first, second.get("items").get(2).get("id").asLong(), "oldest last");
    }

    @Test
    void aClientCannotAskForTheUnboundedListAgain() throws Exception {
        Shop shop = setupShop();
        for (int i = 0; i < 3; i++) {
            placeOrder(shop);
        }

        // The cap is what stops size=100000 reintroducing the original defect.
        JsonNode page = json(authed(get("/api/v1/orders?page=0&size=100000"), shop.token()));
        assertTrue(page.get("size").asInt() <= 100,
                "page size must be capped server-side, not trusted from the query string");
    }

    @Test
    void eachRowCarriesItsPaymentStateAndItems() throws Exception {
        // BUG-009: these fields exist so the browser does not fetch a summary per row.
        Shop shop = setupShop();
        long orderId = placeOrder(shop);

        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + orderId + "/collect"), shop.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":600}]}")).andExpect(status().isCreated());

        JsonNode row = json(authed(get("/api/v1/orders"), shop.token())).get("items").get(0);

        assertEquals("SUCCESS", row.get("paymentStatus").asText());
        assertEquals(0, new java.math.BigDecimal("600").compareTo(row.get("amountPaid").decimalValue()));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(row.get("balanceDue").decimalValue()));

        // Items must be present: they are fetched for the page in a separate query, and a mistake
        // there shows up as an empty array rather than an error.
        assertEquals(1, row.get("items").size());
        assertEquals(1, row.get("items").get(0).get("quantity").asInt());
    }

    @Test
    void anUnpaidOrderReportsItsFullBalance() throws Exception {
        Shop shop = setupShop();
        placeOrder(shop);

        JsonNode row = json(authed(get("/api/v1/orders"), shop.token())).get("items").get(0);

        assertTrue(row.get("paymentStatus").isNull(), "nothing paid yet");
        assertEquals(0, new java.math.BigDecimal("600").compareTo(row.get("balanceDue").decimalValue()));
    }

    @Test
    void theStatusListingIsPagedAndBatchedToo() throws Exception {
        // The last place the unbounded-plus-N+1 pattern survived: it was skipped twice for having no
        // frontend consumer, which did not stop it being a live authenticated endpoint.
        Shop shop = setupShop();
        for (int i = 0; i < 4; i++) {
            placeOrder(shop);
        }

        JsonNode page = json(authed(get("/api/v1/orders/status/PENDING?page=0&size=2"), shop.token()));

        assertEquals(2, page.get("items").size());
        assertEquals(4, page.get("totalItems").asInt());
        assertTrue(page.get("hasNext").asBoolean());

        // Items and payment state come with the row, as on the main listing.
        JsonNode row = page.get("items").get(0);
        assertEquals(1, row.get("items").size());
        assertEquals(0, new java.math.BigDecimal("600").compareTo(row.get("balanceDue").decimalValue()));

        // And the size cap applies here as well.
        assertTrue(json(authed(get("/api/v1/orders/status/PENDING?size=100000"), shop.token()))
                .get("size").asInt() <= 100);
    }

    @Test
    void anInvalidStatusIsStillRejected() throws Exception {
        Shop shop = setupShop();
        mockMvc.perform(authed(get("/api/v1/orders/status/NOT_A_STATUS"), shop.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oneRestaurantNeverSeesAnothersOrders() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();
        placeOrder(a);

        JsonNode page = json(authed(get("/api/v1/orders"), b.token()));
        assertEquals(0, page.get("totalItems").asInt(), "paging must not widen the tenant scope");
    }
}
