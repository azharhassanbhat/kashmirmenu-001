package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.repository.RoleRepository;
import com.kashmirmenu.app.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage A: table-side service requests, end to end through the real controllers, security chain and
 * database. Covers the guest side (raise, repeat, track, cancel), the staff side (board, lifecycle,
 * history), tenant isolation and role checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class ServiceRequestIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private record Shop(String ownerToken, String ownerEmail) {
    }

    private record Table(String qr, long id) {
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

    private MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b, String bearer) {
        return b.header("Authorization", "Bearer " + bearer);
    }

    private MockHttpServletRequestBuilder asGuest(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header(DinerTestSupport.HEADER, token);
    }

    private Shop registerShop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String email = "owner-" + id + "@example.com";
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"%s","password":"Password123"}
                """.formatted(id, id, id, email))).get("accessToken").asText();
        return new Shop(token, email);
    }

    private Table createTable(Shop shop, String code) throws Exception {
        JsonNode t = json(withBody(post("/api/v1/tables"), shop.ownerToken(),
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"));
        return new Table(t.get("qrIdentifier").asText(), t.get("id").asLong());
    }

    /** A staff account of the owner's restaurant, created directly for want of a staff-invite API. */
    private String staffToken(Shop shop, RoleName roleName) throws Exception {
        String email = roleName.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User owner = userRepository.findByEmail(shop.ownerEmail()).orElseThrow();
        userRepository.save(User.builder()
                .name(roleName.name() + " Staff")
                .email(email)
                .password(passwordEncoder.encode("Password123!x"))
                .role(role)
                .restaurant(owner.getRestaurant())
                .active(true)
                .build());
        return json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Password123!x\"}")).get("accessToken").asText();
    }

    private String join(Table table, String name) throws Exception {
        return DinerTestSupport.join(mockMvc, objectMapper, table.qr(), name);
    }

    private String url(Table table) {
        return "/api/public/tables/" + table.qr() + "/service-requests";
    }

    private JsonNode raise(Table table, String token, String type, String note) throws Exception {
        String body = note == null
                ? "{\"type\":\"" + type + "\"}"
                : "{\"type\":\"" + type + "\",\"note\":\"" + note + "\"}";
        return json(asGuest(withBody(post(url(table)), null, body), token));
    }

    private JsonNode board(String bearer) throws Exception {
        return json(staff(get("/api/v1/service-requests"), bearer));
    }

    private JsonNode find(JsonNode array, String requestNumber) {
        for (JsonNode n : array) {
            if (requestNumber.equals(n.get("requestNumber").asText())) {
                return n;
            }
        }
        return null;
    }

    private void setStatus(String bearer, long id, String status, int expected) throws Exception {
        mockMvc.perform(withBody(put("/api/v1/service-requests/" + id + "/status"), bearer,
                "{\"status\":\"" + status + "\"}")).andExpect(status().is(expected));
    }

    // ------------------------------------------------------------------- guests

    @Test
    void aGuestCanAskForWaterWithoutOrderingAndStaffSeeItPending() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T5");
        String guest = join(table, "Rahul");

        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{\"type\":\"DRINKING_WATER\"}"), guest))
                .andExpect(status().isCreated());

        JsonNode list = board(shop.ownerToken());
        assertEquals(1, list.size());
        JsonNode r = list.get(0);
        assertEquals("PENDING", r.get("status").asText());
        assertEquals("DRINKING_WATER", r.get("type").asText());
        assertTrue(r.get("requestNumber").asText().startsWith("SR-"));
        assertEquals("Table T5", r.get("tableName").asText());
        assertEquals("Rahul", r.get("requestedBy").asText());
    }

    @Test
    void aSubmittedRequestIsNeverCompletedAutomatically() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T1");
        String guest = join(table, "Amit");

        JsonNode created = raise(table, guest, "CALL_WAITER", null);
        assertEquals("PENDING", created.get("status").asText());
        assertTrue(created.get("completedAt").isNull());
        assertTrue(created.get("acknowledgedAt").isNull());
    }

    @Test
    void anInvalidQrIsRefused() throws Exception {
        mockMvc.perform(withBody(post("/api/public/tables/no-such-table/service-requests"), null,
                "{\"type\":\"CALL_WAITER\"}")).andExpect(status().isNotFound());
    }

    @Test
    void aRequestWithoutAGuestTokenIsRefused() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T2");
        mockMvc.perform(withBody(post(url(table)), null, "{\"type\":\"CALL_WAITER\"}"))
                .andExpect(status().isNotFound());
        assertEquals(0, board(shop.ownerToken()).size());
    }

    @Test
    void aGuestTokenFromAnotherTableDoesNotWork() throws Exception {
        Shop shop = registerShop();
        Table one = createTable(shop, "T1");
        Table two = createTable(shop, "T2");
        String guestAtOne = join(one, "Priya");

        mockMvc.perform(asGuest(withBody(post(url(two)), null, "{\"type\":\"CALL_WAITER\"}"), guestAtOne))
                .andExpect(status().isNotFound());
        assertEquals(0, board(shop.ownerToken()).size());
    }

    @Test
    void anUnknownRequestTypeIsABadRequest() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T3");
        String guest = join(table, "Sana");
        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{\"type\":\"TELEPORT\"}"), guest))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{}"), guest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tappingTheSameRequestAgainReturnsTheOpenOneInsteadOfMakingAnother() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T4");
        String guest = join(table, "Rahul");

        JsonNode first = raise(table, guest, "CALL_WAITER", null);

        // The second tap is a 200 (nothing created), not a 201.
        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{\"type\":\"CALL_WAITER\"}"), guest))
                .andExpect(status().isOk());
        JsonNode second = raise(table, guest, "CALL_WAITER", null);

        assertEquals(first.get("requestNumber").asText(), second.get("requestNumber").asText());
        assertTrue(second.get("alreadyRequested").asBoolean());
        assertFalse(first.get("alreadyRequested").asBoolean());
        assertEquals(1, board(shop.ownerToken()).size());
    }

    @Test
    void anotherGuestAtTheSameTableJoinsTheOpenRequestWithoutSeeingItsNote() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T6");
        String rahul = join(table, "Rahul");
        String amit = join(table, "Amit");

        raise(table, rahul, "ASSISTANCE", "my child has a nut allergy");
        JsonNode amits = raise(table, amit, "ASSISTANCE", null);

        assertTrue(amits.get("alreadyRequested").asBoolean());
        assertFalse(amits.get("mine").asBoolean());
        assertTrue(amits.get("note").isNull(), "another guest's note must stay private");
        assertEquals(1, board(shop.ownerToken()).size());
    }

    @Test
    void aFreeTextRequestNeedsANote() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T7");
        String guest = join(table, "Sana");
        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{\"type\":\"OTHER\"}"), guest))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{\"type\":\"OTHER\",\"note\":\"   \"}"), guest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void severalFreeTextRequestsAreAllowedUpToALimit() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T8");
        String guest = join(table, "Sana");

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(asGuest(withBody(post(url(table)), null,
                    "{\"type\":\"OTHER\",\"note\":\"request " + i + "\"}"), guest))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(asGuest(withBody(post(url(table)), null,
                "{\"type\":\"OTHER\",\"note\":\"request 4\"}"), guest))
                .andExpect(status().isConflict());
        assertEquals(3, board(shop.ownerToken()).size());
    }

    @Test
    void aNoteOver200CharactersIsRefused() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T9");
        String guest = join(table, "Sana");
        mockMvc.perform(asGuest(withBody(post(url(table)), null,
                "{\"type\":\"OTHER\",\"note\":\"" + "x".repeat(201) + "\"}"), guest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aGuestSeesTheirRequestAndItsStatusChange() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T10");
        String guest = join(table, "Rahul");
        JsonNode created = raise(table, guest, "DRINKING_WATER", null);
        String number = created.get("requestNumber").asText();

        JsonNode before = json(asGuest(get(url(table)), guest));
        assertEquals("PENDING", find(before, number).get("status").asText());
        assertTrue(find(before, number).get("mine").asBoolean());

        long id = board(shop.ownerToken()).get(0).get("id").asLong();
        setStatus(shop.ownerToken(), id, "ACKNOWLEDGED", 200);

        JsonNode after = json(asGuest(get(url(table)), guest));
        assertEquals("ACKNOWLEDGED", find(after, number).get("status").asText());
        assertFalse(find(after, number).get("acknowledgedAt").isNull());
    }

    @Test
    void aGuestCanWithdrawAWaitingRequestAndRetryingIsHarmless() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T11");
        String guest = join(table, "Rahul");
        String number = raise(table, guest, "CUTLERY", null).get("requestNumber").asText();

        mockMvc.perform(asGuest(post(url(table) + "/" + number + "/cancel"), guest))
                .andExpect(status().isOk());
        // A dropped response and a retry must not turn into an error.
        mockMvc.perform(asGuest(post(url(table) + "/" + number + "/cancel"), guest))
                .andExpect(status().isOk());

        assertEquals("CANCELLED",
                find(json(asGuest(get(url(table)), guest)), number).get("status").asText());
    }

    @Test
    void aGuestCannotWithdrawARequestStaffAreAlreadyHandling() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T12");
        String guest = join(table, "Rahul");
        String number = raise(table, guest, "NAPKINS", null).get("requestNumber").asText();
        setStatus(shop.ownerToken(), board(shop.ownerToken()).get(0).get("id").asLong(), "ACKNOWLEDGED", 200);

        mockMvc.perform(asGuest(post(url(table) + "/" + number + "/cancel"), guest))
                .andExpect(status().isConflict());
    }

    @Test
    void aGuestCannotWithdrawAnotherGuestsRequest() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T13");
        String rahul = join(table, "Rahul");
        String amit = join(table, "Amit");
        String number = raise(table, rahul, "EXTRA_PLATES", null).get("requestNumber").asText();

        mockMvc.perform(asGuest(post(url(table) + "/" + number + "/cancel"), amit))
                .andExpect(status().isNotFound());
        assertEquals("PENDING", board(shop.ownerToken()).get(0).get("status").asText());
    }

    // -------------------------------------------------------------------- staff

    @Test
    void staffWalkARequestThroughTheWholeLifecycleAndItIsAllOnRecord() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T14");
        String waiter = staffToken(shop, RoleName.WAITER);
        String guest = join(table, "Rahul");
        raise(table, guest, "REQUEST_BILL", null);
        long id = board(waiter).get(0).get("id").asLong();

        setStatus(waiter, id, "ACKNOWLEDGED", 200);
        setStatus(waiter, id, "IN_PROGRESS", 200);
        setStatus(waiter, id, "COMPLETED", 200);

        JsonNode done = json(staff(get("/api/v1/service-requests/" + id), waiter));
        assertEquals("COMPLETED", done.get("status").asText());
        assertFalse(done.get("completedAt").isNull());
        assertEquals("WAITER Staff", done.get("assignedToName").asText(),
                "acknowledging assigns the request to whoever did it");

        JsonNode history = done.get("history");
        assertEquals(4, history.size(), "created, acknowledged, in progress, completed");
        assertTrue(history.get(0).get("from").isNull());
        assertEquals("PENDING", history.get(0).get("to").asText());
        assertEquals("Rahul", history.get(0).get("actor").asText());
        assertEquals("COMPLETED", history.get(3).get("to").asText());
        assertTrue(history.get(3).get("actor").asText().startsWith("waiter-"));
    }

    @Test
    void aRequestCannotSkipTheAcknowledgement() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T15");
        raise(table, join(table, "Rahul"), "CLEANING", null);
        long id = board(shop.ownerToken()).get(0).get("id").asLong();

        setStatus(shop.ownerToken(), id, "COMPLETED", 409);
        setStatus(shop.ownerToken(), id, "IN_PROGRESS", 409);
        assertEquals("PENDING", board(shop.ownerToken()).get(0).get("status").asText());
    }

    @Test
    void aFinishedRequestCannotBeReopenedAndRetriedTapsAreHarmless() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T16");
        raise(table, join(table, "Rahul"), "CUTLERY", null);
        long id = board(shop.ownerToken()).get(0).get("id").asLong();

        setStatus(shop.ownerToken(), id, "ACKNOWLEDGED", 200);
        setStatus(shop.ownerToken(), id, "ACKNOWLEDGED", 200); // a retried tap
        setStatus(shop.ownerToken(), id, "COMPLETED", 200);
        setStatus(shop.ownerToken(), id, "COMPLETED", 200);    // and again
        setStatus(shop.ownerToken(), id, "PENDING", 409);
        setStatus(shop.ownerToken(), id, "IN_PROGRESS", 409);

        assertEquals(3, json(staff(get("/api/v1/service-requests/" + id), shop.ownerToken()))
                .get("history").size(), "retries must not add history rows");
    }

    @Test
    void afterACompletedRequestTheGuestCanAskForTheSameThingAgain() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T17");
        String guest = join(table, "Rahul");
        String first = raise(table, guest, "DRINKING_WATER", null).get("requestNumber").asText();
        long id = board(shop.ownerToken()).get(0).get("id").asLong();
        setStatus(shop.ownerToken(), id, "ACKNOWLEDGED", 200);
        setStatus(shop.ownerToken(), id, "COMPLETED", 200);

        mockMvc.perform(asGuest(withBody(post(url(table)), null, "{\"type\":\"DRINKING_WATER\"}"), guest))
                .andExpect(status().isCreated());
        JsonNode second = raise(table, guest, "DRINKING_WATER", null);
        assertNotEquals(first, second.get("requestNumber").asText());
    }

    @Test
    void theBoardListsTheLongestWaitFirst() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T18");
        String guest = join(table, "Rahul");
        String water = raise(table, guest, "DRINKING_WATER", null).get("requestNumber").asText();
        Thread.sleep(1100); // timestamps have one-second resolution on MySQL
        String waiter = raise(table, guest, "CALL_WAITER", null).get("requestNumber").asText();

        JsonNode list = board(shop.ownerToken());
        assertEquals(water, list.get(0).get("requestNumber").asText());
        assertEquals(waiter, list.get(1).get("requestNumber").asText());
        assertTrue(list.get(1).get("urgent").asBoolean());
        assertFalse(list.get(0).get("urgent").asBoolean());
    }

    // ---------------------------------------------------------- tenant isolation

    @Test
    void oneRestaurantCannotSeeOrChangeAnotherRestaurantsRequests() throws Exception {
        Shop a = registerShop();
        Shop b = registerShop();
        Table tableA = createTable(a, "T1");
        raise(tableA, join(tableA, "Rahul"), "CALL_WAITER", null);
        long idOfA = board(a.ownerToken()).get(0).get("id").asLong();

        assertEquals(0, board(b.ownerToken()).size());
        mockMvc.perform(staff(get("/api/v1/service-requests/" + idOfA), b.ownerToken()))
                .andExpect(status().isNotFound());
        setStatus(b.ownerToken(), idOfA, "ACKNOWLEDGED", 404);
        setStatus(b.ownerToken(), idOfA, "CANCELLED", 404);

        assertEquals("PENDING", board(a.ownerToken()).get(0).get("status").asText(),
                "the other restaurant's attempts must change nothing");
    }

    // ------------------------------------------------------------------- access

    @Test
    void theStaffEndpointsNeedASignIn() throws Exception {
        mockMvc.perform(get("/api/v1/service-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(withBody(put("/api/v1/service-requests/1/status"), null, "{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerAccountCannotWorkTheStaffBoard() throws Exception {
        Shop shop = registerShop();
        String customer = staffToken(shop, RoleName.CUSTOMER);
        mockMvc.perform(staff(get("/api/v1/service-requests"), customer)).andExpect(status().isForbidden());
    }

    @Test
    void everyFloorAndKitchenRoleCanWorkTheBoard() throws Exception {
        Shop shop = registerShop();
        for (RoleName role : new RoleName[]{RoleName.WAITER, RoleName.CHEF, RoleName.CASHIER, RoleName.MANAGER}) {
            String token = staffToken(shop, role);
            mockMvc.perform(staff(get("/api/v1/service-requests"), token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void anUnknownStatusIsABadRequest() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T19");
        raise(table, join(table, "Rahul"), "CALL_WAITER", null);
        long id = board(shop.ownerToken()).get(0).get("id").asLong();
        mockMvc.perform(withBody(put("/api/v1/service-requests/" + id + "/status"), shop.ownerToken(),
                "{\"status\":\"DONE\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put("/api/v1/service-requests/" + id + "/status"), shop.ownerToken(), "{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------- notifications

    @Test
    void raisingARequestAlertsStaffThroughTheNotificationFeed() throws Exception {
        Shop shop = registerShop();
        Table table = createTable(shop, "T20");
        raise(table, join(table, "Rahul"), "CALL_WAITER", null);

        JsonNode unread = json(staff(get("/api/v1/notifications/unread"), shop.ownerToken()));
        boolean found = false;
        for (JsonNode n : unread.has("data") ? unread.get("data") : unread) {
            if ("SERVICE_REQUEST_CREATED".equals(n.get("notificationType").asText())) {
                found = true;
            }
        }
        assertTrue(found, "a SERVICE_REQUEST_CREATED notification should exist for the restaurant");
    }
}
