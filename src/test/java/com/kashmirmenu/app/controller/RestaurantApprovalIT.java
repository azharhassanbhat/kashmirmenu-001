package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditRepository;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.repository.RoleRepository;
import com.kashmirmenu.app.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The restaurant approval workflow end to end: registration lands PENDING, the platform owner
 * decides, and every status is enforced on the staff APIs and the public QR flow.
 *
 * <p>Runs with approval switched ON; the rest of the suite runs with it off (see application-test.yml)
 * so its owners can trade immediately.
 */
@SpringBootTest(properties = "app.restaurant-approval.required=true")
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class RestaurantApprovalIT {

    private static final String BASE = "/api/platform-admin";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditRepository auditRepository;

    // ------------------------------------------------------------------ helpers

    private record Owner(String token, long restaurantId) {
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token, String body) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString();
        assertEquals(expectedStatus, response.getStatus(), "unexpected status, body: " + body);
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private Owner registerOwner() throws Exception {
        String id = unique();
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id)), 200).get("accessToken").asText();

        long restaurantId = json(auth(get("/api/v1/me"), token), 200).get("restaurantId").asLong();
        return new Owner(token, restaurantId);
    }

    private String registerCustomerToken() throws Exception {
        String email = "cust-" + unique() + "@example.com";
        return json(withBody(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"), 201)
                .get("accessToken").asText();
    }

    private String platformOwnerToken() throws Exception {
        String email = "platform-" + unique() + "@example.com";
        Role role = roleRepository.findByName(RoleName.PLATFORM_OWNER).orElseThrow();
        userRepository.save(User.builder()
                .name("Platform Owner").email(email)
                .password(passwordEncoder.encode("Password123!x"))
                .role(role).active(true).build());

        return json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Password123!x\"}"), 200)
                .get("accessToken").asText();
    }

    private JsonNode me(Owner owner) throws Exception {
        return json(auth(get("/api/v1/me"), owner.token()), 200);
    }

    private JsonNode decide(String platform, long id, String action, String reason, int expected) throws Exception {
        MockHttpServletRequestBuilder request = post(BASE + "/restaurants/" + id + "/" + action);
        request = reason == null
                ? auth(request, platform)
                : withBody(request, platform, "{\"reason\":\"" + reason + "\"}");
        return json(request, expected);
    }

    private JsonNode overview(String platform) throws Exception {
        return json(auth(get(BASE + "/overview"), platform), 200);
    }

    /** Registers an owner and approves the restaurant, leaving it ready to trade. */
    private Owner approvedOwner(String platform) throws Exception {
        Owner owner = registerOwner();
        decide(platform, owner.restaurantId(), "approve", null, 200);
        return owner;
    }

    private String createTable(Owner owner) throws Exception {
        return json(withBody(post("/api/v1/tables"), owner.token(),
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}"), 201)
                .get("qrIdentifier").asText();
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registrationCreatesAPendingRestaurantThatTheOwnerCanSee() throws Exception {
        String platform = platformOwnerToken();
        long pendingBefore = overview(platform).get("pending").asLong();

        Owner owner = registerOwner();

        JsonNode me = me(owner);
        assertEquals("PENDING", me.get("restaurantStatus").asText());
        assertTrue(me.get("restaurantStatusReason").isNull());

        assertEquals(RestaurantStatus.PENDING,
                restaurantRepository.findById(owner.restaurantId()).orElseThrow().getApprovalStatus());
        assertEquals(pendingBefore + 1, overview(platform).get("pending").asLong());

        assertTrue(auditRepository.findAll().stream().anyMatch(e ->
                e.getAction() == AuditAction.RESTAURANT_REGISTERED
                        && e.getDetail().contains("Restaurant " + owner.restaurantId() + " registered as PENDING")));
    }

    @Test
    void aPendingRestaurantAppearsInThePlatformQueueWithItsOwner() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();

        JsonNode page = json(auth(get(BASE + "/restaurants?status=PENDING&size=100"), platform), 200);

        JsonNode row = null;
        for (JsonNode item : page.get("items")) {
            if (item.get("id").asLong() == owner.restaurantId()) {
                row = item;
            }
        }
        assertNotNull(row, "the new registration should be in the pending queue");
        assertEquals("PENDING", row.get("status").asText());
        assertTrue(row.get("ownerEmail").asText().startsWith("owner-"));

        JsonNode detail = json(auth(get(BASE + "/restaurants/" + owner.restaurantId()), platform), 200);
        assertEquals("PENDING", detail.get("status").asText());
        assertEquals(0, detail.get("documents").size());
        assertEquals(0, detail.get("tableCount").asInt());
    }

    @Test
    void searchFiltersByNameAndByStatus() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();
        String name = json(auth(get(BASE + "/restaurants/" + owner.restaurantId()), platform), 200)
                .get("name").asText();

        JsonNode hit = json(auth(get(BASE + "/restaurants").param("q", name), platform), 200);
        assertEquals(1, hit.get("totalItems").asInt());

        JsonNode wrongStatus = json(auth(get(BASE + "/restaurants")
                .param("q", name).param("status", "SUSPENDED"), platform), 200);
        assertEquals(0, wrongStatus.get("totalItems").asInt());

        // % typed by the caller is literal, not a wildcard that matches everything.
        JsonNode wildcard = json(auth(get(BASE + "/restaurants").param("q", "%"), platform), 200);
        assertEquals(0, wildcard.get("totalItems").asInt());

        // An unknown sort column is refused rather than passed to the database.
        json(auth(get(BASE + "/restaurants?sortBy=password"), platform), 400);
    }

    // ------------------------------------------------------------------ restrictions

    @Test
    void aPendingRestaurantCannotOperateButCanStillReadItsStatus() throws Exception {
        Owner owner = registerOwner();

        for (String path : List.of("/api/v1/tables", "/api/categories", "/api/v1/orders",
                "/api/v1/dashboard/summary", "/api/v1/landing")) {
            JsonNode body = json(auth(get(path), owner.token()), 403);
            assertEquals("RESTAURANT_NOT_APPROVED", body.get("code").asText(), path);
            assertEquals("PENDING", body.get("restaurantStatus").asText(), path);
        }

        json(withBody(post("/api/v1/tables"), owner.token(),
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}"), 403);

        assertEquals("PENDING", me(owner).get("restaurantStatus").asText());
    }

    @Test
    void aPendingRestaurantIsHiddenFromCustomers() throws Exception {
        String platform = platformOwnerToken();
        Owner pending = registerOwner();
        Owner approved = approvedOwner(platform);
        String customer = registerCustomerToken();

        // By id: the same answer as for a restaurant that does not exist.
        json(auth(get("/api/restaurants/" + pending.restaurantId()), customer), 404);
        json(auth(get("/api/restaurants/" + approved.restaurantId()), customer), 200);

        // Searching by name: the shared database may hold many restaurants, so look for these two by name.
        String pendingName = json(auth(get(BASE + "/restaurants/" + pending.restaurantId()), platform), 200)
                .get("name").asText();
        String approvedName = json(auth(get(BASE + "/restaurants/" + approved.restaurantId()), platform), 200)
                .get("name").asText();

        JsonNode foundPending = json(auth(get("/api/restaurants/search").param("name", pendingName), customer), 200);
        assertEquals(0, foundPending.get("data").get("totalItems").asInt(),
                "a pending restaurant must not be listed to customers");

        JsonNode foundApproved = json(auth(get("/api/restaurants/search").param("name", approvedName), customer), 200);
        assertEquals(1, foundApproved.get("data").get("totalItems").asInt());
    }

    // ------------------------------------------------------------------ approve

    @Test
    void approvingRecordsWhoAndWhenOpensTheRestaurantAndNotifiesTheOwner() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();
        long approvedBefore = overview(platform).get("approved").asLong();
        long pendingBefore = overview(platform).get("pending").asLong();

        JsonNode detail = decide(platform, owner.restaurantId(), "approve", null, 200);

        assertEquals("APPROVED", detail.get("status").asText());
        assertEquals("Platform Owner", detail.get("reviewedByName").asText());
        assertFalse(detail.get("reviewedAt").isNull());

        Restaurant stored = restaurantRepository.findById(owner.restaurantId()).orElseThrow();
        assertEquals(RestaurantStatus.APPROVED, stored.getApprovalStatus());
        assertNotNull(stored.getReviewedAt());

        assertEquals(approvedBefore + 1, overview(platform).get("approved").asLong());
        assertEquals(pendingBefore - 1, overview(platform).get("pending").asLong());

        assertEquals("APPROVED", me(owner).get("restaurantStatus").asText());

        // The restaurant can now do what it could not.
        json(auth(get("/api/v1/tables"), owner.token()), 200);
        createTable(owner);

        JsonNode notifications = json(auth(get("/api/v1/notifications"), owner.token()), 200);
        boolean told = false;
        for (JsonNode n : notifications.get("items")) {
            told |= "Restaurant approved".equals(n.get("title").asText());
        }
        assertTrue(told, "the owner should be notified of the approval");

        assertTrue(auditRepository.findAll().stream().anyMatch(e ->
                e.getAction() == AuditAction.RESTAURANT_APPROVED
                        && String.valueOf(owner.restaurantId()).equals(e.getResourceId())
                        && e.getActorEmail() != null));
    }

    @Test
    void aDecisionAlreadyTakenCannotBeTakenAgain() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();

        decide(platform, owner.restaurantId(), "approve", null, 200);
        decide(platform, owner.restaurantId(), "approve", null, 409);
        decide(platform, owner.restaurantId(), "reject", "Not a real restaurant", 409);
        decide(platform, owner.restaurantId(), "reactivate", null, 409);
    }

    @Test
    void twoSimultaneousApprovalsProduceOneSuccessAndOneConflict() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> approve = () -> mockMvc.perform(
                            auth(post(BASE + "/restaurants/" + owner.restaurantId() + "/approve"), platform))
                    .andReturn().getResponse().getStatus();
            Future<Integer> a = pool.submit(approve);
            Future<Integer> b = pool.submit(approve);

            List<Integer> statuses = List.of(a.get(), b.get());
            assertTrue(statuses.contains(200), "one approval should succeed: " + statuses);
            assertTrue(statuses.contains(409), "the other should be refused: " + statuses);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ reject

    @Test
    void rejectionNeedsAReasonStoresItAndShowsItToTheOwner() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();

        decide(platform, owner.restaurantId(), "reject", null, 400);
        decide(platform, owner.restaurantId(), "reject", "no", 400);
        json(withBody(post(BASE + "/restaurants/" + owner.restaurantId() + "/reject"), platform,
                "{\"reason\":\"      \"}"), 400);
        assertEquals("PENDING", me(owner).get("restaurantStatus").asText());

        JsonNode detail = decide(platform, owner.restaurantId(), "reject", "Documents do not match", 200);
        assertEquals("REJECTED", detail.get("status").asText());
        assertEquals("Documents do not match", detail.get("rejectionReason").asText());
        assertEquals("Documents do not match", detail.get("statusReason").asText());

        JsonNode me = me(owner);
        assertEquals("REJECTED", me.get("restaurantStatus").asText());
        assertEquals("Documents do not match", me.get("restaurantStatusReason").asText());

        // A rejected restaurant cannot operate.
        JsonNode refused = json(auth(get("/api/v1/tables"), owner.token()), 403);
        assertEquals("REJECTED", refused.get("restaurantStatus").asText());

        assertTrue(auditRepository.findAll().stream().anyMatch(e ->
                e.getAction() == AuditAction.RESTAURANT_REJECTED
                        && e.getDetail().contains("Documents do not match")));
    }

    @Test
    void aRejectedRestaurantCanBeReconsideredAndApproved() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();

        decide(platform, owner.restaurantId(), "reject", "Missing details", 200);
        JsonNode detail = decide(platform, owner.restaurantId(), "approve", null, 200);

        assertEquals("APPROVED", detail.get("status").asText());
        assertTrue(detail.get("rejectionReason").isNull(), "a stale rejection reason must be cleared");
        json(auth(get("/api/v1/tables"), owner.token()), 200);
    }

    // ------------------------------------------------------------------ suspend / reactivate

    @Test
    void suspendingStopsStaffAndDinersAndReactivatingRestoresThem() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = approvedOwner(platform);
        String qr = createTable(owner);

        mockMvc.perform(get("/api/public/tables/" + qr + "/landing")).andExpect(status().isOk());

        // Needs a reason, and only an approved restaurant can be suspended.
        decide(platform, owner.restaurantId(), "suspend", null, 400);
        JsonNode detail = decide(platform, owner.restaurantId(), "suspend", "Chargeback investigation", 200);
        assertEquals("SUSPENDED", detail.get("status").asText());
        assertEquals("Chargeback investigation", detail.get("suspensionReason").asText());

        JsonNode refused = json(auth(get("/api/v1/tables"), owner.token()), 403);
        assertEquals("SUSPENDED", refused.get("restaurantStatus").asText());
        assertEquals("Chargeback investigation", me(owner).get("restaurantStatusReason").asText());

        // Diners see an inactive QR code, indistinguishable from an unknown one.
        mockMvc.perform(get("/api/public/tables/" + qr + "/landing")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/tables/" + qr + "/menu")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/public/tables/" + qr + "/join")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Guest\"}"))
                .andExpect(status().isNotFound());

        decide(platform, owner.restaurantId(), "suspend", "Again please", 409);

        JsonNode reactivated = decide(platform, owner.restaurantId(), "reactivate", null, 200);
        assertEquals("APPROVED", reactivated.get("status").asText());
        assertTrue(reactivated.get("suspensionReason").isNull());

        json(auth(get("/api/v1/tables"), owner.token()), 200);
        mockMvc.perform(get("/api/public/tables/" + qr + "/landing")).andExpect(status().isOk());
    }

    @Test
    void aPendingRestaurantCannotBeSuspendedNorReactivated() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();

        decide(platform, owner.restaurantId(), "suspend", "Not yet approved", 409);
        decide(platform, owner.restaurantId(), "reactivate", null, 409);
    }

    @Test
    void anUnknownRestaurantIsNotFound() throws Exception {
        String platform = platformOwnerToken();
        decide(platform, 987654321L, "approve", null, 404);
        json(auth(get(BASE + "/restaurants/987654321"), platform), 404);
    }

    // ------------------------------------------------------------------ who may decide

    @Test
    void onlyThePlatformOwnerCanReadOrDecide() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = registerOwner();
        Owner other = approvedOwner(platform);
        String customer = registerCustomerToken();
        long id = owner.restaurantId();

        List<String> paths = List.of(
                BASE + "/overview", BASE + "/restaurants", BASE + "/restaurants/" + id);
        List<String> decisions = List.of(
                BASE + "/restaurants/" + id + "/approve", BASE + "/restaurants/" + id + "/reactivate");

        // Anonymous: not signed in.
        for (String p : paths) {
            mockMvc.perform(get(p)).andExpect(status().isUnauthorized());
        }
        for (String p : decisions) {
            mockMvc.perform(post(p)).andExpect(status().isUnauthorized());
        }

        // The restaurant's own owner (the person with most to gain), another restaurant's owner and a
        // customer are all refused - including for approving their own registration.
        for (String token : List.of(owner.token(), other.token(), customer)) {
            for (String p : paths) {
                mockMvc.perform(auth(get(p), token)).andExpect(status().isForbidden());
            }
            for (String p : decisions) {
                mockMvc.perform(auth(post(p), token)).andExpect(status().isForbidden());
            }
            mockMvc.perform(withBody(post(BASE + "/restaurants/" + id + "/reject"), token,
                    "{\"reason\":\"Because I can\"}")).andExpect(status().isForbidden());
            mockMvc.perform(withBody(post(BASE + "/restaurants/" + id + "/suspend"), token,
                    "{\"reason\":\"Because I can\"}")).andExpect(status().isForbidden());
        }

        assertEquals(RestaurantStatus.PENDING,
                restaurantRepository.findById(id).orElseThrow().getApprovalStatus());
        assertNull(restaurantRepository.findById(id).orElseThrow().getReviewedAt());
    }

    @Test
    void anOwnerCannotChangeTheirApprovalStatusThroughTheRestaurantApi() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = approvedOwner(platform);

        // Updating the profile with a status field must neither be accepted as a status change nor
        // alter it.
        mockMvc.perform(withBody(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/restaurants/" + owner.restaurantId()), owner.token(),
                "{\"name\":\"Renamed\",\"approvalStatus\":\"SUSPENDED\",\"status\":\"REJECTED\"}"));

        assertEquals(RestaurantStatus.APPROVED,
                restaurantRepository.findById(owner.restaurantId()).orElseThrow().getApprovalStatus());
    }

    @Test
    void anAdditionalRestaurantNeedsApprovalToo() throws Exception {
        String platform = platformOwnerToken();
        Owner owner = approvedOwner(platform);

        JsonNode created = json(withBody(post("/api/restaurants"), owner.token(),
                "{\"name\":\"Second Place " + unique() + "\"}"), 201);
        long id = created.get("data").get("id").asLong();

        assertEquals(RestaurantStatus.PENDING,
                restaurantRepository.findById(id).orElseThrow().getApprovalStatus());
    }

    // ------------------------------------------------------------------ regressions found in the audit

    @Test
    void aMaximumLengthReasonIsAcceptedAndTheNotificationIsCutToFit() throws Exception {
        // The notification carries the reason after a fixed lead-in, and its column holds 500 characters.
        // A 500-character reason used to fail the whole rejection with a 500 error.
        String platform = platformOwnerToken();
        Owner owner = registerOwner();
        String reason = "r".repeat(500);

        JsonNode detail = decide(platform, owner.restaurantId(), "reject", reason, 200);
        assertEquals(reason, detail.get("rejectionReason").asText());

        // 501 characters is still refused by validation, not by the database.
        Owner other = registerOwner();
        decide(platform, other.restaurantId(), "reject", "r".repeat(501), 400);

        // Reconsidering gives the owner access to their notifications, where the cut message is.
        decide(platform, owner.restaurantId(), "approve", null, 200);
        JsonNode notifications = json(auth(get("/api/v1/notifications"), owner.token()), 200);
        boolean found = false;
        for (JsonNode n : notifications.get("items")) {
            if ("Registration rejected".equals(n.get("title").asText())) {
                found = true;
                assertTrue(n.get("message").asText().length() <= 500);
            }
        }
        assertTrue(found, "the rejection notification should exist");
    }

    @Test
    void aRefusedDecisionLeavesNoAuditLineAndEachDecisionLeavesExactlyOne() throws Exception {
        // Audit lines used to be committed on their own before the decision committed, so a decision that
        // then failed still left a line saying it had happened.
        String platform = platformOwnerToken();
        Owner owner = registerOwner();
        String id = String.valueOf(owner.restaurantId());

        decide(platform, owner.restaurantId(), "approve", null, 200);
        decide(platform, owner.restaurantId(), "approve", null, 409);
        decide(platform, owner.restaurantId(), "reject", "Too late to reject", 409);
        decide(platform, owner.restaurantId(), "reject", "x", 400);

        assertEquals(1, auditCount(AuditAction.RESTAURANT_APPROVED, id));
        assertEquals(0, auditCount(AuditAction.RESTAURANT_REJECTED, id));

        decide(platform, owner.restaurantId(), "suspend", "Spot check failed", 200);
        decide(platform, owner.restaurantId(), "reactivate", null, 200);
        assertEquals(1, auditCount(AuditAction.RESTAURANT_SUSPENDED, id));
        assertEquals(1, auditCount(AuditAction.RESTAURANT_REACTIVATED, id));
    }

    private long auditCount(AuditAction action, String resourceId) {
        return auditRepository.findAll().stream()
                .filter(e -> e.getAction() == action && resourceId.equals(e.getResourceId()))
                .count();
    }

    @Test
    void anUnknownStatusFilterIsABadRequestNotAnError() throws Exception {
        String platform = platformOwnerToken();
        json(auth(get(BASE + "/restaurants").param("status", "FOO"), platform), 400);
        json(auth(get(BASE + "/restaurants").param("size", "100000"), platform), 200);
        json(auth(get(BASE + "/restaurants").param("page", "-5"), platform), 200);
    }

    @Test
    void twoRestaurantsWithTheSameNameCanBothRegister() throws Exception {
        // Registration used the non-unique slug method, so a second restaurant with a name that slugs the
        // same (for example a rejected owner registering again) failed with a 500 on the unique index.
        String name = "Same Name " + unique();
        String[] slugs = new String[2];
        for (int i = 0; i < 2; i++) {
            String id = unique();
            String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                    {"restaurantName":"%s","restaurantEmail":"r-%s@example.com","restaurantPhone":"9999999999",
                     "ownerName":"Owner %s","ownerEmail":"owner-%s@example.com","password":"Password123"}
                    """.formatted(name, id, id, id)), 200).get("accessToken").asText();
            long restaurantId = json(auth(get("/api/v1/me"), token), 200).get("restaurantId").asLong();
            slugs[i] = restaurantRepository.findById(restaurantId).orElseThrow().getSlug();
        }
        assertFalse(slugs[0].equals(slugs[1]), "the slugs must differ: " + slugs[0] + " / " + slugs[1]);
    }

    @Test
    void aWrongMethodOrContentTypeOnADecisionRouteIsAClientErrorNotAServerError() throws Exception {
        // These used to fall into the catch-all handler: a 500 and an ERROR log for a client mistake.
        String platform = platformOwnerToken();
        Owner owner = registerOwner();
        String path = BASE + "/restaurants/" + owner.restaurantId() + "/reject";

        for (var method : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path))) {
            json(auth(method, platform), 405);
        }

        mockMvc.perform(auth(post(path), platform).contentType(MediaType.TEXT_PLAIN).content("reason=abcdef"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(auth(post(path), platform).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("reason=abcdef"))
                .andExpect(status().isUnsupportedMediaType());

        // None of that changed anything.
        assertEquals(RestaurantStatus.PENDING,
                restaurantRepository.findById(owner.restaurantId()).orElseThrow().getApprovalStatus());
    }

    @Test
    void twoDifferentPlatformOwnersRacingRecordTheWinnerAsReviewer() throws Exception {
        String first = platformOwnerToken();
        String second = platformOwnerToken();
        long firstId = json(auth(get("/api/v1/me"), first), 200).get("id").asLong();
        long secondId = json(auth(get("/api/v1/me"), second), 200).get("id").asLong();

        for (int round = 0; round < 5; round++) {
            Owner owner = registerOwner();
            String path = BASE + "/restaurants/" + owner.restaurantId() + "/approve";
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = pool.submit(() ->
                        mockMvc.perform(auth(post(path), first)).andReturn().getResponse().getStatus());
                Future<Integer> b = pool.submit(() ->
                        mockMvc.perform(auth(post(path), second)).andReturn().getResponse().getStatus());
                int codeA = a.get();
                int codeB = b.get();

                assertEquals(List.of(200, 409), java.util.stream.Stream.of(codeA, codeB).sorted().toList(),
                        "round " + round);
                long winner = codeA == 200 ? firstId : secondId;
                Restaurant stored = restaurantRepository.findById(owner.restaurantId()).orElseThrow();
                assertEquals(RestaurantStatus.APPROVED, stored.getApprovalStatus());
                assertEquals(winner, stored.getReviewedBy().getId(),
                        "the reviewer on record must be the owner whose call succeeded");
                assertEquals(1, auditCount(AuditAction.RESTAURANT_APPROVED, String.valueOf(owner.restaurantId())),
                        "exactly one audit line per restaurant");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
