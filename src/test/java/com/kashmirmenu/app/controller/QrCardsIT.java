package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Printable table cards: the card list, and QR images that really scan to the right address. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class QrCardsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, String name) {
    }

    private record Table(long id, String qr) {
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
        String name = "Resto " + id;
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"%s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(name, id, id, id))).get("accessToken").asText();
        return new Shop(token, name);
    }

    private Table table(Shop shop, String code, String name) throws Exception {
        JsonNode t = json(withBody(post("/api/v1/tables"), shop.token(),
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"" + name + "\",\"capacity\":4}"));
        return new Table(t.get("id").asLong(), t.get("qrIdentifier").asText());
    }

    private JsonNode cards(Shop shop) throws Exception {
        return json(as(get("/api/v1/tables/qr-cards"), shop.token()));
    }

    private byte[] qr(Shop shop, long tableId, String query) throws Exception {
        return mockMvc.perform(as(get("/api/v1/tables/" + tableId + "/qr" + query), shop.token()))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static BufferedImage image(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static Result decode(byte[] png) throws Exception {
        BufferedImage img = image(png);
        java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
        hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints);
    }

    // ------------------------------------------------------------------ the card list

    @Test
    void theListCarriesTheRestaurantNameAndEachActiveTable() throws Exception {
        Shop shop = shop();
        Table t = table(shop, "T1", "Table 1");

        JsonNode c = cards(shop);
        assertEquals(shop.name(), c.get("restaurantName").asText());
        assertEquals(1, c.get("tables").size());
        assertEquals(t.id(), c.get("tables").get(0).get("id").asLong());
        assertEquals("Table 1", c.get("tables").get(0).get("tableName").asText());
        assertEquals("T1", c.get("tables").get(0).get("tableCode").asText());
    }

    @Test
    void tablesComeInTheOrderAPersonExpectsNotAlphabetical() throws Exception {
        Shop shop = shop();
        table(shop, "T10", "Table 10");
        table(shop, "T2", "Table 2");
        table(shop, "T1", "Table 1");
        table(shop, "G1", "Garden 1");

        JsonNode tables = cards(shop).get("tables");
        String[] expected = {"Garden 1", "Table 1", "Table 2", "Table 10"};
        assertEquals(expected.length, tables.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], tables.get(i).get("tableName").asText());
        }
    }

    @Test
    void aDeactivatedTableIsNotPrinted() throws Exception {
        Shop shop = shop();
        Table keep = table(shop, "T1", "Table 1");
        Table gone = table(shop, "T2", "Table 2");
        mockMvc.perform(as(patch("/api/v1/tables/" + gone.id() + "/deactivate"), shop.token())).andExpect(status().isOk());

        JsonNode tables = cards(shop).get("tables");
        assertEquals(1, tables.size());
        assertEquals(keep.id(), tables.get(0).get("id").asLong());
    }

    @Test
    void aRestaurantWithNoTablesGetsAnEmptyListNotAnError() throws Exception {
        Shop shop = shop();
        JsonNode c = cards(shop);
        assertEquals(0, c.get("tables").size());
        assertEquals(shop.name(), c.get("restaurantName").asText());
    }

    @Test
    void oneRestaurantNeverSeesAnothersTables() throws Exception {
        Shop mine = shop();
        Shop other = shop();
        table(mine, "T1", "Table 1");
        assertEquals(0, cards(other).get("tables").size());
        assertEquals(other.name(), cards(other).get("restaurantName").asText());
    }

    // ------------------------------------------------------------------ the image

    @Test
    void theImageIsAPngOf300PixelsUnlessAskedOtherwise() throws Exception {
        Shop shop = shop();
        Table t = table(shop, "T1", "Table 1");
        byte[] png = qr(shop, t.id(), "");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        BufferedImage img = image(png);
        assertEquals(300, img.getWidth());
        assertEquals(300, img.getHeight());
    }

    @Test
    void aLargerSizeGivesASharperImageHeldToTheLimits() throws Exception {
        Shop shop = shop();
        Table t = table(shop, "T1", "Table 1");
        assertEquals(800, image(qr(shop, t.id(), "?size=800")).getWidth());
        assertEquals(1200, image(qr(shop, t.id(), "?size=99999")).getWidth());
        assertEquals(200, image(qr(shop, t.id(), "?size=5")).getWidth());
        assertEquals(300, image(qr(shop, t.id(), "?size=banana")).getWidth(), "an unreadable size must not be an error");
    }

    @Test
    void theCodeReallyScansToTheTablesMenuAddress() throws Exception {
        Shop shop = shop();
        Table t = table(shop, "T1", "Table 1");

        Result scanned = decode(qr(shop, t.id(), "?size=800"));

        String address = scanned.getText();
        assertTrue(address.startsWith("http"), address);
        assertTrue(address.endsWith("/menu/" + t.qr()), address);
        assertFalse(address.replaceFirst("^https?://", "").contains("//"), "a doubled slash would be printed on every card: " + address);
    }

    @Test
    void theCodeUsesTheSturdierErrorCorrectionForPrintedCards() throws Exception {
        Shop shop = shop();
        Table t = table(shop, "T1", "Table 1");
        Result scanned = decode(qr(shop, t.id(), ""));
        assertEquals("M", String.valueOf(scanned.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL)));
    }

    @Test
    void theSameTableAlwaysGivesTheSameAddressSoReprintingNeverBreaksOldCards() throws Exception {
        Shop shop = shop();
        Table t = table(shop, "T1", "Table 1");
        assertEquals(decode(qr(shop, t.id(), "")).getText(), decode(qr(shop, t.id(), "?size=1000")).getText());
    }

    @Test
    void anotherRestaurantCannotFetchYourTablesCode() throws Exception {
        Shop mine = shop();
        Shop other = shop();
        Table t = table(mine, "T1", "Table 1");
        mockMvc.perform(as(get("/api/v1/tables/" + t.id() + "/qr"), other.token())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ access

    @Test
    void theCardListNeedsASignInAndAManagementRole() throws Exception {
        mockMvc.perform(get("/api/v1/tables/qr-cards")).andExpect(status().isUnauthorized());

        Shop shop = shop();
        String email = "waiter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(withBody(post("/api/v1/staff"), shop.token(),
                "{\"name\":\"W\",\"email\":\"" + email + "\",\"role\":\"WAITER\",\"password\":\"Waiter2026\"}"))
                .andExpect(status().isCreated());
        String waiter = json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Waiter2026\"}")).get("accessToken").asText();
        mockMvc.perform(as(get("/api/v1/tables/qr-cards"), waiter)).andExpect(status().isForbidden());
    }
}
