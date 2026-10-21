package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.dto.request.LoginRequest;
import com.kashmirmenu.app.dto.request.RegisterRequest;
import com.kashmirmenu.app.dto.request.RegisterRestaurantOwnerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class AuthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLogin_succeeds() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("Jane Customer")
                .email("jane.customer@example.com")
                .password("Password123")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));

        LoginRequest loginRequest = LoginRequest.builder()
                .email("jane.customer@example.com")
                .password("Password123")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void register_duplicateEmail_returnsConflict() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("Dup User")
                .email("dup.user@example.com")
                .password("Password123")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void login_wrongPassword_returnsUnauthorized() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("Bad Login User")
                .email("bad.login@example.com")
                .password("Password123")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = LoginRequest.builder()
                .email("bad.login@example.com")
                .password("WrongPassword")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerOwner_succeeds() throws Exception {
        RegisterRestaurantOwnerRequest request = new RegisterRestaurantOwnerRequest();
        request.setRestaurantName("Test Diner");
        request.setRestaurantEmail("diner@example.com");
        request.setRestaurantPhone("+1-555-0100");
        request.setOwnerName("Owner Person");
        request.setOwnerEmail("owner.person@example.com");
        request.setPassword("Password123");

        mockMvc.perform(post("/api/v1/auth/register-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}