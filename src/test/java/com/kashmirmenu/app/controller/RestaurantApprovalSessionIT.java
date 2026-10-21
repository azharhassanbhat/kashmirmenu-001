package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A platform owner whose session expires part-way through (the token's own lifetime runs out) is
 * refused on every platform call, changes nothing, and can sign in again. The token lifetime is set to
 * two seconds for this class only.
 */
@SpringBootTest(properties = {"app.restaurant-approval.required=true", "jwt.access-token-expiration=2000"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class RestaurantApprovalSessionIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private int status(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(token == null ? request : request.header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void anExpiredPlatformSessionIsRefusedChangesNothingAndCanBeReplacedBySigningInAgain() throws Exception {
        String email = "platform-" + unique() + "@example.com";
        Role role = roleRepository.findByName(RoleName.PLATFORM_OWNER).orElseThrow();
        userRepository.save(User.builder().name("Platform Owner").email(email)
                .password(passwordEncoder.encode("Password123!x")).role(role).active(true).build());

        String id = unique();
        String ownerBody = mockMvc.perform(post("/api/v1/auth/register-owner").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                                """.formatted(id, id, id, id)))
                .andReturn().getResponse().getContentAsString();
        String ownerToken = objectMapper.readTree(ownerBody).get("accessToken").asText();
        long restaurantId = objectMapper.readTree(mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString()).get("restaurantId").asLong();

        String token = login(email, "Password123!x");
        assertEquals(200, status(get("/api/platform-admin/overview"), token), "a fresh session works");

        Thread.sleep(2600);   // past the two-second lifetime

        int overview = status(get("/api/platform-admin/overview"), token);
        int approve = status(post("/api/platform-admin/restaurants/" + restaurantId + "/approve"), token);
        assertTrue(overview == 401 || overview == 403, "expired token on a read: " + overview);
        assertTrue(approve == 401 || approve == 403, "expired token on a decision: " + approve);
        assertEquals(RestaurantStatus.PENDING,
                restaurantRepository.findById(restaurantId).orElseThrow().getApprovalStatus(),
                "an expired session must not change anything");

        String fresh = login(email, "Password123!x");
        assertEquals(200, status(post("/api/platform-admin/restaurants/" + restaurantId + "/approve"), fresh),
                "signing in again restores access");
        assertEquals(RestaurantStatus.APPROVED,
                restaurantRepository.findById(restaurantId).orElseThrow().getApprovalStatus());
    }
}
