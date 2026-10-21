package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditRepository;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.repository.RoleRepository;
import com.kashmirmenu.app.repository.UserRepository;
import com.kashmirmenu.app.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A decision that fails part-way must leave nothing behind: not a changed status, and not an audit line
 * claiming it happened. Audit lines are committed in their own transaction, so they have to be written
 * only after the decision has committed; this forces a failure late in the decision to prove it.
 */
@SpringBootTest(properties = "app.restaurant-approval.required=true")
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class RestaurantDecisionFailureIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditRepository auditRepository;

    @MockitoSpyBean private NotificationService notificationService;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String platformOwnerToken() throws Exception {
        String email = "platform-" + unique() + "@example.com";
        Role role = roleRepository.findByName(RoleName.PLATFORM_OWNER).orElseThrow();
        userRepository.save(User.builder().name("Platform Owner").email(email)
                .password(passwordEncoder.encode("Password123!x")).role(role).active(true).build());

        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123!x\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private long registerRestaurant() throws Exception {
        String id = unique();
        String body = mockMvc.perform(post("/api/v1/auth/register-owner").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                                """.formatted(id, id, id, id)))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("accessToken").asText();

        JsonNode me = objectMapper.readTree(mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        return me.get("restaurantId").asLong();
    }

    @Test
    void aDecisionThatFailsLateChangesNothingAndLeavesNoAuditLine() throws Exception {
        String platform = platformOwnerToken();
        long restaurantId = registerRestaurant();

        doThrow(new RuntimeException("simulated failure while notifying the owner"))
                .when(notificationService).createNotification(any(), any(), any(), any(), any());

        int status = mockMvc.perform(post("/api/platform-admin/restaurants/" + restaurantId + "/approve")
                        .header("Authorization", "Bearer " + platform))
                .andReturn().getResponse().getStatus();

        assertEquals(500, status);
        assertEquals(RestaurantStatus.PENDING,
                restaurantRepository.findById(restaurantId).orElseThrow().getApprovalStatus());
        assertEquals(null, restaurantRepository.findById(restaurantId).orElseThrow().getReviewedAt());
        assertEquals(0, auditRepository.findAll().stream()
                .filter(e -> e.getAction() == AuditAction.RESTAURANT_APPROVED
                        && String.valueOf(restaurantId).equals(e.getResourceId()))
                .count(), "a decision that rolled back must not be in the audit log");
    }
}
