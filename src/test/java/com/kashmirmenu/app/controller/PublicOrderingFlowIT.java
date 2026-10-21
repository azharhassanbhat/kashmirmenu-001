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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class PublicOrderingFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Owner(String token, long restaurantId) {
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request)
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private MockHttpServletRequestBuilder jsonRequest(
            MockHttpServletRequestBuilder builder, String token, String body) {
        builder.contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private Owner registerOwner() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id);

        String token = json(jsonRequest(
                post("/api/v1/auth/register-owner"), null, body))
                .get("accessToken").asText();

        long restaurantId = json(get("/api/v1/me")
                .header("Authorization", "Bearer " + token))
                .get("restaurantId").asLong();

        return new Owner(token, restaurantId);
    }

    @Test
    void customerFlow_fromQrScanToPayment_andTenantIsolation() throws Exception {
        Owner owner = registerOwner();

        long categoryId = json(jsonRequest(post("/api/categories"), owner.token(),
                "{\"name\":\"Starters\"}")).get("data").get("id").asLong();

        long menuItemId = json(jsonRequest(post("/api/menu-items"), owner.token(),
                "{\"name\":\"Samosa\",\"price\":50.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();

        String qr = json(jsonRequest(post("/api/v1/tables"), owner.token(),
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}"))
                .get("qrIdentifier").asText();
        String diner = DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest");

        mockMvc.perform(jsonRequest(
                        post("/api/v1/restaurants/" + owner.restaurantId() + "/payment-settings"),
                        owner.token(), "{\"cashEnabled\":true}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/public/tables/" + qr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("Table 1"))
                .andExpect(jsonPath("$.paymentMethods[0]").value("CASH"));

        mockMvc.perform(get("/api/public/tables/" + qr + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Starters"))
                .andExpect(jsonPath("$[0].items[0].name").value("Samosa"));

        mockMvc.perform(jsonRequest(post("/api/public/tables/" + qr + "/cart/items").header(DinerTestSupport.HEADER, diner), null,
                        "{\"menuItemId\":" + menuItemId + ",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        JsonNode order = json(jsonRequest(
                post("/api/public/tables/" + qr + "/orders").header(DinerTestSupport.HEADER, diner), null, "{\"notes\":\"no onions\"}"));
        String orderNumber = order.get("orderNumber").asText();
        // Tracking and diner payments are keyed by the random public token, not the number.
        String publicToken = order.get("publicToken").asText();
        long orderId = order.get("id").asLong();

        mockMvc.perform(get("/api/public/orders/by-token/" + publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentStatus").doesNotExist());

        mockMvc.perform(jsonRequest(post("/api/public/orders/by-token/" + publicToken + "/payments"),
                        null, "{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        mockMvc.perform(jsonRequest(post("/api/public/orders/by-token/" + publicToken + "/payments"),
                        null, "{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/v1/kitchen/orders/active")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNumber").value(orderNumber))
                .andExpect(jsonPath("$[0].items[0].itemName").value("Samosa"))
                .andExpect(jsonPath("$[0].items[0].quantity").value(2));

        Owner other = registerOwner();

        mockMvc.perform(get("/api/v1/kitchen/orders/active")
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(put("/api/v1/kitchen/orders/" + orderId + "/confirm")
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/kitchen/orders/" + orderId + "/confirm")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void publicEndpoints_unknownTable_returnNotFound() throws Exception {
        mockMvc.perform(get("/api/public/tables/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/tables/" + UUID.randomUUID() + "/menu"))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerRole_cannotUseKitchenOrPaymentStatusEndpoints() throws Exception {
        String email = "cust-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String token = json(jsonRequest(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/kitchen/orders/active")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(jsonRequest(put("/api/v1/payments/1/status"), token,
                        "{\"paymentStatus\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
    }
}
