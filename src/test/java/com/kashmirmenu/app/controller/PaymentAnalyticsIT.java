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
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks of the payment rules and of the arithmetic behind the Payments & Revenue page.
 * Every order costs 2 x 600.00 = 1,200.00, so the figures below can be verified by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class PaymentAnalyticsIT {

    private static final BigDecimal ORDER_TOTAL = new BigDecimal("1200");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String token, long restaurantId, String qr, long menuItemId, String diner) {
    }

    // ------------------------------------------------------------------ helpers

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

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private static BigDecimal dec(JsonNode n) {
        return n.decimalValue();
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual);
    }

    private Shop setupShop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String registration = """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, registration))
                .get("accessToken").asText();
        long restaurantId = json(authed(get("/api/v1/me"), token)).get("restaurantId").asLong();

        long categoryId = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long menuItemId = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh\",\"price\":600.00,\"categoryId\":" + categoryId + "}"))
                .get("data").get("id").asLong();
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}")).get("qrIdentifier").asText();

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                        "{\"cashEnabled\":true,\"upiEnabled\":true,\"upiId\":\"shop@upi\",\"cardEnabled\":true,\"walletEnabled\":true}"))
                .andExpect(status().isCreated());

        return new Shop(token, restaurantId, qr, menuItemId,
                DinerTestSupport.join(mockMvc, objectMapper, qr, "Guest"));
    }

    /** A diner places an order for two dishes (total 1,200.00). */
    private JsonNode placeOrder(Shop shop) throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items").header(DinerTestSupport.HEADER, shop.diner()), null,
                        "{\"menuItemId\":" + shop.menuItemId() + ",\"quantity\":2}"))
                .andExpect(status().isOk());
        JsonNode order = json(withBody(post("/api/public/tables/" + shop.qr() + "/orders").header(DinerTestSupport.HEADER, shop.diner()), null, "{}"));
        assertMoney("1200", order.get("total"));
        return order;
    }

    private JsonNode collect(Shop shop, long orderId, String body, int expectedStatus) throws Exception {
        String content = mockMvc.perform(withBody(post("/api/v1/payments/orders/" + orderId + "/collect"), shop.token(), body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return content.isBlank() ? null : objectMapper.readTree(content);
    }

    private JsonNode dinerPays(JsonNode order, String method, int expectedStatus) throws Exception {
        String content = mockMvc.perform(withBody(
                        post("/api/public/orders/by-token/" + order.get("publicToken").asText() + "/payments"),
                        null, "{\"paymentMethod\":\"" + method + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return content.isBlank() ? null : objectMapper.readTree(content);
    }

    private JsonNode setStatus(Shop shop, long paymentId, String body, int expectedStatus) throws Exception {
        String content = mockMvc.perform(withBody(put("/api/v1/payments/" + paymentId + "/status"), shop.token(), body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return content.isBlank() ? null : objectMapper.readTree(content);
    }

    private static MockHttpServletRequestBuilder put(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url);
    }

    private JsonNode summary(Shop shop, long orderId) throws Exception {
        return json(authed(get("/api/v1/payments/orders/" + orderId + "/summary"), shop.token()));
    }

    private JsonNode overview(Shop shop, String query) throws Exception {
        return json(authed(get("/api/v1/payment-analytics/overview?period=TODAY" + query), shop.token()));
    }

    private static JsonNode byKey(JsonNode array, String key) {
        for (JsonNode n : array) {
            if (key.equals(n.get("key").asText())) {
                return n;
            }
        }
        throw new AssertionError("no row with key " + key + " in " + array);
    }

    // ------------------------------------------------------------------ split payments

    @Test
    void splitBill_isStoredPerComponent_andRevenueIsNotDoubleCounted() throws Exception {
        Shop shop = setupShop();
        JsonNode order = placeOrder(shop);
        long orderId = order.get("id").asLong();

        // Example A from the brief: 1,200 = 500 cash + 700 Google Pay.
        JsonNode result = collect(shop, orderId, """
                {"payments":[
                  {"paymentMethod":"CASH","amount":500},
                  {"paymentMethod":"UPI","paymentProvider":"GOOGLE_PAY","amount":700}],
                 "idempotencyKey":"split-1"}
                """, 201);

        assertEquals("SUCCESS", result.get("status").asText());
        assertMoney("1200", result.get("settled"));
        assertMoney("0", result.get("outstanding"));
        assertEquals(2, result.get("payments").size());

        // Replaying the same request returns the same records instead of recording twice.
        collect(shop, orderId, """
                {"payments":[
                  {"paymentMethod":"CASH","amount":500},
                  {"paymentMethod":"UPI","paymentProvider":"GOOGLE_PAY","amount":700}],
                 "idempotencyKey":"split-1"}
                """, 201);
        assertEquals(2, summary(shop, orderId).get("payments").size());

        // Nothing is left to pay, so more money cannot be recorded against the order.
        collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":1}]}", 400);

        // The dashboard counts the money once.
        JsonNode o = overview(shop, "");
        assertMoney("1200", o.get("totals").get("gross"));
        assertEquals(2, o.get("totals").get("transactions").asInt());
        assertEquals(1, o.get("totals").get("uniqueOrders").asInt());
    }

    @Test
    void partialPayment_leavesTheBalanceOpen_andTheRestCanBePaidLater() throws Exception {
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        JsonNode first = collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":400}]}", 201);
        assertMoney("800", first.get("outstanding"));
        assertTrue(first.get("status").isNull());

        // More than is due is refused and leaves everything as it was.
        collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CARD\",\"cardType\":\"DEBIT\",\"amount\":900}]}", 400);
        assertMoney("800", summary(shop, orderId).get("outstanding"));

        JsonNode second = collect(shop, orderId,
                "{\"payments\":[{\"paymentMethod\":\"CARD\",\"cardType\":\"DEBIT\",\"amount\":800}]}", 201);
        assertEquals("SUCCESS", second.get("status").asText());
    }

    @Test
    void counterCollection_supersedesTheDinersPendingChoice() throws Exception {
        Shop shop = setupShop();
        JsonNode order = placeOrder(shop);
        long orderId = order.get("id").asLong();

        dinerPays(order, "UPI", 201);
        JsonNode result = collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":1200}]}", 201);

        assertEquals("SUCCESS", result.get("status").asText());
        assertMoney("0", result.get("pending"));
        long cancelled = 0;
        for (JsonNode p : result.get("payments")) {
            if ("CANCELLED".equals(p.get("paymentStatus").asText())) {
                cancelled++;
            }
        }
        assertEquals(1, cancelled); // kept on record, never counted as revenue
        assertMoney("1200", overview(shop, "").get("totals").get("gross"));
    }

    // ------------------------------------------------------------------ diner endpoint stays narrow

    @Test
    void aDinerCannotChooseCounterOnlyMethods_orSmuggleInAmountsAndProviders() throws Exception {
        Shop shop = setupShop();
        JsonNode order = placeOrder(shop);

        dinerPays(order, "BANK_TRANSFER", 400);
        dinerPays(order, "VOUCHER", 400);

        // Extra fields a diner might add are simply ignored: the payment is still pending, for the full
        // amount, with no provider and no "already paid" effect.
        String body = "{\"paymentMethod\":\"UPI\",\"amount\":1,\"paymentProvider\":\"GOOGLE_PAY\","
                + "\"paymentStatus\":\"SUCCESS\"}";
        String content = mockMvc.perform(withBody(
                        post("/api/public/orders/by-token/" + order.get("publicToken").asText() + "/payments"), null, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode payment = objectMapper.readTree(content);
        assertEquals("PENDING", payment.get("paymentStatus").asText());
        assertMoney("1200", payment.get("amount"));
        assertTrue(payment.get("paymentProvider").isNull());

        // Only one open request at a time.
        dinerPays(order, "CASH", 400);
        // The tracking page shows the derived status.
        JsonNode tracking = json(get("/api/public/orders/by-token/" + order.get("publicToken").asText()));
        assertEquals("PENDING", tracking.get("paymentStatus").asText());
        // The requested 1,200 is "spoken for" (see PaymentBalance): not paid, but nothing more is due while it is open.
        assertMoney("0", tracking.get("amountPaid"));
        assertMoney("0", tracking.get("balanceDue"));
    }

    @Test
    void aFailedPayment_canBeRetried() throws Exception {
        Shop shop = setupShop();
        JsonNode order = placeOrder(shop);

        long paymentId = dinerPays(order, "UPI", 201).get("id").asLong();
        setStatus(shop, paymentId, "{\"paymentStatus\":\"FAILED\"}", 200);

        JsonNode tracking = json(get("/api/public/orders/by-token/" + order.get("publicToken").asText()));
        assertEquals("FAILED", tracking.get("paymentStatus").asText());

        dinerPays(order, "CASH", 201); // a new attempt is allowed after a failure
    }

    // ------------------------------------------------------------------ status changes

    @Test
    void statusChanges_areIdempotent_final_andCaptureTheProvider() throws Exception {
        Shop shop = setupShop();
        JsonNode order = placeOrder(shop);
        long paymentId = dinerPays(order, "UPI", 201).get("id").asLong();

        JsonNode paid = setStatus(shop, paymentId,
                "{\"paymentStatus\":\"SUCCESS\",\"paymentProvider\":\"PHONEPE\",\"transactionId\":\"UPI-REF-1\"}", 200);
        assertEquals("SUCCESS", paid.get("paymentStatus").asText());
        assertEquals("PHONEPE", paid.get("paymentProvider").asText());
        assertEquals("UPI-REF-1", paid.get("transactionId").asText());

        // A repeated confirmation (double click, duplicate callback) changes nothing.
        JsonNode again = setStatus(shop, paymentId, "{\"paymentStatus\":\"SUCCESS\"}", 200);
        assertEquals("PHONEPE", again.get("paymentProvider").asText());

        // A settled payment cannot be turned into a failure, and refunds do not go through status.
        setStatus(shop, paymentId, "{\"paymentStatus\":\"FAILED\"}", 400);
        setStatus(shop, paymentId, "{\"paymentStatus\":\"REFUNDED\"}", 400);
        assertMoney("1200", overview(shop, "").get("totals").get("gross"));
    }

    @Test
    void aProviderMustBelongToTheMethod() throws Exception {
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();

        collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CARD\",\"paymentProvider\":\"PHONEPE\",\"amount\":100}]}", 400);
        collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CASH\",\"cardType\":\"CREDIT\",\"amount\":100}]}", 400);
        // Paytm UPI is not a wallet.
        collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"WALLET\",\"paymentProvider\":\"PAYTM_UPI\",\"amount\":100}]}", 400);
        // An unknown provider value is a client error, not a server error.
        collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"UPI\",\"paymentProvider\":\"NOT_AN_APP\",\"amount\":100}]}", 400);
        assertEquals(0, summary(shop, orderId).get("payments").size());
    }

    // ------------------------------------------------------------------ refunds

    @Test
    void refunds_arePartial_linkedToThePayment_andNeverExceedIt() throws Exception {
        Shop shop = setupShop();
        long orderId = placeOrder(shop).get("id").asLong();
        JsonNode result = collect(shop, orderId, "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":1200}]}", 201);
        long paymentId = result.get("payments").get(0).get("id").asLong();

        mockMvc.perform(withBody(post("/api/v1/payments/" + paymentId + "/refunds"), shop.token(),
                        "{\"amount\":200,\"reason\":\"Cold dish\"}"))
                .andExpect(status().isCreated());
        JsonNode afterPartial = summary(shop, orderId);
        assertEquals("PARTIALLY_REFUNDED", afterPartial.get("payments").get(0).get("paymentStatus").asText());
        assertMoney("200", afterPartial.get("payments").get(0).get("refundedAmount"));
        assertMoney("200", afterPartial.get("outstanding")); // the refunded part is owed again

        mockMvc.perform(withBody(post("/api/v1/payments/" + paymentId + "/refunds"), shop.token(), "{\"amount\":1100}"))
                .andExpect(status().isBadRequest()); // only 1,000 is left to refund

        mockMvc.perform(withBody(post("/api/v1/payments/" + paymentId + "/refunds"), shop.token(), "{\"amount\":1000}"))
                .andExpect(status().isCreated());
        assertEquals("REFUNDED", summary(shop, orderId).get("payments").get(0).get("paymentStatus").asText());

        JsonNode o = overview(shop, "");
        assertMoney("1200", o.get("totals").get("gross"));   // money that came in
        assertMoney("1200", o.get("totals").get("refunds")); // money that went back
        assertMoney("0", o.get("totals").get("net"));

        JsonNode detail = json(authed(get("/api/v1/payment-analytics/transactions/" + paymentId), shop.token()));
        assertEquals(2, detail.get("refunds").size());
        assertEquals("Cold dish", detail.get("refunds").get(0).get("reason").asText());
    }

    // ------------------------------------------------------------------ analytics arithmetic

    /** Builds a day with a known mix and returns the first order's cash payment id. */
    private long seedKnownDay(Shop shop) throws Exception {
        // 1: split cash 500 + Google Pay 700
        JsonNode o1 = placeOrder(shop);
        JsonNode r1 = collect(shop, o1.get("id").asLong(), """
                {"payments":[{"paymentMethod":"CASH","amount":500},
                             {"paymentMethod":"UPI","paymentProvider":"GOOGLE_PAY","amount":700}]}
                """, 201);
        long cashPaymentId = r1.get("payments").get(0).get("id").asLong();

        // 2: credit card 1,200
        collect(shop, placeOrder(shop).get("id").asLong(),
                "{\"payments\":[{\"paymentMethod\":\"CARD\",\"cardType\":\"CREDIT\",\"amount\":1200}]}", 201);

        // 3: diner picks UPI, staff confirms and records PhonePe
        JsonNode o3 = placeOrder(shop);
        setStatus(shop, dinerPays(o3, "UPI", 201).get("id").asLong(),
                "{\"paymentStatus\":\"SUCCESS\",\"paymentProvider\":\"PHONEPE\",\"transactionId\":\"UPI123\"}", 200);

        // 4: diner picks UPI, staff confirms without recording the app -> must stay Unknown
        JsonNode o4 = placeOrder(shop);
        setStatus(shop, dinerPays(o4, "UPI", 201).get("id").asLong(), "{\"paymentStatus\":\"SUCCESS\"}", 200);

        // 5: a failed cash payment
        JsonNode o5 = placeOrder(shop);
        setStatus(shop, dinerPays(o5, "CASH", 201).get("id").asLong(), "{\"paymentStatus\":\"FAILED\"}", 200);

        // 6: a pending cash payment
        dinerPays(placeOrder(shop), "CASH", 201);

        // a refund of 100 on the cash payment of order 1
        mockMvc.perform(withBody(post("/api/v1/payments/" + cashPaymentId + "/refunds"), shop.token(), "{\"amount\":100}"))
                .andExpect(status().isCreated());
        return cashPaymentId;
    }

    @Test
    void overview_reconcilesEveryViewWithTheTotal_andKeepsUnknownProvidersUnknown() throws Exception {
        Shop shop = setupShop();
        seedKnownDay(shop);

        JsonNode o = overview(shop, "");
        JsonNode totals = o.get("totals");

        // Gross 4,800 = 1,200 (split) + 1,200 (card) + 1,200 (PhonePe) + 1,200 (unknown UPI).
        assertMoney("4800", totals.get("gross"));
        assertEquals(5, totals.get("transactions").asInt());   // the split bill is two transactions
        assertEquals(4, totals.get("uniqueOrders").asInt());   // ... but one order
        assertMoney("100", totals.get("refunds"));
        assertMoney("4700", totals.get("net"));
        assertMoney("960", totals.get("averageTransaction"));  // 4,800 / 5

        // Failed and pending payments are reported, never counted as revenue.
        assertEquals(1, o.get("open").get("failedCount").asInt());
        assertEquals(1, o.get("open").get("pendingCount").asInt());
        assertMoney("1200", o.get("open").get("pendingAmount"));

        // Methods add up to the total.
        BigDecimal methodSum = BigDecimal.ZERO;
        for (JsonNode m : o.get("methods")) {
            methodSum = methodSum.add(dec(m.get("gross")));
        }
        assertEquals(0, dec(totals.get("gross")).compareTo(methodSum));
        assertMoney("500", byKey(o.get("methods"), "CASH").get("gross"));
        assertMoney("3100", byKey(o.get("methods"), "UPI").get("gross"));
        assertMoney("1200", byKey(o.get("methods"), "CARD").get("gross"));

        // UPI apps add up to UPI; the unrecorded app stays Unknown instead of being guessed.
        assertMoney("700", byKey(o.get("providers"), "GOOGLE_PAY").get("gross"));
        assertMoney("1200", byKey(o.get("providers"), "PHONEPE").get("gross"));
        JsonNode unknown = byKey(o.get("providers"), "UNKNOWN_UPI");
        assertMoney("1200", unknown.get("gross"));
        assertFalse(unknown.get("known").asBoolean());
        BigDecimal upiSum = BigDecimal.ZERO;
        for (JsonNode p : o.get("providers")) {
            if ("UPI".equals(p.get("method").asText())) {
                upiSum = upiSum.add(dec(p.get("gross")));
            }
        }
        assertEquals(0, new BigDecimal("3100").compareTo(upiSum));

        // The time series and the heatmap carry the same money.
        BigDecimal seriesSum = BigDecimal.ZERO;
        for (JsonNode row : o.get("series").get("rows")) {
            for (JsonNode a : row.get("amounts")) {
                seriesSum = seriesSum.add(dec(a));
            }
        }
        assertEquals(0, new BigDecimal("4800").compareTo(seriesSum));

        // Gross sales (billing) is separate from collections: six 1,200 orders were placed.
        assertMoney("7200", o.get("sales").get("grossSales"));
        assertEquals(6, o.get("sales").get("ordersPlaced").asInt());
    }

    @Test
    void filters_narrowEveryFigure() throws Exception {
        Shop shop = setupShop();
        seedKnownDay(shop);

        JsonNode cash = overview(shop, "&methods=CASH");
        assertMoney("500", cash.get("totals").get("gross"));
        assertTrue(cash.get("sales").isNull(), "orders cannot be filtered by payment method, so sales are hidden");

        JsonNode unknownUpi = overview(shop, "&providers=UNKNOWN_UPI");
        assertMoney("1200", unknownUpi.get("totals").get("gross"));
        assertEquals(1, unknownUpi.get("totals").get("transactions").asInt());

        JsonNode failed = overview(shop, "&statuses=FAILED");
        assertMoney("0", failed.get("totals").get("gross"));
        assertEquals(1, failed.get("open").get("failedCount").asInt());
    }

    @Test
    void transactionHistory_isFilteredSearchedSortedAndExportable() throws Exception {
        Shop shop = setupShop();
        seedKnownDay(shop);

        JsonNode all = json(authed(get("/api/v1/payment-analytics/transactions?period=TODAY&size=50"), shop.token()));
        assertEquals(7, all.get("totalItems").asInt()); // 2 + 1 + 1 + 1 + 1 (failed) + 1 (pending)

        JsonNode cardOnly = json(authed(get("/api/v1/payment-analytics/transactions?period=TODAY&methods=CARD"), shop.token()));
        assertEquals(1, cardOnly.get("totalItems").asInt());

        JsonNode byReference = json(authed(get("/api/v1/payment-analytics/transactions?period=TODAY&search=UPI123"), shop.token()));
        assertEquals(1, byReference.get("totalItems").asInt());
        assertEquals("PHONEPE", byReference.get("items").get(0).get("provider").asText());

        JsonNode unknownRows = json(authed(get(
                "/api/v1/payment-analytics/transactions?period=TODAY&providers=UNKNOWN_UPI"), shop.token()));
        assertEquals(1, unknownRows.get("totalItems").asInt());
        assertEquals("Unknown UPI app", unknownRows.get("items").get(0).get("providerLabel").asText());

        JsonNode paged = json(authed(get(
                "/api/v1/payment-analytics/transactions?period=TODAY&size=3&page=1&sortBy=amount&direction=desc"), shop.token()));
        assertEquals(3, paged.get("items").size());
        assertEquals(3, paged.get("totalPages").asInt());

        // Only whitelisted sort columns are accepted.
        mockMvc.perform(authed(get("/api/v1/payment-analytics/transactions?period=TODAY&sortBy=passwordHash"), shop.token()))
                .andExpect(status().isBadRequest());

        String csv = mockMvc.perform(authed(get("/api/v1/payment-analytics/transactions/export?period=TODAY"), shop.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.trim().split("\r\n");
        assertEquals(8, lines.length); // header + 7 rows
        assertTrue(lines[0].startsWith("Transaction ID,Date & time,Order"));
        assertTrue(csv.contains("UPI123"));
        assertTrue(csv.contains("Unknown UPI app"));
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    void reconciliation_recordsTheCashCountAgainstAServerComputedExpectation() throws Exception {
        Shop shop = setupShop();
        seedKnownDay(shop);
        String today = LocalDate.now().toString();

        JsonNode before = json(authed(get("/api/v1/payment-analytics/reconciliation?date=" + today), shop.token()));
        assertMoney("400", before.get("cash").get("expected")); // 500 cash received - 100 cash refunded
        assertMoney("4700", before.get("closing").get("netCollected"));
        // Digital = Google Pay 700 + card 1,200 + PhonePe 1,200 + unknown UPI 1,200; cash 400 + digital 4,300 = 4,700 net.
        assertMoney("4300", before.get("closing").get("digitalNet"));
        assertEquals(4, before.get("settlement").get("unsettledCount").asInt());
        assertFalse(before.get("settlement").get("gatewayConnected").asBoolean());
        assertEquals(1, before.get("pending").get("count").asInt());

        // The client cannot supply the expected figure; the server computes it.
        JsonNode counted = json(withBody(post("/api/v1/payment-analytics/reconciliation/counts"), shop.token(),
                "{\"date\":\"" + today + "\",\"countedCash\":380,\"expected\":99999,\"note\":\"Short by 20\"}"));
        assertMoney("400", counted.get("cash").get("counts").get(0).get("expected"));
        assertMoney("-20", counted.get("cash").get("counts").get(0).get("difference"));
        assertMoney("-20", counted.get("closing").get("latestCashDifference"));

        // A count for a future day is refused.
        mockMvc.perform(withBody(post("/api/v1/payment-analytics/reconciliation/counts"), shop.token(),
                        "{\"date\":\"" + LocalDate.now().plusDays(2) + "\",\"countedCash\":0}"))
                .andExpect(status().isBadRequest());

        // Settlement is confirmed by a manager; collection alone does not mean the bank has the money.
        JsonNode settled = json(withBody(post("/api/v1/payment-analytics/reconciliation/settle?date=" + today),
                shop.token(), "{}"));
        assertEquals(0, settled.get("settlement").get("unsettledCount").asInt());
        assertEquals(4, settled.get("settlement").get("settledCount").asInt());
    }

    // ------------------------------------------------------------------ security

    @Test
    void financialEndpoints_areRoleProtected_andTenantIsolated() throws Exception {
        Shop shop = setupShop();
        long cashPaymentId = seedKnownDay(shop);
        long orderId = json(authed(get("/api/v1/payments/" + cashPaymentId), shop.token())).get("orderId").asLong();

        // Anonymous callers and customers cannot read financial data.
        mockMvc.perform(get("/api/v1/payment-analytics/overview")).andExpect(status().is4xxClientError());
        String email = "cust-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String customer = json(withBody(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"" + email + "\",\"password\":\"Password123\"}")).get("accessToken").asText();
        mockMvc.perform(authed(get("/api/v1/payment-analytics/overview"), customer)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/payment-analytics/transactions/export"), customer)).andExpect(status().isForbidden());
        mockMvc.perform(withBody(post("/api/v1/payments/" + cashPaymentId + "/refunds"), customer, "{\"amount\":1}"))
                .andExpect(status().isForbidden());

        // Another restaurant sees none of it and cannot act on it.
        Shop other = setupShop();
        assertMoney("0", overview(other, "").get("totals").get("gross"));
        assertEquals(0, json(authed(get("/api/v1/payment-analytics/transactions?period=TODAY"), other.token()))
                .get("totalItems").asInt());
        mockMvc.perform(authed(get("/api/v1/payment-analytics/transactions/" + cashPaymentId), other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(get("/api/v1/payments/orders/" + orderId + "/summary"), other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(withBody(post("/api/v1/payments/" + cashPaymentId + "/refunds"), other.token(), "{\"amount\":1}"))
                .andExpect(status().isNotFound());
        collect(other, orderId, "{\"payments\":[{\"paymentMethod\":\"CASH\",\"amount\":1}]}", 404);
        assertMoney("0", json(authed(get("/api/v1/payment-analytics/reconciliation"), other.token()))
                .get("closing").get("netCollected"));
    }

    @Test
    void invalidInput_isAClientError() throws Exception {
        Shop shop = setupShop();
        String base = "/api/v1/payment-analytics/overview";

        for (String query : new String[]{
                "?period=NOPE",
                "?period=CUSTOM",                                          // needs from and to
                "?period=CUSTOM&from=2025-01-01&to=2026-10-07",            // longer than 366 days
                "?period=CUSTOM&from=2026-10-07&to=2026-10-01",            // reversed
                "?methods=FOO",
                "?statuses=BAD",
                "?providers=NOT_AN_APP",
                "?staffId=abc",
                "?from=13-10-2026&period=CUSTOM&to=2026-10-13",
                "?granularity=HOURLY&period=CUSTOM&from=" + LocalDate.now().minusDays(60)
                        + "&to=" + LocalDate.now()                          // too many hourly buckets
        }) {
            mockMvc.perform(authed(get(base + query), shop.token()))
                    .andExpect(status().isBadRequest());
        }
        assertNotNull(json(authed(get(base + "?period=LAST_30_DAYS"), shop.token())));
    }
}
