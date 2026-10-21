package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class GoogleReviewsIT {

    private static final String PLACE_ID = "ChIJN1t_tDeuEmsRUsoyG83frY4";

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

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token);
    }

    private Owner registerOwner() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id);
        String token = json(post("/api/v1/auth/register-owner")
                .contentType(MediaType.APPLICATION_JSON).content(body)).get("accessToken").asText();
        long restaurantId = json(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .get("restaurantId").asLong();
        return new Owner(token, restaurantId);
    }

    @Test
    void ownerSetsPlaceId_dinersGetReviewLink_otherOwnersCannotChangeIt() throws Exception {
        Owner owner = registerOwner();
        String qr = json(authed(post("/api/v1/tables"), owner.token(),
                "{\"tableCode\":\"T1\",\"tableName\":\"Table 1\",\"capacity\":4}"))
                .get("qrIdentifier").asText();

        // nothing configured yet: no link, endpoint still answers
        mockMvc.perform(get("/api/public/tables/" + qr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleReviewUrl").doesNotExist());
        mockMvc.perform(get("/api/public/tables/" + qr + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeReviewUrl").doesNotExist());

        // malformed ids are rejected
        mockMvc.perform(authed(put("/api/restaurants/" + owner.restaurantId() + "/google-reviews"),
                        owner.token(), "{\"googlePlaceId\":\"not a place id!\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(put("/api/restaurants/" + owner.restaurantId() + "/google-reviews"),
                        owner.token(), "{\"googlePlaceId\":\"" + PLACE_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googlePlaceId").value(PLACE_ID));

        String link = "https://search.google.com/local/writereview?placeid=" + PLACE_ID;
        mockMvc.perform(get("/api/public/tables/" + qr))
                .andExpect(jsonPath("$.googleReviewUrl").value(link))
                .andExpect(jsonPath("$.googleMapsUrl").value(
                        org.hamcrest.Matchers.containsString("query_place_id=" + PLACE_ID)));
        // no API key in tests: links only, no rating, and the Maps page is offered instead
        mockMvc.perform(get("/api/public/tables/" + qr + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeReviewUrl").value(link))
                .andExpect(jsonPath("$.mapsUrl").value(
                        org.hamcrest.Matchers.containsString("query_place_id=" + PLACE_ID)))
                .andExpect(jsonPath("$.rating").doesNotExist());

        // tenant isolation
        Owner other = registerOwner();
        mockMvc.perform(authed(put("/api/restaurants/" + owner.restaurantId() + "/google-reviews"),
                        other.token(), "{\"googlePlaceId\":\"ChIJxxxxxxxxxxxxxxxxxxxx\"}"))
                .andExpect(status().isForbidden());

        // blank clears it
        mockMvc.perform(authed(put("/api/restaurants/" + owner.restaurantId() + "/google-reviews"),
                        owner.token(), "{\"googlePlaceId\":\"\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/tables/" + qr))
                .andExpect(jsonPath("$.googleReviewUrl").doesNotExist())
                .andExpect(jsonPath("$.googleMapsUrl").doesNotExist());
    }
}
