package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.service.ImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage G: photos stored in the database. Real generated images go through the real controller, security,
 * processing and database, and come back out of the public address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class ImageUploadIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ImageService imageService;

    @Autowired
    private MenuItemRepository menuItems;

    private record Shop(String token, long categoryId) {
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
        long category = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}")).get("data").get("id").asLong();
        return new Shop(token, category);
    }

    private MockHttpServletRequestBuilder upload(String bearer, byte[] bytes, String name, String claimedType) {
        MockHttpServletRequestBuilder request =
                multipart("/api/v1/images").file(new MockMultipartFile("file", name, claimedType, bytes));
        return bearer == null ? request : as(request, bearer);
    }

    private JsonNode uploaded(Shop shop, byte[] bytes) throws Exception {
        return json(upload(shop.token(), bytes, "dish.jpg", "image/jpeg"));
    }

    private int uploadStatus(String bearer, byte[] bytes, String name, String type) throws Exception {
        return mockMvc.perform(upload(bearer, bytes, name, type)).andReturn().getResponse().getStatus();
    }

    private static BufferedImage halves(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        return encode(halves(w, h), "jpeg");
    }

    private static byte[] splice(byte[] jpeg, byte[] segment) {
        byte[] out = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(jpeg, 2, out, 2 + segment.length, jpeg.length - 2);
        return out;
    }

    private static byte[] withComment(byte[] jpeg, String text) {
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        byte[] seg = new byte[4 + t.length];
        seg[0] = (byte) 0xFF;
        seg[1] = (byte) 0xFE;
        seg[2] = (byte) ((t.length + 2) >> 8);
        seg[3] = (byte) ((t.length + 2) & 0xFF);
        System.arraycopy(t, 0, seg, 4, t.length);
        return splice(jpeg, seg);
    }

    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {(byte) 0xFF, (byte) 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, 0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00};
        return splice(jpeg, app1);
    }

    private MvcResult fetch(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn();   // deliberately no sign-in: guests are not signed in
    }

    private int fetchStatus(String url) throws Exception {
        return fetch(url).getResponse().getStatus();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void anUploadedPhotoComesBackWithAPublicAddressAndIsServedToAnyone() throws Exception {
        Shop shop = shop();
        JsonNode result = uploaded(shop, jpeg(2000, 1500));

        assertTrue(result.get("url").asText().startsWith("/api/public/images/"));
        assertEquals(32, result.get("url").asText().length() - "/api/public/images/".length());
        assertEquals(1200, result.get("width").asInt());
        assertEquals(900, result.get("height").asInt());
        assertTrue(result.get("sizeBytes").asInt() > 0);

        MvcResult served = fetch(result.get("url").asText());
        assertEquals(200, served.getResponse().getStatus());
        assertEquals("image/jpeg", served.getResponse().getContentType());
        String cache = served.getResponse().getHeader("Cache-Control");
        assertTrue(cache.contains("max-age=31536000") && cache.contains("immutable") && cache.contains("public"), cache);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(served.getResponse().getContentAsByteArray()));
        assertEquals(1200, back.getWidth());
        assertEquals(900, back.getHeight());
        assertEquals(result.get("sizeBytes").asInt(), served.getResponse().getContentAsByteArray().length);
    }

    @Test
    void aPngIsAccepted() throws Exception {
        Shop shop = shop();
        JsonNode r = json(upload(shop.token(), encode(halves(400, 300), "png"), "dish.png", "image/png"));
        assertEquals(400, r.get("width").asInt());
        assertEquals("image/jpeg", fetch(r.get("url").asText()).getResponse().getContentType());
    }

    @Test
    void whatIsServedIsOurOwnReEncodingNotTheUploadedBytes() throws Exception {
        Shop shop = shop();
        byte[] original = jpeg(500, 300);
        JsonNode r = uploaded(shop, original);
        byte[] served = fetch(r.get("url").asText()).getResponse().getContentAsByteArray();
        assertFalse(Arrays.equals(original, served));
    }

    @Test
    void locationAndOtherHiddenDataInThePhotoIsRemoved() throws Exception {
        Shop shop = shop();
        JsonNode r = uploaded(shop, withComment(jpeg(500, 300), "SECRET-GPS-51.5N-0.12W"));
        String served = new String(fetch(r.get("url").asText()).getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse(served.contains("SECRET-GPS"));
    }

    @Test
    void aPhonePortraitStoredSidewaysIsServedUpright() throws Exception {
        Shop shop = shop();
        JsonNode r = uploaded(shop, withOrientation(jpeg(300, 100), 6));
        assertEquals(100, r.get("width").asInt());
        assertEquals(300, r.get("height").asInt());
    }

    @Test
    void everyUploadGetsItsOwnUnguessableAddress() throws Exception {
        Shop shop = shop();
        String a = uploaded(shop, jpeg(100, 100)).get("url").asText();
        String b = uploaded(shop, jpeg(100, 100)).get("url").asText();
        assertNotEquals(a, b);
    }

    // ------------------------------------------------------------------ refused

    @Test
    void thingsThatAreNotPhotosAreRefusedWhateverTheyClaimToBe() throws Exception {
        Shop shop = shop();
        assertEquals(400, uploadStatus(shop.token(), "just text".getBytes(StandardCharsets.UTF_8), "a.png", "image/png"));
        assertEquals(400, uploadStatus(shop.token(), "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), "a.jpg", "image/jpeg"));
        assertEquals(400, uploadStatus(shop.token(), "<svg xmlns='http://www.w3.org/2000/svg'><script>1</script></svg>".getBytes(StandardCharsets.UTF_8), "a.svg", "image/svg+xml"));
        assertEquals(400, uploadStatus(shop.token(), "%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "a.jpg", "image/jpeg"));
        assertEquals(400, uploadStatus(shop.token(), new byte[0], "a.jpg", "image/jpeg"));
    }

    @Test
    void aTruncatedPhotoIsRefusedNotStoredHalfGrey() throws Exception {
        Shop shop = shop();
        byte[] whole = jpeg(800, 600);
        MockHttpServletRequestBuilder request = upload(shop.token(), Arrays.copyOf(whole, whole.length / 3), "a.jpg", "image/jpeg");
        MvcResult result = mockMvc.perform(request).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("incomplete"));
    }

    @Test
    void aPhotoOverTheSizeLimitIsRefusedWithAClearMessage() throws Exception {
        Shop shop = shop();
        byte[] huge = new byte[10 * 1024 * 1024 + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        MvcResult result = mockMvc.perform(upload(shop.token(), huge, "a.jpg", "image/jpeg")).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("too large"));
    }

    @Test
    void aRequestWithNoFileOrNotAFormIsAClearErrorNotACrash() throws Exception {
        Shop shop = shop();
        assertEquals(400, mockMvc.perform(as(multipart("/api/v1/images"), shop.token())).andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(withBody(post("/api/v1/images"), shop.token(), "{}")).andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------ who may upload

    @Test
    void uploadingNeedsASignInAndAManagementRole() throws Exception {
        Shop shop = shop();
        assertEquals(401, uploadStatus(null, jpeg(100, 100), "a.jpg", "image/jpeg"));

        String email = "waiter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(withBody(post("/api/v1/staff"), shop.token(),
                "{\"name\":\"W\",\"email\":\"" + email + "\",\"role\":\"WAITER\",\"password\":\"Waiter2026\"}"))
                .andExpect(status().isCreated());
        String waiter = json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Waiter2026\"}")).get("accessToken").asText();
        assertEquals(403, uploadStatus(waiter, jpeg(100, 100), "a.jpg", "image/jpeg"));
    }

    // ------------------------------------------------------------------ public addresses

    @Test
    void anUnknownOrMalformedAddressIsNotFound() throws Exception {
        assertEquals(404, fetchStatus("/api/public/images/" + "0".repeat(32)));
        assertEquals(404, fetchStatus("/api/public/images/1"));                  // a guessable id is not a key
        assertEquals(404, fetchStatus("/api/public/images/not-a-key"));
        assertEquals(404, fetchStatus("/api/public/images/" + "A".repeat(32)));  // capitals are not valid
    }

    // ------------------------------------------------------------------ using a photo on a dish

    @Test
    void anUploadedPhotoCanBeAttachedToADishAndGuestsSeeIt() throws Exception {
        Shop shop = shop();
        String url = uploaded(shop, jpeg(600, 400)).get("url").asText();

        long dish = json(withBody(post("/api/menu-items"), shop.token(),
                "{\"name\":\"Rogan Josh\",\"price\":400.00,\"imageUrl\":\"" + url + "\",\"categoryId\":" + shop.categoryId() + "}"))
                .get("data").get("id").asLong();

        JsonNode saved = json(as(get("/api/menu-items/" + dish), shop.token())).get("data");
        assertEquals(url, saved.get("imageUrl").asText());
    }

    @Test
    void dishLinksMustBeHttpsOrOurOwnPhotosAndAreCheckedOnTheServer() throws Exception {
        Shop shop = shop();
        String base = "{\"name\":\"Dish %d\",\"price\":100.00,\"categoryId\":" + shop.categoryId() + ",\"imageUrl\":\"%s\"}";
        int n = 0;
        for (String bad : new String[]{"http://example.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
                "/api/public/images/notakey", "https://user:pass@example.com/a.jpg", "https:///path"}) {
            assertEquals(400, mockMvc.perform(withBody(post("/api/menu-items"), shop.token(), base.formatted(++n, bad)))
                    .andReturn().getResponse().getStatus(), bad);
        }
        for (String good : new String[]{"https://example.com/a.jpg", "https://\u4f8b\u3048.jp/a.jpg", ""}) {
            assertEquals(200, mockMvc.perform(withBody(post("/api/menu-items"), shop.token(), base.formatted(++n, good)))
                    .andReturn().getResponse().getStatus(), good);
        }
    }

    @Test
    void anOldLinkDoesNotBlockAnUnrelatedEditButANewBadOneIsRefused() throws Exception {
        Shop shop = shop();
        long dish = json(withBody(post("/api/menu-items"), shop.token(),
                "{\"name\":\"Old\",\"price\":100.00,\"imageUrl\":\"https://example.com/a.jpg\",\"categoryId\":" + shop.categoryId() + "}"))
                .get("data").get("id").asLong();
        // A link saved before the rule existed: written straight to the database.
        MenuItem item = menuItems.findById(dish).orElseThrow();
        item.setImageUrl("http://legacy.example.com/a.jpg");
        menuItems.save(item);

        String keepsOld = "{\"name\":\"Old\",\"price\":150.00,\"imageUrl\":\"http://legacy.example.com/a.jpg\",\"categoryId\":" + shop.categoryId() + "}";
        assertEquals(200, mockMvc.perform(withBody(put("/api/menu-items/" + dish), shop.token(), keepsOld)).andReturn().getResponse().getStatus(),
                "changing the price must not be blocked by an old link");

        String newBad = "{\"name\":\"Old\",\"price\":150.00,\"imageUrl\":\"http://another.example.com/b.jpg\",\"categoryId\":" + shop.categoryId() + "}";
        assertEquals(400, mockMvc.perform(withBody(put("/api/menu-items/" + dish), shop.token(), newBad)).andReturn().getResponse().getStatus());

        String removes = "{\"name\":\"Old\",\"price\":150.00,\"categoryId\":" + shop.categoryId() + "}";
        assertEquals(200, mockMvc.perform(withBody(put("/api/menu-items/" + dish), shop.token(), removes)).andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------ clean-up of unused photos

    @Test
    void photosNothingUsesAreClearedAfterTheGracePeriodButUsedOnesAndFreshOnesStay() throws Exception {
        Shop shop = shop();
        String unused = uploaded(shop, jpeg(200, 200)).get("url").asText();
        String used = uploaded(shop, jpeg(200, 200)).get("url").asText();
        mockMvc.perform(withBody(post("/api/menu-items"), shop.token(),
                "{\"name\":\"Uses it\",\"price\":100.00,\"imageUrl\":\"" + used + "\",\"categoryId\":" + shop.categoryId() + "}"))
                .andExpect(status().isOk());

        // Nothing is old enough yet, so nothing may be removed: this protects a photo uploaded while a form is open.
        imageService.deleteUnreferencedOlderThan(LocalDateTime.now().minusHours(24));
        assertEquals(200, fetchStatus(unused));
        assertEquals(200, fetchStatus(used));

        // Two days later, the unused one goes and the one a dish uses stays.
        imageService.deleteUnreferencedOlderThan(LocalDateTime.now().plusDays(2));
        assertEquals(404, fetchStatus(unused));
        assertEquals(200, fetchStatus(used));
    }

    @Test
    void aReplacedPhotoBecomesUnusedAndIsClearedLater() throws Exception {
        Shop shop = shop();
        String first = uploaded(shop, jpeg(200, 200)).get("url").asText();
        String second = uploaded(shop, jpeg(210, 210)).get("url").asText();
        long dish = json(withBody(post("/api/menu-items"), shop.token(),
                "{\"name\":\"Swap\",\"price\":100.00,\"imageUrl\":\"" + first + "\",\"categoryId\":" + shop.categoryId() + "}"))
                .get("data").get("id").asLong();
        mockMvc.perform(withBody(put("/api/menu-items/" + dish), shop.token(),
                "{\"name\":\"Swap\",\"price\":100.00,\"imageUrl\":\"" + second + "\",\"categoryId\":" + shop.categoryId() + "}"))
                .andExpect(status().isOk());

        imageService.deleteUnreferencedOlderThan(LocalDateTime.now().plusDays(2));

        assertEquals(404, fetchStatus(first));
        assertEquals(200, fetchStatus(second));
    }
}
