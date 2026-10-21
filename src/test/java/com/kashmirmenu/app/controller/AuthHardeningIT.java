package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Sign-in, tokens, registration and staff rules: the doors an attacker tries first. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class AuthHardeningIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuditRepository auditRepository;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, String token, String json) {
        b.contentType(MediaType.APPLICATION_JSON).content(json);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(b).andReturn().getResponse().getStatus();
    }

    private JsonNode json(MockHttpServletRequestBuilder b) throws Exception {
        var r = mockMvc.perform(b).andReturn().getResponse();
        if (r.getStatus() >= 400) {
            throw new AssertionError("Setup call failed with " + r.getStatus() + ": " + r.getContentAsString());
        }
        return objectMapper.readTree(r.getContentAsString());
    }

    private record Owner(String email, String password, String token) {
    }

    private Owner owner() throws Exception {
        String id = unique();
        String email = "owner-" + id + "@example.com";
        String token = json(body(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com","restaurantPhone":"9999999999",
                 "ownerName":"Owner %s","ownerEmail":"%s","password":"Password123"}
                """.formatted(id, id, id, email))).get("accessToken").asText();
        return new Owner(email, "Password123", token);
    }

    private int login(String email, String password) throws Exception {
        return status(body(post("/api/v1/auth/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    // ------------------------------------------------------------------ guessing passwords

    @Test
    void repeatedWrongPasswordsLockThatAccountEvenForTheRightPasswordAndSparesOthers() throws Exception {
        Owner victim = owner();
        Owner bystander = owner();

        for (int i = 0; i < 10; i++) {
            assertEquals(401, login(victim.email(), "wrong-password-" + i), "attempt " + i);
        }
        // The eleventh is refused outright, whatever it carries.
        MvcResult refused = mockMvc.perform(body(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + victim.email() + "\",\"password\":\"" + victim.password() + "\"}")).andReturn();
        assertEquals(429, refused.getResponse().getStatus());
        assertNotNull(refused.getResponse().getHeader("Retry-After"));
        assertTrue(refused.getResponse().getContentAsString().toLowerCase().contains("too many"));
        // Case and spacing do not buy a fresh set of guesses.
        assertEquals(429, login(victim.email().toUpperCase(), "wrong"));

        // Somebody else is not affected.
        assertEquals(200, login(bystander.email(), bystander.password()));

        // The refusals are on the record.
        long throttled = auditRepository.findAll().stream()
                .filter(e -> e.getAction() == AuditAction.LOGIN_FAILED && "Refused: too many failed attempts".equals(e.getDetail())).count();
        assertTrue(throttled >= 2, "refused attempts must be audited, saw " + throttled);
    }

    @Test
    void aFewMistypesNeverLockAnAccountAndASuccessClearsTheCount() throws Exception {
        Owner o = owner();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5; i++) {
                assertEquals(401, login(o.email(), "nope-" + i));
            }
            assertEquals(200, login(o.email(), o.password()), "five mistakes then the right password signs in");
        }
    }

    @Test
    void anUnknownAccountAnswersLikeAWrongPassword() throws Exception {
        Owner o = owner();
        MvcResult known = mockMvc.perform(body(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + o.email() + "\",\"password\":\"wrong-password\"}")).andReturn();
        MvcResult unknown = mockMvc.perform(body(post("/api/v1/auth/login"), null,
                "{\"email\":\"nobody-" + unique() + "@example.com\",\"password\":\"wrong-password\"}")).andReturn();
        assertEquals(known.getResponse().getStatus(), unknown.getResponse().getStatus());
        assertEquals(objectMapper.readTree(known.getResponse().getContentAsString()).get("message"),
                objectMapper.readTree(unknown.getResponse().getContentAsString()).get("message"),
                "the message must not reveal whether the email has an account");
    }

    @Test
    void enormousPasswordsAreRefusedBeforeAnyHashingHappens() throws Exception {
        String huge = "A".repeat(20_000);
        assertEquals(400, login("someone@example.com", huge));
        assertEquals(400, status(body(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"R","restaurantEmail":"r@example.com","restaurantPhone":"9999999999",
                 "ownerName":"O","ownerEmail":"o-%s@example.com","password":"%s"}
                """.formatted(unique(), huge))));
        assertEquals(400, status(body(post("/api/v1/auth/register"), null,
                "{\"name\":\"C\",\"email\":\"c-" + unique() + "@example.com\",\"password\":\"" + huge + "\"}")));
    }

    // ------------------------------------------------------------------ tokens

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void forgedTokensAreRejected() throws Exception {
        Owner o = owner();
        String real = o.token();
        String[] parts = real.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        // alg=none, with and without a signature segment
        String none = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + parts[1];
        // A genuine token whose subject was swapped for another account, signature kept
        String swapped = parts[0] + "." + b64(payload.replaceFirst("\"sub\":\"[^\"]*\"", "\"sub\":\"admin@example.com\"")) + "." + parts[2];
        // The same claims signed with a different key
        String wrongKey = parts[0] + "." + parts[1] + "." + b64("not-the-signature-not-the-signature");
        // An HS256 header on a token whose signature part is empty
        String unsigned = parts[0] + "." + parts[1] + ".";

        for (String bad : new String[]{none + ".", none, swapped, wrongKey, unsigned, "Bearer", "null", "undefined", real + "x", real.substring(0, real.length() - 4)}) {
            assertEquals(401, status(get("/api/v1/me").header("Authorization", "Bearer " + bad)),
                    "forged token accepted: " + bad.substring(0, Math.min(30, bad.length())));
        }
        // The real one still works.
        assertEquals(200, status(get("/api/v1/me").header("Authorization", "Bearer " + real)));
        // And tokens are not accepted from anywhere but the Authorization header.
        assertEquals(401, status(get("/api/v1/me?access_token=" + real)));
    }

    @Test
    void signingOutRevokesTheTokenEverywhere() throws Exception {
        Owner o = owner();
        assertEquals(200, status(get("/api/v1/me").header("Authorization", "Bearer " + o.token())));
        Thread.sleep(1100); // the revocation point has one-second precision
        assertEquals(204, status(post("/api/v1/auth/logout").header("Authorization", "Bearer " + o.token())));
        assertEquals(401, status(get("/api/v1/me").header("Authorization", "Bearer " + o.token())), "the signed-out token is dead");
        assertEquals(401, status(post("/api/v1/auth/logout").header("Authorization", "Bearer " + o.token())));
    }

    // ------------------------------------------------------------------ registration and roles

    @Test
    void registrationCannotChooseARole() throws Exception {
        String email = "c-" + unique() + "@example.com";
        String token = json(body(post("/api/v1/auth/register"), null,
                "{\"name\":\"C\",\"email\":\"" + email + "\",\"password\":\"Password123\",\"role\":\"ADMIN\",\"roleName\":\"PLATFORM_OWNER\",\"restaurantId\":1,\"active\":true}"))
                .get("accessToken").asText();
        assertEquals("CUSTOMER", json(get("/api/v1/me").header("Authorization", "Bearer " + token)).get("role").asText());
        assertEquals(403, status(get("/api/platform-admin/overview").header("Authorization", "Bearer " + token)));

        String id = unique();
        String ownerToken = json(body(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"R %s","restaurantEmail":"r-%s@example.com","restaurantPhone":"9999999999",
                 "ownerName":"O","ownerEmail":"o-%s@example.com","password":"Password123","role":"PLATFORM_OWNER","approvalStatus":"APPROVED"}
                """.formatted(id, id, id))).get("accessToken").asText();
        JsonNode me = json(get("/api/v1/me").header("Authorization", "Bearer " + ownerToken));
        assertEquals("RESTAURANT_OWNER", me.get("role").asText());
        assertEquals(403, status(get("/api/platform-admin/overview").header("Authorization", "Bearer " + ownerToken)));
    }

    @Test
    void aManagerCannotCreateOrPromoteHigherRolesOrTouchTheOwner() throws Exception {
        Owner o = owner();
        String managerEmail = "mgr-" + unique() + "@example.com";
        json(body(post("/api/v1/staff"), o.token(),
                "{\"name\":\"Manager\",\"email\":\"" + managerEmail + "\",\"role\":\"MANAGER\",\"password\":\"Manager2026pw\"}"));
        String manager = json(body(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + managerEmail + "\",\"password\":\"Manager2026pw\"}")).get("accessToken").asText();

        for (String role : new String[]{"RESTAURANT_OWNER", "ADMIN", "PLATFORM_OWNER"}) {
            int code = status(body(post("/api/v1/staff"), manager,
                    "{\"name\":\"Boss\",\"email\":\"boss-" + unique() + "@example.com\",\"role\":\"" + role + "\",\"password\":\"Boss2026pwd\"}"));
            assertTrue(code >= 400 && code < 500, "a manager creating a " + role + " answered " + code);
        }
        // Nor can a manager reset the owner's password and walk into the owner's account.
        JsonNode staff = json(get("/api/v1/staff").header("Authorization", "Bearer " + manager));
        JsonNode list = staff.isArray() ? staff : staff.get("data");
        long ownerId = -1;
        for (JsonNode s : list) {
            if (o.email().equalsIgnoreCase(s.path("email").asText())) {
                ownerId = s.get("id").asLong();
            }
        }
        if (ownerId > 0) {
            int code = status(body(put("/api/v1/staff/" + ownerId + "/password"), manager, "{\"password\":\"Takeover2026pw\"}"));
            assertTrue(code >= 400 && code < 500, "a manager resetting the owner's password answered " + code);
            assertEquals(200, login(o.email(), o.password()), "the owner's password is unchanged");
        }
    }
}
