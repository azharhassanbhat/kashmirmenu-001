package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.service.DinerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 1: guests join a table with a name and get their own secret. Nothing else changes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class TableSessionIT {

    private static final String TOKEN = "X-Diner-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DinerSessionRepository dinerSessions;

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private MockHttpServletRequestBuilder jsonRequest(
            MockHttpServletRequestBuilder builder, String bearer, String body) {
        builder.contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return builder;
    }

    private String registerOwner() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id);
        return json(jsonRequest(post("/api/v1/auth/register-owner"), null, body))
                .get("accessToken").asText();
    }

    private String createTable(String ownerToken, String code) throws Exception {
        return json(jsonRequest(post("/api/v1/tables"), ownerToken,
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"))
                .get("qrIdentifier").asText();
    }

    private JsonNode join(String qr, String name) throws Exception {
        return json(jsonRequest(post("/api/public/tables/" + qr + "/join"), null,
                "{\"name\":\"" + name + "\"}"));
    }

    @Test
    void aGuestJoinsWithJustAName_andCanBeIdentifiedAfterwards() throws Exception {
        String qr = createTable(registerOwner(), "T1");

        mockMvc.perform(jsonRequest(post("/api/public/tables/" + qr + "/join"), null, "{\"name\":\"  Rahul  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Rahul"))
                .andExpect(jsonPath("$.dinerToken").isString())
                .andExpect(jsonPath("$.visitId").isString());

        JsonNode joined = join(qr, "Amit");
        String token = joined.get("dinerToken").asText();

        mockMvc.perform(get("/api/public/tables/" + qr + "/me").header(TOKEN, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Amit"))
                .andExpect(jsonPath("$.visitId").value(joined.get("visitId").asText()))
                // The token is a credential: it is given once and never repeated back.
                .andExpect(jsonPath("$.dinerToken").doesNotExist());
    }

    @Test
    void guestsAtOneTableShareAVisit_butEachHasTheirOwnSecret() throws Exception {
        String qr = createTable(registerOwner(), "T1");

        JsonNode rahul = join(qr, "Rahul");
        JsonNode amit = join(qr, "Amit");
        JsonNode priya = join(qr, "Priya");

        assertEquals(rahul.get("visitId").asText(), amit.get("visitId").asText());
        assertEquals(rahul.get("visitId").asText(), priya.get("visitId").asText());

        assertNotEquals(rahul.get("dinerToken").asText(), amit.get("dinerToken").asText());
        assertNotEquals(amit.get("dinerToken").asText(), priya.get("dinerToken").asText());

        // Each token identifies exactly its own guest.
        mockMvc.perform(get("/api/public/tables/" + qr + "/me").header(TOKEN, amit.get("dinerToken").asText()))
                .andExpect(jsonPath("$.displayName").value("Amit"));
        mockMvc.perform(get("/api/public/tables/" + qr + "/me").header(TOKEN, priya.get("dinerToken").asText()))
                .andExpect(jsonPath("$.displayName").value("Priya"));
    }

    @Test
    void twoGuestsWithTheSameName_areToldApart() throws Exception {
        String qr = createTable(registerOwner(), "T1");

        JsonNode first = join(qr, "Rahul");
        JsonNode second = join(qr, "rahul");
        JsonNode third = join(qr, "Rahul");

        assertEquals("Rahul", first.get("displayName").asText());
        assertEquals("rahul (2)", second.get("displayName").asText());
        assertEquals("Rahul (3)", third.get("displayName").asText());

        // Identity is the token, not the name: each still resolves to its own guest.
        mockMvc.perform(get("/api/public/tables/" + qr + "/me").header(TOKEN, second.get("dinerToken").asText()))
                .andExpect(jsonPath("$.displayName").value("rahul (2)"));
    }

    @Test
    void differentTablesHaveDifferentVisits() throws Exception {
        String owner = registerOwner();
        String qr1 = createTable(owner, "T1");
        String qr2 = createTable(owner, "T2");

        assertNotEquals(join(qr1, "Rahul").get("visitId").asText(),
                join(qr2, "Rahul").get("visitId").asText());
    }

    @Test
    void aTokenOnlyWorksAtItsOwnTable() throws Exception {
        String owner = registerOwner();
        String qr1 = createTable(owner, "T1");
        String qr2 = createTable(owner, "T2");

        String token = join(qr1, "Rahul").get("dinerToken").asText();

        mockMvc.perform(get("/api/public/tables/" + qr1 + "/me").header(TOKEN, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/tables/" + qr2 + "/me").header(TOKEN, token))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownOrMissingTokenLooksTheSame() throws Exception {
        String qr = createTable(registerOwner(), "T1");
        join(qr, "Rahul");

        String forged = DinerIdentity.newToken();

        String unknown = mockMvc.perform(get("/api/public/tables/" + qr + "/me").header(TOKEN, forged))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(get("/api/public/tables/" + qr + "/me"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertEquals(objectMapper.readTree(unknown).get("message"), objectMapper.readTree(missing).get("message"));
    }

    @Test
    void theRawTokenIsNeverStored_onlyItsHash() throws Exception {
        String qr = createTable(registerOwner(), "T1");
        String token = join(qr, "Rahul").get("dinerToken").asText();

        assertTrue(dinerSessions.findByTokenHash(token).isEmpty(),
                "the raw token must not be what the database holds");
        assertTrue(dinerSessions.findByTokenHash(DinerIdentity.hash(token)).isPresent());
    }

    @Test
    void badNamesAreRefusedWithAClearMessage() throws Exception {
        String qr = createTable(registerOwner(), "T1");

        for (String body : new String[]{
                "{\"name\":\"\"}",
                "{\"name\":\"   \"}",
                "{}",
                "{\"name\":\"<script>alert(1)</script>\"}",
                "{\"name\":\"" + "a".repeat(41) + "\"}"}) {
            mockMvc.perform(jsonRequest(post("/api/public/tables/" + qr + "/join"), null, body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anUnknownTableCannotBeJoined() throws Exception {
        mockMvc.perform(jsonRequest(post("/api/public/tables/" + UUID.randomUUID() + "/join"), null,
                        "{\"name\":\"Rahul\"}"))
                .andExpect(status().isNotFound());
    }
}
