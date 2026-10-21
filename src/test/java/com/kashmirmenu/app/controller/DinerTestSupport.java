package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cart and order endpoints act as one guest, identified by a token from the join endpoint. Tests
 * that place orders join first and send the token with each request.
 */
final class DinerTestSupport {

    /** The header carrying a guest's token. */
    static final String HEADER = "X-Diner-Token";

    private DinerTestSupport() {
    }

    /** Joins the table as a new guest and returns that guest's token. */
    static String join(MockMvc mockMvc, ObjectMapper objectMapper, String qr, String name) throws Exception {
        String body = mockMvc.perform(post("/api/public/tables/" + qr + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("dinerToken").asText();
    }
}
