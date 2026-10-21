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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3: "my bill" and "table bill". Both are read-only and act as the calling guest.
 *
 * <p>Table 5 in the brief: Rahul 800, Amit 250, Priya 650, here with real orders and real payments.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class BillIT {

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

        String qr = createTable(owner, "T5").get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), owner,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());

        return new Shop(owner, restaurantId, qr, pizza, burger);
    }

    private JsonNode createTable(String owner, String code) throws Exception {
        return json(withBody(post("/api/v1/tables"), owner,
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"));
    }

    private void addItem(Shop shop, String token, long menuItemId, int quantity) throws Exception {
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items"), null,
                        "{\"menuItemId\":" + menuItemId + ",\"quantity\":" + quantity + "}"), token))
                .andExpect(status().isOk());
    }

    /** Joins, adds the items, and places the order. The order is PENDING until staff confirm it. */
    private Guest order(Shop shop, String name, long[] itemIds, int[] quantities) throws Exception {
        String token = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), name);
        for (int i = 0; i < itemIds.length; i++) {
            addItem(shop, token, itemIds[i], quantities[i]);
        }
        String body = mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + shop.qr() + "/orders"), null, "{}"), token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode order = objectMapper.readTree(body);
        return new Guest(token, order.get("id").asLong(), order.get("publicToken").asText());
    }

    private void setStatus(Shop shop, Guest guest, String status) throws Exception {
        mockMvc.perform(put("/api/v1/orders/" + guest.orderId() + "/status?status=" + status)
                        .header("Authorization", "Bearer " + shop.owner()))
                .andExpect(status().isOk());
    }

    private void staffCollects(Shop shop, Guest guest, String amount) throws Exception {
        mockMvc.perform(withBody(post("/api/v1/payments/orders/" + guest.orderId() + "/collect"), shop.owner(),
                        "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":" + amount + "}]}"))
                .andExpect(status().isCreated());
    }

    private JsonNode myBill(Shop shop, String token) throws Exception {
        String body = mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/bill"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode tableBill(Shop shop, String token) throws Exception {
        String body = mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/bill/table"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())),
                "expected " + expected + " but was " + actual.asText());
    }

    private static JsonNode guestLine(JsonNode tableBill, String name) {
        for (JsonNode g : tableBill.get("guests")) {
            if (g.get("displayName").asText().equals(name)) {
                return g;
            }
        }
        throw new AssertionError("no line for " + name + " in " + tableBill);
    }

    /** Table 5: Rahul 800 (2 pizzas), Amit 250 (a burger), Priya 650 (a pizza and a burger). */
    private Guest[] table5(Shop shop) throws Exception {
        return new Guest[]{
                order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{2}),
                order(shop, "Amit", new long[]{shop.burger()}, new int[]{1}),
                order(shop, "Priya", new long[]{shop.pizza(), shop.burger()}, new int[]{1, 1})};
    }

    // ------------------------------------------------------------------ tests

    @Test
    void anOrderIsOwedButNotPayableUntilTheKitchenConfirmsIt() throws Exception {
        Shop shop = setupShop();
        Guest rahul = order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{2});

        JsonNode before = myBill(shop, rahul.token());
        assertMoney("0", before.get("due"));
        assertMoney("800", before.get("awaitingConfirmation"));
        assertEquals("PENDING", before.get("orders").get(0).get("status").asText());
        assertFalse(before.get("orders").get(0).get("payable").asBoolean());

        JsonNode tableBefore = tableBill(shop, rahul.token());
        assertMoney("0", tableBefore.get("due"));
        assertMoney("800", tableBefore.get("awaitingConfirmation"));

        setStatus(shop, rahul, "CONFIRMED");

        JsonNode after = myBill(shop, rahul.token());
        assertMoney("800", after.get("due"));
        assertMoney("0", after.get("awaitingConfirmation"));
        assertTrue(after.get("orders").get(0).get("payable").asBoolean());
        assertMoney("800", tableBill(shop, rahul.token()).get("due"));
    }

    @Test
    void myBillListsOnlyTheCallersOwnOrders() throws Exception {
        Shop shop = setupShop();
        Guest[] guests = table5(shop);
        for (Guest g : guests) {
            setStatus(shop, g, "CONFIRMED");
        }

        JsonNode priya = myBill(shop, guests[2].token());

        assertEquals("Priya", priya.get("displayName").asText());
        assertEquals(1, priya.get("orders").size(), "only Priya's own order");
        assertMoney("650", priya.get("due"));

        JsonNode items = priya.get("orders").get(0).get("items");
        Set<String> names = new HashSet<>();
        items.forEach(i -> names.add(i.get("name").asText()));
        assertEquals(Set.of("Pizza", "Burger"), names);

        JsonNode amit = myBill(shop, guests[1].token());
        assertMoney("250", amit.get("due"));
        assertEquals("Burger", amit.get("orders").get(0).get("items").get(0).get("name").asText());
    }

    @Test
    void theTableBillShowsEachGuestsAmount_andNothingPrivate() throws Exception {
        Shop shop = setupShop();
        Guest[] guests = table5(shop);
        for (Guest g : guests) {
            setStatus(shop, g, "CONFIRMED");
        }

        JsonNode bill = tableBill(shop, guests[1].token());   // as Amit

        assertEquals("Table T5", bill.get("tableName").asText());
        assertEquals(3, bill.get("guests").size());
        assertMoney("1700", bill.get("due"));

        assertMoney("800", guestLine(bill, "Rahul").get("due"));
        assertMoney("250", guestLine(bill, "Amit").get("due"));
        assertMoney("650", guestLine(bill, "Priya").get("due"));

        // Only the caller's own line says "you".
        assertFalse(guestLine(bill, "Rahul").get("you").asBoolean());
        assertTrue(guestLine(bill, "Amit").get("you").asBoolean());
        assertFalse(guestLine(bill, "Priya").get("you").asBoolean());

        // A guest's line carries a name and amounts, and nothing else: no items, no order numbers,
        // no tokens, no payment details.
        Set<String> fields = new HashSet<>();
        guestLine(bill, "Rahul").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("displayName", "you", "due", "inProgress", "awaitingConfirmation"), fields);
        String raw = bill.toString();
        assertFalse(raw.contains("Pizza") || raw.contains("Burger"), "item names must not appear");
        assertFalse(raw.contains("ORD-"), "order numbers must not appear");
        assertFalse(raw.contains(guests[0].orderToken()), "another guest's order token must not appear");
    }

    @Test
    void paymentsMoveMoneyOutOfDue_andACancelledOrderIsNotBilled() throws Exception {
        Shop shop = setupShop();
        Guest[] guests = table5(shop);
        Guest rahul = guests[0];
        Guest amit = guests[1];
        Guest priya = guests[2];
        for (Guest g : guests) {
            setStatus(shop, g, "CONFIRMED");
        }

        // Rahul pays in full at the counter: his 800 is paid, and no longer due.
        staffCollects(shop, rahul, "800");

        JsonNode rahulsBill = myBill(shop, rahul.token());
        assertMoney("0", rahulsBill.get("due"));
        assertMoney("800", rahulsBill.get("paid"));
        assertFalse(rahulsBill.get("orders").get(0).get("payable").asBoolean());

        JsonNode afterRahul = tableBill(shop, amit.token());
        assertMoney("900", afterRahul.get("due"));           // Amit 250 + Priya 650
        assertMoney("800", afterRahul.get("paid"));
        assertMoney("0", guestLine(afterRahul, "Rahul").get("due"));

        // Amit chooses cash at the table: promised, not yet confirmed, so in progress - not due, not paid.
        mockMvc.perform(withBody(post("/api/public/orders/by-token/" + amit.orderToken() + "/payments"), null,
                        "{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated());

        JsonNode amitsBill = myBill(shop, amit.token());
        assertMoney("0", amitsBill.get("due"));
        assertMoney("250", amitsBill.get("inProgress"));
        assertMoney("0", amitsBill.get("paid"));

        JsonNode afterAmit = tableBill(shop, priya.token());
        assertMoney("650", afterAmit.get("due"));
        assertMoney("250", afterAmit.get("inProgress"));
        assertMoney("800", afterAmit.get("paid"));
        assertMoney("250", guestLine(afterAmit, "Amit").get("inProgress"));

        // Priya's order is cancelled: nobody owes it any more, and she drops off the table bill.
        setStatus(shop, priya, "CANCELLED");

        JsonNode priyasBill = myBill(shop, priya.token());
        assertEquals("CANCELLED", priyasBill.get("orders").get(0).get("status").asText());
        assertMoney("0", priyasBill.get("due"));

        JsonNode afterCancel = tableBill(shop, rahul.token());
        assertMoney("0", afterCancel.get("due"));
        assertEquals(2, afterCancel.get("guests").size());
    }

    @Test
    void aPartPaymentLeavesTheRestDue_andAgreesWithTheStaffSummary() throws Exception {
        Shop shop = setupShop();
        Guest rahul = order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{2});
        setStatus(shop, rahul, "CONFIRMED");

        staffCollects(shop, rahul, "300");

        JsonNode bill = myBill(shop, rahul.token());
        assertMoney("500", bill.get("due"));
        assertMoney("300", bill.get("paid"));
        assertTrue(bill.get("orders").get(0).get("payable").asBoolean(), "still part-owing, so still payable");

        // The bill is built from the same balance staff see; the two cannot disagree.
        JsonNode summary = json(get("/api/v1/payments/orders/" + rahul.orderId() + "/summary")
                .header("Authorization", "Bearer " + shop.owner()));
        assertEquals(0, new BigDecimal(summary.get("outstanding").asText())
                .compareTo(new BigDecimal(bill.get("due").asText())));
    }

    @Test
    void aGuestWhoHasOrderedNothingHasAnEmptyBill() throws Exception {
        Shop shop = setupShop();
        String token = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Rahul");

        JsonNode mine = myBill(shop, token);
        assertEquals(0, mine.get("orders").size());
        assertMoney("0", mine.get("due"));

        JsonNode table = tableBill(shop, token);
        assertEquals(0, table.get("guests").size());
        assertMoney("0", table.get("due"));
    }

    @Test
    void anotherTablesBillIsSeparate() throws Exception {
        Shop shop = setupShop();
        Guest rahul = order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{2});
        setStatus(shop, rahul, "CONFIRMED");

        String otherQr = createTable(shop.owner(), "T6").get("qrIdentifier").asText();
        String otherGuest = DinerTestSupport.join(mockMvc, objectMapper, otherQr, "Amit");

        String body = mockMvc.perform(asGuest(get("/api/public/tables/" + otherQr + "/bill/table"), otherGuest))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode other = objectMapper.readTree(body);

        assertEquals(0, other.get("guests").size(), "Table 5's guests are not on Table 6's bill");
        assertMoney("0", other.get("due"));
    }

    @Test
    void billsNeedAValidGuestToken() throws Exception {
        Shop shop = setupShop();
        Guest rahul = order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{1});
        setStatus(shop, rahul, "CONFIRMED");

        String otherQr = createTable(shop.owner(), "T6").get("qrIdentifier").asText();
        String otherGuest = DinerTestSupport.join(mockMvc, objectMapper, otherQr, "Amit");

        for (String token : new String[]{null, "", "not-a-real-token", otherGuest}) {
            mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/bill"), token))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asGuest(get("/api/public/tables/" + shop.qr() + "/bill/table"), token))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void readingABillChangesNothing() throws Exception {
        Shop shop = setupShop();
        Guest rahul = order(shop, "Rahul", new long[]{shop.pizza()}, new int[]{1});
        setStatus(shop, rahul, "CONFIRMED");

        JsonNode first = myBill(shop, rahul.token());
        for (int i = 0; i < 3; i++) {
            myBill(shop, rahul.token());
            tableBill(shop, rahul.token());
        }
        JsonNode last = myBill(shop, rahul.token());

        assertEquals(first.toString(), last.toString());
        assertMoney("400", last.get("due"));
    }
}
