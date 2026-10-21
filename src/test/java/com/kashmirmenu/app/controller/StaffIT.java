package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage D: a restaurant's own team. Creating staff, who may do what, switching accounts off and on,
 * password resets, tenant isolation, and that a switched-off account is refused immediately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class StaffIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Owner(String token, long userId) {
    }

    private record Member(long id, String email, String password, String token) {
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

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String bearer) {
        return b.header("Authorization", "Bearer " + bearer);
    }

    private Owner owner() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long userId = json(as(get("/api/v1/me"), token)).get("id").asLong();
        return new Owner(token, userId);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private JsonNode create(String bearer, String name, String email, String role, String password) throws Exception {
        return json(withBody(post("/api/v1/staff"), bearer,
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"role\":\"" + role
                        + "\",\"password\":\"" + password + "\"}"));
    }

    private int createStatus(String bearer, String name, String email, String role, String password) throws Exception {
        return mockMvc.perform(withBody(post("/api/v1/staff"), bearer,
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"role\":\"" + role
                        + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse().getStatus();
    }

    private int loginStatus(String email, String password) throws Exception {
        return mockMvc.perform(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse().getStatus();
    }

    private String login(String email, String password) throws Exception {
        return json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).get("accessToken").asText();
    }

    private Member hire(Owner owner, String role) throws Exception {
        String email = uniqueEmail(role.toLowerCase());
        String password = "Staff2026pw";
        JsonNode created = create(owner.token(), role + " Person", email, role, password);
        return new Member(created.get("id").asLong(), email, password, login(email, password));
    }

    private int put(String bearer, String path, String body) throws Exception {
        return mockMvc.perform(withBody(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path), bearer, body)).andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ creating

    @Test
    void anOwnerHiresAWaiterWhoCanThenSignInAndDoFloorWorkButNotManageStaff() throws Exception {
        Owner owner = owner();
        String email = uniqueEmail("waiter");

        JsonNode created = create(owner.token(), "Sana Waiter", email, "WAITER", "Waiter2026");
        assertEquals("WAITER", created.get("role").asText());
        assertTrue(created.get("active").asBoolean());
        assertEquals(email, created.get("email").asText());

        String token = login(email, "Waiter2026");
        mockMvc.perform(as(get("/api/v1/service-requests"), token)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/staff"), token)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/billing-settings"), token)).andExpect(status().isForbidden());
    }

    @Test
    void theResponseNeverContainsAPasswordOrAHash() throws Exception {
        Owner owner = owner();
        String raw = mockMvc.perform(withBody(post("/api/v1/staff"), owner.token(),
                        "{\"name\":\"Amit\",\"email\":\"" + uniqueEmail("c") + "\",\"role\":\"CHEF\",\"password\":\"Chef2026pw\"}"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(raw.toLowerCase().contains("password"));
        assertFalse(raw.contains("$2"));
        assertFalse(raw.contains("Chef2026pw"));

        String list = mockMvc.perform(as(get("/api/v1/staff"), owner.token()))
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.toLowerCase().contains("password"));
        assertFalse(list.contains("$2"));
    }

    @Test
    void theTeamListShowsTheOwnerAndStaffWithWhatTheCallerMayDo() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");

        JsonNode team = json(as(get("/api/v1/staff"), owner.token()));
        assertEquals(2, team.size());
        for (JsonNode m : team) {
            if (m.get("id").asLong() == owner.userId()) {
                assertTrue(m.get("you").asBoolean());
                assertFalse(m.get("canManage").asBoolean());
                assertEquals("RESTAURANT_OWNER", m.get("role").asText());
            } else {
                assertEquals(waiter.id(), m.get("id").asLong());
                assertTrue(m.get("canManage").asBoolean());
                assertFalse(m.get("you").asBoolean());
            }
        }
    }

    @Test
    void anEmailThatIsAlreadyRegisteredIsRefused() throws Exception {
        Owner owner = owner();
        String email = uniqueEmail("dup");
        assertEquals(201, createStatus(owner.token(), "A", email, "CHEF", "Chef2026pw"));
        assertEquals(409, createStatus(owner.token(), "B", email, "WAITER", "Waiter2026"));
    }

    @Test
    void weakOrMalformedInputIsRefusedAndNothingIsCreated() throws Exception {
        Owner owner = owner();
        assertEquals(400, createStatus(owner.token(), "A", uniqueEmail("x"), "CHEF", "short1"));
        assertEquals(400, createStatus(owner.token(), "A", uniqueEmail("x"), "CHEF", "onlyletters"));
        assertEquals(400, createStatus(owner.token(), "A", uniqueEmail("x"), "CHEF", "12345678"));
        assertEquals(400, createStatus(owner.token(), "A", "waiter12@example.com", "CHEF", "waiter12"));   // = email local part
        assertEquals(400, createStatus(owner.token(), " ", uniqueEmail("x"), "CHEF", "Chef2026pw"));
        assertEquals(400, createStatus(owner.token(), "A", "not-an-email", "CHEF", "Chef2026pw"));
        assertEquals(400, mockMvc.perform(withBody(post("/api/v1/staff"), owner.token(),
                "{\"name\":\"A\",\"email\":\"" + uniqueEmail("x") + "\",\"password\":\"Chef2026pw\"}"))
                .andReturn().getResponse().getStatus());   // no role
        assertEquals(400, mockMvc.perform(withBody(post("/api/v1/staff"), owner.token(),
                "{\"name\":\"A\",\"email\":\"" + uniqueEmail("x") + "\",\"role\":\"EMPEROR\",\"password\":\"Chef2026pw\"}"))
                .andReturn().getResponse().getStatus());   // unknown role

        assertEquals(1, json(as(get("/api/v1/staff"), owner.token())).size(), "only the owner remains");
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    void noOneCanCreateAnOwnerAdminPlatformOwnerOrDinerAccountThroughTheProduct() throws Exception {
        Owner owner = owner();
        for (String role : new String[]{"RESTAURANT_OWNER", "ADMIN", "PLATFORM_OWNER", "CUSTOMER"}) {
            assertEquals(403, createStatus(owner.token(), "X", uniqueEmail("x"), role, "Strong2026pw"), role);
        }
        assertEquals(1, json(as(get("/api/v1/staff"), owner.token())).size());
    }

    @Test
    void aManagerManagesTheFloorAndKitchenButNotOtherManagersOrTheOwner() throws Exception {
        Owner owner = owner();
        Member manager = hire(owner, "MANAGER");
        Member otherManager = hire(owner, "MANAGER");

        // allowed
        assertEquals(201, createStatus(manager.token(), "Chef", uniqueEmail("chef"), "CHEF", "Chef2026pw"));
        assertEquals(201, createStatus(manager.token(), "Cash", uniqueEmail("cash"), "CASHIER", "Cash2026pw"));
        // refused
        assertEquals(403, createStatus(manager.token(), "M", uniqueEmail("m"), "MANAGER", "Manager2026"));
        assertEquals(403, put(manager.token(), "/api/v1/staff/" + otherManager.id(), "{\"active\":false}"));
        assertEquals(403, put(manager.token(), "/api/v1/staff/" + owner.userId(), "{\"role\":\"WAITER\"}"));
        assertEquals(403, put(manager.token(), "/api/v1/staff/" + otherManager.id() + "/password",
                "{\"password\":\"Hijack2026pw\"}"));

        // and the manager's refused attempts changed nothing
        assertEquals(200, loginStatus(otherManager.email(), otherManager.password()));
    }

    @Test
    void aManagerCannotPromoteSomeoneToManager() throws Exception {
        Owner owner = owner();
        Member manager = hire(owner, "MANAGER");
        Member waiter = hire(owner, "WAITER");
        assertEquals(403, put(manager.token(), "/api/v1/staff/" + waiter.id(), "{\"role\":\"MANAGER\"}"));
        assertEquals("WAITER", roleOf(owner, waiter.id()));
    }

    @Test
    void floorAndKitchenRolesCannotReachTheStaffEndpointsAtAll() throws Exception {
        Owner owner = owner();
        for (String role : new String[]{"WAITER", "CHEF", "CASHIER"}) {
            Member m = hire(owner, role);
            mockMvc.perform(as(get("/api/v1/staff"), m.token())).andExpect(status().isForbidden());
            assertEquals(403, createStatus(m.token(), "X", uniqueEmail("x"), "WAITER", "Waiter2026"), role);
            assertEquals(403, put(m.token(), "/api/v1/staff/" + owner.userId(), "{\"active\":false}"), role);
        }
    }

    @Test
    void theStaffEndpointsNeedASignIn() throws Exception {
        mockMvc.perform(get("/api/v1/staff")).andExpect(status().isUnauthorized());
        mockMvc.perform(withBody(post("/api/v1/staff"), null,
                "{\"name\":\"A\",\"email\":\"a@example.com\",\"role\":\"CHEF\",\"password\":\"Chef2026pw\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nobodyCanChangeTheirOwnAccountHere() throws Exception {
        Owner owner = owner();
        Member manager = hire(owner, "MANAGER");
        assertEquals(400, put(owner.token(), "/api/v1/staff/" + owner.userId(), "{\"active\":false}"));
        assertEquals(400, put(manager.token(), "/api/v1/staff/" + manager.id(), "{\"role\":\"WAITER\"}"));
        assertEquals(400, put(manager.token(), "/api/v1/staff/" + manager.id() + "/password",
                "{\"password\":\"Another2026pw\"}"));
        assertEquals(200, loginStatus(manager.email(), manager.password()));
    }

    // ------------------------------------------------------------------ switching off and on

    @Test
    void aDeactivatedAccountIsRefusedAtOnceEvenWithATokenAlreadyInHand() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");
        mockMvc.perform(as(get("/api/v1/service-requests"), waiter.token())).andExpect(status().isOk());

        assertEquals(200, put(owner.token(), "/api/v1/staff/" + waiter.id(), "{\"active\":false}"));

        // The token they already hold is refused straight away (not after it expires)...
        mockMvc.perform(as(get("/api/v1/service-requests"), waiter.token())).andExpect(status().isUnauthorized());
        // ...and they cannot sign in again.
        assertNotEquals(200, loginStatus(waiter.email(), waiter.password()));
        assertFalse(isActive(owner, waiter.id()));
    }

    @Test
    void aReactivatedAccountWorksAgain() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");
        put(owner.token(), "/api/v1/staff/" + waiter.id(), "{\"active\":false}");
        assertNotEquals(200, loginStatus(waiter.email(), waiter.password()));

        assertEquals(200, put(owner.token(), "/api/v1/staff/" + waiter.id(), "{\"active\":true}"));
        assertEquals(200, loginStatus(waiter.email(), waiter.password()));
        assertTrue(isActive(owner, waiter.id()));
    }

    @Test
    void changingARoleTakesEffectOnTheVeryNextRequestWithTheSameToken() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");
        mockMvc.perform(as(get("/api/v1/kitchen/orders/active"), waiter.token())).andExpect(status().isOk());

        assertEquals(200, put(owner.token(), "/api/v1/staff/" + waiter.id(), "{\"role\":\"CASHIER\"}"));
        assertEquals("CASHIER", roleOf(owner, waiter.id()));

        // A cashier does not work the kitchen board; the same token is now refused there. Either answer is a
        // refusal: 403 (wrong role) or, when the role change fell in the same second as the sign-in, 401, because
        // token revocation compares whole seconds and MySQL rounds the stored time up to the next one.
        int refused = mockMvc.perform(as(get("/api/v1/kitchen/orders/active"), waiter.token()))
                .andReturn().getResponse().getStatus();
        assertTrue(refused == 401 || refused == 403, "the old role's token must be refused, got " + refused);
    }

    @Test
    void renamingChangesOnlyTheName() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");
        assertEquals(200, put(owner.token(), "/api/v1/staff/" + waiter.id(), "{\"name\":\"  Sana K  \"}"));
        JsonNode m = find(owner, waiter.id());
        assertEquals("Sana K", m.get("name").asText());
        assertEquals("WAITER", m.get("role").asText());
        assertTrue(m.get("active").asBoolean());
        assertEquals(400, put(owner.token(), "/api/v1/staff/" + waiter.id(), "{\"name\":\"  \"}"));
    }

    // ------------------------------------------------------------------ passwords

    @Test
    void aPasswordResetWorksForTheNewPasswordAndNotTheOldOne() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");

        assertEquals(204, put(owner.token(), "/api/v1/staff/" + waiter.id() + "/password",
                "{\"password\":\"Brand2026new\"}"));

        assertNotEquals(200, loginStatus(waiter.email(), waiter.password()));
        assertEquals(200, loginStatus(waiter.email(), "Brand2026new"));
    }

    @Test
    void aResetToAWeakPasswordIsRefusedAndTheOldOneStillWorks() throws Exception {
        Owner owner = owner();
        Member waiter = hire(owner, "WAITER");
        assertEquals(400, put(owner.token(), "/api/v1/staff/" + waiter.id() + "/password", "{\"password\":\"abc\"}"));
        assertEquals(400, put(owner.token(), "/api/v1/staff/" + waiter.id() + "/password", "{\"password\":\"onlyletters\"}"));
        assertEquals(200, loginStatus(waiter.email(), waiter.password()));
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    void oneRestaurantCannotSeeOrChangeAnotherRestaurantsStaff() throws Exception {
        Owner a = owner();
        Owner b = owner();
        Member waiterOfA = hire(a, "WAITER");

        for (JsonNode m : json(as(get("/api/v1/staff"), b.token()))) {
            assertNotEquals(waiterOfA.id(), m.get("id").asLong());
        }
        assertEquals(404, put(b.token(), "/api/v1/staff/" + waiterOfA.id(), "{\"active\":false}"));
        assertEquals(404, put(b.token(), "/api/v1/staff/" + waiterOfA.id(), "{\"role\":\"MANAGER\"}"));
        assertEquals(404, put(b.token(), "/api/v1/staff/" + waiterOfA.id() + "/password",
                "{\"password\":\"Hijack2026pw\"}"));
        assertEquals(404, put(b.token(), "/api/v1/staff/" + a.userId(), "{\"active\":false}"));
        assertEquals(404, put(b.token(), "/api/v1/staff/999999999", "{\"active\":false}"));

        assertTrue(isActive(a, waiterOfA.id()), "the other restaurant's attempts changed nothing");
        assertEquals(200, loginStatus(waiterOfA.email(), waiterOfA.password()));
    }

    @Test
    void staffHiredByOneRestaurantWorkOnlyForThatRestaurant() throws Exception {
        Owner a = owner();
        Owner b = owner();
        Member waiterOfA = hire(a, "WAITER");

        // The waiter's board is their own restaurant's: restaurant B's requests never show on it.
        JsonNode board = json(as(get("/api/v1/service-requests"), waiterOfA.token()));
        assertEquals(0, board.size());
        // And the account cannot be used to reach B's staff list (not management anyway).
        mockMvc.perform(as(get("/api/v1/staff"), waiterOfA.token())).andExpect(status().isForbidden());
        assertNotNull(b);
    }

    // ------------------------------------------------------------------ helpers over the list

    private JsonNode find(Owner owner, long id) throws Exception {
        for (JsonNode m : json(as(get("/api/v1/staff"), owner.token()))) {
            if (m.get("id").asLong() == id) {
                return m;
            }
        }
        throw new AssertionError("staff member " + id + " not in the list");
    }

    private String roleOf(Owner owner, long id) throws Exception {
        return find(owner, id).get("role").asText();
    }

    private boolean isActive(Owner owner, long id) throws Exception {
        return find(owner, id).get("active").asBoolean();
    }
}
