package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.kashmirmenu.app.TestProfiles;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Joining needs no login, so a visit admits only so many guests. Uses a small cap to prove it. */
@SpringBootTest(properties = "app.table-session.max-diners=3")
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class TableSessionLimitIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aVisitStopsAdmittingGuestsAtTheCap() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String register = """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id);

        String token = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/register-owner")
                        .contentType(MediaType.APPLICATION_JSON).content(register))
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        String qr = objectMapper.readTree(mockMvc.perform(post("/api/v1/tables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}"))
                .andReturn().getResponse().getContentAsString()).get("qrIdentifier").asText();

        for (String name : new String[]{"Rahul", "Amit", "Priya"}) {
            mockMvc.perform(post("/api/public/tables/" + qr + "/join")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/public/tables/" + qr + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fourth\"}"))
                .andExpect(status().isBadRequest());
    }
}
