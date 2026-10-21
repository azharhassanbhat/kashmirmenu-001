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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage C: configurable service charge and tax, through the real cart, order, tracking and payment
 * balance. Every dish here is 400.00 and the guest orders two, so the subtotal is 800.00.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class BillingChargesIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String owner, long restaurantId, long dish, String qr, String guest) {
    }

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

    private MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b, String bearer) {
        return b.header("Authorization", "Bearer " + bearer);
    }

    private Shop shop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String owner = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(staff(get("/api/v1/me"), owner)).get("restaurantId").asLong();
        long category = json(withBody(post("/api/categories"), owner, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long dish = json(withBody(post("/api/menu-items"), owner,
                "{\"name\":\"Rogan Josh\",\"price\":400.00,\"categoryId\":" + category + "}"))
                .get("data").get("id").asLong();
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), owner,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        String qr = json(withBody(post("/api/v1/tables"), owner,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
        String guest = DinerTestSupport.join(mockMvc, objectMapper, qr, "Rahul");
        return new Shop(owner, restaurantId, dish, qr, guest);
    }

    private void configure(Shop shop, String taxName, String tax, String service, boolean inclusive) throws Exception {
        mockMvc.perform(withBody(put("/api/v1/billing-settings"), shop.owner(),
                "{\"taxName\":\"" + taxName + "\",\"taxRate\":" + tax + ",\"serviceChargeRate\":" + service
                        + ",\"pricesIncludeTax\":" + inclusive + "}")).andExpect(status().isOk());
    }

    private JsonNode addDishes(Shop shop, int quantity) throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                        .header(DinerTestSupport.HEADER, shop.guest()), null,
                "{\"menuItemId\":" + shop.dish() + ",\"quantity\":" + quantity + "}")).andExpect(status().isOk());
        return cart(shop);
    }

    private JsonNode cart(Shop shop) throws Exception {
        return json(get("/api/public/tables/" + shop.qr() + "/cart").header(DinerTestSupport.HEADER, shop.guest()));
    }

    private JsonNode place(Shop shop) throws Exception {
        return json(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, shop.guest()), null, "{}"));
    }

    private JsonNode tracking(JsonNode order) throws Exception {
        return json(get("/api/public/orders/by-token/" + order.get("publicToken").asText()));
    }

    private static void money(double expected, JsonNode actual) {
        assertEquals(expected, actual.asDouble(), 0.001);
    }

    // ---------------------------------------------------------------- unchanged by default

    @Test
    void aRestaurantThatNeverConfiguresAnythingChargesExactlyWhatItDidBefore() throws Exception {
        Shop shop = shop();
        JsonNode c = addDishes(shop, 2);
        money(800.00, c.get("subtotal"));
        money(0, c.get("tax"));
        money(0, c.get("serviceCharge"));
        money(800.00, c.get("total"));

        JsonNode order = place(shop);
        money(800.00, order.get("total"));
        money(800.00, tracking(order).get("balanceDue"));
    }

    @Test
    void theSettingsReadBackAsZerosUntilTheRestaurantSetsThem() throws Exception {
        Shop shop = shop();
        JsonNode s = json(staff(get("/api/v1/billing-settings"), shop.owner()));
        money(0, s.get("taxRate"));
        money(0, s.get("serviceChargeRate"));
        assertFalse(s.get("pricesIncludeTax").asBoolean());
    }

    // ---------------------------------------------------------------- exclusive pricing

    @Test
    void serviceChargeAndTaxAreShownOnTheCartAndChargedOnTheOrder() throws Exception {
        Shop shop = shop();
        configure(shop, "GST", "5", "10", false);

        JsonNode c = addDishes(shop, 2);
        money(800.00, c.get("subtotal"));
        money(80.00, c.get("serviceCharge"));
        money(44.00, c.get("tax"));          // 5% of 880
        money(924.00, c.get("total"));
        assertEquals("GST", c.get("taxName").asText());

        JsonNode order = place(shop);
        money(924.00, order.get("total"));
        JsonNode t = tracking(order);
        money(80.00, t.get("serviceCharge"));
        money(44.00, t.get("tax"));
        money(924.00, t.get("total"));
        assertEquals("GST", t.get("taxName").asText());
        money(5, t.get("taxRate"));
        money(10, t.get("serviceChargeRate"));
    }

    @Test
    void whatTheGuestOwesIsTheTotalSoPaymentsSeeTheTaxToo() throws Exception {
        Shop shop = shop();
        configure(shop, "GST", "5", "0", false);
        addDishes(shop, 2);
        JsonNode order = place(shop);

        money(840.00, order.get("total"));                 // 800 + 40
        money(840.00, tracking(order).get("balanceDue"));  // payments are balanced against the total
    }

    @Test
    void aCouponComesOffBeforeServiceChargeAndTaxAreWorkedOut() throws Exception {
        Shop shop = shop();
        configure(shop, "GST", "5", "10", false);
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        mockMvc.perform(withBody(post("/api/v1/coupons"), shop.owner(), """
                {"restaurantId":%d,"code":"TEN","name":"TEN offer","discountType":"PERCENTAGE","discountValue":10,
                 "minimumOrderAmount":0,"minimumItems":0,"usageLimit":0,
                 "startDate":"%s","expiryDate":"%s","active":true}
                """.formatted(shop.restaurantId(), start, end))).andExpect(status().is2xxSuccessful());

        addDishes(shop, 2);
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/coupon")
                .header(DinerTestSupport.HEADER, shop.guest()), null, "{\"couponCode\":\"TEN\"}"))
                .andExpect(status().isOk());

        JsonNode c = cart(shop);
        money(80.00, c.get("discount"));
        money(72.00, c.get("serviceCharge"));   // 10% of 720, not of 800
        money(39.60, c.get("tax"));             // 5% of 792
        money(831.60, c.get("total"));

        JsonNode order = place(shop);
        money(831.60, order.get("total"));
        money(831.60, tracking(order).get("balanceDue"));
    }

    // ---------------------------------------------------------------- inclusive pricing

    @Test
    void inclusivePricingAddsNothingAndShowsTheTaxInsideTheMenuPrices() throws Exception {
        Shop shop = shop();
        configure(shop, "GST", "5", "0", true);

        JsonNode c = addDishes(shop, 2);
        money(800.00, c.get("total"));
        money(0, c.get("tax"));
        money(38.10, c.get("taxIncluded"));      // 800 x 5 / 105
        assertTrue(c.get("pricesIncludeTax").asBoolean());

        JsonNode order = place(shop);
        money(800.00, order.get("total"));
        money(38.10, tracking(order).get("taxIncluded"));
    }

    // ---------------------------------------------------------------- settings change over time

    @Test
    void aPlacedOrderKeepsTheRatesItWasPricedWithWhenTheSettingsChangeLater() throws Exception {
        Shop shop = shop();
        configure(shop, "GST", "5", "0", false);
        addDishes(shop, 2);
        JsonNode first = place(shop);
        money(840.00, first.get("total"));

        configure(shop, "VAT", "18", "10", false);

        JsonNode t = tracking(first);
        money(840.00, t.get("total"));
        assertEquals("GST", t.get("taxName").asText());
        money(5, t.get("taxRate"));

        // The next order, priced after the change, uses the new rules.
        addDishes(shop, 2);
        JsonNode second = place(shop);
        money(1038.40, second.get("total"));    // 800 + 80 service + 18% of 880 = 158.40
        assertEquals("VAT", tracking(second).get("taxName").asText());
    }

    @Test
    void aGuestIsChargedTheRatesTheyWereShownEvenIfTheRestaurantChangesThemBeforeOrdering() throws Exception {
        Shop shop = shop();
        configure(shop, "GST", "5", "0", false);
        money(840.00, addDishes(shop, 2).get("total"));

        configure(shop, "GST", "28", "0", false);   // changed while the guest is still choosing

        JsonNode order = place(shop);
        money(840.00, order.get("total"));
    }

    // ---------------------------------------------------------------- validation

    @Test
    void impossibleRatesAreRefusedAndNothingIsSaved() throws Exception {
        Shop shop = shop();
        String url = "/api/v1/billing-settings";

        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"taxName\":\"GST\",\"taxRate\":-1,\"serviceChargeRate\":0,\"pricesIncludeTax\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"taxName\":\"GST\",\"taxRate\":101,\"serviceChargeRate\":0,\"pricesIncludeTax\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"taxName\":\"GST\",\"taxRate\":5.123,\"serviceChargeRate\":0,\"pricesIncludeTax\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"taxName\":\" \",\"taxRate\":5,\"serviceChargeRate\":0,\"pricesIncludeTax\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"taxName\":\"GST\",\"serviceChargeRate\":0,\"pricesIncludeTax\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"taxName\":\"GST\",\"taxRate\":5,\"serviceChargeRate\":0}"))
                .andExpect(status().isBadRequest());

        money(0, json(staff(get(url), shop.owner())).get("taxRate"));
    }

    // ------------------------------------------------------------------ tenancy & access

    @Test
    void eachRestaurantHasItsOwnRatesAndCannotSeeOrChangeAnothers() throws Exception {
        Shop a = shop();
        Shop b = shop();
        configure(a, "GST", "5", "10", false);

        money(0, json(staff(get("/api/v1/billing-settings"), b.owner())).get("taxRate"));
        configure(b, "VAT", "12", "0", false);
        money(5, json(staff(get("/api/v1/billing-settings"), a.owner())).get("taxRate"));
        assertEquals("GST", json(staff(get("/api/v1/billing-settings"), a.owner())).get("taxName").asText());

        // And b's guests are not charged a's rates.
        JsonNode c = addDishes(b, 2);
        money(96.00, c.get("tax"));
        money(0, c.get("serviceCharge"));
    }

    @Test
    void theSettingsNeedASignIn() throws Exception {
        mockMvc.perform(get("/api/v1/billing-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(withBody(put("/api/v1/billing-settings"), null,
                "{\"taxName\":\"GST\",\"taxRate\":5,\"serviceChargeRate\":0,\"pricesIncludeTax\":false}"))
                .andExpect(status().isUnauthorized());
    }
}
