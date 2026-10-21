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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Restaurant isolation (BOLA/IDOR) across every endpoint that takes an object id. Restaurant A owns a full set
 * of resources; restaurant B, a different paying tenant with a valid token, attacks each one by id. Every
 * attempt must be refused (403 or 404, never a success and never a 500), and A's data must be untouched after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class TenantIsolationIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private record Shop(String token, long restaurantId, long categoryId, long menuItemId, long tableId, String qr) {
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
        long category = idOf(json(body(post("/api/categories"), token, "{\"name\":\"Mains " + id + "\"}")));
        long item = idOf(json(body(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh " + id + "\",\"price\":400.00,\"categoryId\":" + category + "}")));
        mockMvc.perform(body(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token, "{\"cashEnabled\":true}"));
        JsonNode table = json(body(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table " + id + "\",\"capacity\":4}"));
        return new Shop(token, restaurantId, category, item, idOf(table), table.get("qrIdentifier").asText());
    }

    @Test
    void anotherRestaurantCannotReadOrChangeAnyOfARestaurantsObjectsById() throws Exception {
        Shop a = shop();
        Shop b = shop();

        // ---- everything restaurant A owns
        String start = LocalDateTime.now().minusDays(1).toString();
        String end = LocalDateTime.now().plusDays(30).toString();
        long coupon = idOf(json(body(post("/api/v1/coupons"), a.token(), """
                {"restaurantId":%d,"code":"A%s","name":"A offer","discountType":"FIXED","discountValue":50,
                 "minimumOrderAmount":0,"minimumItems":0,"usageLimit":5,"startDate":"%s","expiryDate":"%s","active":true}
                """.formatted(a.restaurantId(), unique().substring(0, 4), start, end))));
        long inventory = idOf(json(body(post("/api/v1/inventory"), a.token(),
                "{\"restaurantId\":" + a.restaurantId() + ",\"menuItemId\":" + a.menuItemId()
                        + ",\"availableQuantity\":42,\"minimumQuantity\":5}")));
        long staff = idOf(json(body(post("/api/v1/staff"), a.token(),
                "{\"name\":\"Chef A\",\"email\":\"chef-" + unique() + "@example.com\",\"role\":\"CHEF\",\"password\":\"Chef2026pw\"}")));

        String diner = DinerTestSupport.join(mockMvc, objectMapper, a.qr(), "Asha");
        mockMvc.perform(body(post("/api/public/tables/" + a.qr() + "/cart/items").header(DinerTestSupport.HEADER, diner), null,
                "{\"menuItemId\":" + a.menuItemId() + ",\"quantity\":1}")).andReturn();
        JsonNode order = json(body(post("/api/public/tables/" + a.qr() + "/orders").header(DinerTestSupport.HEADER, diner), null, "{}"));
        long orderId = order.get("id").asLong();
        String orderNumber = order.get("orderNumber").asText();
        String total = order.get("total").asText();
        JsonNode paid = json(body(post("/api/v1/payments/orders/" + orderId + "/collect"), a.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":" + total + "}]}"));
        long payment = paid.get("payments").get(0).get("id").asLong();
        mockMvc.perform(body(post("/api/public/tables/" + a.qr() + "/service-requests").header(DinerTestSupport.HEADER, diner), null,
                "{\"type\":\"CALL_WAITER\"}"));
        JsonNode requests = json(as(get("/api/v1/service-requests"), a.token()));
        long serviceRequest = (requests.isArray() ? requests : requests.get("data")).get(0).get("id").asLong();
        JsonNode sessions = json(as(get("/api/v1/table-sessions/active"), a.token()));
        long session = (sessions.isArray() ? sessions : sessions.get("data")).get(0).get("id").asLong();
        JsonNode unread = json(as(get("/api/v1/notifications/unread"), a.token()));
        long notification = (unread.isArray() ? unread : unread.get("data")).get(0).get("id").asLong();

        // ---- B attacks each by id
        String t = b.token();
        List<String> failures = new ArrayList<>();
        record Attack(String name, MockHttpServletRequestBuilder request) {
        }
        List<Attack> attacks = List.of(
                new Attack("GET category", as(get("/api/categories/" + a.categoryId()), t)),
                new Attack("PUT category", body(put("/api/categories/" + a.categoryId()), t, "{\"name\":\"hacked\"}")),
                new Attack("PATCH category deactivate", as(patch("/api/categories/" + a.categoryId() + "/deactivate"), t)),
                new Attack("GET menu item", as(get("/api/menu-items/" + a.menuItemId()), t)),
                new Attack("PUT menu item", body(put("/api/menu-items/" + a.menuItemId()), t,
                        "{\"name\":\"hacked\",\"price\":1.00,\"categoryId\":" + b.categoryId() + "}")),
                new Attack("PATCH menu item unavailable", as(patch("/api/menu-items/" + a.menuItemId() + "/unavailable"), t)),
                new Attack("PATCH menu item deactivate", as(patch("/api/menu-items/" + a.menuItemId() + "/deactivate"), t)),
                new Attack("GET menu options", as(get("/api/menu-items/" + a.menuItemId() + "/options"), t)),
                new Attack("GET table", as(get("/api/v1/tables/" + a.tableId()), t)),
                new Attack("PUT table", body(put("/api/v1/tables/" + a.tableId()), t, "{\"tableName\":\"hacked\"}")),
                new Attack("GET table QR", as(get("/api/v1/tables/" + a.tableId() + "/qr"), t)),
                new Attack("PATCH table deactivate", as(patch("/api/v1/tables/" + a.tableId() + "/deactivate"), t)),
                new Attack("DELETE table", as(delete("/api/v1/tables/" + a.tableId()), t)),
                new Attack("GET coupon", as(get("/api/v1/coupons/" + coupon), t)),
                new Attack("PUT coupon", body(put("/api/v1/coupons/" + coupon), t, "{\"active\":false,\"name\":\"hacked\"}")),
                new Attack("DELETE coupon", as(delete("/api/v1/coupons/" + coupon), t)),
                new Attack("GET inventory", as(get("/api/v1/inventory/" + inventory), t)),
                new Attack("PUT inventory", body(put("/api/v1/inventory/" + inventory), t, "{\"minimumQuantity\":99}")),
                new Attack("PATCH inventory stock", body(patch("/api/v1/inventory/" + inventory + "/stock"), t, "{\"availableQuantity\":0}")),
                new Attack("DELETE inventory", as(delete("/api/v1/inventory/" + inventory), t)),
                new Attack("PUT staff", body(put("/api/v1/staff/" + staff), t, "{\"name\":\"hacked\",\"role\":\"MANAGER\"}")),
                new Attack("PUT staff password", body(put("/api/v1/staff/" + staff + "/password"), t, "{\"password\":\"Takeover2026pw\"}")),
                new Attack("GET order", as(get("/api/v1/orders/" + orderId), t)),
                new Attack("PUT order status", as(put("/api/v1/orders/" + orderId + "/status").param("status", "CANCELLED"), t)),
                new Attack("GET order receipt", as(get("/api/v1/orders/" + orderId + "/receipt"), t)),
                new Attack("GET order history", as(get("/api/v1/orders/" + orderId + "/history"), t)),
                new Attack("GET order tracking by number", as(get("/api/v1/orders/" + orderNumber + "/tracking"), t)),
                new Attack("kitchen confirm", as(put("/api/v1/kitchen/orders/" + orderId + "/confirm"), t)),
                new Attack("kitchen served", as(put("/api/v1/kitchen/orders/" + orderId + "/served"), t)),
                new Attack("GET order notifications", as(get("/api/v1/notifications/orders/" + orderId), t)),
                new Attack("PUT notification read", body(put("/api/v1/notifications/" + notification + "/read"), t, "{\"read\":true}")),
                new Attack("GET payment", as(get("/api/v1/payments/" + payment), t)),
                new Attack("GET payment for order", as(get("/api/v1/payments/orders/" + orderId), t)),
                new Attack("GET payment summary", as(get("/api/v1/payments/orders/" + orderId + "/summary"), t)),
                new Attack("POST collect on A's order", body(post("/api/v1/payments/orders/" + orderId + "/collect"), t,
                        "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":1}]}")),
                new Attack("POST payment on A's order", body(post("/api/v1/payments/orders/" + orderId), t,
                        "{\"paymentMethod\":\"CASH\",\"amount\":" + total + "}")),
                new Attack("POST refund of A's payment", body(post("/api/v1/payments/" + payment + "/refunds"), t,
                        "{\"amount\":10.00,\"reason\":\"theft\"}")),
                new Attack("PUT payment status", body(put("/api/v1/payments/" + payment + "/status"), t, "{\"paymentStatus\":\"FAILED\"}")),
                new Attack("GET payment transaction detail", as(get("/api/v1/payment-analytics/transactions/" + payment), t)),
                new Attack("GET service request", as(get("/api/v1/service-requests/" + serviceRequest), t)),
                new Attack("PUT service request status", body(put("/api/v1/service-requests/" + serviceRequest + "/status"), t, "{\"status\":\"COMPLETED\"}")),
                new Attack("GET table session", as(get("/api/v1/table-sessions/" + session), t)),
                new Attack("POST close table session", as(post("/api/v1/table-sessions/" + session + "/close"), t)),
                new Attack("GET A's payment settings", as(get("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"), t)),
                new Attack("PUT A's payment settings", body(put("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"), t, "{\"cashEnabled\":false}")),
                new Attack("PUT A's restaurant", body(put("/api/restaurants/" + a.restaurantId()), t, "{\"name\":\"hacked\"}")),
                new Attack("PATCH A's restaurant deactivate", as(patch("/api/restaurants/" + a.restaurantId() + "/deactivate"), t)),
                new Attack("PUT A's google reviews", body(put("/api/restaurants/" + a.restaurantId() + "/google-reviews"), t, "{\"googlePlaceId\":\"x\"}")),
                new Attack("platform: approve A", as(post("/api/platform-admin/restaurants/" + a.restaurantId() + "/approve"), t)),
                new Attack("platform: suspend A", body(post("/api/platform-admin/restaurants/" + a.restaurantId() + "/suspend"), t, "{\"reason\":\"competitor\"}")),
                new Attack("platform: read A", as(get("/api/platform-admin/restaurants/" + a.restaurantId()), t)));

        for (Attack attack : attacks) {
            int s = status(attack.request());
            if (s != 403 && s != 404 && s != 400 && s != 409) {
                failures.add(attack.name() + " answered " + s);
            }
        }
        assertTrue(failures.isEmpty(), "Cross-tenant requests that were NOT refused: " + failures);

        // ---- and A's data is exactly as it was (a 400 above could otherwise hide a write that went through)
        assertEquals(200, status(as(get("/api/categories/" + a.categoryId()), a.token())));
        assertFalse(json(as(get("/api/categories/" + a.categoryId()), a.token())).toString().contains("hacked"));
        assertFalse(json(as(get("/api/menu-items/" + a.menuItemId()), a.token())).toString().contains("hacked"));
        assertEquals(true, json(as(get("/api/menu-items/" + a.menuItemId()), a.token())).at("/data/active").asBoolean(true));
        assertFalse(json(as(get("/api/v1/tables/" + a.tableId()), a.token())).toString().contains("hacked"));
        // 42 in stock, minus the one unit A's own order used; B's attempts to zero it changed nothing.
        assertEquals(41, json(as(get("/api/v1/inventory/" + inventory), a.token())).at("/data/availableQuantity").asInt());
        assertFalse(json(as(get("/api/v1/coupons/" + coupon), a.token())).toString().contains("hacked"));
        assertFalse(json(as(get("/api/v1/orders/" + orderId), a.token())).toString().contains("CANCELLED"));
        assertEquals(200, status(as(get("/api/v1/payments/orders/" + orderId + "/summary"), a.token())));
        assertFalse(json(as(get("/api/v1/payments/" + payment), a.token())).toString().contains("\"FAILED\""));
        assertFalse(json(as(get("/api/restaurants/" + a.restaurantId()), a.token())).toString().contains("hacked"));
        JsonNode settings = json(as(get("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"), a.token()));
        assertTrue(settings.toString().contains("true"), "A's cash setting must be unchanged");
        // A's staff member still signs in with the ORIGINAL password, not the one B tried to set.
        String chefEmail = json(as(get("/api/v1/staff"), a.token())).toString();
        assertTrue(chefEmail.contains("Chef A"));
        assertFalse(chefEmail.contains("hacked"));
    }
}
