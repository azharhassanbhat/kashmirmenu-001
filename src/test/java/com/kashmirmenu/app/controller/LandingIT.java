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

/** The QR landing page: owner settings, what guests may see, anonymous feedback and rewards, end to end. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class LandingIT {

    private static final String REWARD_HEADER = "X-Reward-Token";
    private static final String SECRET = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";
    private static final String REVIEW = "https://g.page/r/CabcDEF123456/review";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, String qr, long dishId) {
    }

    private record Placed(long id, String publicToken, String total) {
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

    /** Settings with everything on and a review link; each test overrides what it needs. */
    private String settings(String extra) {
        return "{\"landingEnabled\":true,\"tagline\":\"Authentic flavours\",\"googleReviewUrl\":\"" + REVIEW + "\","
                + "\"openingHours\":\"Mon-Sun 00:00-24:00\",\"instagramUrl\":\"https://www.instagram.com/kashmirmenu\","
                + "\"feedbackEnabled\":true,\"assistanceEnabled\":true" + (extra == null ? "" : "," + extra) + "}";
    }

    private int save(Shop shop, String body) throws Exception {
        return mockMvc.perform(withBody(put("/api/v1/landing"), shop.token(), body)).andReturn().getResponse().getStatus();
    }

    private JsonNode landing(Shop shop) throws Exception {
        return json(get("/api/public/tables/" + shop.qr() + "/landing"));
    }

    private Placed placeOrder(Shop shop) throws Exception {
        String guest = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Asha");
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                        .header(DinerTestSupport.HEADER, guest), null,
                "{\"menuItemId\":" + shop.dishId() + ",\"quantity\":1}")).andExpect(status().isOk());
        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, guest), null, "{}"));
        return new Placed(order.get("id").asLong(), order.get("publicToken").asText(), order.get("total").asText());
    }

    private void serve(Shop shop, Placed p) throws Exception {
        for (String step : new String[]{"confirm", "preparing", "ready", "served"}) {
            mockMvc.perform(as(put("/api/v1/kitchen/orders/" + p.id() + "/" + step), shop.token())).andExpect(status().isOk());
        }
    }

    private void payInFull(Shop shop, Placed p) throws Exception {
        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + p.id() + "/collect"), shop.token(),
                "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":" + p.total() + "}]}")).andExpect(status().isCreated());
    }

    private int claim(Shop shop, String secret, String orderToken) throws Exception {
        return mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/rewards/claim")
                        .header(REWARD_HEADER, secret), null, "{\"orderToken\":\"" + orderToken + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode join(Shop shop, String secret) throws Exception {
        return json(post("/api/public/tables/" + shop.qr() + "/rewards/join").header(REWARD_HEADER, secret));
    }

    private String rewardsOn() {
        return "\"rewardsEnabled\":true,\"rewardsSpendPerPoint\":10,\"rewardsRedeemPoints\":10,\"rewardsRewardText\":\"A free kahwa\"";
    }

    // ------------------------------------------------------------------ defaults and branding

    @Test
    void aRestaurantThatNeverOptedInKeepsItsMenuAndShowsNoLandingPage() throws Exception {
        Shop shop = shop();
        JsonNode l = landing(shop);
        assertFalse(l.get("landingEnabled").asBoolean());
        assertEquals(0, l.get("actions").size());
        assertTrue(l.get("wifi").isNull());
    }

    @Test
    void savedSettingsShowOnTheGuestsPage() throws Exception {
        Shop shop = shop();
        assertEquals(200, save(shop, settings(null)));
        JsonNode l = landing(shop);
        assertTrue(l.get("landingEnabled").asBoolean());
        assertEquals("Authentic flavours", l.get("tagline").asText());
        assertEquals(REVIEW, l.get("links").get("googleReviewUrl").asText());
        assertEquals("https://www.instagram.com/kashmirmenu", l.get("links").get("instagramUrl").asText());
        assertTrue(l.get("links").get("youtubeUrl").isNull(), "an unset link is absent, so the screen hides it");
        assertTrue(l.get("hours").get("known").asBoolean());
        assertTrue(l.get("hours").get("open").asBoolean());
        assertTrue(l.get("actions").toString().contains("REVIEW"));
        assertTrue(l.get("actions").toString().contains("MENU"));
        assertFalse(l.get("actions").toString().contains("WIFI"), "Wi-Fi is off until configured");
        assertFalse(l.get("actions").toString().contains("REWARDS"), "rewards are off until configured");
    }

    @Test
    void theMenusOwnReviewLinkNowUsesTheConfiguredUrlWithoutAPlaceId() throws Exception {
        Shop shop = shop();
        save(shop, settings(null));
        JsonNode table = json(get("/api/public/tables/" + shop.qr()));
        assertEquals(REVIEW, table.get("googleReviewUrl").asText());
        JsonNode reviews = json(get("/api/public/tables/" + shop.qr() + "/reviews"));
        assertEquals(REVIEW, reviews.get("writeReviewUrl").asText());
    }

    // ------------------------------------------------------------------ the Google review link

    @Test
    void unsafeOrWrongLinksAreRefusedAndNothingIsSaved() throws Exception {
        Shop shop = shop();
        assertEquals(200, save(shop, settings(null)));
        for (String bad : new String[]{
                "http://g.page/r/abc/review",
                "https://evil.example/r/abc/review",
                "https://www.google.com/url?q=https://evil.example",
                "javascript:alert(1)",
                "https://google.com.evil.example/maps/place/x",
                "ChIJN1t_tDeuEmsRUsoyG83frY4"}) {
            assertEquals(400, save(shop, settings(null).replace(REVIEW, bad)), bad);
        }
        assertEquals(REVIEW, landing(shop).get("links").get("googleReviewUrl").asText(), "a refused save changes nothing");
    }

    @Test
    void aBlankReviewLinkHidesTheButtonInsteadOfBreakingIt() throws Exception {
        Shop shop = shop();
        save(shop, settings(null).replace("\"googleReviewUrl\":\"" + REVIEW + "\"", "\"googleReviewUrl\":\"\""));
        JsonNode l = landing(shop);
        assertTrue(l.get("links").get("googleReviewUrl").isNull());
        assertFalse(l.get("actions").toString().contains("REVIEW"));
    }

    @Test
    void socialLinksMustBeOnTheirOwnSite() throws Exception {
        Shop shop = shop();
        assertEquals(400, save(shop, settings("\"youtubeUrl\":\"https://www.instagram.com/x\"")));
        assertEquals(200, save(shop, settings("\"youtubeUrl\":\"https://www.youtube.com/@kashmirmenu\"")));
    }

    // ------------------------------------------------------------------ hours

    @Test
    void badOpeningHoursAreRefusedWithAMessage() throws Exception {
        Shop shop = shop();
        String body = settings(null).replace("Mon-Sun 00:00-24:00", "Funday 9-5");
        assertEquals(400, save(shop, body));
    }

    // ------------------------------------------------------------------ restaurant isolation

    @Test
    void eachRestaurantShowsItsOwnSettingsAndNeverAnothers() throws Exception {
        Shop a = shop();
        Shop b = shop();
        save(a, settings("\"tagline\":\"Alpha\""));
        save(b, settings("\"tagline\":\"Beta\"").replace(REVIEW, "https://g.page/r/Zzz999/review"));
        assertEquals("Alpha", landing(a).get("tagline").asText());
        assertEquals("Beta", landing(b).get("tagline").asText());
        assertEquals("https://g.page/r/Zzz999/review", landing(b).get("links").get("googleReviewUrl").asText());
        JsonNode adminA = json(as(get("/api/v1/landing"), a.token()));
        assertEquals("Alpha", adminA.get("tagline").asText(), "the owner reads only their own restaurant");
    }

    @Test
    void anUnknownQrIs404() throws Exception {
        mockMvc.perform(get("/api/public/tables/no-such-qr/landing")).andExpect(status().isNotFound());
    }

    @Test
    void settingsAreForManagersAndOwnersOnly() throws Exception {
        mockMvc.perform(get("/api/v1/landing")).andExpect(status().is4xxClientError());
        mockMvc.perform(withBody(put("/api/v1/landing"), null, settings(null))).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------ Wi-Fi

    @Test
    void theWifiPasswordIsNeverInAnyAdminOrLandingResponse() throws Exception {
        Shop shop = shop();
        assertEquals(200, save(shop, settings("\"wifiEnabled\":true,\"wifiSsid\":\"Kashmir Guest\",\"wifiSecurity\":\"WPA\",\"wifiPassword\":\"chinar-2026\"")));
        String admin = mockMvc.perform(as(get("/api/v1/landing"), shop.token())).andReturn().getResponse().getContentAsString();
        String saved = mockMvc.perform(as(put("/api/v1/landing").contentType(MediaType.APPLICATION_JSON)
                .content(settings("\"wifiEnabled\":true,\"wifiSsid\":\"Kashmir Guest\",\"wifiSecurity\":\"WPA\"")), shop.token()))
                .andReturn().getResponse().getContentAsString();
        String page = mockMvc.perform(get("/api/public/tables/" + shop.qr() + "/landing")).andReturn().getResponse().getContentAsString();
        String table = mockMvc.perform(get("/api/public/tables/" + shop.qr())).andReturn().getResponse().getContentAsString();
        for (String body : new String[]{admin, saved, page, table}) {
            assertFalse(body.contains("chinar-2026"), "password leaked in: " + body);
        }
        assertTrue(json(as(get("/api/v1/landing"), shop.token())).get("wifiPasswordSet").asBoolean());
    }

    @Test
    void aSaveWithoutAPasswordKeepsTheSavedOne() throws Exception {
        Shop shop = shop();
        save(shop, settings("\"wifiEnabled\":true,\"wifiSsid\":\"Kashmir Guest\",\"wifiSecurity\":\"WPA\",\"wifiPassword\":\"chinar-2026\""));
        assertEquals(200, save(shop, settings("\"wifiEnabled\":true,\"wifiSsid\":\"Kashmir Guest\",\"wifiSecurity\":\"WPA\"")));
        JsonNode wifi = json(get("/api/public/tables/" + shop.qr() + "/wifi"));
        assertEquals("chinar-2026", wifi.get("password").asText());
    }

    @Test
    void guestsGetTheCredentialsFromTheTableScopedEndpointOnly() throws Exception {
        Shop shop = shop();
        save(shop, settings("\"wifiEnabled\":true,\"wifiSsid\":\"Kashmir Guest\",\"wifiSecurity\":\"WPA\",\"wifiPassword\":\"chinar-2026\""));
        var result = mockMvc.perform(get("/api/public/tables/" + shop.qr() + "/wifi")).andExpect(status().isOk()).andReturn();
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
        JsonNode wifi = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("Kashmir Guest", wifi.get("ssid").asText());
        assertEquals("chinar-2026", wifi.get("password").asText());
        assertTrue(landing(shop).get("wifi").get("hasPassword").asBoolean());
        assertTrue(landing(shop).get("actions").toString().contains("WIFI"));
    }

    @Test
    void theWifiQrIsAPng() throws Exception {
        Shop shop = shop();
        save(shop, settings("\"wifiEnabled\":true,\"wifiSsid\":\"Kashmir Guest\",\"wifiSecurity\":\"WPA\",\"wifiPassword\":\"chinar-2026\""));
        byte[] png = mockMvc.perform(get("/api/public/tables/" + shop.qr() + "/wifi/qr")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
    }

    @Test
    void whenWifiIsOffTheEndpointsAre404() throws Exception {
        Shop shop = shop();
        save(shop, settings(null));
        mockMvc.perform(get("/api/public/tables/" + shop.qr() + "/wifi")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/tables/" + shop.qr() + "/wifi/qr")).andExpect(status().isNotFound());
    }

    @Test
    void aBadWifiPasswordIsRefused() throws Exception {
        Shop shop = shop();
        assertEquals(400, save(shop, settings("\"wifiEnabled\":true,\"wifiSsid\":\"Guest\",\"wifiSecurity\":\"WPA\",\"wifiPassword\":\"short\"")));
    }

    // ------------------------------------------------------------------ anonymous feedback

    @Test
    void feedbackNeedsNoSignInAndSaysThankYou() throws Exception {
        Shop shop = shop();
        save(shop, settings(null));
        JsonNode ack = json(withBody(post("/api/public/tables/" + shop.qr() + "/anonymous-feedback"), null,
                "{\"rating\":4,\"category\":\"FOOD\",\"comment\":\"Lovely kahwa\"}"));
        assertTrue(ack.get("received").asBoolean());
        JsonNode inbox = json(as(get("/api/v1/landing/feedback"), shop.token()));
        assertEquals(1, inbox.get("items").size());
        assertEquals("FOOD", inbox.get("items").get(0).get("category").asText());
        assertEquals(4, inbox.get("items").get(0).get("rating").asInt());
        assertTrue(inbox.get("items").get(0).get("contact").isNull(), "anonymous stays anonymous");
    }

    @Test
    void aContactDetailIsDroppedUnlessTheGuestOptedIn() throws Exception {
        Shop shop = shop();
        save(shop, settings(null));
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/anonymous-feedback"), null,
                "{\"rating\":2,\"category\":\"SERVICE\",\"contact\":\"me@example.com\",\"wantsReply\":false}")).andExpect(status().isCreated());
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/anonymous-feedback"), null,
                "{\"rating\":2,\"category\":\"SERVICE\",\"contact\":\"you@example.com\",\"wantsReply\":true}")).andExpect(status().isCreated());
        String inbox = json(as(get("/api/v1/landing/feedback"), shop.token())).toString();
        assertFalse(inbox.contains("me@example.com"));
        assertTrue(inbox.contains("you@example.com"));
    }

    @Test
    void invalidFeedbackIsRefused() throws Exception {
        Shop shop = shop();
        save(shop, settings(null));
        for (String bad : new String[]{"{\"rating\":0}", "{\"rating\":6}", "{\"rating\":2.5}", "{}",
                "{\"rating\":3,\"category\":\"PARKING\"}"}) {
            mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/anonymous-feedback"), null, bad))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void feedbackCanNameOnlyAnOrderOfThisRestaurant() throws Exception {
        Shop a = shop();
        Shop b = shop();
        save(a, settings(null));
        save(b, settings(null));
        Placed other = placeOrder(b);
        mockMvc.perform(withBody(post("/api/public/tables/" + a.qr() + "/anonymous-feedback"), null,
                "{\"rating\":3,\"category\":\"FOOD\",\"orderToken\":\"" + other.publicToken() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedbackCanBeSwitchedOff() throws Exception {
        Shop shop = shop();
        save(shop, settings("\"feedbackEnabled\":false").replace("\"feedbackEnabled\":true,", ""));
        assertFalse(landing(shop).get("actions").toString().contains("FEEDBACK"));
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/anonymous-feedback"), null, "{\"rating\":4}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theInboxShowsOnlyThisRestaurantsFeedback() throws Exception {
        Shop a = shop();
        Shop b = shop();
        save(a, settings(null));
        save(b, settings(null));
        mockMvc.perform(withBody(post("/api/public/tables/" + b.qr() + "/anonymous-feedback"), null,
                "{\"rating\":1,\"category\":\"FOOD\",\"comment\":\"only for B\"}")).andExpect(status().isCreated());
        assertEquals(0, json(as(get("/api/v1/landing/feedback"), a.token())).get("items").size());
        assertEquals(1, json(as(get("/api/v1/landing/feedback"), b.token())).get("items").size());
    }

    // ------------------------------------------------------------------ rewards

    @Test
    void rewardsStartOffAndOpeningThePageEarnsNothing() throws Exception {
        Shop shop = shop();
        save(shop, settings(null));
        assertFalse(json(get("/api/public/tables/" + shop.qr() + "/rewards")).get("enabled").asBoolean());
        mockMvc.perform(post("/api/public/tables/" + shop.qr() + "/rewards/join").header(REWARD_HEADER, SECRET))
                .andExpect(status().isNotFound());
    }

    @Test
    void joiningIsIdempotentAndGivesAShortCode() throws Exception {
        Shop shop = shop();
        save(shop, settings(rewardsOn()));
        JsonNode first = join(shop, SECRET);
        JsonNode second = join(shop, SECRET);
        assertEquals(8, first.get("member").get("code").asText().length());
        assertEquals(first.get("member").get("code").asText(), second.get("member").get("code").asText());
        assertEquals(0, first.get("member").get("balance").asInt());
        JsonNode info = json(get("/api/public/tables/" + shop.qr() + "/rewards").header(REWARD_HEADER, SECRET));
        assertEquals(first.get("member").get("code").asText(), info.get("member").get("code").asText());
    }

    @Test
    void aWeakSecretCannotJoin() throws Exception {
        Shop shop = shop();
        save(shop, settings(rewardsOn()));
        mockMvc.perform(post("/api/public/tables/" + shop.qr() + "/rewards/join").header(REWARD_HEADER, "short"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOrderEarnsPointsOnlyOnceItIsServedAndPaid() throws Exception {
        Shop shop = shop();
        save(shop, settings(rewardsOn()));
        join(shop, SECRET);
        Placed p = placeOrder(shop);

        assertEquals(400, claim(shop, SECRET, p.publicToken()), "not served yet");
        serve(shop, p);
        assertEquals(400, claim(shop, SECRET, p.publicToken()), "served but not paid");
        payInFull(shop, p);
        assertEquals(200, claim(shop, SECRET, p.publicToken()), "served and paid");
        assertEquals(409, claim(shop, SECRET, p.publicToken()), "never twice for the same order");

        JsonNode info = json(get("/api/public/tables/" + shop.qr() + "/rewards").header(REWARD_HEADER, SECRET));
        int expected = (int) (Double.parseDouble(p.total()) / 10);
        assertEquals(expected, info.get("member").get("balance").asInt());
    }

    @Test
    void anotherMemberCannotClaimTheSameOrder() throws Exception {
        Shop shop = shop();
        save(shop, settings(rewardsOn()));
        join(shop, SECRET);
        String other = "f".repeat(48);
        join(shop, other);
        Placed p = placeOrder(shop);
        serve(shop, p);
        payInFull(shop, p);
        assertEquals(200, claim(shop, SECRET, p.publicToken()));
        assertEquals(409, claim(shop, other, p.publicToken()));
    }

    @Test
    void anOrderFromAnotherRestaurantCannotBeClaimed() throws Exception {
        Shop a = shop();
        Shop b = shop();
        save(a, settings(rewardsOn()));
        save(b, settings(rewardsOn()));
        join(a, SECRET);
        Placed other = placeOrder(b);
        serve(b, other);
        payInFull(b, other);
        assertEquals(404, claim(a, SECRET, other.publicToken()));
    }

    @Test
    void claimingNeedsMembership() throws Exception {
        Shop shop = shop();
        save(shop, settings(rewardsOn()));
        Placed p = placeOrder(shop);
        serve(shop, p);
        payInFull(shop, p);
        assertEquals(400, claim(shop, SECRET, p.publicToken()));
    }

    @Test
    void staffRedeemOnlyWithEnoughPointsAndOnlyOwnMembers() throws Exception {
        Shop shop = shop();
        Shop other = shop();
        save(shop, settings(rewardsOn()));
        save(other, settings(rewardsOn()));
        String code = join(shop, SECRET).get("member").get("code").asText();

        mockMvc.perform(withBody(post("/api/v1/rewards/redeem"), shop.token(), "{\"memberCode\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());

        Placed p = placeOrder(shop);
        serve(shop, p);
        payInFull(shop, p);
        assertEquals(200, claim(shop, SECRET, p.publicToken()));

        mockMvc.perform(withBody(post("/api/v1/rewards/redeem"), other.token(), "{\"memberCode\":\"" + code + "\"}"))
                .andExpect(status().isNotFound());
        JsonNode done = json(withBody(post("/api/v1/rewards/redeem"), shop.token(), "{\"memberCode\":\"" + code + "\"}"));
        assertEquals(10, done.get("pointsSpent").asInt());
        assertTrue(done.get("newBalance").asInt() >= 0);
        assertEquals("A free kahwa", done.get("rewardText").asText());
    }

    @Test
    void staffCanLookAMemberUpBeforeRedeemingAndOnlyInTheirOwnRestaurant() throws Exception {
        Shop shop = shop();
        Shop other = shop();
        save(shop, settings(rewardsOn()));
        save(other, settings(rewardsOn()));
        String code = join(shop, SECRET).get("member").get("code").asText();

        JsonNode before = json(as(get("/api/v1/rewards/members/" + code.toLowerCase()), shop.token()));
        assertEquals(code, before.get("memberCode").asText());
        assertEquals(0, before.get("balance").asInt());
        assertFalse(before.get("canRedeem").asBoolean());
        assertEquals(10, before.get("pointsToGo").asInt());

        Placed p = placeOrder(shop);
        serve(shop, p);
        payInFull(shop, p);
        assertEquals(200, claim(shop, SECRET, p.publicToken()));
        JsonNode after = json(as(get("/api/v1/rewards/members/" + code), shop.token()));
        assertTrue(after.get("canRedeem").asBoolean());
        assertEquals("A free kahwa", after.get("rewardText").asText());

        mockMvc.perform(as(get("/api/v1/rewards/members/" + code), other.token())).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/v1/rewards/members/BAD"), shop.token())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/rewards/members/" + code)).andExpect(status().is4xxClientError());
    }

    @Test
    void rewardsNeedAFreeKahwaToBeNamed() throws Exception {
        Shop shop = shop();
        assertEquals(400, save(shop, settings("\"rewardsEnabled\":true,\"rewardsSpendPerPoint\":10,\"rewardsRedeemPoints\":10")));
    }
}
