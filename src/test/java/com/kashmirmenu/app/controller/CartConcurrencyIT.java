package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.enums.CartStatus;
import com.kashmirmenu.app.repository.CartRepository;
import com.kashmirmenu.app.repository.CouponRepository;
import com.kashmirmenu.app.repository.InventoryRepository;
import com.kashmirmenu.app.repository.OrderRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency regressions for a guest's cart and checkout (BUG-001, BUG-007).
 *
 * <p>Each guest at a table has their own cart, so the contention here is two requests writing the SAME
 * guest's cart at once: a double tap on a phone, or two requests racing to be first. The QR flow is
 * unauthenticated and phones are unreliable, so that happens by design. The guest's row lock serialises
 * it. Nothing in the suite exercised this before, which is why these defects survived.
 *
 * <p>Assertions count only THIS test's restaurant. Every integration test shares one database, so
 * "the number of orders in it" depends on which tests ran first.
 *
 * <p>Each test fires genuinely simultaneous requests using a latch, so the threads are released
 * together rather than merely being started together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class CartConcurrencyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private CouponRepository couponRepository;

    private record Shop(String token, long restaurantId, long menuItemId, String qr, String diner) {
    }

    // ------------------------------------------------------------------ concurrency helper

    /**
     * Runs {@code count} copies of a call at the same instant and returns every outcome.
     *
     * <p>A latch rather than a loop of {@code submit}: starting threads sequentially often lets the
     * first finish before the last begins, which is exactly the interleaving these tests must avoid
     * producing a false pass.
     */
    private <T> List<Object> inParallel(int count, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return (Object) call.call();
                    } catch (Exception e) {
                        // Returned rather than thrown: a refusal is a valid outcome here, and the
                        // assertions are about how many succeeded, not that none failed.
                        return (Object) e;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not reach the start line");
            go.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int statusOf(Object outcome) {
        return outcome instanceof MvcResult result ? result.getResponse().getStatus() : -1;
    }

    // ------------------------------------------------------------------ setup

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response =
                mockMvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString();

        // A setup call that fails used to surface as a NullPointerException several lines later,
        // when the test dereferenced a field the error body does not have - which says nothing
        // about WHY. Failing here, with the status and body, names the actual cause.
        if (response.getStatus() >= 400) {
            throw new AssertionError("Unexpected " + response.getStatus() + " from "
                    + request.buildRequest(new org.springframework.mock.web.MockServletContext())
                    .getMethod() + " "
                    + request.buildRequest(new org.springframework.mock.web.MockServletContext())
                    .getRequestURI() + ": " + body);
        }
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token, String body) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private Shop setupShop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();

        long restaurantId = json(withBody(get("/api/v1/me"), token, "").contentType(MediaType.APPLICATION_JSON))
                .get("restaurantId").asLong();

        long categoryId = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long menuItemId = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Dish " + id + "\",\"price\":600.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}"))
                .get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"shop@upi\"}")).andExpect(status().isCreated());

        return new Shop(token, restaurantId, menuItemId, qr,
                DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest"));
    }

    /**
     * Counted for THIS restaurant only. Every integration test shares one in-memory database, so a count
     * of everything in it depends on which tests happened to run first.
     */
    private int ordersOf(Shop shop) {
        return (int) orderRepository.findAll().stream()
                .filter(o -> o.getRestaurant().getId().equals(shop.restaurantId()))
                .count();
    }

    private int activeCartsOf(Shop shop) {
        return (int) cartRepository.findAll().stream()
                .filter(c -> c.getStatus() == CartStatus.ACTIVE)
                .filter(c -> c.getRestaurant().getId().equals(shop.restaurantId()))
                .count();
    }

    private void addItem(Shop shop, int quantity) throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                        "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ BUG-001

    @Test
    void doubleTappingPlaceOrderCreatesOneOrder() throws Exception {
        Shop shop = setupShop();
        addItem(shop, 2);

        List<Object> results = inParallel(2, () ->
                mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"))
                        .andReturn());

        long created = results.stream().filter(r -> statusOf(r) == 200 || statusOf(r) == 201).count();

        assertEquals(1, created, "exactly one of two simultaneous checkouts may succeed");
        assertEquals(1, ordersOf(shop), "a second order row must not exist");
        assertEquals(0, activeCartsOf(shop), "the cart must be checked out, not left active");
    }

    @Test
    void doubleTappingDeductsStockOnlyOnce() throws Exception {
        Shop shop = setupShop();

        long inventoryId = json(withBody(post("/api/v1/inventory"), shop.token(),
                "{\"restaurantId\":" + shop.restaurantId() + ",\"menuItemId\":" + shop.menuItemId()
                        + ",\"availableQuantity\":10,\"minimumQuantity\":1}"))
                .get("data").get("id").asLong();

        addItem(shop, 2);

        inParallel(2, () -> mockMvc.perform(
                withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}")).andReturn());

        assertEquals(8, inventoryRepository.findById(inventoryId).orElseThrow().getAvailableQuantity(),
                "stock must move once for one order, not twice");
    }

    @Test
    void doubleTappingClaimsACouponOnlyOnce() throws Exception {
        Shop shop = setupShop();
        String code = "ONCE" + UUID.randomUUID().toString().substring(0, 4);

        long couponId = json(withBody(post("/api/v1/coupons"), shop.token(), """
                {"restaurantId":%d,"code":"%s","name":"Once","discountType":"FIXED","discountValue":100,
                 "minimumOrderAmount":0,"minimumItems":0,"usageLimit":5,
                 "startDate":"%s","expiryDate":"%s","active":true}
                """.formatted(shop.restaurantId(), code,
                java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now().plusDays(1))))
                .get("id").asLong();

        addItem(shop, 2);
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/coupon").header(DinerTestSupport.HEADER, shop.diner()), null,
                "{\"couponCode\":\"" + code + "\"}")).andExpect(status().isOk());

        inParallel(2, () -> mockMvc.perform(
                withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}")).andReturn());

        assertEquals(1, couponRepository.findById(couponId).orElseThrow().getUsedCount(),
                "one order may consume one use");
    }

    // ------------------------------------------------------------------ the duplicate-cart race

    @Test
    void twoFirstRequestsCreateOneCart() throws Exception {
        // Found while implementing BUG-001, and worse than it: carts has no unique constraint on
        // (restaurant_table_id, status), so two concurrent first requests each inserted an ACTIVE
        // cart. findByRestaurantTableAndStatus then matched two rows and threw on every subsequent
        // read - a permanent 500 for that table.
        Shop shop = setupShop();

        inParallel(4, () -> mockMvc.perform(
                        withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                                "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":1}"))
                .andReturn());

        assertEquals(1, activeCartsOf(shop), "one guest may have at most one active cart");

        // The read that used to fail once duplicates existed.
        mockMvc.perform(get("/api/public/tables/" + shop.qr() + "/cart").header(DinerTestSupport.HEADER, shop.diner()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ BUG-007

    @Test
    void concurrentAddsBothLandAndTheTotalMatches() throws Exception {
        Shop shop = setupShop();

        long secondItem = json(withBody(post("/api/menu-items"), shop.token(),
                "{\"name\":\"Second " + UUID.randomUUID().toString().substring(0, 6)
                        + "\",\"price\":150.00,\"categoryId\":"
                        + json(withBody(post("/api/categories"), shop.token(), "{\"name\":\"Sides\"}"))
                        .get("data").get("id").asLong() + "}"))
                .get("data").get("id").asLong();

        List<Long> items = List.of(shop.menuItemId(), secondItem);
        List<Object> results = inParallel(2, () -> {
            long itemId = items.get((int) (Thread.currentThread().getId() % 2));
            return mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                    "{\"menuItemId\":" + itemId + ",\"quantity\":1}")).andReturn();
        });

        assertTrue(results.stream().allMatch(r -> statusOf(r) == 200), "both adds should succeed");

        JsonNode cart = json(get("/api/public/tables/" + shop.qr() + "/cart").header(DinerTestSupport.HEADER, shop.diner()));
        double lineSum = 0;
        for (JsonNode item : cart.get("items")) {
            lineSum += item.get("lineTotal").asDouble();
        }
        assertEquals(lineSum, cart.get("subtotal").asDouble(), 0.001,
                "the subtotal must equal the sum of the lines - a lost update breaks this");
    }
}
