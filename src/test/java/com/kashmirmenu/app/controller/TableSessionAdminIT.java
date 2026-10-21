package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.enums.CartStatus;
import com.kashmirmenu.app.repository.CartRepository;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.service.DinerIdentity;
import com.kashmirmenu.app.service.TableSessionExpiryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6: the owner's live view of the floor, and closing, reopening and idle expiry.
 *
 * <p>Table 5 in the brief: Rahul 800, Amit 250, Priya 650, with real orders and real payments.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class TableSessionAdminIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DinerSessionRepository dinerSessions;

    @Autowired
    private CartRepository carts;

    @Autowired
    private TableSessionExpiryService expiry;

    private record Shop(String owner, long restaurantId, long pizza, long burger) {
    }

    private record Table(String qr, long id) {
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

    private MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b, String owner) {
        return b.header("Authorization", "Bearer " + owner);
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
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), owner,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        return new Shop(owner, restaurantId, pizza, burger);
    }

    private Table createTable(Shop shop, String code) throws Exception {
        JsonNode t = json(withBody(post("/api/v1/tables"), shop.owner(),
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"));
        return new Table(t.get("qrIdentifier").asText(), t.get("id").asLong());
    }

    private void addItem(Table table, String token, long menuItemId, int quantity) throws Exception {
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/cart/items"), null,
                        "{\"menuItemId\":" + menuItemId + ",\"quantity\":" + quantity + "}"), token))
                .andExpect(status().isOk());
    }

    private Guest order(Shop shop, Table table, String name, long[] itemIds, int[] quantities) throws Exception {
        String token = DinerTestSupport.join(mockMvc, objectMapper, table.qr(), name);
        for (int i = 0; i < itemIds.length; i++) {
            addItem(table, token, itemIds[i], quantities[i]);
        }
        JsonNode order = objectMapper.readTree(mockMvc.perform(asGuest(
                        withBody(post("/api/public/tables/" + table.qr() + "/orders"), null, "{}"), token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(put("/api/v1/orders/" + order.get("id").asLong() + "/status?status=CONFIRMED")
                        .header("Authorization", "Bearer " + shop.owner()))
                .andExpect(status().isOk());
        return new Guest(token, order.get("id").asLong());
    }

    /** Table 5, orders confirmed: Rahul 800, Amit 250, Priya 650. */
    private Guest[] table5(Shop shop, Table table) throws Exception {
        return new Guest[]{
                order(shop, table, "Rahul", new long[]{shop.pizza()}, new int[]{2}),
                order(shop, table, "Amit", new long[]{shop.burger()}, new int[]{1}),
                order(shop, table, "Priya", new long[]{shop.pizza(), shop.burger()}, new int[]{1, 1})};
    }

    /** The counter takes cash for part or all of an order. */
    private void counterCollects(Shop shop, Guest guest, String amount) throws Exception {
        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + guest.orderId() + "/collect"), shop.owner(),
                        "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":" + amount + "}]}"))
                .andExpect(status().isCreated());
    }

    private JsonNode settle(Table table, Guest guest, String kind, int expected) throws Exception {
        String body = mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/settlements"), null,
                        "{\"kind\":\"" + kind + "\",\"paymentMethod\":\"CASH\"}"), guest.token()))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode staffConfirmsSettlement(Shop shop, String code) throws Exception {
        JsonNode pending = json(staff(get("/api/v1/settlements/pending"), shop.owner()));
        for (JsonNode s : pending) {
            if (s.get("code").asText().equals(code)) {
                return json(staff(withBody(put("/api/v1/settlements/" + s.get("id").asLong() + "/status"), null,
                        "{\"paymentStatus\":\"SUCCESS\"}"), shop.owner()));
            }
        }
        throw new AssertionError("settlement " + code + " is not awaiting staff");
    }

    private JsonNode active(Shop shop) throws Exception {
        return json(staff(get("/api/v1/table-sessions/active"), shop.owner()));
    }

    private static JsonNode byTable(JsonNode list, String tableName) {
        for (JsonNode s : list) {
            if (s.get("tableName").asText().equals(tableName)) {
                return s;
            }
        }
        throw new AssertionError("no table " + tableName + " in " + list);
    }

    private JsonNode detail(Shop shop, long visitId) throws Exception {
        return json(staff(get("/api/v1/table-sessions/" + visitId), shop.owner()));
    }

    private JsonNode visitOf(Shop shop, String tableName) throws Exception {
        return byTable(active(shop), tableName);
    }

    private JsonNode close(Shop shop, long visitId, int expected) throws Exception {
        String body = mockMvc.perform(staff(post("/api/v1/table-sessions/" + visitId + "/close"), shop.owner()))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private JsonNode reopen(Shop shop, long visitId, int expected) throws Exception {
        String body = mockMvc.perform(staff(post("/api/v1/table-sessions/" + visitId + "/reopen"), shop.owner()))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private void ageGuest(Guest guest, int hours) {
        DinerSession d = dinerSessions.findByTokenHash(DinerIdentity.hash(guest.token())).orElseThrow();
        d.setLastSeenAt(LocalDateTime.now().minusHours(hours));
        dinerSessions.save(d);
    }

    private void ageToken(String token, int hours) {
        DinerSession d = dinerSessions.findByTokenHash(DinerIdentity.hash(token)).orElseThrow();
        d.setLastSeenAt(LocalDateTime.now().minusHours(hours));
        dinerSessions.save(d);
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())),
                "expected " + expected + " but was " + actual.asText());
    }

    private static JsonNode guestNamed(JsonNode detail, String name) {
        for (JsonNode g : detail.get("guests")) {
            if (g.get("displayName").asText().equals(name)) {
                return g;
            }
        }
        throw new AssertionError("no guest " + name);
    }

    // ------------------------------------------------------------------ the live view

    @Test
    void theLiveViewListsOpenTablesWithWhatIsOwed() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Table t6 = createTable(shop, "T6");
        Guest[] g = table5(shop, t5);
        DinerTestSupport.join(mockMvc, objectMapper, t6.qr(), "Zoya");   // sat down, has not ordered

        JsonNode list = active(shop);
        assertEquals(2, list.size());

        JsonNode five = byTable(list, "Table T5");
        assertEquals("OPEN", five.get("status").asText());
        assertEquals(3, five.get("guestCount").asInt());
        assertMoney("1700", five.get("due"));
        assertMoney("0", five.get("paid"));
        assertEquals("UNPAID", five.get("payState").asText());
        assertFalse(five.get("idle").asBoolean());
        assertFalse(five.get("needsStaff").asBoolean());

        JsonNode six = byTable(list, "Table T6");
        assertEquals(1, six.get("guestCount").asInt());
        assertEquals("NO_ORDERS", six.get("payState").asText());

        // Rahul pays at the counter: the table is part paid, owing what Amit and Priya owe.
        counterCollects(shop, g[0], "800");
        JsonNode partly = byTable(active(shop), "Table T5");
        assertEquals("PARTIALLY_PAID", partly.get("payState").asText());
        assertMoney("900", partly.get("due"));
        assertMoney("800", partly.get("paid"));

        counterCollects(shop, g[1], "250");
        counterCollects(shop, g[2], "650");
        JsonNode done = byTable(active(shop), "Table T5");
        assertEquals("PAID", done.get("payState").asText());
        assertMoney("0", done.get("due"));
        assertMoney("1700", done.get("paid"));
    }

    @Test
    void theDetailShowsEachGuestsOrdersAndWhatTheyOwe() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Guest[] g = table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        counterCollects(shop, g[0], "800");        // Rahul: paid in full
        counterCollects(shop, g[1], "100");        // Amit: 100 of 250

        JsonNode d = detail(shop, visitId);

        assertEquals(3, d.get("guests").size());
        assertEquals("Rahul", d.get("guests").get(0).get("displayName").asText(), "guests are in the order they joined");

        JsonNode rahul = guestNamed(d, "Rahul");
        assertEquals("PAID", rahul.get("payState").asText());
        assertEquals(1, rahul.get("orders").size());
        assertEquals("PAID", rahul.get("orders").get(0).get("payState").asText());
        assertEquals("Pizza", rahul.get("orders").get(0).get("items").get(0).get("name").asText());
        assertEquals(2, rahul.get("orders").get(0).get("items").get(0).get("quantity").asInt());

        JsonNode amit = guestNamed(d, "Amit");
        assertEquals("PARTIALLY_PAID", amit.get("payState").asText());
        assertMoney("150", amit.get("due"));
        assertMoney("100", amit.get("paid"));
        assertEquals("PARTIALLY_PAID", amit.get("orders").get(0).get("payState").asText());

        JsonNode priya = guestNamed(d, "Priya");
        assertEquals("UNPAID", priya.get("payState").asText());
        assertMoney("650", priya.get("due"));
        assertEquals(2, priya.get("orders").get(0).get("items").size());

        // The table's figures are its guests' added up, each payment counted once:
        // Rahul's 800 and Amit's 100 are paid; Amit's other 150 and Priya's 650 are still due.
        JsonNode summary = d.get("summary");
        assertMoney("900", summary.get("paid"));
        assertMoney("800", summary.get("due"));
        assertEquals("PARTIALLY_PAID", summary.get("payState").asText());

        BigDecimal guestsDue = BigDecimal.ZERO;
        BigDecimal guestsPaid = BigDecimal.ZERO;
        for (JsonNode guest : d.get("guests")) {
            guestsDue = guestsDue.add(new BigDecimal(guest.get("due").asText()));
            guestsPaid = guestsPaid.add(new BigDecimal(guest.get("paid").asText()));
        }
        assertEquals(0, new BigDecimal(summary.get("due").asText()).compareTo(guestsDue));
        assertEquals(0, new BigDecimal(summary.get("paid").asText()).compareTo(guestsPaid));
    }

    @Test
    void paymentHistoryShowsWhetherEachPaymentWasSeparateCombinedOrAtTheCounter() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Guest[] g = table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        counterCollects(shop, g[0], "800");                                  // at the counter
        JsonNode own = settle(t5, g[1], "SEPARATE", 201);                    // Amit pays his own
        staffConfirmsSettlement(shop, own.get("code").asText());
        JsonNode together = settle(t5, g[2], "COMBINED", 201);               // Priya pays what is left
        staffConfirmsSettlement(shop, together.get("code").asText());

        JsonNode d = detail(shop, visitId);

        assertEquals(3, d.get("payments").size());
        Set<String> kinds = new HashSet<>();
        for (JsonNode p : d.get("payments")) {
            assertEquals("SUCCESS", p.get("status").asText());
            kinds.add(p.get("settlementKind").isNull() ? "COUNTER" : p.get("settlementKind").asText());
        }
        assertEquals(Set.of("COUNTER", "SEPARATE", "COMBINED"), kinds);

        assertEquals(2, d.get("settlements").size(), "the counter payment is not a settlement");
        assertMoney("1700", d.get("summary").get("paid"));
        assertEquals("PAID", d.get("summary").get("payState").asText());
    }

    @Test
    void aCombinedPaymentIsCountedOnceNotOncePerOrder() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Guest[] g = table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        JsonNode together = settle(t5, g[2], "COMBINED", 201);       // Priya pays the whole table: 1700

        // While it waits for staff, the money is in progress, not paid.
        JsonNode waiting = detail(shop, visitId);
        assertMoney("0", waiting.get("summary").get("paid"));
        assertMoney("1700", waiting.get("summary").get("inProgress"));
        assertMoney("0", waiting.get("summary").get("due"));
        assertEquals("UNPAID", waiting.get("summary").get("payState").asText());

        staffConfirmsSettlement(shop, together.get("code").asText());

        JsonNode d = detail(shop, visitId);

        // One settlement, three payment records (one per order), 1,700 in total: never 5,100.
        assertEquals(1, d.get("settlements").size());
        assertEquals("COMBINED", d.get("settlements").get(0).get("kind").asText());
        assertEquals(3, d.get("payments").size());
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode p : d.get("payments")) {
            assertEquals("COMBINED", p.get("settlementKind").asText());
            sum = sum.add(new BigDecimal(p.get("amount").asText()));
        }
        assertEquals(0, new BigDecimal("1700").compareTo(sum));
        assertMoney("1700", d.get("summary").get("paid"));
        assertMoney("0", d.get("summary").get("due"));

        // And each order is still tied to the guest who placed it.
        assertMoney("800", guestNamed(d, "Rahul").get("paid"));
        assertMoney("250", guestNamed(d, "Amit").get("paid"));
        assertMoney("650", guestNamed(d, "Priya").get("paid"));
    }

    // ------------------------------------------------------------------ closing and reopening

    @Test
    void closingATableIsNeverBlockedByMoneyStillOwing() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Guest[] g = table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        addItem(t5, g[0].token(), shop.burger(), 1);                         // Rahul's cart, never ordered
        JsonNode started = settle(t5, g[2], "SEPARATE", 201);                // Priya's payment waits for staff

        JsonNode closed = close(shop, visitId, 200);

        assertEquals("CLOSED", closed.get("summary").get("status").asText());
        assertEquals("STAFF", closed.get("summary").get("closeReason").asText());
        assertFalse(closed.get("summary").get("closedAt").isNull());
        assertFalse(closed.get("closedBy").isNull());

        // Nothing was deleted or settled: the orders and what is owed are as they were.
        assertEquals(3, closed.get("guests").size());
        assertMoney("1050", closed.get("summary").get("due"));              // Rahul 800 + Amit 250
        assertMoney("650", closed.get("summary").get("inProgress"));        // Priya's, waiting for staff

        // The table is no longer open, and appears among those recently closed.
        assertEquals(0, active(shop).size());
        assertEquals(visitId, json(staff(get("/api/v1/table-sessions/recent"), shop.owner())).get(0).get("id").asLong());

        // The guests lose access.
        for (Guest guest : g) {
            mockMvc.perform(asGuest(get("/api/public/tables/" + t5.qr() + "/me"), guest.token()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asGuest(get("/api/public/tables/" + t5.qr() + "/bill"), guest.token()))
                    .andExpect(status().isNotFound());
        }
        // Rahul's unsubmitted cart was withdrawn.
        assertTrue(carts.findAll().stream().noneMatch(c ->
                c.getStatus() == CartStatus.ACTIVE && c.getTableSession() != null
                        && c.getTableSession().getId().equals(visitId)));

        // A payment already waiting for staff can still be confirmed after the table is closed.
        JsonNode confirmed = staffConfirmsSettlement(shop, started.get("code").asText());
        assertEquals("SUCCESS", confirmed.get("status").asText());
        assertMoney("650", detail(shop, visitId).get("summary").get("paid"));
    }

    @Test
    void closingTwiceChangesNothing() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        JsonNode first = close(shop, visitId, 200);
        JsonNode second = close(shop, visitId, 200);

        // The same instant: the first response carries the in-memory time (Windows: 100ns), the second the stored
        // one, which the database rounds to whole seconds (the column has no fractional part), so allow one second.
        java.time.OffsetDateTime a = java.time.OffsetDateTime.parse(first.get("summary").get("closedAt").asText());
        java.time.OffsetDateTime b = java.time.OffsetDateTime.parse(second.get("summary").get("closedAt").asText());
        org.junit.jupiter.api.Assertions.assertTrue(java.time.Duration.between(a, b).abs().toNanos() <= 1_000_000_000L,
                "closing twice must not move the closing time: " + a + " vs " + b);
    }

    @Test
    void aNewScanAfterClosingStartsAFreshVisit() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        table5(shop, t5);
        JsonNode before = visitOf(shop, "Table T5");
        close(shop, before.get("id").asLong(), 200);

        // The next guests to sit down are not mixed with the ones who just left.
        String newcomer = DinerTestSupport.join(mockMvc, objectMapper, t5.qr(), "Zoya");

        JsonNode now = visitOf(shop, "Table T5");
        assertNotEquals(before.get("id").asLong(), now.get("id").asLong());
        assertNotEquals(before.get("visitCode").asText(), now.get("visitCode").asText());
        assertEquals(1, now.get("guestCount").asInt());
        assertEquals("NO_ORDERS", now.get("payState").asText());

        JsonNode tableBill = objectMapper.readTree(mockMvc.perform(asGuest(
                        get("/api/public/tables/" + t5.qr() + "/bill/table"), newcomer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, tableBill.get("guests").size(), "the last visit's guests are not on this bill");
    }

    @Test
    void reopeningGivesTheGuestsTheirSessionsBack() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Guest[] g = table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        close(shop, visitId, 200);
        mockMvc.perform(asGuest(get("/api/public/tables/" + t5.qr() + "/me"), g[0].token()))
                .andExpect(status().isNotFound());

        JsonNode reopened = reopen(shop, visitId, 200);
        assertEquals("OPEN", reopened.get("summary").get("status").asText());
        assertTrue(reopened.get("summary").get("closedAt").isNull());
        assertTrue(reopened.get("summary").get("closeReason").isNull());

        // Rahul is back, with his bill as he left it.
        mockMvc.perform(asGuest(get("/api/public/tables/" + t5.qr() + "/me"), g[0].token()))
                .andExpect(status().isOk());
        JsonNode bill = objectMapper.readTree(mockMvc.perform(asGuest(get("/api/public/tables/" + t5.qr() + "/bill"), g[0].token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertMoney("800", bill.get("due"));

        // Reopening an open table is harmless.
        reopen(shop, visitId, 200);
    }

    @Test
    void aTableThatHasStartedANewVisitCannotHaveAnEarlierOneReopened() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        table5(shop, t5);
        long oldVisit = visitOf(shop, "Table T5").get("id").asLong();

        close(shop, oldVisit, 200);
        DinerTestSupport.join(mockMvc, objectMapper, t5.qr(), "Zoya");     // a new visit begins

        JsonNode refused = reopen(shop, oldVisit, 409);
        assertTrue(refused.get("message").asText().contains("already has an open session"));

        // Still exactly one open visit, and it is the new one.
        JsonNode list = active(shop);
        assertEquals(1, list.size());
        assertNotEquals(oldVisit, list.get(0).get("id").asLong());
    }

    // ------------------------------------------------------------------ idle tables

    @Test
    void idleTablesThatOweNothingCloseThemselves_andTheOnesStillOwingNeverDo() throws Exception {
        Shop shop = setupShop();
        Table paidUp = createTable(shop, "T1");
        Table owing = createTable(shop, "T2");
        Table emptyCart = createTable(shop, "T3");
        Table waitingForStaff = createTable(shop, "T4");
        Table busy = createTable(shop, "T5");

        // T1: all paid at the counter, then everyone goes quiet.
        Guest paid = order(shop, paidUp, "Rahul", new long[]{shop.pizza()}, new int[]{1});
        counterCollects(shop, paid, "400");

        // T2: an order still unpaid, quiet.
        Guest unpaid = order(shop, owing, "Amit", new long[]{shop.burger()}, new int[]{1});

        // T3: someone sat down and put a dish in their cart, never ordered, quiet.
        String browsing = DinerTestSupport.join(mockMvc, objectMapper, emptyCart.qr(), "Priya");
        addItem(emptyCart, browsing, shop.pizza(), 1);

        // T4: a payment started and not yet confirmed by staff, quiet.
        Guest paying = order(shop, waitingForStaff, "Zoya", new long[]{shop.pizza()}, new int[]{1});
        settle(waitingForStaff, paying, "SEPARATE", 201);

        // T5: unpaid, and someone is still active.
        order(shop, busy, "Imran", new long[]{shop.burger()}, new int[]{1});

        ageGuest(paid, 4);
        ageGuest(unpaid, 4);
        ageToken(browsing, 4);
        ageGuest(paying, 4);

        // Counted across the whole database, which may hold other idle tables (from an earlier run on a
        // real MySQL, say), so the count is a floor; what matters is which of THIS restaurant's tables closed.
        assertTrue(expiry.closeIdleVisits() >= 2, "at least the two that are idle and owe nothing");

        JsonNode open = active(shop);
        assertEquals(3, open.size());

        // Idle with money owing: left open, and flagged for staff.
        JsonNode t2 = byTable(open, "Table T2");
        assertTrue(t2.get("idle").asBoolean());
        assertTrue(t2.get("needsStaff").asBoolean());
        // A payment waiting for staff is money owing too.
        assertTrue(byTable(open, "Table T4").get("needsStaff").asBoolean());
        // Someone is still active: not idle at all.
        assertFalse(byTable(open, "Table T5").get("idle").asBoolean());
        assertFalse(byTable(open, "Table T5").get("needsStaff").asBoolean());

        // The two that closed say why, and name nobody, because nobody did it.
        JsonNode recent = json(staff(get("/api/v1/table-sessions/recent"), shop.owner()));
        assertEquals(2, recent.size());
        for (JsonNode r : recent) {
            assertEquals("IDLE_EXPIRED", r.get("closeReason").asText());
            assertTrue(detail(shop, r.get("id").asLong()).get("closedBy").isNull());
        }

        // The browsing guest's cart was withdrawn along with the table.
        assertTrue(carts.findAll().stream().noneMatch(c ->
                c.getStatus() == CartStatus.ACTIVE && c.getDinerSession() != null
                        && c.getDinerSession().getId().equals(
                                dinerSessions.findByTokenHash(DinerIdentity.hash(browsing)).orElseThrow().getId())));
    }

    @Test
    void aTableIsOnlyIdleOnceEveryGuestHasGoneQuiet() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        Guest[] g = table5(shop, t5);

        ageGuest(g[0], 5);
        ageGuest(g[1], 5);                         // Priya is still active

        assertFalse(visitOf(shop, "Table T5").get("idle").asBoolean());
        expiry.closeIdleVisits();
        assertEquals("OPEN", visitOf(shop, "Table T5").get("status").asText(), "someone is still active");

        ageGuest(g[2], 5);                         // now everyone has
        assertTrue(visitOf(shop, "Table T5").get("idle").asBoolean());
        assertTrue(visitOf(shop, "Table T5").get("needsStaff").asBoolean(), "and 1,700 is still owing");
        expiry.closeIdleVisits();
        assertEquals("OPEN", visitOf(shop, "Table T5").get("status").asText(), "owing, so never closed automatically");
    }

    // ------------------------------------------------------------------ deleting a table

    @Test
    void aTableWithHistoryCannotBeDeleted_butANeverUsedOneCan() throws Exception {
        Shop shop = setupShop();
        Table used = createTable(shop, "T1");
        Table fresh = createTable(shop, "T2");
        DinerTestSupport.join(mockMvc, objectMapper, used.qr(), "Rahul");   // scanned once: a visit now points at it

        // The database refuses, and the answer says why and what to do instead, not "unexpected error".
        String refusal = mockMvc.perform(staff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/tables/" + used.id()), shop.owner()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertTrue(objectMapper.readTree(refusal).get("message").asText().contains("Deactivate it instead"));

        // A table nobody has ever scanned is simply removed.
        mockMvc.perform(staff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/tables/" + fresh.id()), shop.owner()))
                .andExpect(status().isNoContent());

        // The refused one is untouched, and still open.
        assertEquals("Table T1", visitOf(shop, "Table T1").get("tableName").asText());
    }

    // ------------------------------------------------------------------ access

    @Test
    void theseEndpointsNeedStaffAndStayWithinTheirRestaurant() throws Exception {
        Shop shop = setupShop();
        Table t5 = createTable(shop, "T5");
        table5(shop, t5);
        long visitId = visitOf(shop, "Table T5").get("id").asLong();

        // Not signed in.
        for (String url : new String[]{"/api/v1/table-sessions/active", "/api/v1/table-sessions/recent",
                "/api/v1/table-sessions/" + visitId}) {
            mockMvc.perform(get(url)).andExpect(status().is4xxClientError());
        }
        mockMvc.perform(post("/api/v1/table-sessions/" + visitId + "/close")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/table-sessions/" + visitId + "/reopen")).andExpect(status().is4xxClientError());

        // Another restaurant's owner sees nothing of it and cannot touch it.
        Shop other = setupShop();
        assertEquals(0, active(other).size());
        mockMvc.perform(staff(get("/api/v1/table-sessions/" + visitId), other.owner())).andExpect(status().isNotFound());
        mockMvc.perform(staff(post("/api/v1/table-sessions/" + visitId + "/close"), other.owner()))
                .andExpect(status().isNotFound());
        mockMvc.perform(staff(post("/api/v1/table-sessions/" + visitId + "/reopen"), other.owner()))
                .andExpect(status().isNotFound());

        // And the table is still open for its own restaurant.
        assertEquals(1, active(shop).size());
    }


}
