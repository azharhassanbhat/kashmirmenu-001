package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * List endpoints take a sort field and a page size from the request. The sort field must come from a short
 * allowlist (an unrestricted one lets a caller order rows by columns they cannot read, such as the owner's
 * email or password hash, and so learn them by comparison) and the page size must be bounded. Hostile values
 * are treated as data and refused, never turned into part of a query.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class ListQuerySecurityIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String ownerToken() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register-owner").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                         "restaurantPhone":"9999999999","ownerName":"Owner %s",
                         "ownerEmail":"owner-%s@example.com","password":"Password123"}
                        """.formatted(id, id, id, id))).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private MvcResult list(String token, String path, String query) throws Exception {
        return mockMvc.perform(get(path + query).header("Authorization", "Bearer " + token)).andReturn();
    }

    @Test
    void sortingByAHiddenOrNestedColumnIsRefusedOnEveryListing() throws Exception {
        String token = ownerToken();
        String[] hostile = {
                "owner.password", "owner.email", "restaurant.owner.password",
                "password", "id;drop table users", "id,(select 1)", "name' or '1'='1", "1",
        };
        for (String path : new String[]{"/api/restaurants", "/api/categories", "/api/menu-items"}) {
            for (String sort : hostile) {
                int status = list(token, path, "?sortBy=" + java.net.URLEncoder.encode(sort, "UTF-8")).getResponse().getStatus();
                assertEquals(400, status, path + " must refuse sortBy='" + sort + "'");
            }
        }
    }

    @Test
    void allowedSortFieldsStillWork() throws Exception {
        String token = ownerToken();
        assertEquals(200, list(token, "/api/restaurants", "?sortBy=name&direction=desc").getResponse().getStatus());
        assertEquals(200, list(token, "/api/categories", "?sortBy=displayOrder").getResponse().getStatus());
        assertEquals(200, list(token, "/api/menu-items", "?sortBy=price&direction=DESC").getResponse().getStatus());
        // The default every client uses.
        assertEquals(200, list(token, "/api/menu-items", "?page=0&size=500&sortBy=id&direction=asc").getResponse().getStatus());
    }

    @Test
    void pageSizeIsBoundedAndNegativePagesAreHarmless() throws Exception {
        String token = ownerToken();
        for (String path : new String[]{"/api/restaurants", "/api/categories", "/api/menu-items"}) {
            MvcResult r = list(token, path, "?size=2000000000");
            assertEquals(200, r.getResponse().getStatus(), path);
            JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
            JsonNode data = body.has("data") ? body.get("data") : body;
            assertTrue(data.get("size").asInt() <= 500, path + " page size must be capped, was " + data.get("size"));
            assertEquals(200, list(token, path, "?page=-5&size=-3").getResponse().getStatus(), path);
        }
    }

    @Test
    void sqlLookingSearchTextIsJustText() throws Exception {
        String token = ownerToken();
        for (String q : new String[]{"' OR '1'='1", "%'; DROP TABLE restaurants;--", "\\", "%", "_"}) {
            MvcResult r = list(token, "/api/restaurants/search", "?name=" + java.net.URLEncoder.encode(q, "UTF-8"));
            int status = r.getResponse().getStatus();
            assertTrue(status == 200 || status == 400, "search for '" + q + "' answered " + status);
        }
        // The table still exists and still answers.
        assertEquals(200, list(token, "/api/restaurants", "").getResponse().getStatus());
    }
}
