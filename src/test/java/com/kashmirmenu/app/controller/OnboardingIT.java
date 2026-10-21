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

/** The get-started checklist: every step is ticked because the thing exists, never because anyone said so. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class OnboardingIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Owner(String token, long restaurantId) {
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
        long restaurantId = json(as(get("/api/v1/me"), token)).get("restaurantId").asLong();
        return new Owner(token, restaurantId);
    }

    private JsonNode progress(Owner o) throws Exception {
        return json(as(get("/api/v1/onboarding"), o.token()));
    }

    private boolean done(JsonNode p, String key) {
        for (JsonNode s : p.get("steps")) {
            if (key.equals(s.get("key").asText())) {
                return s.get("done").asBoolean();
            }
        }
        throw new AssertionError("no step " + key);
    }

    private long category(Owner o) throws Exception {
        return json(withBody(post("/api/categories"), o.token(), "{\"name\":\"Mains\"}")).get("data").get("id").asLong();
    }

    private void dish(Owner o, long categoryId, boolean available) throws Exception {
        mockMvc.perform(withBody(post("/api/menu-items"), o.token(),
                "{\"name\":\"Dal\",\"price\":150.00,\"available\":" + available + ",\"categoryId\":" + categoryId + "}"))
                .andExpect(status().is2xxSuccessful());
    }

    private String table(Owner o) throws Exception {
        return json(withBody(post("/api/v1/tables"), o.token(),
                "{\"tableCode\":\"T1\",\"tableName\":\"Table T1\",\"capacity\":4}")).get("qrIdentifier").asText();
    }

    // ------------------------------------------------------------------ progress

    @Test
    void aBrandNewRestaurantHasDoneNothing() throws Exception {
        JsonNode p = progress(owner());
        assertEquals(0, p.get("requiredDone").asInt());
        assertEquals(5, p.get("requiredTotal").asInt());
        assertEquals(0, p.get("percent").asInt());
        assertFalse(p.get("complete").asBoolean());
        assertEquals("categories", p.get("nextKey").asText());
        for (JsonNode s : p.get("steps")) {
            assertFalse(s.get("done").asBoolean(), s.get("key").asText());
        }
    }

    @Test
    void eachStepIsTickedByTheThingItAsksFor() throws Exception {
        Owner o = owner();

        long cat = category(o);
        JsonNode p = progress(o);
        assertTrue(done(p, "categories"));
        assertFalse(done(p, "dishes"));
        assertEquals("dishes", p.get("nextKey").asText());

        dish(o, cat, true);
        p = progress(o);
        assertTrue(done(p, "dishes"));
        assertEquals("tables", p.get("nextKey").asText());

        String qr = table(o);
        p = progress(o);
        assertTrue(done(p, "tables"));
        assertEquals("payments", p.get("nextKey").asText());

        mockMvc.perform(withBody(post("/api/v1/restaurants/" + o.restaurantId() + "/payment-settings"), o.token(),
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        p = progress(o);
        assertTrue(done(p, "payments"));
        assertEquals("testOrder", p.get("nextKey").asText());
        assertFalse(p.get("complete").asBoolean());

        // a real order through the guest flow
        String guest = DinerTestSupport.join(mockMvc, objectMapper, qr, "Rahul");
        long dishId = json(as(get("/api/menu-items?size=10"), o.token())).get("data").get("items").get(0).get("id").asLong();
        mockMvc.perform(withBody(post("/api/public/tables/" + qr + "/cart/items").header(DinerTestSupport.HEADER, guest), null,
                "{\"menuItemId\":" + dishId + ",\"quantity\":1}")).andExpect(status().isOk());
        mockMvc.perform(withBody(post("/api/public/tables/" + qr + "/orders").header(DinerTestSupport.HEADER, guest), null, "{}"))
                .andExpect(status().isCreated());

        p = progress(o);
        assertTrue(done(p, "testOrder"));
        assertTrue(p.get("complete").asBoolean());
        assertEquals(100, p.get("percent").asInt());
        assertTrue(p.get("nextKey").isNull());
        assertFalse(done(p, "team"), "the team step is optional and still open");
    }

    @Test
    void aDishGuestsCannotOrderDoesNotCount() throws Exception {
        Owner o = owner();
        dish(o, category(o), false);   // switched off
        assertFalse(done(progress(o), "dishes"));
    }

    @Test
    void hiringSomeoneTicksTheOptionalTeamStepAndTheOwnerAloneDoesNot() throws Exception {
        Owner o = owner();
        assertFalse(done(progress(o), "team"));
        String email = "chef-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(withBody(post("/api/v1/staff"), o.token(),
                "{\"name\":\"C\",\"email\":\"" + email + "\",\"role\":\"CHEF\",\"password\":\"Chef2026pw\"}"))
                .andExpect(status().isCreated());
        assertTrue(done(progress(o), "team"));
    }

    // ------------------------------------------------------------------ tenancy and access

    @Test
    void oneRestaurantsProgressDoesNotLeakIntoAnothers() throws Exception {
        Owner a = owner();
        Owner b = owner();
        dish(a, category(a), true);
        table(a);

        assertTrue(done(progress(a), "dishes"));
        assertFalse(done(progress(b), "categories"));
        assertFalse(done(progress(b), "dishes"));
        assertFalse(done(progress(b), "tables"));
    }

    @Test
    void theChecklistNeedsASignInAndAManagementRole() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding")).andExpect(status().isUnauthorized());

        Owner o = owner();
        String email = "waiter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(withBody(post("/api/v1/staff"), o.token(),
                "{\"name\":\"W\",\"email\":\"" + email + "\",\"role\":\"WAITER\",\"password\":\"Waiter2026\"}"))
                .andExpect(status().isCreated());
        String waiter = json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Waiter2026\"}")).get("accessToken").asText();
        mockMvc.perform(as(get("/api/v1/onboarding"), waiter)).andExpect(status().isForbidden());
    }
}
