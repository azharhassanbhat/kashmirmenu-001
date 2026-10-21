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

/** Printable receipts, through the real controllers, security and database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class ReceiptIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, String qr, long dishId) {
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

    private int saveBilling(Shop shop, double taxRate, double serviceRate, String registration) throws Exception {
        String reg = registration == null ? "null" : objectMapper.valueToTree(registration).toString();
        return mockMvc.perform(withBody(put("/api/v1/billing-settings"), shop.token(),
                "{\"taxName\":\"GST\",\"taxRate\":" + taxRate + ",\"serviceChargeRate\":" + serviceRate
                        + ",\"pricesIncludeTax\":false,\"taxRegistrationNumber\":" + reg + "}"))
                .andReturn().getResponse().getStatus();
    }

    /** A restaurant whose dish costs 400, with 10% service charge and 5% GST: 400 + 40 + 22 = 462. */
    private Shop shop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(as(get("/api/v1/me"), token)).get("restaurantId").asLong();
        long category = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}")).get("data").get("id").asLong();
        long dish = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh\",\"price\":400.00,\"categoryId\":" + category + "}")).get("data").get("id").asLong();
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
        Shop shop = new Shop(token, qr, dish);
        assertEquals(200, saveBilling(shop, 5, 10, "29 abcde 1234 f1z5"));
        return shop;
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

    private void collect(Shop shop, Placed p, int amount) throws Exception {
        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + p.id() + "/collect"), shop.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":" + amount + "}]}")).andExpect(status().isCreated());
    }

    private JsonNode guestReceipt(Placed p) throws Exception {
        return json(get("/api/public/orders/by-token/" + p.publicToken() + "/receipt"));
    }

    private JsonNode staffReceipt(Shop shop, Placed p) throws Exception {
        return json(as(get("/api/v1/orders/" + p.id() + "/receipt"), shop.token()));
    }

    // ------------------------------------------------------------------ what is printed

    @Test
    void aGuestReceiptShowsTheRestaurantTheItemsAndTheBill() throws Exception {
        Shop shop = shop();
        JsonNode r = guestReceipt(placeOrder(shop));

        assertTrue(r.get("restaurant").get("name").asText().startsWith("Resto "));
        assertEquals("9999999999", r.get("restaurant").get("phone").asText());
        assertTrue(r.get("orderNumber").asText().startsWith("ORD-"));
        assertEquals("Table T1", r.get("tableName").asText());
        assertFalse(r.get("placedAt").isNull());

        assertEquals(1, r.get("items").size());
        JsonNode item = r.get("items").get(0);
        assertEquals("Rogan Josh", item.get("name").asText());
        assertEquals(1, item.get("quantity").asInt());
        assertEquals(400.0, item.get("unitPrice").asDouble(), 0.001);
        assertEquals(400.0, item.get("lineTotal").asDouble(), 0.001);

        assertEquals(400.0, r.get("subtotal").asDouble(), 0.001);
        assertEquals(40.0, r.get("serviceCharge").asDouble(), 0.001);
        assertEquals(22.0, r.get("tax").asDouble(), 0.001);
        assertEquals("GST", r.get("taxName").asText());
        assertEquals(462.0, r.get("total").asDouble(), 0.001);
    }

    @Test
    void theRegistrationNumberIsPrintedTidied() throws Exception {
        Shop shop = shop();
        assertEquals("29ABCDE1234F1Z5", guestReceipt(placeOrder(shop)).get("restaurant").get("taxRegistrationNumber").asText());
    }

    @Test
    void anUnpaidOrderSaysUnpaidAndWhatIsOwed() throws Exception {
        Shop shop = shop();
        JsonNode r = guestReceipt(placeOrder(shop));
        assertEquals("UNPAID", r.get("paymentHeading").asText());
        assertEquals(462.0, r.get("balanceDue").asDouble(), 0.001);
        assertEquals(0.0, r.get("amountPaid").asDouble(), 0.001);
        assertEquals(0, r.get("payments").size());
    }

    @Test
    void aPaidOrderListsTheCashAndOwesNothing() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        collect(shop, p, 462);
        JsonNode r = guestReceipt(p);
        assertEquals("PAID", r.get("paymentHeading").asText());
        assertEquals(0.0, r.get("balanceDue").asDouble(), 0.001);
        assertEquals(462.0, r.get("amountPaid").asDouble(), 0.001);
        assertEquals(1, r.get("payments").size());
        assertEquals("Cash", r.get("payments").get(0).get("method").asText());
        assertEquals(462.0, r.get("payments").get(0).get("amount").asDouble(), 0.001);
    }

    @Test
    void aPartPaymentSaysPartPaidAndWhatRemains() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        collect(shop, p, 200);
        JsonNode r = guestReceipt(p);
        assertEquals("PART PAID", r.get("paymentHeading").asText());
        assertEquals(262.0, r.get("balanceDue").asDouble(), 0.001);
        assertEquals(200.0, r.get("amountPaid").asDouble(), 0.001);
    }

    // ------------------------------------------------------------------ never recalculated

    @Test
    void aReceiptPrintsWhatWasBilledEvenIfTheRatesChangeAfterwards() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        assertEquals(200, saveBilling(shop, 18, 0, "29ABCDE1234F1Z5"));   // the restaurant changes its rates

        JsonNode r = guestReceipt(p);
        assertEquals(22.0, r.get("tax").asDouble(), 0.001, "the tax the guest was billed, not today's rate");
        assertEquals(40.0, r.get("serviceCharge").asDouble(), 0.001);
        assertEquals(462.0, r.get("total").asDouble(), 0.001);
    }

    @Test
    void theStaffAndGuestReceiptsAreTheSameBill() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        collect(shop, p, 100);
        JsonNode guest = guestReceipt(p);
        JsonNode staff = staffReceipt(shop, p);
        assertEquals(guest.toString(), staff.toString());
    }

    // ------------------------------------------------------------------ privacy and access

    @Test
    void aReceiptNeverCarriesAGuestNameOrFeedback() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        String body = mockMvc.perform(get("/api/public/orders/by-token/" + p.publicToken() + "/receipt"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("Rahul"), "the guest's name must not be printed");
        assertFalse(body.toLowerCase().contains("feedback"));
    }

    @Test
    void aCancelledOrderHasNoReceipt() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop);
        mockMvc.perform(as(put("/api/v1/orders/" + p.id() + "/status").param("status", "CANCELLED"), shop.token()))
                .andExpect(status().isOk());
        assertEquals(400, mockMvc.perform(get("/api/public/orders/by-token/" + p.publicToken() + "/receipt"))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(as(get("/api/v1/orders/" + p.id() + "/receipt"), shop.token()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void anUnknownTokenIsNotFoundAndSaysNothingAboutWhichTokensExist() throws Exception {
        var result = mockMvc.perform(get("/api/public/orders/by-token/" + "z".repeat(43) + "/receipt")).andReturn();
        assertEquals(404, result.getResponse().getStatus());
        assertFalse(result.getResponse().getContentAsString().contains("zzzz"));
    }

    @Test
    void anotherRestaurantCannotPrintYourReceipt() throws Exception {
        Shop mine = shop();
        Shop other = shop();
        Placed p = placeOrder(mine);
        mockMvc.perform(as(get("/api/v1/orders/" + p.id() + "/receipt"), other.token())).andExpect(status().isNotFound());
    }

    @Test
    void theStaffReceiptNeedsASignIn() throws Exception {
        mockMvc.perform(get("/api/v1/orders/1/receipt")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ the registration number setting

    @Test
    void aBadRegistrationNumberIsRefusedAndNothingIsSaved() throws Exception {
        Shop shop = shop();
        for (String bad : new String[]{"<script>1</script>", "AB", "ABC12;DROP TABLE", "GSTIN: 29ABCDE1234F1Z5", "A".repeat(31)}) {
            assertEquals(400, saveBilling(shop, 5, 10, bad), bad);
        }
        assertEquals("29ABCDE1234F1Z5", guestReceipt(placeOrder(shop)).get("restaurant").get("taxRegistrationNumber").asText());
    }

    @Test
    void aNumberCopiedWithAnUnusualSpaceIsTidiedNotRefused() throws Exception {
        Shop shop = shop();
        assertEquals(200, saveBilling(shop, 5, 10, "29ABCDE\u00a01234\u2003F1Z5"));
        JsonNode saved = json(as(get("/api/v1/billing-settings"), shop.token()));
        assertEquals("29ABCDE1234F1Z5", saved.get("taxRegistrationNumber").asText());
    }

    @Test
    void blankClearsTheNumberAndNothingIsPrinted() throws Exception {
        Shop shop = shop();
        assertEquals(200, saveBilling(shop, 5, 10, ""));
        assertTrue(guestReceipt(placeOrder(shop)).get("restaurant").get("taxRegistrationNumber").isNull());
        assertEquals(200, saveBilling(shop, 5, 10, null));
        assertTrue(json(as(get("/api/v1/billing-settings"), shop.token())).get("taxRegistrationNumber").isNull());
    }
}
