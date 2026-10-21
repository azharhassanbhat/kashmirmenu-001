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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2: every guest has their own cart, and every order records who placed it.
 *
 * <p>Table 5 in the brief: Rahul, Amit and Priya scan the same QR code. Each must see only their own
 * cart, and the restaurant must see which of them placed each order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class DinerCartIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String owner, long restaurantId, long tableId, String qr, long pizza, long burger) {
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

    private MockHttpServletRequestBuilder asGuest(MockHttpServletRequestBuilder b, String diner) {
        return diner == null ? b : b.header(DinerTestSupport.HEADER, diner);
    }

    private Shop setupShop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String owner = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();

        long restaurantId = json(withBody(get("/api/v1/me"), owner, "").contentType(MediaType.APPLICATION_JSON))
                .get("restaurantId").asLong();

        long categoryId = json(withBody(post("/api/categories"), owner, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long pizza = json(withBody(post("/api/menu-items"), owner,
                "{\"name\":\"Pizza\",\"price\":400.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        long burger = json(withBody(post("/api/menu-items"), owner,
                "{\"name\":\"Burger\",\"price\":250.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();

        JsonNode table = createTable(owner, "T5");
        return new Shop(owner, restaurantId, table.get("id").asLong(), table.get("qrIdentifier").asText(), pizza, burger);
    }

    private JsonNode createTable(String owner, String code) throws Exception {
        return json(withBody(post("/api/v1/tables"), owner,
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"));
    }

    private String join(Shop shop, String name) throws Exception {
        return DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), name);
    }

    private JsonNode addItem(Shop shop, String diner, long menuItemId, int quantity) throws Exception {
        String body = mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items"), null,
                        "{\"menuItemId\":" + menuItemId + ",\"quantity\":" + quantity + "}"), diner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode cart(Shop shop, String diner) throws Exception {
        String body = mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/cart"), diner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode placeOrder(Shop shop, String diner) throws Exception {
        String body = mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), diner))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())),
                "expected " + expected + " but was " + actual.asText());
    }

    // ------------------------------------------------------------------ cart isolation

    @Test
    void eachGuestAtATableSeesOnlyTheirOwnCart() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        String amit = join(shop, "Amit");
        String priya = join(shop, "Priya");

        addItem(shop, rahul, shop.pizza(), 2);
        addItem(shop, amit, shop.burger(), 1);
        addItem(shop, priya, shop.pizza(), 1);
        addItem(shop, priya, shop.burger(), 1);

        JsonNode rahulsCart = cart(shop, rahul);
        JsonNode amitsCart = cart(shop, amit);
        JsonNode priyasCart = cart(shop, priya);

        assertEquals(1, rahulsCart.get("items").size());
        assertMoney("800", rahulsCart.get("total"));

        assertEquals(1, amitsCart.get("items").size());
        assertMoney("250", amitsCart.get("total"));

        assertEquals(2, priyasCart.get("items").size());
        assertMoney("650", priyasCart.get("total"));

        Set<Long> cartIds = new HashSet<>(List.of(
                rahulsCart.get("id").asLong(), amitsCart.get("id").asLong(), priyasCart.get("id").asLong()));
        assertEquals(3, cartIds.size(), "three guests must have three carts");

        // All three are still the same table.
        assertEquals(shop.tableId(), rahulsCart.get("restaurantTableId").asLong());
        assertEquals(shop.tableId(), amitsCart.get("restaurantTableId").asLong());
    }

    @Test
    void twoGuestsWithTheSameNameStillHaveSeparateCarts() throws Exception {
        Shop shop = setupShop();
        String first = join(shop, "Rahul");
        String second = join(shop, "Rahul");

        addItem(shop, first, shop.pizza(), 1);

        assertEquals(1, cart(shop, first).get("items").size());
        assertEquals(0, cart(shop, second).get("items").size());
    }

    @Test
    void aGuestCannotChangeAnotherGuestsCart() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        String amit = join(shop, "Amit");

        long rahulsLine = addItem(shop, rahul, shop.pizza(), 1).get("items").get(0).get("id").asLong();

        // Amit knows the id of Rahul's cart line and tries to change and delete it.
        mockMvc.perform(asGuest(withBody(put("/api/public/tables/" + shop.qr() + "/cart/items/" + rahulsLine), null,
                        "{\"quantity\":9}"), amit))
                .andExpect(status().isNotFound());
        mockMvc.perform(asGuest(delete("/api/public/tables/" + shop.qr() + "/cart/items/" + rahulsLine), amit))
                .andExpect(status().isNotFound());

        JsonNode rahulsCart = cart(shop, rahul);
        assertEquals(1, rahulsCart.get("items").size());
        assertEquals(1, rahulsCart.get("items").get(0).get("quantity").asInt());
    }

    @Test
    void clearingOneGuestsCartLeavesTheOthersAlone() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        String amit = join(shop, "Amit");
        addItem(shop, rahul, shop.pizza(), 1);
        addItem(shop, amit, shop.burger(), 1);

        mockMvc.perform(asGuest(delete("/api/public/tables/" + shop.qr() + "/cart"), rahul))
                .andExpect(status().isNoContent());

        assertEquals(0, cart(shop, rahul).get("items").size());
        assertEquals(1, cart(shop, amit).get("items").size());
    }

    @Test
    void withoutAValidGuestTokenTheCartAndOrdersAreRefused() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        addItem(shop, rahul, shop.pizza(), 1);

        String otherTableQr = createTable(shop.owner(), "T6").get("qrIdentifier").asText();
        String otherTablesGuest = DinerTestSupport.join(mockMvc, objectMapper, otherTableQr, "Amit");

        String[] tokens = {null, "", "not-a-real-token", otherTablesGuest};
        for (String token : tokens) {
            mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/cart"), token))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items"), null,
                            "{\"menuItemId\":" + shop.pizza() + ",\"quantity\":1}"), token))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), token))
                    .andExpect(status().isNotFound());
        }

        // And none of that disturbed Rahul's cart.
        assertEquals(1, cart(shop, rahul).get("items").size());
    }

    // ------------------------------------------------------------------ order ownership

    @Test
    void everyOrderRecordsWhichGuestPlacedIt_andStaffCanSee() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        String amit = join(shop, "Amit");
        String secondRahul = join(shop, "Rahul");

        addItem(shop, rahul, shop.pizza(), 2);
        addItem(shop, amit, shop.burger(), 1);
        addItem(shop, secondRahul, shop.pizza(), 1);
        addItem(shop, secondRahul, shop.burger(), 1);

        JsonNode rahulsOrder = placeOrder(shop, rahul);
        JsonNode amitsOrder = placeOrder(shop, amit);
        JsonNode secondOrder = placeOrder(shop, secondRahul);

        assertEquals("Rahul", rahulsOrder.get("dinerName").asText());
        assertEquals("Amit", amitsOrder.get("dinerName").asText());
        assertEquals("Rahul (2)", secondOrder.get("dinerName").asText());

        assertMoney("800", rahulsOrder.get("total"));
        assertMoney("250", amitsOrder.get("total"));
        assertMoney("650", secondOrder.get("total"));

        // All three belong to Table 5, but to three different guests.
        for (JsonNode order : List.of(rahulsOrder, amitsOrder, secondOrder)) {
            assertEquals(shop.tableId(), order.get("restaurantTableId").asLong());
        }
        Set<Long> guests = new HashSet<>(List.of(
                rahulsOrder.get("dinerSessionId").asLong(),
                amitsOrder.get("dinerSessionId").asLong(),
                secondOrder.get("dinerSessionId").asLong()));
        assertEquals(3, guests.size());

        // Staff see the same thing in the order list.
        JsonNode page = json(get("/api/v1/orders?page=0&size=50").header("Authorization", "Bearer " + shop.owner()));
        Map<String, String> nameByOrder = new HashMap<>();
        for (JsonNode o : page.get("items")) {
            nameByOrder.put(o.get("orderNumber").asText(), o.get("dinerName").asText());
        }
        assertEquals("Rahul", nameByOrder.get(rahulsOrder.get("orderNumber").asText()));
        assertEquals("Amit", nameByOrder.get(amitsOrder.get("orderNumber").asText()));
        assertEquals("Rahul (2)", nameByOrder.get(secondOrder.get("orderNumber").asText()));
    }

    @Test
    void placingOneGuestsOrderLeavesTheOthersCartsAlone() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        String amit = join(shop, "Amit");
        String priya = join(shop, "Priya");
        addItem(shop, rahul, shop.pizza(), 1);
        addItem(shop, amit, shop.burger(), 2);

        JsonNode rahulsOrder = placeOrder(shop, rahul);
        assertMoney("400", rahulsOrder.get("total"));

        // Amit's cart is untouched, and his order is his own.
        assertEquals(1, cart(shop, amit).get("items").size());
        JsonNode amitsOrder = placeOrder(shop, amit);
        assertMoney("500", amitsOrder.get("total"));

        // Rahul's cart was checked out; he starts a fresh, empty one.
        assertEquals(0, cart(shop, rahul).get("items").size());

        // Priya never added anything, so she has nothing to order - and Rahul's and Amit's items
        // are not hers to order.
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), priya))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aGuestCanPlaceSeveralOrdersDuringOneVisit() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");

        addItem(shop, rahul, shop.pizza(), 1);
        JsonNode first = placeOrder(shop, rahul);
        addItem(shop, rahul, shop.burger(), 1);
        JsonNode second = placeOrder(shop, rahul);

        assertEquals(first.get("dinerSessionId").asLong(), second.get("dinerSessionId").asLong());
        assertTrue(!first.get("orderNumber").asText().equals(second.get("orderNumber").asText()));
        assertMoney("400", first.get("total"));
        assertMoney("250", second.get("total"));
    }

    @Test
    void aGuestsOrderCanStillBeTrackedByItsTokenWithoutTheGuestToken() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        addItem(shop, rahul, shop.pizza(), 1);
        String orderToken = placeOrder(shop, rahul).get("publicToken").asText();

        // Tracking is its own credential, unchanged by phase 2.
        mockMvc.perform(get("/api/public/orders/by-token/" + orderToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ coupons and the staff cart

    @Test
    void aCouponHeldByOneGuestDoesNotReachAnother() throws Exception {
        Shop shop = setupShop();
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        mockMvc.perform(withBody(post("/api/v1/coupons"), shop.owner(), """
                        {"restaurantId":%d,"code":"WELCOME10","name":"Welcome","discountType":"PERCENTAGE",
                         "discountValue":10,"minimumOrderAmount":0,"minimumItems":0,"usageLimit":0,
                         "startDate":"%s","expiryDate":"%s","active":true}
                        """.formatted(shop.restaurantId(), start, end)))
                .andExpect(status().is2xxSuccessful());

        String rahul = join(shop, "Rahul");
        String amit = join(shop, "Amit");
        addItem(shop, rahul, shop.pizza(), 1);
        addItem(shop, amit, shop.pizza(), 1);

        JsonNode applied = json(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/coupon"), null,
                "{\"couponCode\":\"WELCOME10\"}"), rahul));
        assertMoney("40", applied.get("discount"));

        JsonNode amitsCart = cart(shop, amit);
        assertMoney("0", amitsCart.get("discount"));
        assertMoney("400", amitsCart.get("total"));
        assertTrue(amitsCart.get("couponCode").isNull());
    }

    @Test
    void theStaffCartIsSeparateFromEveryGuestsCart() throws Exception {
        Shop shop = setupShop();
        String rahul = join(shop, "Rahul");
        addItem(shop, rahul, shop.pizza(), 1);

        // A waiter builds an order for the table through the staff API.
        mockMvc.perform(withBody(post("/api/v1/cart/items"), shop.owner(),
                        "{\"tableQrIdentifier\":\"" + shop.qr() + "\",\"menuItemId\":" + shop.burger() + ",\"quantity\":3}"))
                .andExpect(status().isOk());
        JsonNode staffOrder = json(withBody(post("/api/v1/orders"), shop.owner(),
                "{\"restaurantTableId\":" + shop.tableId() + "}"));

        assertMoney("750", staffOrder.get("total"));
        assertTrue(staffOrder.get("dinerName").isNull(), "a staff order belongs to no guest");
        assertTrue(staffOrder.get("dinerSessionId").isNull());

        // Rahul's own cart was neither merged into it nor placed by it.
        JsonNode rahulsCart = cart(shop, rahul);
        assertEquals(1, rahulsCart.get("items").size());
        assertMoney("400", rahulsCart.get("total"));
        assertMoney("400", placeOrder(shop, rahul).get("total"));
    }

    // ------------------------------------------------------------------ simultaneous guests

    @Test
    void guestsOrderingAtTheSameMomentAllSucceed_evenOnTheRestaurantsVeryFirstOrder() throws Exception {
        Shop shop = setupShop();

        int guests = 6;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < guests; i++) {
            String token = join(shop, "Guest" + i);
            addItem(shop, token, shop.pizza(), 1);
            tokens.add(token);
        }

        // A brand-new restaurant has no order counter row yet, so this is the case that used to race.
        ExecutorService pool = Executors.newFixedThreadPool(guests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> pending = new ArrayList<>();
        try {
            for (String token : tokens) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(asGuest(withBody(
                            post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), token)).andReturn();
                }));
            }
            go.countDown();

            Set<String> numbers = new HashSet<>();
            for (Future<MvcResult> f : pending) {
                MvcResult result = f.get(60, TimeUnit.SECONDS);
                assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
                numbers.add(objectMapper.readTree(result.getResponse().getContentAsString()).get("orderNumber").asText());
            }
            assertEquals(guests, numbers.size(), "every simultaneous order must get its own number");
        } finally {
            pool.shutdownNow();
        }
    }
}
