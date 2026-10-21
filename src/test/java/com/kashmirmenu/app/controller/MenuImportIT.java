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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Loading a menu from CSV, through the real controllers, security and database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class MenuImportIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String GOOD = """
            name,category,price,veg,description,available,prep_time
            Paneer Tikka,Starters,280,veg,Grilled cottage cheese,yes,15
            Rogan Josh,Mains,450.50,non-veg,Slow-cooked lamb,yes,25
            Kahwa,Drinks,50,veg,,no,5
            """;

    // ------------------------------------------------------------------ helpers

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String bearer, String body) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return b;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String bearer) {
        return b.header("Authorization", "Bearer " + bearer);
    }

    private String owner() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
    }

    private String body(String csv) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("csv", csv));
    }

    private JsonNode preview(String owner, String csv) throws Exception {
        return json(withBody(post("/api/menu-items/import/preview"), owner, body(csv)));
    }

    private JsonNode importCsv(String owner, String csv) throws Exception {
        return json(withBody(post("/api/menu-items/import"), owner, body(csv)));
    }

    private int importStatus(String owner, String csv) throws Exception {
        return mockMvc.perform(withBody(post("/api/menu-items/import"), owner, body(csv)))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode dishes(String owner) throws Exception {
        return json(as(get("/api/menu-items?size=200"), owner)).get("data").get("items");
    }

    private JsonNode categories(String owner) throws Exception {
        return json(as(get("/api/categories?size=200"), owner)).get("data").get("items");
    }

    private JsonNode dish(String owner, String name) throws Exception {
        for (JsonNode d : dishes(owner)) {
            if (name.equals(d.get("name").asText())) {
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ preview

    @Test
    void aPreviewShowsWhatWouldHappenAndChangesNothing() throws Exception {
        String owner = owner();
        JsonNode p = preview(owner, GOOD);

        assertTrue(p.get("canImport").asBoolean());
        assertEquals(3, p.get("toCreate").asInt());
        assertEquals(0, p.get("alreadyExist").asInt());
        assertEquals(3, p.get("newCategories").size());
        assertEquals("NEW", p.get("rows").get(0).get("status").asText());
        assertEquals(2, p.get("rows").get(0).get("line").asInt());
        assertTrue(p.get("rows").get(0).get("newCategory").asBoolean());

        assertEquals(0, dishes(owner).size(), "a preview must not create anything");
        assertEquals(0, categories(owner).size());
    }

    // ------------------------------------------------------------------ importing

    @Test
    void importingCreatesCategoriesAndDishesExactlyAsTheFileSaysAndOnlyForThisRestaurant() throws Exception {
        String owner = owner();
        String other = owner();

        JsonNode result = importCsv(owner, GOOD);
        assertEquals(3, result.get("created").asInt());
        assertEquals(0, result.get("skippedExisting").asInt());
        assertEquals(3, result.get("categoriesCreated").asInt());

        assertEquals(3, categories(owner).size());
        JsonNode rogan = dish(owner, "Rogan Josh");
        assertEquals(450.50, rogan.get("price").asDouble(), 0.001);
        assertFalse(rogan.get("veg").asBoolean());
        assertEquals("Mains", rogan.get("categoryName").asText());
        assertEquals("Slow-cooked lamb", rogan.get("description").asText());
        assertEquals(25, rogan.get("preparationTime").asInt());
        assertTrue(rogan.get("available").asBoolean());
        assertTrue(rogan.get("active").asBoolean());

        JsonNode kahwa = dish(owner, "Kahwa");
        assertFalse(kahwa.get("available").asBoolean());
        assertTrue(dish(owner, "Paneer Tikka").get("veg").asBoolean());

        assertEquals(0, dishes(other).size(), "another restaurant's menu is untouched");
        assertEquals(0, categories(other).size());
    }

    @Test
    void importingTheSameFileTwiceIsHarmless() throws Exception {
        String owner = owner();
        importCsv(owner, GOOD);

        JsonNode p = preview(owner, GOOD);
        assertEquals(0, p.get("toCreate").asInt());
        assertEquals(3, p.get("alreadyExist").asInt());
        assertEquals("EXISTS", p.get("rows").get(0).get("status").asText());
        assertFalse(p.get("rows").get(0).get("newCategory").asBoolean());

        JsonNode again = importCsv(owner, GOOD);
        assertEquals(0, again.get("created").asInt());
        assertEquals(3, again.get("skippedExisting").asInt());
        assertEquals(0, again.get("categoriesCreated").asInt());
        assertEquals(3, dishes(owner).size());
    }

    @Test
    void anExistingDishIsNeverOverwrittenEvenWithADifferentPrice() throws Exception {
        String owner = owner();
        importCsv(owner, GOOD);

        importCsv(owner, "name,category,price,veg\nRogan Josh,Mains,999,veg\nNaan,Breads,40,veg\n");

        JsonNode rogan = dish(owner, "Rogan Josh");
        assertEquals(450.50, rogan.get("price").asDouble(), 0.001);
        assertFalse(rogan.get("veg").asBoolean());
        assertNotNull(dish(owner, "Naan"));
    }

    @Test
    void anExistingCategoryIsReusedWhateverTheCapitalisation() throws Exception {
        String owner = owner();
        mockMvc.perform(withBody(post("/api/categories"), owner, "{\"name\":\"Mains\"}")).andExpect(status().is2xxSuccessful());

        JsonNode p = preview(owner, "name,category,price,veg\nDal,mains,150,veg\n");
        assertEquals(0, p.get("newCategories").size());
        JsonNode result = importCsv(owner, "name,category,price,veg\nDal,mains,150,veg\n");
        assertEquals(0, result.get("categoriesCreated").asInt());
        assertEquals(1, categories(owner).size());
        assertEquals("Mains", dish(owner, "Dal").get("categoryName").asText());
    }

    @Test
    void theSameDishNameInAnotherRestaurantDoesNotCountAsExisting() throws Exception {
        String a = owner();
        String b = owner();
        importCsv(a, GOOD);

        JsonNode p = preview(b, GOOD);
        assertEquals(3, p.get("toCreate").asInt());
        assertEquals(0, p.get("alreadyExist").asInt());
        assertEquals(3, importCsv(b, GOOD).get("created").asInt());
    }

    // ------------------------------------------------------------------ refusing bad files

    @Test
    void aFileWithAnyProblemImportsNothingAndEveryProblemIsListedWithItsLine() throws Exception {
        String owner = owner();
        String csv = "name,category,price,veg\nGood,Mains,100,veg\n,Mains,100,veg\nBad,Mains,abc,veg\nNoVeg,Mains,10,\n";

        JsonNode p = preview(owner, csv);
        assertFalse(p.get("canImport").asBoolean());
        assertEquals(0, p.get("rows").size());
        assertEquals(3, p.get("problems").size());
        assertEquals(3, p.get("problems").get(0).get("line").asInt());
        assertEquals("name", p.get("problems").get(0).get("field").asText());
        assertEquals(4, p.get("problems").get(1).get("line").asInt());
        assertEquals(5, p.get("problems").get(2).get("line").asInt());

        assertEquals(400, importStatus(owner, csv));
        assertEquals(0, dishes(owner).size(), "the valid row must not be imported on its own");
        assertEquals(0, categories(owner).size());
    }

    @Test
    void theVegColumnCannotBeLeftOutAndNothingIsAssumed() throws Exception {
        String owner = owner();
        JsonNode p = preview(owner, "name,category,price\nKebab,Starters,300\n");
        assertFalse(p.get("canImport").asBoolean());
        assertEquals("veg", p.get("problems").get(0).get("field").asText());
        assertEquals(400, importStatus(owner, "name,category,price\nKebab,Starters,300\n"));
        assertEquals(0, dishes(owner).size());
    }

    @Test
    void theImportReadsTheFileItselfAndNeverTrustsAPreview() throws Exception {
        String owner = owner();
        // A preview of a good file, then a commit of a different, bad one: the commit is judged on its own text.
        assertTrue(preview(owner, GOOD).get("canImport").asBoolean());
        assertEquals(400, importStatus(owner, "name,category,price,veg\nX,Y,-5,veg\n"));
        assertEquals(0, dishes(owner).size());
    }

    @Test
    void emptyOrOversizedFilesAreRefused() throws Exception {
        String owner = owner();
        assertEquals(400, mockMvc.perform(withBody(post("/api/menu-items/import/preview"), owner, "{\"csv\":\"\"}"))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(withBody(post("/api/menu-items/import/preview"), owner, "{}"))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(withBody(post("/api/menu-items/import"), owner, body("x".repeat(1_000_001))))
                .andReturn().getResponse().getStatus());
        assertEquals(400, importStatus(owner, "name,category,price,veg\n"));
    }

    @Test
    void aFileOverTheRowLimitIsRefusedWholesale() throws Exception {
        String owner = owner();
        StringBuilder sb = new StringBuilder("name,category,price,veg\n");
        for (int i = 0; i <= 500; i++) {
            sb.append("Dish ").append(i).append(",Mains,10,veg\n");
        }
        assertFalse(preview(owner, sb.toString()).get("canImport").asBoolean());
        assertEquals(400, importStatus(owner, sb.toString()));
        assertEquals(0, dishes(owner).size());
    }

    // ------------------------------------------------------------------ access

    @Test
    void importingNeedsASignInAndAManagementRole() throws Exception {
        mockMvc.perform(withBody(post("/api/menu-items/import/preview"), null, body(GOOD))).andExpect(status().isUnauthorized());
        mockMvc.perform(withBody(post("/api/menu-items/import"), null, body(GOOD))).andExpect(status().isUnauthorized());

        String owner = owner();
        String email = "waiter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(withBody(post("/api/v1/staff"), owner,
                "{\"name\":\"W\",\"email\":\"" + email + "\",\"role\":\"WAITER\",\"password\":\"Waiter2026\"}"))
                .andExpect(status().isCreated());
        String waiter = json(withBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Waiter2026\"}")).get("accessToken").asText();

        mockMvc.perform(withBody(post("/api/menu-items/import/preview"), waiter, body(GOOD))).andExpect(status().isForbidden());
        mockMvc.perform(withBody(post("/api/menu-items/import"), waiter, body(GOOD))).andExpect(status().isForbidden());
        assertEquals(0, dishes(owner).size());
    }

    @Test
    void unicodeDishNamesRoundTrip() throws Exception {
        String owner = owner();
        importCsv(owner, "name,category,price,veg\n\u06A9\u0634\u0645\u06CC\u0631\u06CC \u0642\u06C1\u0648\u06C1,\u0686\u0627\u0626\u06D2,40,veg\n");
        assertNotNull(dish(owner, "\u06A9\u0634\u0645\u06CC\u0631\u06CC \u0642\u06C1\u0648\u06C1"));
    }
}
