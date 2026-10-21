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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4: paying at a table, separately or together.
 *
 * <p>Table 5 in the brief: Rahul 800, Amit 250, Priya 650, with real orders, real settlements and
 * staff confirming the money. Nothing is paid until staff confirm it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class SettlementIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String owner, long restaurantId, String qr, long pizza, long burger) {
    }

    private record Guest(String token, long orderId, String orderToken) {
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

    private MockHttpServletRequestBuilder asGuest(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header(DinerTestSupport.HEADER, token);
    }

    private String registerOwner() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
    }

    /** Only cash is enabled, so the tests can also prove a disabled method is refused. */
    private Shop setupShop() throws Exception {
        String owner = registerOwner();
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

        String qr = createTable(owner, "T5").get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), owner,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());

        return new Shop(owner, restaurantId, qr, pizza, burger);
    }

    private JsonNode createTable(String owner, String code) throws Exception {
        return json(withBody(post("/api/v1/tables"), owner,
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"));
    }

    private Guest order(Shop shop, String name, long[] itemIds, int[] quantities) throws Exception {
        String token = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), name);
        return orderAs(shop, token, itemIds, quantities);
    }

    private Guest orderAs(Shop shop, String token, long[] itemIds, int[] quantities) throws Exception {
        for (int i = 0; i < itemIds.length; i++) {
            mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items"), null,
                            "{\"menuItemId\":" + itemIds[i] + ",\"quantity\":" + quantities[i] + "}"), token))
                    .andExpect(status().isOk());
        }
        String body = mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode order = objectMapper.readTree(body);
        return new Guest(token, order.get("id").asLong(), order.get("publicToken").asText());
    }

    private void confirm(Shop shop, Guest guest) throws Exception {
        mockMvc.perform(put("/api/v1/orders/" + guest.orderId() + "/status?status=CONFIRMED")
                        .header("Authorization", "Bearer " + shop.owner()))
                .andExpect(status().isOk());
    }

    /** Table 5, every order confirmed: Rahul 800, Amit 250, Priya 650. */
    private Guest[] table5(Shop shop) throws Exception {
        Guest[] guests = {
                order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{2}),
                order(shop, "Amit", new long[]{shop.burger()}, new int[]{1}),
                order(shop, "Priya", new long[]{shop.pizza(), shop.burger()}, new int[]{1, 1})};
        for (Guest g : guests) {
            confirm(shop, g);
        }
        return guests;
    }

    private MockHttpServletRequestBuilder settleRequest(
            Shop shop, String token, String kind, String method, String key) {
        MockHttpServletRequestBuilder b = withBody(post("/api/public/tables/" + shop.qr() + "/settlements"), null,
                "{\"kind\":\"" + kind + "\",\"paymentMethod\":\"" + method + "\"}");
        if (key != null) {
            b.header(PublicSettlementController.IDEMPOTENCY_HEADER, key);
        }
        return asGuest(b, token);
    }

    private JsonNode settle(Shop shop, Guest guest, String kind, String method, String key, int expected) throws Exception {
        String body = mockMvc.perform(settleRequest(shop, guest.token(), kind, method, key))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private JsonNode settle(Shop shop, Guest guest, String kind) throws Exception {
        return settle(shop, guest, kind, "CASH", null, 201);
    }

    private JsonNode settlement(Shop shop, Guest guest, String code) throws Exception {
        String body = mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/settlements/" + code), guest.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private long staffIdOf(Shop shop, String code) throws Exception {
        JsonNode pending = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner()));
        for (JsonNode s : pending) {
            if (s.get("code").asText().equals(code)) {
                return s.get("id").asLong();
            }
        }
        throw new AssertionError("settlement " + code + " is not awaiting staff");
    }

    private JsonNode staffSets(Shop shop, long settlementId, String status, String reference, int expected) throws Exception {
        String body = mockMvc.perform(withBody(put("/api/v1/settlements/" + settlementId + "/status"), shop.owner(),
                        "{\"paymentStatus\":\"" + status + "\""
                                + (reference == null ? "" : ",\"transactionId\":\"" + reference + "\"") + "}"))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private JsonNode confirmSettlement(Shop shop, String code) throws Exception {
        return staffSets(shop, staffIdOf(shop, code), "SUCCESS", null, 200);
    }

    private JsonNode orderSummary(Shop shop, Guest guest) throws Exception {
        return json(get("/api/v1/payments/orders/" + guest.orderId() + "/summary")
                .header("Authorization", "Bearer " + shop.owner()));
    }

    private JsonNode myBill(Shop shop, Guest guest) throws Exception {
        return objectMapper.readTree(mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/bill"), guest.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode tableBill(Shop shop, Guest guest) throws Exception {
        return objectMapper.readTree(mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/bill/table"), guest.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())),
                "expected " + expected + " but was " + actual.asText());
    }

    private List<Integer> inParallel(int count, Callable<MvcResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> f : pending) {
                statuses.add(f.get(90, TimeUnit.SECONDS).getResponse().getStatus());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    // ------------------------------------------------------------------ paying separately

    @Test
    void payingSeparatelyTakesOnlyThatGuestsBill() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest rahul = g[0], amit = g[1], priya = g[2];

        JsonNode started = settle(shop, rahul, "SEPARATE");

        assertEquals("SEPARATE", started.get("kind").asText());
        assertEquals("PENDING", started.get("status").asText(), "nothing is paid until staff confirm it");
        assertMoney("800", started.get("total"));
        assertMoney("800", started.get("pending"));
        assertMoney("0", started.get("confirmed"));
        assertEquals(1, started.get("guests").size());
        assertEquals("Rahul", started.get("guests").get(0).get("displayName").asText());
        assertTrue(started.get("guests").get(0).get("you").asBoolean());

        // Rahul's money is in progress, not paid; the others are untouched.
        assertMoney("0", myBill(shop, rahul).get("due"));
        assertMoney("800", myBill(shop, rahul).get("inProgress"));
        assertMoney("0", myBill(shop, rahul).get("paid"));
        assertMoney("250", myBill(shop, amit).get("due"));
        assertMoney("650", myBill(shop, priya).get("due"));

        // Staff see it and confirm it.
        JsonNode pending = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner()));
        assertEquals(1, pending.size());
        assertEquals("Rahul", pending.get(0).get("payerName").asText());
        assertEquals("Table T5", pending.get(0).get("tableName").asText());
        assertEquals(1, pending.get(0).get("orders").size());

        JsonNode confirmed = confirmSettlement(shop, started.get("code").asText());
        assertEquals("SUCCESS", confirmed.get("status").asText());

        // Only Rahul's balance changed.
        assertMoney("800", myBill(shop, rahul).get("paid"));
        assertMoney("0", myBill(shop, rahul).get("due"));
        assertMoney("0", myBill(shop, rahul).get("inProgress"));
        assertMoney("250", myBill(shop, amit).get("due"));
        assertMoney("0", myBill(shop, amit).get("paid"));
        assertMoney("650", myBill(shop, priya).get("due"));
        assertMoney("250", orderSummary(shop, amit).get("outstanding"));
        assertMoney("0", orderSummary(shop, amit).get("settled"));

        // The guest can read their settlement back, now SUCCESS, and nothing is pending for staff.
        assertEquals("SUCCESS", settlement(shop, rahul, started.get("code").asText()).get("status").asText());
        assertEquals(0, json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner())).size());
    }

    // ------------------------------------------------------------------ paying together

    @Test
    void payingTogetherCoversTheWholeTable_andEachOrderKeepsItsOwnPayment() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest rahul = g[0], amit = g[1], priya = g[2];

        JsonNode started = settle(shop, priya, "COMBINED");

        assertEquals("COMBINED", started.get("kind").asText());
        assertMoney("1700", started.get("total"));
        assertEquals(3, started.get("guests").size());

        // The breakdown is by person: a name and an amount each, and only Priya's line says "you".
        for (JsonNode line : started.get("guests")) {
            String name = line.get("displayName").asText();
            assertEquals(name.equals("Priya"), line.get("you").asBoolean());
            assertMoney(name.equals("Rahul") ? "800" : name.equals("Amit") ? "250" : "650", line.get("amount"));
        }
        String raw = started.toString();
        assertFalse(raw.contains("Pizza") || raw.contains("Burger"), "no item names");
        assertFalse(raw.contains("ORD-"), "no order numbers");

        // One payment per order, each for that order's own amount, linked to nothing else.
        for (Guest guest : g) {
            JsonNode summary = orderSummary(shop, guest);
            assertEquals(1, summary.get("payments").size());
            assertEquals("PENDING", summary.get("payments").get(0).get("paymentStatus").asText());
        }
        assertMoney("800", orderSummary(shop, rahul).get("payments").get(0).get("amount"));
        assertMoney("250", orderSummary(shop, amit).get("payments").get(0).get("amount"));
        assertMoney("650", orderSummary(shop, priya).get("payments").get(0).get("amount"));

        // In progress for everyone, due for no one, paid for no one.
        assertMoney("0", tableBill(shop, rahul).get("due"));
        assertMoney("1700", tableBill(shop, rahul).get("inProgress"));
        assertMoney("0", tableBill(shop, rahul).get("paid"));

        // Staff confirm once, with one receipt reference, and every covered order is paid.
        JsonNode done = staffSets(shop, staffIdOf(shop, started.get("code").asText()), "SUCCESS", "RCPT-1", 200);
        assertEquals("SUCCESS", done.get("status").asText());
        assertMoney("1700", done.get("confirmed"));

        for (Guest guest : g) {
            JsonNode summary = orderSummary(shop, guest);
            assertEquals("SUCCESS", summary.get("status").asText());
            assertMoney("0", summary.get("outstanding"));
            assertEquals("RCPT-1", summary.get("payments").get(0).get("transactionId").asText());
        }
        assertMoney("0", tableBill(shop, amit).get("due"));
        assertMoney("1700", tableBill(shop, amit).get("paid"));

        // What the receipt needs: when it was confirmed, and the reference staff gave.
        JsonNode receipt = settlement(shop, priya, started.get("code").asText());
        assertEquals("SUCCESS", receipt.get("status").asText());
        assertFalse(receipt.get("confirmedAt").isNull(), "a confirmed payment says when");
        assertEquals("RCPT-1", receipt.get("reference").asText());
        assertTrue(started.get("confirmedAt").isNull(), "nothing was confirmed when it started");
        assertTrue(started.get("reference").isNull());

        // Each order is still tied to the guest who placed it.
        assertMoney("800", myBill(shop, rahul).get("paid"));
        assertMoney("250", myBill(shop, amit).get("paid"));
        assertMoney("650", myBill(shop, priya).get("paid"));
    }

    @Test
    void aCombinedPaymentLeavesOutWhatIsAlreadyPaidOrInProgress() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest rahul = g[0], amit = g[1], priya = g[2];

        // Rahul has already paid at the counter; Amit has a payment of his own in progress.
        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + rahul.orderId() + "/collect"), shop.owner(),
                        "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":800}]}"))
                .andExpect(status().isCreated());
        settle(shop, amit, "SEPARATE");

        JsonNode together = settle(shop, priya, "COMBINED");

        assertMoney("650", together.get("total"));
        assertEquals(1, together.get("guests").size());
        assertEquals("Priya", together.get("guests").get(0).get("displayName").asText());

        // Nothing is left for anyone to take a second time.
        JsonNode again = settle(shop, priya, "COMBINED", "CASH", null, 409);
        assertTrue(again.get("message").asText().contains("nothing left to pay"));
        assertEquals(1, orderSummary(shop, rahul).get("payments").size(), "Rahul was not charged again");
        assertEquals(1, orderSummary(shop, amit).get("payments").size(), "Amit was not charged twice");
    }

    @Test
    void ordersPlacedAfterAPaymentStartsAreNotSwallowedByIt() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest amit = g[1], priya = g[2];

        JsonNode started = settle(shop, priya, "COMBINED");
        assertMoney("1700", started.get("total"));

        // Amit orders again after Priya has started paying for the table.
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items"), null,
                        "{\"menuItemId\":" + shop.burger() + ",\"quantity\":1}"), amit.token()))
                .andExpect(status().isOk());
        JsonNode second = objectMapper.readTree(mockMvc.perform(asGuest(
                        withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), amit.token()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(put("/api/v1/orders/" + second.get("id").asLong() + "/status?status=CONFIRMED")
                        .header("Authorization", "Bearer " + shop.owner()))
                .andExpect(status().isOk());

        // The new order is its own outstanding balance; the payment in progress is unchanged.
        JsonNode table = tableBill(shop, priya);
        assertMoney("250", table.get("due"));
        assertMoney("1700", table.get("inProgress"));
        assertMoney("1700", settlement(shop, priya, started.get("code").asText()).get("total"));

        confirmSettlement(shop, started.get("code").asText());

        assertMoney("250", tableBill(shop, priya).get("due"));
        assertMoney("1700", tableBill(shop, priya).get("paid"));
    }

    // ------------------------------------------------------------------ failure and cancellation

    @Test
    void aFailedOrCancelledPaymentReleasesTheOrdersForARetry() throws Exception {
        Shop shop = setupShop();
        Guest rahul = table5(shop)[0];

        // Staff mark it failed: the order is payable again.
        JsonNode first = settle(shop, rahul, "SEPARATE");
        JsonNode failed = staffSets(shop, staffIdOf(shop, first.get("code").asText()), "FAILED", null, 200);
        assertEquals("FAILED", failed.get("status").asText());
        assertMoney("800", myBill(shop, rahul).get("due"));
        assertMoney("0", myBill(shop, rahul).get("paid"));

        // The guest tries again, then changes their mind and withdraws it.
        JsonNode second = settle(shop, rahul, "SEPARATE");
        assertFalse(first.get("code").asText().equals(second.get("code").asText()));

        String cancelUrl = "/api/public/tables/" + shop.qr() + "/settlements/" + second.get("code").asText() + "/cancel";
        JsonNode cancelled = objectMapper.readTree(mockMvc.perform(asGuest(post(cancelUrl), rahul.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertMoney("800", myBill(shop, rahul).get("due"));

        // Withdrawing twice is harmless.
        mockMvc.perform(asGuest(post(cancelUrl), rahul.token())).andExpect(status().isOk());

        // A third attempt goes through; staff confirm it.
        JsonNode third = settle(shop, rahul, "SEPARATE");
        confirmSettlement(shop, third.get("code").asText());
        assertMoney("800", myBill(shop, rahul).get("paid"));

        // Money received cannot be withdrawn by the guest, and staff cannot flip the decision.
        mockMvc.perform(asGuest(post("/api/public/tables/" + shop.qr() + "/settlements/"
                        + third.get("code").asText() + "/cancel"), rahul.token()))
                .andExpect(status().isConflict());
        assertEquals(0, json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + shop.owner())).size(),
                "nothing is left waiting");
    }

    @Test
    void staffDecisionsAreIdempotentAndCannotBeContradicted() throws Exception {
        Shop shop = setupShop();
        Guest rahul = table5(shop)[0];

        JsonNode started = settle(shop, rahul, "SEPARATE");
        long id = staffIdOf(shop, started.get("code").asText());

        staffSets(shop, id, "SUCCESS", null, 200);
        // The same decision again (a double click, a retried request) changes nothing.
        assertEquals("SUCCESS", staffSets(shop, id, "SUCCESS", null, 200).get("status").asText());
        // A different decision after money was received is refused.
        staffSets(shop, id, "FAILED", null, 409);
        staffSets(shop, id, "CANCELLED", null, 409);
        // And SUCCESS cannot be recorded as anything staff do not offer.
        staffSets(shop, id, "REFUNDED", null, 400);

        assertEquals(1, orderSummary(shop, rahul).get("payments").size());
        assertMoney("800", orderSummary(shop, rahul).get("settled"));
    }

    // ------------------------------------------------------------------ retries and simultaneous requests

    @Test
    void retryingWithTheSameKeyReturnsTheSameSettlement() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest rahul = g[0], amit = g[1];

        JsonNode first = settle(shop, rahul, "SEPARATE", "CASH", "attempt-1", 201);
        JsonNode retry = settle(shop, rahul, "SEPARATE", "CASH", "attempt-1", 201);

        assertEquals(first.get("code").asText(), retry.get("code").asText());
        assertEquals(1, orderSummary(shop, rahul).get("payments").size(), "no second payment was made");

        // The same key for a different payment is a mistake, not a replay.
        settle(shop, rahul, "COMBINED", "CASH", "attempt-1", 400);

        // Keys belong to the guest who made them: Amit using the same text makes his own settlement.
        JsonNode others = settle(shop, amit, "SEPARATE", "CASH", "attempt-1", 201);
        assertFalse(others.get("code").asText().equals(first.get("code").asText()));

        // Keys are limited to a safe alphabet and length.
        settle(shop, rahul, "SEPARATE", "CASH", "bad key with spaces!", 400);
    }

    @Test
    void twoGuestsStartingACombinedPaymentAtOnceChargeTheTableOnce() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        List<String> payers = List.of(g[1].token(), g[2].token());   // Amit and Priya
        AtomicInteger next = new AtomicInteger();

        // Both ask to pay the whole table at the same moment.
        List<Integer> statuses = inParallel(2, () -> mockMvc.perform(settleRequest(
                shop, payers.get(next.getAndIncrement()), "COMBINED", "CASH", null)).andReturn());

        assertEquals(1, count(statuses, 201), "exactly one of them may take the table");
        assertEquals(1, count(statuses, 409), "the other finds it already being paid");

        for (Guest guest : g) {
            assertEquals(1, orderSummary(shop, guest).get("payments").size(), "every order has exactly one payment");
        }
        assertMoney("1700", tableBill(shop, g[0]).get("inProgress"));
        assertMoney("0", tableBill(shop, g[0]).get("due"));
    }

    @Test
    void simultaneousRequestsWithTheSameKeyAllReturnTheSameSettlement() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest priya = g[2];

        int tries = 4;
        ExecutorService pool = Executors.newFixedThreadPool(tries);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> pending = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        try {
            for (int i = 0; i < tries; i++) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(settleRequest(shop, priya.token(), "COMBINED", "CASH", "same-key")).andReturn();
                }));
            }
            go.countDown();
            for (Future<MvcResult> f : pending) {
                MvcResult r = f.get(90, TimeUnit.SECONDS);
                assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
                codes.add(objectMapper.readTree(r.getResponse().getContentAsString()).get("code").asText());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, codes.size(), "one key, one settlement");
        for (Guest guest : g) {
            assertEquals(1, orderSummary(shop, guest).get("payments").size(), "every order has exactly one payment");
        }
    }

    @Test
    void aDoubleTapWithoutAKeyStillStartsOnlyOnePayment() throws Exception {
        Shop shop = setupShop();
        Guest rahul = table5(shop)[0];

        List<Integer> statuses = inParallel(4, () -> mockMvc.perform(
                settleRequest(shop, rahul.token(), "SEPARATE", "CASH", null)).andReturn());

        assertEquals(1, count(statuses, 201), "only one tap may start the payment");
        assertEquals(3, count(statuses, 409));
        assertEquals(1, orderSummary(shop, rahul).get("payments").size());
        assertMoney("800", orderSummary(shop, rahul).get("pending"));
    }

    // ------------------------------------------------------------------ what can be paid

    @Test
    void nothingIsPayableUntilTheKitchenConfirmsTheOrder() throws Exception {
        Shop shop = setupShop();
        Guest rahul = order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{1});

        JsonNode refused = settle(shop, rahul, "SEPARATE", "CASH", null, 409);
        assertTrue(refused.get("message").asText().contains("waiting for the kitchen"), refused.toString());

        confirm(shop, rahul);
        assertMoney("400", settle(shop, rahul, "SEPARATE").get("total"));
    }

    @Test
    void aGuestWithNothingOrderedHasNothingToPay() throws Exception {
        Shop shop = setupShop();
        Guest nobody = new Guest(DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Rahul"), 0, "");

        settle(shop, nobody, "SEPARATE", "CASH", null, 409);
        settle(shop, nobody, "COMBINED", "CASH", null, 409);
    }

    @Test
    void aCancelledOrderIsNeverCharged() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest rahul = g[0], priya = g[2];

        mockMvc.perform(put("/api/v1/orders/" + rahul.orderId() + "/status?status=CANCELLED")
                        .header("Authorization", "Bearer " + shop.owner()))
                .andExpect(status().isOk());

        JsonNode together = settle(shop, priya, "COMBINED");

        assertMoney("900", together.get("total"));
        assertEquals(0, orderSummary(shop, rahul).get("payments").size());
    }

    @Test
    void onlyMethodsADinerMayChooseAndTheRestaurantEnablesAreAccepted() throws Exception {
        Shop shop = setupShop();
        Guest rahul = table5(shop)[0];

        settle(shop, rahul, "SEPARATE", "BANK_TRANSFER", null, 400);   // staff-only method
        settle(shop, rahul, "SEPARATE", "GIFT_CARD", null, 400);       // staff-only method
        settle(shop, rahul, "SEPARATE", "UPI", null, 400);             // not enabled at this restaurant

        // A refused attempt leaves nothing behind: the order is still fully payable.
        assertMoney("800", myBill(shop, rahul).get("due"));
        assertEquals(0, orderSummary(shop, rahul).get("payments").size());

        // The guest chooses the kind and the method; leaving either out is an error.
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/settlements"), null, "{\"paymentMethod\":\"CASH\"}")
                        .header(DinerTestSupport.HEADER, rahul.token()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/settlements"), null, "{\"kind\":\"SEPARATE\"}")
                        .header(DinerTestSupport.HEADER, rahul.token()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ access

    @Test
    void aSettlementBelongsToItsPayerAlone() throws Exception {
        Shop shop = setupShop();
        Guest[] g = table5(shop);
        Guest rahul = g[0], amit = g[1];

        JsonNode started = settle(shop, rahul, "SEPARATE");
        String code = started.get("code").asText();
        String url = "/api/public/tables/" + shop.qr() + "/settlements/" + code;

        // Another guest at the same table cannot read it or withdraw it.
        mockMvc.perform(asGuest(get(url), amit.token())).andExpect(status().isNotFound());
        mockMvc.perform(asGuest(post(url + "/cancel"), amit.token())).andExpect(status().isNotFound());

        // Nor can someone with no token, a forged one, or a guest from another table.
        String otherQr = createTable(shop.owner(), "T6").get("qrIdentifier").asText();
        String strangers = DinerTestSupport.join(mockMvc, objectMapper, otherQr, "Amit");
        for (String token : new String[]{null, "", "not-a-real-token", strangers}) {
            mockMvc.perform(asGuest(get(url), token)).andExpect(status().isNotFound());
            mockMvc.perform(asGuest(post(url + "/cancel"), token)).andExpect(status().isNotFound());
            mockMvc.perform(settleRequest(shop, token, "SEPARATE", "CASH", null)).andExpect(status().isNotFound());
        }

        // Amit's own list does not show Rahul's, and Rahul's payment is untouched by all of it.
        JsonNode amitsList = objectMapper.readTree(mockMvc.perform(asGuest(
                        get("/api/public/tables/" + shop.qr() + "/settlements"), amit.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, amitsList.size());
        assertEquals("PENDING", settlement(shop, rahul, code).get("status").asText());
    }

    @Test
    void aGuestCanFindTheirPaymentAgainAfterRefreshing() throws Exception {
        Shop shop = setupShop();
        Guest rahul = table5(shop)[0];

        JsonNode started = settle(shop, rahul, "SEPARATE");

        JsonNode list = objectMapper.readTree(mockMvc.perform(asGuest(
                        get("/api/public/tables/" + shop.qr() + "/settlements"), rahul.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals(1, list.size());
        assertEquals(started.get("code").asText(), list.get(0).get("code").asText());
        assertEquals("PENDING", list.get(0).get("status").asText());
    }

    @Test
    void staffEndpointsNeedStaffAndStayWithinTheirRestaurant() throws Exception {
        Shop shop = setupShop();
        Guest rahul = table5(shop)[0];
        JsonNode started = settle(shop, rahul, "SEPARATE");
        long id = staffIdOf(shop, started.get("code").asText());

        // No sign-in: refused.
        mockMvc.perform(get("/api/v1/settlements/pending")).andExpect(status().is4xxClientError());
        mockMvc.perform(withBody(put("/api/v1/settlements/" + id + "/status"), null, "{\"paymentStatus\":\"SUCCESS\"}"))
                .andExpect(status().is4xxClientError());

        // Another restaurant's owner cannot see it, and cannot confirm it.
        String other = registerOwner();
        JsonNode theirs = json(get("/api/v1/settlements/pending").header("Authorization", "Bearer " + other));
        assertEquals(0, theirs.size());
        mockMvc.perform(withBody(put("/api/v1/settlements/" + id + "/status"), other, "{\"paymentStatus\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());

        assertEquals("PENDING", settlement(shop, rahul, started.get("code").asText()).get("status").asText());
    }
}
