package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The owner's uploaded UPI QR reaches that restaurant's customers, and only theirs; a guest's
 * "I've completed payment" is a claim that leaves the payment pending until staff confirm it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class UpiQrPaymentIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String owner, long restaurantId, String qr, long pizza) {
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String bearer, String body) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return b;
    }

    private MockHttpServletRequestBuilder asGuest(MockHttpServletRequestBuilder b, String token) {
        return b.header(DinerTestSupport.HEADER, token);
    }

    private static byte[] qrLikePng(Color dark) throws Exception {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 300; x++) {
            for (int y = 0; y < 300; y++) {
                img.setRGB(x, y, ((x / 20 + y / 20) % 2 == 0) ? dark.getRGB() : Color.WHITE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private Shop setupShop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String owner = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(get("/api/v1/me").header("Authorization", "Bearer " + owner)).get("restaurantId").asLong();
        long categoryId = json(withBody(post("/api/categories"), owner, "{\"name\":\"Mains\"}")).get("data").get("id").asLong();
        long pizza = json(withBody(post("/api/menu-items"), owner,
                "{\"name\":\"Pizza\",\"price\":400.00,\"categoryId\":" + categoryId + "}")).get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), owner,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
        return new Shop(owner, restaurantId, qr, pizza);
    }

    private String uploadQr(Shop shop, Color colour) throws Exception {
        JsonNode uploaded = json(multipart("/api/v1/images")
                .file(new MockMultipartFile("file", "upi.png", "image/png", qrLikePng(colour)))
                .header("Authorization", "Bearer " + shop.owner()));
        return uploaded.get("url").asText();
    }

    private int saveSettings(Shop shop, boolean create, boolean upiEnabled, String upiId, String name, String qrUrl)
            throws Exception {
        String body = """
                {"cashEnabled":true,"upiEnabled":%s,"upiId":%s,"merchantName":%s,"upiQrUrl":%s}
                """.formatted(upiEnabled, quoted(upiId), quoted(name), quoted(qrUrl));
        var builder = create ? post("/api/v1/restaurants/" + shop.restaurantId() + "/payment-settings")
                : put("/api/v1/restaurants/" + shop.restaurantId() + "/payment-settings");
        return statusOf(withBody(builder, shop.owner(), body));
    }

    private static String quoted(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private JsonNode publicTable(Shop shop) throws Exception {
        return json(get("/api/public/tables/" + shop.qr()));
    }

    private String orderPizza(Shop shop, String guest) throws Exception {
        String token = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), guest);
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items"), null,
                "{\"menuItemId\":" + shop.pizza() + ",\"quantity\":1}"), token));
        String orderBody = mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), token))
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(orderBody).get("id").asLong();
        mockMvc.perform(put("/api/v1/orders/" + orderId + "/status?status=CONFIRMED")
                .header("Authorization", "Bearer " + shop.owner()));
        return token;
    }

    private JsonNode startUpi(Shop shop, String token) throws Exception {
        return json(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/settlements"), null,
                "{\"kind\":\"SEPARATE\",\"paymentMethod\":\"UPI\"}"), token));
    }

    private String claimUrl(Shop shop, String code) {
        return "/api/public/tables/" + shop.qr() + "/settlements/" + code + "/claim-paid";
    }

    // ------------------------------------------------------------------ the right restaurant's QR

    @Test
    void eachRestaurantsCustomersSeeOnlyThatRestaurantsQrAndRecipient() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();
        String qrA = uploadQr(a, Color.BLACK);
        String qrB = uploadQr(b, new Color(30, 30, 120));
        assertNotEquals(qrA, qrB);

        assertEquals(201, saveSettings(a, true, true, "alpha@okbank", "Alpha Kitchen", qrA));
        assertEquals(201, saveSettings(b, true, true, "beta@okbank", "Beta Bistro", qrB));

        JsonNode pageA = publicTable(a);
        JsonNode pageB = publicTable(b);
        assertEquals(qrA, pageA.get("upi").get("qrUrl").asText());
        assertEquals("alpha@okbank", pageA.get("upi").get("upiId").asText());
        assertEquals("Alpha Kitchen", pageA.get("upi").get("recipientName").asText());
        assertEquals(qrB, pageB.get("upi").get("qrUrl").asText());
        assertEquals("beta@okbank", pageB.get("upi").get("upiId").asText());
        assertEquals("Beta Bistro", pageB.get("upi").get("recipientName").asText());
        assertTrue(pageA.get("paymentMethods").toString().contains("UPI"));
    }

    @Test
    void anOwnerCannotPointCheckoutAtAnotherRestaurantsQrOrAnExternalImage() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();
        String qrA = uploadQr(a, Color.BLACK);

        assertEquals(400, saveSettings(b, true, true, "beta@okbank", "Beta", qrA), "A's photo is not B's to use");
        assertEquals(400, saveSettings(b, true, true, "beta@okbank", "Beta", "https://evil.example/qr.png"));
        assertEquals(400, saveSettings(b, true, true, "beta@okbank", "Beta", "/api/public/images/" + "0".repeat(32)));
    }

    @Test
    void settingsCannotBeChangedWithoutBeingThatRestaurantsStaff() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();
        assertEquals(201, saveSettings(a, true, false, null, null, null));

        int anonymous = statusOf(withBody(put("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"), null,
                "{\"cashEnabled\":true,\"upiEnabled\":false}"));
        assertTrue(anonymous == 401 || anonymous == 403);
        // B's owner naming A's restaurant looks exactly like a missing restaurant.
        assertEquals(404, statusOf(withBody(put("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings"),
                b.owner(), "{\"cashEnabled\":true,\"upiEnabled\":false}")));
    }

    // ------------------------------------------------------------------ missing, disabled, removed

    @Test
    void upiIsNotOfferedWithoutAnUploadedQrAndCannotBeStarted() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "gamma@okbank", "Gamma", null));

        JsonNode page = publicTable(shop);
        assertTrue(page.get("upi").isNull());
        assertFalse(page.get("paymentMethods").toString().contains("UPI"));

        String token = orderPizza(shop, "Rahul");
        assertEquals(400, statusOf(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/settlements"), null,
                "{\"kind\":\"SEPARATE\",\"paymentMethod\":\"UPI\"}"), token)));
    }

    @Test
    void removingOrDisablingTheQrTakesUpiAwayAndTheLatestUploadIsUsed() throws Exception {
        Shop shop = setupShop();
        String first = uploadQr(shop, Color.BLACK);
        assertEquals(201, saveSettings(shop, true, true, "delta@okbank", "Delta", first));
        assertEquals(first, publicTable(shop).get("upi").get("qrUrl").asText());

        String second = uploadQr(shop, new Color(120, 20, 20));
        assertEquals(200, saveSettings(shop, false, true, "delta@okbank", "Delta", second));
        assertEquals(second, publicTable(shop).get("upi").get("qrUrl").asText());

        assertEquals(200, saveSettings(shop, false, false, "delta@okbank", "Delta", second));
        assertTrue(publicTable(shop).get("upi").isNull());

        assertEquals(200, saveSettings(shop, false, true, "delta@okbank", "Delta", null));
        assertTrue(publicTable(shop).get("upi").isNull());
    }

    // ------------------------------------------------------------------ claiming and verification

    @Test
    void claimingPaymentLeavesItPendingUntilStaffConfirm() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "eps@okbank", "Eps", uploadQr(shop, Color.BLACK)));
        String token = orderPizza(shop, "Rahul");

        JsonNode settlement = startUpi(shop, token);
        String code = settlement.get("code").asText();
        assertEquals("PENDING", settlement.get("status").asText());
        assertTrue(settlement.get("customerConfirmedAt").isNull());
        assertEquals(400.00, settlement.get("total").asDouble());

        JsonNode claimed = json(asGuest(withBody(post(claimUrl(shop, code)), null, "{\"reference\":\"412345678901\"}"), token));
        assertEquals("PENDING", claimed.get("status").asText(), "a claim never marks anything paid");
        assertFalse(claimed.get("customerConfirmedAt").isNull());
        assertEquals(0.0, claimed.get("confirmed").asDouble());

        // A second tap (or a retry) keeps the first claim and its reference.
        JsonNode again = json(asGuest(withBody(post(claimUrl(shop, code)), null, "{\"reference\":\"999999999999\"}"), token));
        assertEquals(claimed.get("customerConfirmedAt").asText(), again.get("customerConfirmedAt").asText());

        JsonNode pending = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner()));
        assertEquals(1, pending.size());
        assertEquals("412345678901", pending.get(0).get("customerReference").asText());
        assertFalse(pending.get(0).get("customerConfirmedAt").isNull());
        assertEquals(400.00, pending.get(0).get("total").asDouble());

        long id = pending.get(0).get("id").asLong();
        JsonNode confirmed = json(withBody(put("/api/v1/settlements/" + id + "/status"), shop.owner(),
                "{\"paymentStatus\":\"SUCCESS\"}"));
        assertEquals("SUCCESS", confirmed.get("status").asText());
        assertEquals(400.00, confirmed.get("confirmed").asDouble());
    }

    @Test
    void staffCanRejectAClaimAndTheOrderIsPayableAgain() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "zeta@okbank", "Zeta", uploadQr(shop, Color.BLACK)));
        String token = orderPizza(shop, "Amit");

        String code = startUpi(shop, token).get("code").asText();
        mockMvc.perform(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), token));

        long id = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner()))
                .get(0).get("id").asLong();
        JsonNode rejected = json(withBody(put("/api/v1/settlements/" + id + "/status"), shop.owner(),
                "{\"paymentStatus\":\"FAILED\"}"));
        assertEquals("FAILED", rejected.get("status").asText());

        // Rejected: nothing was received, the guest may try again, and a claim on the dead one is refused.
        assertEquals(409, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), token)));
        assertEquals("PENDING", startUpi(shop, token).get("status").asText());
    }

    @Test
    void claimRulesAreEnforcedOnTheServer() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "eta@okbank", "Eta", uploadQr(shop, Color.BLACK)));
        String token = orderPizza(shop, "Rahul");
        String other = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Priya");
        String code = startUpi(shop, token).get("code").asText();

        // Reference must look like a transaction reference.
        assertEquals(400, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null,
                "{\"reference\":\"<script>alert(1)</script>\"}"), token)));
        assertEquals(400, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null, "{\"reference\":\"12\"}"), token)));

        // Another guest cannot claim it; nor can someone without a guest token.
        assertEquals(404, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), other)));
        int noToken = statusOf(withBody(post(claimUrl(shop, code)), null, "{}"));
        assertTrue(noToken == 404 || noToken == 401 || noToken == 403);

        // Still pending, still unclaimed after all of that.
        JsonNode pending = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner()));
        assertTrue(pending.get(0).get("customerConfirmedAt").isNull());
    }

    @Test
    void aCashPaymentCannotBeClaimedAsUpi() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "theta@okbank", "Theta", uploadQr(shop, Color.BLACK)));
        String token = orderPizza(shop, "Rahul");
        String code = json(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/settlements"), null,
                "{\"kind\":\"SEPARATE\",\"paymentMethod\":\"CASH\"}"), token)).get("code").asText();

        assertEquals(400, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), token)));
    }

    // ------------------------------------------------------------------ isolation, races, cancellation

    @Test
    void anotherRestaurantsStaffCannotSeeOrDecideThisPaymentAndGuestsCannotMarkItPaid() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();
        assertEquals(201, saveSettings(a, true, true, "iota@okbank", "Iota", uploadQr(a, Color.BLACK)));
        String token = orderPizza(a, "Rahul");
        String code = startUpi(a, token).get("code").asText();
        mockMvc.perform(asGuest(withBody(post(claimUrl(a, code)), null, "{\"reference\":\"412345678901\"}"), token));

        long id = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + a.owner()))
                .get(0).get("id").asLong();

        // B's staff see none of A's payments and cannot decide one.
        assertEquals(0, json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + b.owner())).size());
        assertEquals(404, statusOf(withBody(put("/api/v1/settlements/" + id + "/status"), b.owner(),
                "{\"paymentStatus\":\"SUCCESS\"}")));

        // A guest (no staff login) cannot mark anything paid, with or without their diner token.
        int guestDecide = statusOf(asGuest(withBody(put("/api/v1/settlements/" + id + "/status"), null,
                "{\"paymentStatus\":\"SUCCESS\"}"), token));
        assertTrue(guestDecide == 401 || guestDecide == 403, "got " + guestDecide);
        assertTrue(json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + a.owner())).size() == 1);
    }

    @Test
    void twoStaffDecidingTheSameClaimIsSafe() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "kappa@okbank", "Kappa", uploadQr(shop, Color.BLACK)));
        String token = orderPizza(shop, "Rahul");
        String code = startUpi(shop, token).get("code").asText();
        mockMvc.perform(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), token));
        long id = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner()))
                .get(0).get("id").asLong();

        String success = "{\"paymentStatus\":\"SUCCESS\"}";
        assertEquals(200, statusOf(withBody(put("/api/v1/settlements/" + id + "/status"), shop.owner(), success)));
        // The second staff member confirming the same thing changes nothing; contradicting it is refused.
        assertEquals(200, statusOf(withBody(put("/api/v1/settlements/" + id + "/status"), shop.owner(), success)));
        assertEquals(409, statusOf(withBody(put("/api/v1/settlements/" + id + "/status"), shop.owner(),
                "{\"paymentStatus\":\"FAILED\"}")));

        JsonNode mine = json(asGuest(get("/api/public/tables/" + shop.qr() + "/settlements"), token));
        assertEquals("SUCCESS", mine.get(0).get("status").asText());
        assertEquals(400.00, mine.get(0).get("confirmed").asDouble(), "paid once, not twice");
        // Already paid: a late claim is refused.
        assertEquals(409, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), token)));
    }

    @Test
    void aWithdrawnPaymentCannotBeClaimedAndTheBillShowsServerBreakdown() throws Exception {
        Shop shop = setupShop();
        assertEquals(201, saveSettings(shop, true, true, "lambda@okbank", "Lambda", uploadQr(shop, Color.BLACK)));
        String token = orderPizza(shop, "Rahul");

        JsonNode order = json(asGuest(get("/api/public/tables/" + shop.qr() + "/bill"), token)).get("orders").get(0);
        assertEquals(400.00, order.get("subtotal").asDouble());
        assertTrue(order.has("tax") && order.has("serviceCharge") && order.has("discount"));
        assertTrue(order.get("total").asDouble() >= order.get("subtotal").asDouble() - order.get("discount").asDouble());

        String code = startUpi(shop, token).get("code").asText();
        mockMvc.perform(asGuest(post("/api/public/tables/" + shop.qr() + "/settlements/" + code + "/cancel"), token));
        assertEquals(409, statusOf(asGuest(withBody(post(claimUrl(shop, code)), null, "{}"), token)));
        assertEquals(0, json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner())).size());
    }
}
