package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency that only a real MySQL can get wrong. Runs under ./gradlew mysqlTest and is skipped on H2.
 *
 * <p>Two defects of exactly this kind were found by reading the code, and neither could have shown on H2:
 * <ul>
 *   <li>MySQL's default REPEATABLE READ fixes a transaction's snapshot at its first plain read, so a
 *       payment started while another was committing could miss it and charge an order twice;</li>
 *   <li>inserting an order, cart or payment takes a shared lock on the table visit's row, and updating
 *       that row afterwards in the same transaction made two guests acting at once deadlock.</li>
 * </ul>
 * These tests fire genuinely simultaneous requests, released together by a latch, at the shapes of
 * traffic where those would show: many guests at one table, rival payers, a table closing under its
 * guests, and everything at once. A 5xx anywhere means a lock problem has come back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
@EnabledIfSystemProperty(named = TestProfiles.PROPERTY, matches = "mysql-test")
class MysqlConcurrencyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String owner, long pizza, long burger) {
    }

    private record Table(String qr, long id, String name) {
    }

    private record Guest(String token, long orderId) {
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
        return b.header(DinerTestSupport.HEADER, token);
    }

    private MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b, Shop shop) {
        return b.header("Authorization", "Bearer " + shop.owner());
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
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), owner,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        return new Shop(owner, pizza, burger);
    }

    private Table createTable(Shop shop, String code) throws Exception {
        JsonNode t = json(withBody(post("/api/v1/tables"), shop.owner(),
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":30}"));
        return new Table(t.get("qrIdentifier").asText(), t.get("id").asLong(), "Table " + code);
    }

    private String join(Table table, String name) throws Exception {
        return DinerTestSupport.join(mockMvc, objectMapper, table.qr(), name);
    }

    private void addItem(Table table, String token, long menuItemId) throws Exception {
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/cart/items"), null,
                        "{\"menuItemId\":" + menuItemId + ",\"quantity\":1}"), token))
                .andExpect(status().isOk());
    }

    private MvcResult placeOrder(Table table, String token) throws Exception {
        return mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/orders"), null, "{}"), token))
                .andReturn();
    }

    /** A guest who has joined, ordered one pizza, and had the order confirmed by the kitchen. */
    private Guest confirmedGuest(Shop shop, Table table, String name) throws Exception {
        String token = join(table, name);
        addItem(table, token, shop.pizza());
        MvcResult placed = placeOrder(table, token);
        assertEquals(201, placed.getResponse().getStatus(), placed.getResponse().getContentAsString());
        long orderId = objectMapper.readTree(placed.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(staff(put("/api/v1/orders/" + orderId + "/status?status=CONFIRMED"), shop))
                .andExpect(status().isOk());
        return new Guest(token, orderId);
    }

    private MvcResult settle(Table table, String token, String kind) throws Exception {
        return mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/settlements"), null,
                "{\"kind\":\"" + kind + "\",\"paymentMethod\":\"CASH\"}"), token)).andReturn();
    }

    private long visitIdOf(Shop shop, Table table) throws Exception {
        for (JsonNode v : json(staff(get("/api/v1/table-sessions/active"), shop))) {
            if (v.get("tableName").asText().equals(table.name())) {
                return v.get("id").asLong();
            }
        }
        throw new AssertionError("no open visit for " + table.name());
    }

    /** Runs every task at the same instant and returns each outcome. */
    private List<MvcResult> inParallel(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> pending = new ArrayList<>();
        try {
            for (Callable<MvcResult> task : tasks) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : pending) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int statusOf(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static void assertNoServerErrors(List<MvcResult> results, String what) throws Exception {
        for (MvcResult r : results) {
            assertTrue(statusOf(r) < 500,
                    what + ": a request failed with " + statusOf(r) + ": " + r.getResponse().getContentAsString());
        }
    }

    private static long count(List<MvcResult> results, int status) {
        return results.stream().filter(r -> statusOf(r) == status).count();
    }

    // ------------------------------------------------------------------ the tests

    @Test
    void manyGuestsOrderingAtTheSameMomentNeverDeadlock() throws Exception {
        Shop shop = setupShop();

        for (int round = 0; round < 3; round++) {
            Table table = createTable(shop, "O" + round);
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String token = join(table, "Guest" + i);
                addItem(table, token, shop.pizza());
                tokens.add(token);
            }

            List<Callable<MvcResult>> orders = new ArrayList<>();
            for (String token : tokens) {
                orders.add(() -> placeOrder(table, token));
            }
            List<MvcResult> results = inParallel(orders);

            assertNoServerErrors(results, "twelve guests ordering at once");
            Set<String> numbers = new HashSet<>();
            for (MvcResult r : results) {
                assertEquals(201, statusOf(r), r.getResponse().getContentAsString());
                numbers.add(objectMapper.readTree(r.getResponse().getContentAsString()).get("orderNumber").asText());
            }
            assertEquals(12, numbers.size(), "every order gets its own number");
        }
    }

    @Test
    void rivalPayersAreNeverBothTakenForTheSameOrders_underMySqlsOwnIsolation() throws Exception {
        Shop shop = setupShop();

        // Repeated, because a race may only lose some of the time.
        for (int round = 0; round < 8; round++) {
            Table table = createTable(shop, "P" + round);
            List<Guest> guests = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                guests.add(confirmedGuest(shop, table, "Guest" + i));
            }

            // All three try to pay for the whole table at the same instant.
            List<Callable<MvcResult>> payers = new ArrayList<>();
            for (Guest g : guests) {
                payers.add(() -> settle(table, g.token(), "COMBINED"));
            }
            List<MvcResult> results = inParallel(payers);

            assertNoServerErrors(results, "three guests paying for the table at once, round " + round);
            assertEquals(1, count(results, 201), "exactly one may take the table, round " + round);
            assertEquals(2, count(results, 409), "the others are told it is already being paid, round " + round);

            for (Guest g : guests) {
                JsonNode summary = json(staff(get("/api/v1/payments/orders/" + g.orderId() + "/summary"), shop));
                assertEquals(1, summary.get("payments").size(),
                        "an order must never carry two payments from one table, round " + round);
            }
        }
    }

    @Test
    void closingATableWhileItsGuestsAreOrderingNeverFails() throws Exception {
        Shop shop = setupShop();
        Table table = createTable(shop, "C1");

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String token = join(table, "Guest" + i);
            addItem(table, token, shop.pizza());
            tokens.add(token);
        }
        long visitId = visitIdOf(shop, table);

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (String token : tokens) {
            tasks.add(() -> placeOrder(table, token));
        }
        tasks.add(() -> mockMvc.perform(staff(post("/api/v1/table-sessions/" + visitId + "/close"), shop)).andReturn());

        List<MvcResult> results = inParallel(tasks);

        // Each guest either got their order in or was told the session had ended; staff closed it. Nothing failed.
        assertNoServerErrors(results, "closing a table while guests order");
        assertEquals(200, statusOf(results.get(results.size() - 1)), "the close itself succeeds");

        JsonNode detail = json(staff(get("/api/v1/table-sessions/" + visitId), shop));
        assertEquals("CLOSED", detail.get("summary").get("status").asText());

        // Every order a guest was told was placed is really on the table's record.
        long placed = results.subList(0, tokens.size()).stream().filter(r -> statusOf(r) == 201).count();
        int recorded = 0;
        for (JsonNode guest : detail.get("guests")) {
            recorded += guest.get("orders").size();
        }
        assertEquals(placed, recorded, "no order was lost or invented");
    }

    @Test
    void paymentsOrdersAndStaffReadsAllAtOnceNeverFail() throws Exception {
        Shop shop = setupShop();
        Table table = createTable(shop, "M1");

        List<Guest> paying = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            paying.add(confirmedGuest(shop, table, "Payer" + i));
        }
        List<String> ordering = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String token = join(table, "Orderer" + i);
            addItem(table, token, shop.burger());
            ordering.add(token);
        }
        long visitId = visitIdOf(shop, table);

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (Guest g : paying) {
            tasks.add(() -> settle(table, g.token(), "SEPARATE"));
        }
        for (String token : ordering) {
            tasks.add(() -> placeOrder(table, token));
        }
        tasks.add(() -> mockMvc.perform(staff(get("/api/v1/table-sessions/active"), shop)).andReturn());
        tasks.add(() -> mockMvc.perform(staff(get("/api/v1/table-sessions/" + visitId), shop)).andReturn());
        tasks.add(() -> mockMvc.perform(staff(get("/api/v1/settlements/pending"), shop)).andReturn());

        List<MvcResult> results = inParallel(tasks);

        assertNoServerErrors(results, "payments, orders and staff reads at once");
        // Each payer pays only their own order, so none of them can collide with another.
        for (int i = 0; i < paying.size(); i++) {
            assertEquals(201, statusOf(results.get(i)), results.get(i).getResponse().getContentAsString());
        }
        for (int i = 0; i < ordering.size(); i++) {
            assertEquals(201, statusOf(results.get(paying.size() + i)));
        }
    }

    /**
     * Two staff members (or a double click, or a kitchen screen and a manager) acting on the same order at the
     * same instant. Both used to read the order unlocked, both inserted their status-history row (a shared
     * lock on the order) and both then updated it: MySQL rolled one back with error 1213 and the client got a
     * 5xx. Found by the load test under k6/tables.js (486 of them in one run). Now the second request waits for
     * the first and is answered by the status rules (refused as an unchanged status), never by a server error.
     */
    @Test
    void twoStaffChangingTheSameOrderAtOnceNeverDeadlock() throws Exception {
        Shop shop = setupShop();
        Table table = createTable(shop, "S1");

        for (int round = 0; round < 15; round++) {
            String token = join(table, "Staffed" + round);
            addItem(table, token, shop.pizza());
            MvcResult placed = placeOrder(table, token);
            assertEquals(201, statusOf(placed), placed.getResponse().getContentAsString());
            long orderId = objectMapper.readTree(placed.getResponse().getContentAsString()).get("id").asLong();

            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (int staffMember = 0; staffMember < 4; staffMember++) {
                tasks.add(() -> mockMvc.perform(staff(put("/api/v1/orders/" + orderId + "/status?status=CONFIRMED"), shop))
                        .andReturn());
            }
            List<MvcResult> results = inParallel(tasks);

            assertNoServerErrors(results, "four staff confirming one order at once (round " + round + ")");
            // A repeated confirmation is accepted as a no-op, so every answer may be 200; what must hold is that
            // the change was recorded once.
            long confirmations = 0;
            for (JsonNode entry : json(staff(get("/api/v1/orders/" + orderId + "/history"), shop))) {
                if ("CONFIRMED".equals(entry.get("to").asText())) {
                    confirmations++;
                }
            }
            assertEquals(1, confirmations, "the order is recorded as confirmed exactly once");
        }
    }
}
