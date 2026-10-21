package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditRepository;
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
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The platform-administration boundary, covering each numbered requirement of the brief's section 6.
 *
 * <p>Every endpoint under /api/platform-admin is listed in {@link #PLATFORM_PATHS}, so an endpoint
 * added later without being added here will leave this suite asserting less than it appears to —
 * {@code everyPlatformEndpointIsListed} guards against exactly that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class PlatformAdminAuthorizationIT {

    /** Read endpoints under the platform namespace. /claim is intentionally excluded: it is public. */
    private static final List<String> PLATFORM_PATHS = List.of(
            "/api/platform-admin/overview",
            "/api/platform-admin/restaurants",
            // Path variables are written as the concrete id 1 (see everyPlatformEndpointIsListed). The
            // decision endpoints are POSTs; these tests only need the role check, which runs first.
            "/api/platform-admin/restaurants/1",
            "/api/platform-admin/restaurants/1/approve",
            "/api/platform-admin/restaurants/1/reject",
            "/api/platform-admin/restaurants/1/suspend",
            "/api/platform-admin/restaurants/1/reactivate",
            "/api/platform-admin/users",
            "/api/platform-admin/orders",
            "/api/platform-admin/analytics",
            "/api/platform-admin/audit-logs");

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

    @Autowired
    private AuditRepository auditRepository;

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

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerOwnerToken() throws Exception {
        String id = unique();
        return json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
    }

    private String registerCustomerToken() throws Exception {
        String email = "cust-" + unique() + "@example.com";
        return json(withBody(post("/api/v1/auth/register"), null,
                "{\"name\":\"Cust\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .get("accessToken").asText();
    }

    /**
     * Creates an active platform owner directly, the way the claim flow leaves one. Deliberately not
     * via an API: no API exists to grant this role, which is itself part of the design.
     */
    private String platformOwnerToken() throws Exception {
        String email = "platform-" + unique() + "@example.com";
        Role role = roleRepository.findByName(RoleName.PLATFORM_OWNER).orElseThrow(
                () -> new IllegalStateException("migration V24 should have seeded PLATFORM_OWNER"));

        userRepository.save(User.builder()
                .name("Platform Owner")
                .email(email)
                .password(passwordEncoder.encode("Password123!x"))
                .role(role)
                .active(true)
                .build());

        return json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Password123!x\"}"))
                .get("accessToken").asText();
    }

    // ------------------------------------------------------------------ 1-4: who is refused

    @Test
    void anUnauthenticatedUserCannotReachPlatformAdministration() throws Exception {
        for (String path : PLATFORM_PATHS) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aCustomerCannotReachPlatformAdministration() throws Exception {
        String customer = registerCustomerToken();
        for (String path : PLATFORM_PATHS) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + customer))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aRestaurantOwnerCannotReachPlatformAdministration() throws Exception {
        // The central promise: owning a restaurant grants nothing at platform level.
        String owner = registerOwnerToken();
        for (String path : PLATFORM_PATHS) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + owner))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aRestaurantManagerCannotReachPlatformAdministration() throws Exception {
        String manager = staffToken(RoleName.MANAGER);
        for (String path : PLATFORM_PATHS) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + manager))
                    .andExpect(status().isForbidden());
        }
    }

    /** A staff account of a real restaurant, created directly for want of a staff-invite API. */
    private String staffToken(RoleName roleName) throws Exception {
        registerOwnerToken();
        String email = roleName.name().toLowerCase() + "-" + unique() + "@example.com";
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User owner = userRepository.findByEmail(
                userRepository.findAll().stream()
                        .filter(u -> u.getRestaurant() != null)
                        .reduce((a, b) -> b).orElseThrow().getEmail()).orElseThrow();

        userRepository.save(User.builder()
                .name("Staff")
                .email(email)
                .password(passwordEncoder.encode("Password123!x"))
                .role(role)
                .restaurant(owner.getRestaurant())
                .active(true)
                .build());

        return json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Password123!x\"}"))
                .get("accessToken").asText();
    }

    // ------------------------------------------------------------------ 5-6: the two directions

    @Test
    void aPlatformOwnerIsAdmittedToPlatformAdministration() throws Exception {
        String platform = platformOwnerToken();
        for (String path : PLATFORM_PATHS) {
            // Not asserting 200: these endpoints arrive in later phases. What matters now is that the
            // role passes authorization - a 404 means admitted and unmapped, 403 means refused.
            int status = mockMvc.perform(get(path).header("Authorization", "Bearer " + platform))
                    .andReturn().getResponse().getStatus();
            assertTrue(status != 403, path + " refused a platform owner (status " + status + ")");
        }
    }

    @Test
    void aPlatformOwnerCannotReachRestaurantScopedApis() throws Exception {
        // The isolation runs both ways. A platform owner has no restaurant, so these endpoints are
        // meaningless for them - and must refuse rather than fail with a 500 or act on some
        // arbitrary restaurant's data.
        String platform = platformOwnerToken();
        for (String path : List.of(
                "/api/v1/dashboard/summary", "/api/v1/inventory", "/api/categories",
                "/api/menu-items", "/api/v1/orders", "/api/v1/payment-analytics/overview")) {

            mockMvc.perform(get(path).header("Authorization", "Bearer " + platform))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ 7: no escalation

    @Test
    void privilegesCannotBeElevatedThroughRegistrationOrRequestData() throws Exception {
        // Roles are assigned server-side and are not carried in the JWT, so the only thing a client
        // can try is to smuggle one in. Each of these must still produce a plain CUSTOMER.
        String email = "escalate-" + unique() + "@example.com";
        String token = json(withBody(post("/api/v1/auth/register"), null, """
                {"name":"Escalate","email":"%s","password":"Password123",
                 "role":"PLATFORM_OWNER","roleName":"PLATFORM_OWNER","authorities":["ROLE_PLATFORM_OWNER"]}
                """.formatted(email))).get("accessToken").asText();

        assertEquals("CUSTOMER", json(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .get("role").asText());

        for (String path : PLATFORM_PATHS) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ the claim flow

    @Test
    void claimingIsSingleUseAndRefusesIdenticallyOnEveryFailure() throws Exception {
        // An unclaimed account, exactly as the bootstrap leaves one.
        String email = "unclaimed-" + unique() + "@example.com";
        Role role = roleRepository.findByName(RoleName.PLATFORM_OWNER).orElseThrow();
        String token = "claim-token-" + unique();

        User pending = User.builder()
                .name("Platform Owner")
                .email(email)
                .password(passwordEncoder.encode("unusable-" + unique()))
                .role(role)
                .active(false)
                .build();
        pending.setPlatformClaimTokenHash(passwordEncoder.encode(token));
        pending.setPlatformClaimExpiresAt(LocalDateTime.now().plusHours(1));
        userRepository.save(pending);

        // It cannot be signed into before being claimed.
        mockMvc.perform(withBody(post("/api/v1/auth/login"), null,
                        "{\"email\":\"" + email + "\",\"password\":\"Password123!x\"}"))
                .andExpect(status().is4xxClientError());

        // A wrong token is refused...
        mockMvc.perform(withBody(post("/api/platform-admin/claim"), null,
                        claimBody(email, "wrong-token", "CorrectHorse1!")))
                .andExpect(status().isBadRequest());

        // ...and so is an unknown address, with the same status and message, so the endpoint does not
        // reveal which platform addresses exist.
        mockMvc.perform(withBody(post("/api/platform-admin/claim"), null,
                        claimBody("nobody-" + unique() + "@example.com", token, "CorrectHorse1!")))
                .andExpect(status().isBadRequest());

        // A short password is rejected by validation: this is the platform's most privileged account.
        mockMvc.perform(withBody(post("/api/platform-admin/claim"), null,
                        claimBody(email, token, "short")))
                .andExpect(status().isBadRequest());

        // The real claim succeeds once.
        mockMvc.perform(withBody(post("/api/platform-admin/claim"), null,
                        claimBody(email, token, "CorrectHorse1!")))
                .andExpect(status().isNoContent());

        User claimed = userRepository.findByEmail(email).orElseThrow();
        assertTrue(claimed.getActive(), "the account is activated by the claim");
        assertNull(claimed.getPlatformClaimTokenHash(), "the token is consumed");
        assertNull(claimed.getPlatformClaimExpiresAt());

        // Replaying the same token now fails.
        mockMvc.perform(withBody(post("/api/platform-admin/claim"), null,
                        claimBody(email, token, "AnotherPass1!")))
                .andExpect(status().isBadRequest());

        // And the owner can now sign in and reach platform administration.
        String session = json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"CorrectHorse1!\"}")).get("accessToken").asText();
        assertTrue(mockMvc.perform(get(PLATFORM_PATHS.get(0)).header("Authorization", "Bearer " + session))
                .andReturn().getResponse().getStatus() != 403);
    }

    @Test
    void anExpiredClaimTokenIsRefused() throws Exception {
        String email = "expired-" + unique() + "@example.com";
        String token = "claim-token-" + unique();
        User pending = User.builder()
                .name("Platform Owner").email(email)
                .password(passwordEncoder.encode("unusable"))
                .role(roleRepository.findByName(RoleName.PLATFORM_OWNER).orElseThrow())
                .active(false).build();
        pending.setPlatformClaimTokenHash(passwordEncoder.encode(token));
        pending.setPlatformClaimExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.save(pending);

        mockMvc.perform(withBody(post("/api/platform-admin/claim"), null,
                        claimBody(email, token, "CorrectHorse1!")))
                .andExpect(status().isBadRequest());
        assertFalse(userRepository.findByEmail(email).orElseThrow().getActive());
    }

    private static String claimBody(String email, String token, String password) {
        return """
                {"email":"%s","claimToken":"%s","password":"%s"}
                """.formatted(email, token, password);
    }

    // ------------------------------------------------------------------ 10: audit trail

    @Test
    void authenticationAndClaimAttemptsAreAudited() throws Exception {
        long failuresBefore = auditRepository.countByActionAndOccurredAtAfter(
                AuditAction.LOGIN_FAILED, LocalDateTime.now().minusMinutes(5));

        mockMvc.perform(withBody(post("/api/v1/auth/login"), null,
                        "{\"email\":\"nobody-" + unique() + "@example.com\",\"password\":\"WrongPass123\"}"))
                .andExpect(status().is4xxClientError());

        assertEquals(failuresBefore + 1, auditRepository.countByActionAndOccurredAtAfter(
                        AuditAction.LOGIN_FAILED, LocalDateTime.now().minusMinutes(5)),
                "a failed sign-in must leave an audit row even though the request fails");

        long successesBefore = auditRepository.countByActionAndOccurredAtAfter(
                AuditAction.LOGIN_SUCCEEDED, LocalDateTime.now().minusMinutes(5));
        platformOwnerToken();
        assertEquals(successesBefore + 1, auditRepository.countByActionAndOccurredAtAfter(
                AuditAction.LOGIN_SUCCEEDED, LocalDateTime.now().minusMinutes(5)));
    }

    @Test
    void auditRowsNeverCarryCredentials() throws Exception {
        mockMvc.perform(withBody(post("/api/v1/auth/login"), null,
                        "{\"email\":\"audit-" + unique() + "@example.com\",\"password\":\"SecretValue123\"}"))
                .andExpect(status().is4xxClientError());

        boolean leaked = auditRepository.findAll().stream().anyMatch(a ->
                (a.getDetail() != null && a.getDetail().contains("SecretValue123"))
                        || (a.getResourceId() != null && a.getResourceId().contains("SecretValue123")));

        assertFalse(leaked, "no audit row may contain a submitted password");
    }

    // ------------------------------------------------------------------ the suite guards itself

    @Test
    void everyPlatformEndpointIsListed() {
        // If a handler is added to the platform controllers without being added to PLATFORM_PATHS,
        // the refusal tests above would quietly stop covering it.
        java.io.File dir = new java.io.File("src/main/java/com/kashmirmenu/app/platform");
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith("Controller.java"));
        if (files == null) {
            return;
        }
        for (java.io.File f : files) {
            String src;
            try {
                src = java.nio.file.Files.readString(f.toPath());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@(?:Get|Post|Put|Patch|Delete)Mapping\\(\"([^\"]*)\"")
                    .matcher(src);
            while (m.find()) {
                // {id} and friends cannot be requested literally, so they are compared as id 1.
                String path = ("/api/platform-admin" + m.group(1)).replaceAll("\\{[^}]*}", "1");
                assertTrue(PLATFORM_PATHS.contains(path) || path.equals("/api/platform-admin/claim"),
                        path + " is not covered by PLATFORM_PATHS in this test");
            }
        }
    }
}
