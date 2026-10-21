package com.kashmirmenu.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.dto.request.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class SecurityAccessIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void protectedEndpoint_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/menu-items"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withInvalidToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/menu-items")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withValidToken_authenticatesButIsAuthorisedByRole() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("Security Test User")
                .email("security.test@example.com")
                .password("Password123")
                .build();

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        @SuppressWarnings("unchecked")
        String token = ((Map<String, Object>) objectMapper.readValue(response, Map.class))
                .get("accessToken")
                .toString();

        // This test originally asserted that a freshly registered CUSTOMER could call
        // GET /api/menu-items without a 401 or 403. That was the vulnerability (SEC-001): any
        // self-registered account could reach a management endpoint. It passed because nothing
        // checked the role, and it stopped passing - correctly - once something did.
        //
        // What it was really checking is that a VALID token authenticates. That is still worth
        // asserting, and is now stated precisely: the token is accepted (so not 401), and the
        // account is then refused on authorization grounds (403), which is a different answer from
        // "I don't know who you are".
        mockMvc.perform(get("/api/menu-items")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // And the same token is accepted somewhere a customer IS allowed: it identifies the caller.
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    public void swaggerUi_isPubliclyReachableInTestProfile() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}