package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.service.NotificationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The part RealtimeNotificationIT cannot show: that a frame really travels over a real WebSocket, with the
 * browser's own protocol (STOMP over SockJS), to the dashboard that is allowed to hear it and to nobody
 * else. Data is created through the real controllers; the sockets connect to the running server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class LiveSocketDeliveryIT {

    private static final long QUIET_MS = 1500;

    private record Shop(String token, long restaurantId, String qr, long dishId) {
    }

    /** One dashboard's socket: every frame it received, and whether the server refused it. */
    private static final class Listener extends StompSessionHandlerAdapter {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch refused = new CountDownLatch(1);
        volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            this.session = session;
            connected.countDown();
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            // Only ERROR frames come here; subscribed frames go to the subscription's own handler.
            refused.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            refused.countDown();
        }

        @Override
        public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand c,
                                    StompHeaders h, byte[] p, Throwable ex) {
            refused.countDown();
        }
    }

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationService notificationService;
    @Autowired private RestaurantRepository restaurantRepository;

    private final List<WebSocketStompClient> clients = new ArrayList<>();

    @AfterEach
    void closeSockets() {
        clients.forEach(WebSocketStompClient::stop);
    }

    // ------------------------------------------------------------------ helpers

    private Listener connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        Listener listener = new Listener();
        client.connectAsync("http://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders, listener);
        listener.connected.await(10, TimeUnit.SECONDS);
        return listener;
    }

    private static void trySend(Listener listener, String destination, Object payload) {
        try {
            listener.session.send(destination, payload);
        } catch (RuntimeException closedByTheServer) {
            // expected once the server has refused a frame and dropped the connection
        }
    }

    private void subscribe(Listener listener, String destination) {
        listener.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return java.util.Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    listener.frames.add(objectMapper.writeValueAsString(payload));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

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

    private Shop shop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long restaurantId = json(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .get("restaurantId").asLong();
        long category = json(withBody(post("/api/categories"), token, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        long dish = json(withBody(post("/api/menu-items"), token,
                "{\"name\":\"Rogan Josh\",\"price\":400.00,\"categoryId\":" + category + "}"))
                .get("data").get("id").asLong();
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), token,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        String qr = json(withBody(post("/api/v1/tables"), token,
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
        return new Shop(token, restaurantId, qr, dish);
    }

    /** A guest at the table orders one dish, through the public flow the customer's phone uses. */
    private JsonNode placeOrder(Shop shop) throws Exception {
        String guest = DinerTestSupport.join(mockMvc, objectMapper, shop.qr(), "Asha");
        mockMvc.perform(withBody(post("/api/public/tables/" + shop.qr() + "/cart/items")
                        .header(DinerTestSupport.HEADER, guest), null,
                "{\"menuItemId\":" + shop.dishId() + ",\"quantity\":1}")).andExpect(status().isOk());
        return json(withBody(post("/api/public/tables/" + shop.qr() + "/orders")
                .header(DinerTestSupport.HEADER, guest), null, "{}"));
    }

    private static String topic(Shop shop) {
        return "/topic/restaurants/" + shop.restaurantId();
    }

    private static void waitFor(Listener l, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (l.frames.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    // ------------------------------------------------------------------ bursts

    /** What one dashboard saw during a burst: when each notification id arrived, and how many frames in all. */
    private static final class Seen {
        final Map<Long, Long> arrivedAt = new ConcurrentHashMap<>();
        final Map<Long, String> kinds = new ConcurrentHashMap<>();
        final Set<Long> restaurants = ConcurrentHashMap.newKeySet();
        final AtomicInteger frames = new AtomicInteger();
        final AtomicInteger malformed = new AtomicInteger();
    }

    private void watch(Listener listener, String destination, Seen seen) {
        listener.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                long now = System.nanoTime();
                seen.frames.incrementAndGet();
                Map<String, Object> m = (Map<String, Object>) payload;
                Object id = m.get("notificationId");
                if (!(id instanceof Number)) {
                    seen.malformed.incrementAndGet();
                    return;
                }
                seen.arrivedAt.putIfAbsent(((Number) id).longValue(), now);
                seen.kinds.put(((Number) id).longValue(), String.valueOf(m.get("eventKind")));
                seen.restaurants.add(((Number) m.get("restaurantId")).longValue());
            }
        });
    }

    /**
     * Creates {@code count} notifications for one restaurant from many threads at once, through the real service
     * (so each is saved and published after commit, exactly as an order or a waiter call would be).
     * Returns id to the System.nanoTime() just before it was created.
     */
    private Map<Long, Long> burst(Shop shop, int count, int threads) throws Exception {
        Restaurant restaurant = restaurantRepository.findById(shop.restaurantId()).orElseThrow();
        Map<Long, Long> startedAt = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> work = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int n = i;
                work.add(pool.submit(() -> {
                    NotificationEventKind kind = switch (n % 3) {
                        case 0 -> NotificationEventKind.NEW_ORDER;
                        case 1 -> NotificationEventKind.WAITER_CALL;
                        default -> NotificationEventKind.BILL_REQUEST;
                    };
                    NotificationType type = kind == NotificationEventKind.NEW_ORDER
                            ? NotificationType.ORDER_PLACED : NotificationType.SERVICE_REQUEST_CREATED;
                    long t0 = System.nanoTime();
                    long id = notificationService.createNotification(
                            restaurant, null, "Burst " + n, "Burst event " + n, type, kind).getId();
                    startedAt.put(id, t0);
                }));
            }
            for (java.util.concurrent.Future<?> f : work) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        return startedAt;
    }

    private static void waitForCount(Seen seen, int count, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (seen.arrivedAt.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private static double percentile(List<Double> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {10, 50, 100, 500})
    void aBurstIsDeliveredOnceEachToTheRightRestaurantOnly(int count) throws Exception {
        Shop a = shop();
        Shop b = shop();
        Listener dashA = connect(a.token());
        Listener dashA2 = connect(a.token()); // a second device of the same restaurant
        Listener dashB = connect(b.token());
        Seen seenA = new Seen();
        Seen seenA2 = new Seen();
        Seen seenB = new Seen();
        watch(dashA, topic(a), seenA);
        watch(dashA2, topic(a), seenA2);
        watch(dashB, topic(b), seenB);
        Thread.sleep(500);

        // Both restaurants are busy at the same moment; B gets half as many events as A.
        int bCount = Math.max(1, count / 2);
        java.util.concurrent.CompletableFuture<Map<Long, Long>> bBurst =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return burst(b, bCount, 4);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        Map<Long, Long> startedA = burst(a, count, 16);
        Map<Long, Long> startedB = bBurst.get(120, TimeUnit.SECONDS);

        waitForCount(seenA, count, 30_000);
        waitForCount(seenA2, count, 30_000);
        waitForCount(seenB, bCount, 30_000);
        Thread.sleep(QUIET_MS); // any duplicate would arrive in this time

        // Every event arrived, exactly once, on every device of its own restaurant.
        assertEquals(count, seenA.arrivedAt.size(), "restaurant A, device 1: every event arrives");
        assertEquals(count, seenA.frames.get(), "restaurant A, device 1: no frame twice");
        assertEquals(count, seenA2.frames.get(), "restaurant A, device 2: every event, once");
        assertEquals(bCount, seenB.frames.get(), "restaurant B: its own events, once each");
        assertEquals(0, seenA.malformed.get() + seenB.malformed.get(), "every frame carries an id");
        // Complete isolation, in both directions.
        assertEquals(Set.of(a.restaurantId()), seenA.restaurants);
        assertEquals(Set.of(b.restaurantId()), seenB.restaurants);
        assertTrue(Collections.disjoint(seenA.arrivedAt.keySet(), seenB.arrivedAt.keySet()), "no id crossed over");
        assertEquals(startedA.keySet(), seenA.arrivedAt.keySet());
        assertEquals(startedB.keySet(), seenB.arrivedAt.keySet());
        // The structured kind travels with every frame.
        assertTrue(seenA.kinds.values().stream().allMatch(k -> Set.of("NEW_ORDER", "WAITER_CALL", "BILL_REQUEST").contains(k)));

        // Latency: from just before the event was created (so it includes saving it) to the frame reaching the socket client.
        List<Double> ms = startedA.entrySet().stream()
                .map(e -> (seenA.arrivedAt.get(e.getKey()) - e.getValue()) / 1_000_000.0)
                .sorted().collect(Collectors.toList());
        System.out.printf("[latency] burst=%d (plus %d for another restaurant) p50=%.1fms p95=%.1fms max=%.1fms%n",
                count, bCount, percentile(ms, 0.50), percentile(ms, 0.95), ms.get(ms.size() - 1));
        assertTrue(percentile(ms, 0.95) < 10_000, "p95 stays within a generous 10 s on a test machine");
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aNewOrderReachesTheRestaurantsOwnDashboardOnceAndNobodyElse() throws Exception {
        Shop a = shop();
        Shop b = shop();
        Listener dashboardA = connect(a.token());
        Listener dashboardA2 = connect(a.token()); // a second open dashboard of the same restaurant
        Listener dashboardB = connect(b.token());
        subscribe(dashboardA, topic(a));
        subscribe(dashboardA2, topic(a));
        subscribe(dashboardB, topic(b));
        Thread.sleep(500); // let the broker register the subscriptions

        JsonNode order = placeOrder(a);

        waitFor(dashboardA, 1);
        waitFor(dashboardA2, 1);
        Thread.sleep(QUIET_MS); // a duplicate, if there were one, would arrive in this time
        assertEquals(1, dashboardA.frames.size(), "one order, one frame on the dashboard");
        assertEquals(1, dashboardA2.frames.size(), "every dashboard of that restaurant hears it, once");
        JsonNode frame = objectMapper.readTree(dashboardA.frames.get(0));
        assertEquals("ORDER_PLACED", frame.get("notificationType").asText());
        assertEquals(a.restaurantId(), frame.get("restaurantId").asLong());
        assertEquals(order.get("orderNumber").asText(), frame.get("orderNumber").asText());
        assertTrue(frame.get("notificationId").asLong() > 0);
        assertTrue(dashboardB.frames.isEmpty(), "restaurant B must hear nothing about A's order");
    }

    @Test
    void aStaffAccountCannotListenToAnotherRestaurantsFeed() throws Exception {
        Shop a = shop();
        Shop b = shop();
        Listener intruder = connect(b.token());
        subscribe(intruder, topic(a)); // B's account asks for A's feed

        assertTrue(intruder.refused.await(10, TimeUnit.SECONDS), "the server refuses the subscription");
        placeOrder(a);
        Thread.sleep(QUIET_MS);
        assertTrue(intruder.frames.isEmpty(), "and nothing of A's trade leaks to it");
    }

    @Test
    void aSocketWithoutAValidTokenNeverConnects() throws Exception {
        Listener none = connect(null);
        assertEquals(1, none.connected.getCount(), "no token: no session");
        assertTrue(none.refused.await(10, TimeUnit.SECONDS), "the server answers with an error, not a session");

        Listener forged = connect("not-a-real-token");
        assertEquals(1, forged.connected.getCount(), "a forged token: no session");
        assertFalse(forged.frames.size() > 0);
    }

    /**
     * Subscribing is guarded, but so must publishing be: with a simple broker, a frame a client SENDs to a
     * /topic destination is relayed to everyone subscribed to it. Without a rule, any signed-in account could
     * push a forged "Payment received" or "New order" notice into another restaurant's dashboard.
     */
    @Test
    void aClientCannotPublishIntoAnyTopicTheServerOwns() throws Exception {
        Shop a = shop();
        Shop b = shop();
        Listener dashboardA = connect(a.token());
        subscribe(dashboardA, topic(a));
        Listener platformOwnerStandIn = connect(a.token());
        Listener attacker = connect(b.token());
        Thread.sleep(500);

        // Real JSON objects (a bare String would not go through the JSON converter, and a test that never
        // sent anything would prove nothing).
        java.util.Map<String, Object> forged = java.util.Map.of(
                "notificationId", 999999,
                "restaurantId", a.restaurantId(),
                "title", "Payment Successful",
                "message", "Payment received for Order ORD-1",
                "notificationType", "PAYMENT_SUCCESS");
        // The server refuses the first frame and closes that connection, so a later send on it may throw
        // (the session is gone). That is the refusal working, not a test failure: only what reaches A matters.
        // Into A's own topic, from B's account
        trySend(attacker, topic(a), forged);
        // Into A's topic, from A's own (legitimate, subscribed) account: clients publish nothing, ever
        trySend(platformOwnerStandIn, topic(a), forged);
        // Into the platform feed (a second attacker connection, since the first may already be closed)
        Listener attacker2 = connect(b.token());
        trySend(attacker2, "/topic/platform", java.util.Map.of("eventId", "x", "type", "PLATFORM_RESTAURANT_REGISTERED", "title", "t", "message", "m"));

        Thread.sleep(QUIET_MS);
        assertTrue(dashboardA.frames.isEmpty(), "a client-published frame must never reach a dashboard: " + dashboardA.frames);
    }

    @Test
    void aRestaurantAccountCannotListenToThePlatformFeed() throws Exception {
        Shop a = shop();
        Listener owner = connect(a.token());
        subscribe(owner, "/topic/platform");
        assertTrue(owner.refused.await(10, TimeUnit.SECONDS), "only the platform owner may subscribe");
    }
}
