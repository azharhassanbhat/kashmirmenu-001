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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Private customer feedback, through the real controllers, security and database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class FeedbackIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, String qr, long dishId) {
    }

    private record Placed(long id, String publicToken, String orderNumber) {
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
        return new Shop(token, qr, dish);
    }

    private Placed placeOrder(Shop shop, String diner) throws Exception {
        String guest = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), diner);
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                        .header(DinerTestSupport.HEADER, guest), null,
                "{\"menuItemId\":" + shop.dishId() + ",\"quantity\":1}")).andExpect(status().isOk());
        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, guest), null, "{}"));
        return new Placed(order.get("id").asLong(), order.get("publicToken").asText(), order.get("orderNumber").asText());
    }

    private void advance(Shop shop, Placed p, String step) throws Exception {
        mockMvc.perform(as(put("/api/v1/kitchen/orders/" + p.id() + "/" + step), shop.token())).andExpect(status().isOk());
    }

    private Placed servedOrder(Shop shop, String diner) throws Exception {
        Placed p = placeOrder(shop, diner);
        advance(shop, p, "confirm");
        advance(shop, p, "preparing");
        advance(shop, p, "ready");
        advance(shop, p, "served");
        return p;
    }

    private int rate(Placed p, String body) throws Exception {
        return mockMvc.perform(withBody(put("/api/public/orders/by-token/" + p.publicToken() + "/feedback"), null, body))
                .andReturn().getResponse().getStatus();
    }

    private String feedbackBody(Object rating, String comment) {
        return "{\"rating\":" + rating + (comment == null ? "" : ",\"comment\":" + objectMapper.valueToTree(comment)) + "}";
    }

    private JsonNode tracking(Placed p) throws Exception {
        return json(get("/api/public/orders/by-token/" + p.publicToken()));
    }

    private JsonNode summary(Shop shop) throws Exception {
        return json(as(get("/api/v1/feedback/summary"), shop.token()));
    }

    private JsonNode list(Shop shop, String query) throws Exception {
        return json(as(get("/api/v1/feedback" + query), shop.token()));
    }

    // ------------------------------------------------------------------ rating a meal

    @Test
    void aGuestCanRateAServedOrderAndSeeWhatTheySent() throws Exception {
        Shop shop = shop();
        Placed p = servedOrder(shop, "Rahul");

        assertTrue(tracking(p).get("feedbackRating").isNull(), "nothing rated yet");
        assertEquals(200, rate(p, feedbackBody(4, "Lovely rogan josh")));

        JsonNode t = tracking(p);
        assertEquals(4, t.get("feedbackRating").asInt());
        assertEquals("Lovely rogan josh", t.get("feedbackComment").asText());
    }

    @Test
    void theCommentIsOptionalAndAnyScriptIsKept() throws Exception {
        Shop shop = shop();
        Placed a = servedOrder(shop, "Amit");
        assertEquals(200, rate(a, feedbackBody(5, null)));
        assertTrue(tracking(a).get("feedbackComment").isNull());

        Placed b = servedOrder(shop, "Bilal");
        assertEquals(200, rate(b, feedbackBody(5, "\u0628\u06c1\u062a \u0627\u0686\u06be\u0627")));
        assertEquals("\u0628\u06c1\u062a \u0627\u0686\u06be\u0627", tracking(b).get("feedbackComment").asText());
    }

    @Test
    void changingYourMindReplacesTheRatingAndNeverCountsTwice() throws Exception {
        Shop shop = shop();
        Placed p = servedOrder(shop, "Rahul");
        assertEquals(200, rate(p, feedbackBody(5, "Great")));
        assertEquals(200, rate(p, feedbackBody(2, "Cold on arrival")));

        assertEquals(2, tracking(p).get("feedbackRating").asInt());
        assertEquals("Cold on arrival", tracking(p).get("feedbackComment").asText());
        JsonNode s = summary(shop);
        assertEquals(1, s.get("count").asInt(), "one order is one rating, however many times it is changed");
        assertEquals(2.0, s.get("average").asDouble(), 0.001);
    }

    // ------------------------------------------------------------------ when it is refused

    @Test
    void anOrderNotYetServedCannotBeRated() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop, "Rahul");
        MvcResult early = mockMvc.perform(withBody(put("/api/public/orders/by-token/" + p.publicToken() + "/feedback"),
                null, feedbackBody(5, null))).andReturn();
        assertEquals(400, early.getResponse().getStatus());
        assertTrue(early.getResponse().getContentAsString().contains("once your order has been served"));

        advance(shop, p, "confirm");
        assertEquals(400, rate(p, feedbackBody(5, null)));
        advance(shop, p, "preparing");
        advance(shop, p, "ready");
        assertEquals(400, rate(p, feedbackBody(5, null)), "ready is not yet served");
        advance(shop, p, "served");
        assertEquals(200, rate(p, feedbackBody(5, null)));
    }

    @Test
    void aCancelledOrderCannotBeRated() throws Exception {
        Shop shop = shop();
        Placed p = placeOrder(shop, "Rahul");
        mockMvc.perform(as(put("/api/v1/orders/" + p.id() + "/status").param("status", "CANCELLED"), shop.token()))
                .andExpect(status().isOk());
        assertEquals(400, rate(p, feedbackBody(1, "Where is my food")));
    }

    @Test
    void badRatingsAndCommentsAreRefusedAndNothingIsSaved() throws Exception {
        Shop shop = shop();
        Placed p = servedOrder(shop, "Rahul");
        for (Object bad : new Object[]{0, 6, -1, 100, "null"}) {
            assertEquals(400, rate(p, "{\"rating\":" + bad + "}"), "rating " + bad);
        }
        assertEquals(400, rate(p, "{}"));
        assertEquals(400, rate(p, "{\"rating\":\"abc\"}"));
        assertEquals(400, rate(p, "{\"rating\":2.5}"));
        assertEquals(400, rate(p, feedbackBody(3, "x".repeat(501))));
        assertEquals(0, summary(shop).get("count").asInt(), "a refused rating must not be saved");
        assertEquals(200, rate(p, feedbackBody(3, "x".repeat(500))));
    }

    @Test
    void anUnknownTokenIsNotFoundAndSaysNothingAboutWhichTokensExist() throws Exception {
        MvcResult r = mockMvc.perform(withBody(put("/api/public/orders/by-token/" + "z".repeat(43) + "/feedback"),
                null, feedbackBody(5, null))).andReturn();
        assertEquals(404, r.getResponse().getStatus());
        assertFalse(r.getResponse().getContentAsString().contains("zzzz"));
    }

    // ------------------------------------------------------------------ the owner's view

    @Test
    void theOwnerSeesRatingsNewestFirstWithOrderAndTable() throws Exception {
        Shop shop = shop();
        Placed first = servedOrder(shop, "Amit");
        Placed second = servedOrder(shop, "Bilal");
        rate(first, feedbackBody(5, "Excellent"));
        rate(second, feedbackBody(1, "Cold"));

        JsonNode all = list(shop, "");
        assertEquals(2, all.get("totalItems").asInt());
        JsonNode newest = all.get("items").get(0);
        assertEquals(second.orderNumber(), newest.get("orderNumber").asText());
        assertEquals("Table T1", newest.get("tableName").asText());
        assertEquals(1, newest.get("rating").asInt());
        assertEquals("Cold", newest.get("comment").asText());
        assertEquals(first.orderNumber(), all.get("items").get(1).get("orderNumber").asText());
    }

    @Test
    void lowRatingsCanBeFilteredAndListsArePaged() throws Exception {
        Shop shop = shop();
        int[] ratings = {5, 1, 4, 2, 3};
        for (int i = 0; i < ratings.length; i++) {
            rate(servedOrder(shop, "Guest" + i), feedbackBody(ratings[i], null));
        }
        JsonNode low = list(shop, "?maxRating=2");
        assertEquals(2, low.get("totalItems").asInt());
        for (JsonNode f : low.get("items")) {
            assertTrue(f.get("rating").asInt() <= 2);
        }

        JsonNode page1 = list(shop, "?size=2&page=0");
        assertEquals(2, page1.get("items").size());
        assertEquals(5, page1.get("totalItems").asInt());
        assertEquals(3, page1.get("totalPages").asInt());
        assertTrue(page1.get("hasNext").asBoolean());
        assertEquals(1, list(shop, "?size=2&page=2").get("items").size());
    }

    @Test
    void theSummaryCountsAveragesAndFlagsLowRatings() throws Exception {
        Shop shop = shop();
        rate(servedOrder(shop, "A"), feedbackBody(5, null));
        rate(servedOrder(shop, "B"), feedbackBody(5, null));
        rate(servedOrder(shop, "C"), feedbackBody(3, null));
        rate(servedOrder(shop, "D"), feedbackBody(1, null));

        JsonNode s = summary(shop);
        assertEquals(4, s.get("count").asInt());
        assertEquals(3.5, s.get("average").asDouble(), 0.001);       // 14 / 4
        assertEquals(1, s.get("distribution").get(0).asInt());
        assertEquals(0, s.get("distribution").get(1).asInt());
        assertEquals(1, s.get("distribution").get(2).asInt());
        assertEquals(2, s.get("distribution").get(4).asInt());
        assertEquals(1, s.get("lowCount").asInt());
        assertEquals(25, s.get("lowPercent").asInt());
        assertEquals(30, s.get("days").asInt());
    }

    @Test
    void aRestaurantNobodyHasRatedHasNoAverageNotAZeroAverage() throws Exception {
        JsonNode s = summary(shop());
        assertEquals(0, s.get("count").asInt());
        assertTrue(s.get("average").isNull());
        assertEquals(0, s.get("lowPercent").asInt());
    }

    @Test
    void theSummaryPeriodIsClamped() throws Exception {
        Shop shop = shop();
        assertEquals(365, json(as(get("/api/v1/feedback/summary?days=99999"), shop.token())).get("days").asInt());
        assertEquals(1, json(as(get("/api/v1/feedback/summary?days=0"), shop.token())).get("days").asInt());
        assertEquals(7, json(as(get("/api/v1/feedback/summary?days=7"), shop.token())).get("days").asInt());
    }

    // ------------------------------------------------------------------ privacy and access

    @Test
    void oneRestaurantNeverSeesAnothersFeedback() throws Exception {
        Shop mine = shop();
        Shop other = shop();
        rate(servedOrder(mine, "Rahul"), feedbackBody(1, "Secret complaint"));

        assertEquals(0, list(other, "").get("totalItems").asInt());
        assertEquals(0, summary(other).get("count").asInt());
        assertEquals(1, summary(mine).get("count").asInt());
    }

    @Test
    void oneOrdersFeedbackNeverAppearsOnAnotherOrdersPage() throws Exception {
        Shop shop = shop();
        Placed a = servedOrder(shop, "Amit");
        Placed b = servedOrder(shop, "Bilal");
        rate(a, feedbackBody(1, "Only for A"));
        assertTrue(tracking(b).get("feedbackRating").isNull());
        assertTrue(tracking(b).get("feedbackComment").isNull());
    }

    @Test
    void onlyManagementMayReadFeedback() throws Exception {
        mockMvc.perform(get("/api/v1/feedback")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/feedback/summary")).andExpect(status().isUnauthorized());

        Shop shop = shop();
        String email = "waiter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(withBody(post("/api/v1/staff"), shop.token(),
                "{\"name\":\"W\",\"email\":\"" + email + "\",\"role\":\"WAITER\",\"password\":\"Waiter2026\"}"))
                .andExpect(status().isCreated());
        String waiter = json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Waiter2026\"}")).get("accessToken").asText();
        mockMvc.perform(as(get("/api/v1/feedback"), waiter)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/feedback/summary"), waiter)).andExpect(status().isForbidden());
    }

    @Test
    void theGuestsOwnPageNeverIncludesAnyoneElsesComments() throws Exception {
        Shop shop = shop();
        Placed a = servedOrder(shop, "Amit");
        Placed b = servedOrder(shop, "Bilal");
        rate(a, feedbackBody(2, "Amit's private words"));
        String bPage = mockMvc.perform(get("/api/public/orders/by-token/" + b.publicToken()))
                .andReturn().getResponse().getContentAsString();
        assertFalse(bPage.contains("Amit's private words"));
    }
}
