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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage B: portion sizes, add-ons and spice levels priced on the server, carried through cart and
 * order to the kitchen, plus allergens, dietary labels, flags and the popularity badge.
 *
 * <p>The dish used throughout is a biryani at 320.00 with a REQUIRED portion (Half -80, Full 0,
 * Family +250), up to two OPTIONAL extras (Raita +30, Egg +20, Salan +25) and an optional spice level.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class MenuOptionsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record Shop(String owner, long categoryId) {
    }

    private record Dish(long id, long half, long full, long family, long raita, long egg, long salan,
                        long mild, long hot) {
    }

    private record Table(String qr) {
    }

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

    private MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b, String bearer) {
        return b.header("Authorization", "Bearer " + bearer);
    }

    private MockHttpServletRequestBuilder asGuest(MockHttpServletRequestBuilder b, String token) {
        return b.header(DinerTestSupport.HEADER, token);
    }

    private Shop shop() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String owner = json(withBody(post("/api/v1/auth/register-owner"), null, """
                {"restaurantName":"Resto %s","restaurantEmail":"r-%s@example.com",
                 "restaurantPhone":"9999999999","ownerName":"Owner %s",
                 "ownerEmail":"owner-%s@example.com","password":"Password123"}
                """.formatted(id, id, id, id))).get("accessToken").asText();
        long category = json(withBody(post("/api/categories"), owner, "{\"name\":\"Mains\"}"))
                .get("data").get("id").asLong();
        // Ordering is exercised in several tests, so the restaurant accepts cash like the other order ITs.
        long restaurantId = json(staff(get("/api/v1/me"), owner)).get("restaurantId").asLong();
        mockMvc.perform(withBody(post("/api/v1/restaurants/" + restaurantId + "/payment-settings"), owner,
                "{\"cashEnabled\":true}")).andExpect(status().isCreated());
        return new Shop(owner, category);
    }

    private long createItem(Shop shop, String name, String price) throws Exception {
        return json(withBody(post("/api/menu-items"), shop.owner(),
                "{\"name\":\"" + name + "\",\"price\":" + price + ",\"categoryId\":" + shop.categoryId() + "}"))
                .get("data").get("id").asLong();
    }

    private Table table(Shop shop, String code) throws Exception {
        return new Table(json(withBody(post("/api/v1/tables"), shop.owner(),
                "{\"tableCode\":\"" + code + "\",\"tableName\":\"Table " + code + "\",\"capacity\":4}"))
                .get("qrIdentifier").asText());
    }

    private static final String BIRYANI_OPTIONS = """
            {"groups":[
              {"name":"Portion","kind":"SINGLE","required":true,"options":[
                 {"name":"Half","priceDelta":-80.00},
                 {"name":"Full","priceDelta":0,"defaultOption":true},
                 {"name":"Family","priceDelta":250.00}]},
              {"name":"Extras","kind":"MULTI","required":false,"maxSelect":2,"options":[
                 {"name":"Raita","priceDelta":30.00},
                 {"name":"Egg","priceDelta":20.00},
                 {"name":"Salan","priceDelta":25.00}]},
              {"name":"Spice level","kind":"SINGLE","required":false,"options":[
                 {"name":"Mild","priceDelta":0},
                 {"name":"Hot","priceDelta":0}]}
            ]}
            """;

    private long optionId(JsonNode groups, String group, String option) {
        for (JsonNode g : groups) {
            if (group.equals(g.get("name").asText())) {
                for (JsonNode o : g.get("options")) {
                    if (option.equals(o.get("name").asText())) {
                        return o.get("id").asLong();
                    }
                }
            }
        }
        throw new AssertionError("no option " + group + "/" + option);
    }

    private double optionPrice(JsonNode groups, String option) {
        for (JsonNode g : groups) {
            for (JsonNode o : g.get("options")) {
                if (option.equals(o.get("name").asText())) {
                    return o.get("priceDelta").asDouble();
                }
            }
        }
        throw new AssertionError("no option " + option);
    }

    private Dish biryani(Shop shop) throws Exception {
        long id = createItem(shop, "Biryani", "320.00");
        JsonNode groups = json(withBody(put("/api/menu-items/" + id + "/options"), shop.owner(), BIRYANI_OPTIONS));
        return new Dish(id,
                optionId(groups, "Portion", "Half"), optionId(groups, "Portion", "Full"),
                optionId(groups, "Portion", "Family"), optionId(groups, "Extras", "Raita"),
                optionId(groups, "Extras", "Egg"), optionId(groups, "Extras", "Salan"),
                optionId(groups, "Spice level", "Mild"), optionId(groups, "Spice level", "Hot"));
    }

    private String join(Table t, String name) throws Exception {
        return DinerTestSupport.join(mockMvc, objectMapper, t.qr(), name);
    }

    private int addStatus(Table t, String guest, long dish, int qty, long... options) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long o : options) {
            ids.append(ids.length() == 0 ? "" : ",").append(o);
        }
        return mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + t.qr() + "/cart/items"), null,
                        "{\"menuItemId\":" + dish + ",\"quantity\":" + qty + ",\"optionIds\":[" + ids + "]}"), guest))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode add(Table t, String guest, long dish, int qty, long... options) throws Exception {
        assertEquals(200, addStatus(t, guest, dish, qty, options));
        return cart(t, guest);
    }

    private JsonNode cart(Table t, String guest) throws Exception {
        return json(asGuest(get("/api/public/tables/" + t.qr() + "/cart"), guest));
    }

    private JsonNode publicDish(Table t, long dishId) throws Exception {
        for (JsonNode category : json(get("/api/public/tables/" + t.qr() + "/menu"))) {
            for (JsonNode item : category.get("items")) {
                if (item.get("id").asLong() == dishId) {
                    return item;
                }
            }
        }
        throw new AssertionError("dish not on the public menu");
    }

    private JsonNode place(Table t, String guest) throws Exception {
        return json(asGuest(withBody(post("/api/public/tables/" + t.qr() + "/orders"), null, "{}"), guest));
    }

    // ------------------------------------------------------------ define & show

    @Test
    void anOwnerDefinesOptionsAndGuestsSeeThemOnThePublicMenu() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T1");

        JsonNode item = publicDish(table, dish.id());
        assertEquals(3, item.get("optionGroups").size());
        JsonNode portion = item.get("optionGroups").get(0);
        assertEquals("Portion", portion.get("name").asText());
        assertEquals("SINGLE", portion.get("kind").asText());
        assertTrue(portion.get("required").asBoolean());
        assertEquals(3, portion.get("options").size());
        assertEquals(-80.00, portion.get("options").get(0).get("priceDelta").asDouble(), 0.001);
    }

    @Test
    void savingAgainWithIdsKeepsTheIdsStable() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);

        JsonNode current = json(staff(get("/api/menu-items/" + dish.id() + "/options"), shop.owner()));
        // Rename one option and resend the whole set exactly as received.
        String resent = objectMapper.writeValueAsString(objectMapper.createObjectNode().set("groups", current));
        JsonNode after = json(withBody(put("/api/menu-items/" + dish.id() + "/options"), shop.owner(), resent));

        assertEquals(dish.raita(), optionId(after, "Extras", "Raita"));
        assertEquals(dish.hot(), optionId(after, "Spice level", "Hot"));
    }

    @Test
    void aDishWithNoOptionsBehavesExactlyAsBefore() throws Exception {
        Shop shop = shop();
        long plain = createItem(shop, "Naan", "40.00");
        Table table = table(shop, "T2");
        String guest = join(table, "Rahul");

        // The old request shape, with no optionIds at all.
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/cart/items"), null,
                "{\"menuItemId\":" + plain + ",\"quantity\":2}"), guest)).andExpect(status().isOk());
        JsonNode c = cart(table, guest);
        assertEquals(80.00, c.get("subtotal").asDouble(), 0.001);
        assertEquals(0, c.get("items").get(0).get("options").size());
        assertTrue(publicDish(table, plain).get("optionGroups").isEmpty());
    }

    // ------------------------------------------------------------- server pricing

    @Test
    void theServerPricesTheChoicesAndTheCartShowsThem() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T3");
        String guest = join(table, "Rahul");

        JsonNode c = add(table, guest, dish.id(), 2, dish.family(), dish.raita(), dish.egg(), dish.hot());
        JsonNode line = c.get("items").get(0);

        assertEquals(620.00, line.get("unitPrice").asDouble(), 0.001);   // 320 + 250 + 30 + 20
        assertEquals(1240.00, line.get("lineTotal").asDouble(), 0.001);
        assertEquals(1240.00, c.get("subtotal").asDouble(), 0.001);
        assertEquals(4, line.get("options").size());
        boolean sawRaita = false;
        for (JsonNode o : line.get("options")) {
            if ("Raita".equals(o.get("optionName").asText())) {
                sawRaita = true;
                assertEquals("Extras", o.get("groupName").asText());
                assertEquals(30.00, o.get("priceDelta").asDouble(), 0.001);
            }
        }
        assertTrue(sawRaita);
    }

    @Test
    void aClientCannotNameItsOwnPrice() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T4");
        String guest = join(table, "Rahul");

        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/cart/items"), null,
                "{\"menuItemId\":" + dish.id() + ",\"quantity\":1,\"optionIds\":[" + dish.half()
                        + "],\"unitPrice\":1.00,\"priceDelta\":-9999,\"total\":1}"), guest))
                .andExpect(status().isOk());
        assertEquals(240.00, cart(table, guest).get("items").get(0).get("unitPrice").asDouble(), 0.001);
    }

    @Test
    void aRequiredGroupMustBeChosen() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T5");
        String guest = join(table, "Rahul");

        // No options at all, which is also what an older client would send.
        assertEquals(400, addStatus(table, guest, dish.id(), 1));
        // Extras alone do not satisfy the required portion.
        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.raita()));
        assertEquals(0, cart(table, guest).get("items").size());
    }

    @Test
    void theRulesOfEachGroupAreEnforced() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T6");
        String guest = join(table, "Rahul");

        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.half(), dish.full()));              // two portions
        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.full(), dish.mild(), dish.hot()));  // two spice levels
        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.full(), dish.raita(), dish.egg(), dish.salan())); // max 2 extras
        assertEquals(200, addStatus(table, guest, dish.id(), 1, dish.full(), dish.raita(), dish.egg()));
    }

    @Test
    void anOptionOfAnotherDishOrRestaurantIsRefused() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Dish theirs = biryani(shop());   // a different restaurant's biryani
        Table table = table(shop, "T7");
        String guest = join(table, "Rahul");

        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.full(), theirs.raita()));
        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.full(), 987654321L));
        assertEquals(0, cart(table, guest).get("items").size());
    }

    // -------------------------------------------------------------------- lines

    @Test
    void theSameChoicesMergeAndDifferentChoicesStaySeparateLines() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T8");
        String guest = join(table, "Rahul");

        add(table, guest, dish.id(), 1, dish.full(), dish.raita());
        add(table, guest, dish.id(), 2, dish.raita(), dish.full());   // same choices, other order
        JsonNode c = add(table, guest, dish.id(), 1, dish.half());     // a different line

        assertEquals(2, c.get("items").size());
        int merged = 0;
        for (JsonNode line : c.get("items")) {
            if (line.get("options").size() == 2) {
                merged = line.get("quantity").asInt();
            }
        }
        assertEquals(3, merged);
        assertEquals(3 * 350.00 + 240.00, c.get("subtotal").asDouble(), 0.001);
    }

    @Test
    void stockIsCountedAcrossEveryLineOfTheSameDish() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        long restaurantId = json(staff(get("/api/v1/me"), shop.owner())).get("restaurantId").asLong();
        mockMvc.perform(withBody(post("/api/v1/inventory"), shop.owner(),
                "{\"restaurantId\":" + restaurantId + ",\"menuItemId\":" + dish.id()
                        + ",\"availableQuantity\":3,\"minimumQuantity\":0}")).andExpect(status().is2xxSuccessful());
        Table table = table(shop, "T9");
        String guest = join(table, "Rahul");

        assertEquals(200, addStatus(table, guest, dish.id(), 2, dish.full()));
        // One more of a different line is fine (3 in total)...
        assertEquals(200, addStatus(table, guest, dish.id(), 1, dish.half()));
        // ...but a fourth, on yet another line, is not.
        assertEquals(400, addStatus(table, guest, dish.id(), 1, dish.family()));
    }

    // ---------------------------------------------------------- order and kitchen

    @Test
    void theKitchenAndTheGuestSeeWhatWasChosenAndThePriceIsFrozen() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T10");
        String guest = join(table, "Rahul");
        add(table, guest, dish.id(), 1, dish.family(), dish.raita());   // 320 + 250 + 30 = 600

        JsonNode order = place(table, guest);
        String token = order.get("publicToken").asText();

        JsonNode kitchen = json(staff(get("/api/v1/kitchen/orders/active"), shop.owner()));
        JsonNode item = kitchen.get(0).get("items").get(0);
        assertEquals(600.00, item.get("unitPrice").asDouble(), 0.001);
        assertEquals(2, item.get("options").size());

        JsonNode tracked = json(get("/api/public/orders/by-token/" + token));
        assertEquals(2, tracked.get("items").get(0).get("options").size());

        // The restaurant now raises Raita to 90 and removes Family. The placed order must not move.
        JsonNode groups = json(staff(get("/api/menu-items/" + dish.id() + "/options"), shop.owner()));
        boolean changed = false;
        for (JsonNode g : groups) {
            for (JsonNode o : g.get("options")) {
                if ("Raita".equals(o.get("name").asText())) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) o).put("priceDelta", 90.00);
                    changed = true;
                }
            }
        }
        assertTrue(changed, "the test must really have changed the price");
        String edited = objectMapper.writeValueAsString(objectMapper.createObjectNode().set("groups", groups));
        mockMvc.perform(withBody(put("/api/menu-items/" + dish.id() + "/options"), shop.owner(), edited))
                .andExpect(status().isOk());
        assertEquals(90.00, optionPrice(
                json(staff(get("/api/menu-items/" + dish.id() + "/options"), shop.owner())), "Raita"), 0.001);

        JsonNode after = json(get("/api/public/orders/by-token/" + token));
        assertEquals(600.00, after.get("items").get(0).get("unitPrice").asDouble(), 0.001);
        boolean raitaStill30 = false;
        for (JsonNode o : after.get("items").get(0).get("options")) {
            if ("Raita".equals(o.get("optionName").asText())) {
                raitaStill30 = o.get("priceDelta").asDouble() == 30.00;
            }
        }
        assertTrue(raitaStill30, "an order keeps the price change it was placed with");
    }

    @Test
    void anOptionSoldOutAfterItWasAddedBlocksPlacementWithAClearMessage() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T11");
        String guest = join(table, "Rahul");
        add(table, guest, dish.id(), 1, dish.full(), dish.raita());

        mockMvc.perform(withBody(put("/api/menu-items/" + dish.id() + "/options/" + dish.raita() + "/availability"),
                shop.owner(), "{\"available\":false}")).andExpect(status().isOk());

        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/orders"), null, "{}"), guest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Raita")));
        // Nothing was lost: the cart is intact and the guest can remove the add-on and carry on.
        assertEquals(1, cart(table, guest).get("items").size());
    }

    @Test
    void anOptionDeletedAfterItWasAddedBlocksPlacementToo() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        Table table = table(shop, "T12");
        String guest = join(table, "Rahul");
        add(table, guest, dish.id(), 1, dish.full(), dish.raita());

        String withoutExtras = """
                {"groups":[{"name":"Portion","kind":"SINGLE","required":true,"options":[
                   {"name":"Full","priceDelta":0}]}]}""";
        mockMvc.perform(withBody(put("/api/menu-items/" + dish.id() + "/options"), shop.owner(), withoutExtras))
                .andExpect(status().isOk());

        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/orders"), null, "{}"), guest))
                .andExpect(status().isBadRequest());
        // The cart line still shows what the guest chose and was quoted.
        assertEquals(2, cart(table, guest).get("items").get(0).get("options").size());
    }

    // ----------------------------------------------------------------- validation

    @Test
    void aDiscountThatCouldMakeTheDishFreeIsRefusedWhenSaved() throws Exception {
        Shop shop = shop();
        long id = createItem(shop, "Tea", "50.00");
        mockMvc.perform(withBody(put("/api/menu-items/" + id + "/options"), shop.owner(), """
                {"groups":[{"name":"Size","kind":"SINGLE","required":true,"options":[
                   {"name":"Tiny","priceDelta":-60.00},{"name":"Normal","priceDelta":0}]}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sloppyOptionSetsAreRefused() throws Exception {
        Shop shop = shop();
        long id = createItem(shop, "Tea", "50.00");
        String url = "/api/menu-items/" + id + "/options";

        // duplicate group names
        mockMvc.perform(withBody(put(url), shop.owner(), """
                {"groups":[{"name":"Size","kind":"SINGLE","options":[{"name":"S","priceDelta":0}]},
                           {"name":"size","kind":"SINGLE","options":[{"name":"M","priceDelta":0}]}]}"""))
                .andExpect(status().isBadRequest());
        // duplicate option names in a group
        mockMvc.perform(withBody(put(url), shop.owner(), """
                {"groups":[{"name":"Size","kind":"SINGLE","options":[
                   {"name":"S","priceDelta":0},{"name":"s","priceDelta":5}]}]}"""))
                .andExpect(status().isBadRequest());
        // two pre-selected options in a single-choice group
        mockMvc.perform(withBody(put(url), shop.owner(), """
                {"groups":[{"name":"Size","kind":"SINGLE","options":[
                   {"name":"S","priceDelta":0,"defaultOption":true},{"name":"M","priceDelta":5,"defaultOption":true}]}]}"""))
                .andExpect(status().isBadRequest());
        // a group with no options, a blank name, a missing price change
        mockMvc.perform(withBody(put(url), shop.owner(), "{\"groups\":[{\"name\":\"Size\",\"kind\":\"SINGLE\",\"options\":[]}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"groups\":[{\"name\":\" \",\"kind\":\"SINGLE\",\"options\":[{\"name\":\"S\",\"priceDelta\":0}]}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put(url), shop.owner(),
                "{\"groups\":[{\"name\":\"Size\",\"kind\":\"SINGLE\",\"options\":[{\"name\":\"S\"}]}]}"))
                .andExpect(status().isBadRequest());
        // an id that is not this dish's
        mockMvc.perform(withBody(put(url), shop.owner(), """
                {"groups":[{"id":99999,"name":"Size","kind":"SINGLE","options":[{"name":"S","priceDelta":0}]}]}"""))
                .andExpect(status().isNotFound());

        assertEquals(0, json(staff(get(url), shop.owner())).size(), "no refused save may leave anything behind");
    }

    // -------------------------------------------------------------- tenancy & roles

    @Test
    void anotherRestaurantCannotReadOrChangeADishsOptions() throws Exception {
        Shop mine = shop();
        Dish dish = biryani(mine);
        Shop other = shop();

        mockMvc.perform(staff(get("/api/menu-items/" + dish.id() + "/options"), other.owner()))
                .andExpect(status().isNotFound());
        mockMvc.perform(withBody(put("/api/menu-items/" + dish.id() + "/options"), other.owner(),
                "{\"groups\":[]}")).andExpect(status().isNotFound());
        mockMvc.perform(withBody(put("/api/menu-items/" + dish.id() + "/options/" + dish.raita() + "/availability"),
                other.owner(), "{\"available\":false}")).andExpect(status().isNotFound());

        assertEquals(3, json(staff(get("/api/menu-items/" + dish.id() + "/options"), mine.owner())).size());
    }

    @Test
    void optionsNeedASignInAndAManagementRole() throws Exception {
        Shop shop = shop();
        Dish dish = biryani(shop);
        mockMvc.perform(get("/api/menu-items/" + dish.id() + "/options")).andExpect(status().isUnauthorized());
        mockMvc.perform(withBody(put("/api/menu-items/" + dish.id() + "/options"), null, "{\"groups\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    // ----------------------------------------------- allergens, labels, flags, badge

    @Test
    void allergensLabelsAndFlagsAreDeclaredByStaffAndShownOnlyOnceDeclared() throws Exception {
        Shop shop = shop();
        long declared = createItem(shop, "Paneer Tikka", "280.00");
        long silent = createItem(shop, "Salad", "120.00");
        Table table = table(shop, "T13");

        mockMvc.perform(withBody(put("/api/menu-items/" + declared), shop.owner(), """
                {"name":"Paneer Tikka","price":280.00,"categoryId":%d,
                 "allergens":["SOY","MILK"],"dietaryLabels":["JAIN"],"chefPick":true,"todaysSpecial":true}
                """.formatted(shop.categoryId()))).andExpect(status().isOk());

        JsonNode d = publicDish(table, declared);
        assertTrue(d.get("allergenInfoProvided").asBoolean());
        assertEquals("MILK", d.get("allergens").get(0).get("code").asText());   // enum order, not request order
        assertEquals("Milk", d.get("allergens").get(0).get("label").asText());
        assertEquals("SOY", d.get("allergens").get(1).get("code").asText());
        assertEquals("JAIN", d.get("dietaryLabels").get(0).get("code").asText());
        assertTrue(d.get("chefPick").asBoolean());
        assertTrue(d.get("todaysSpecial").asBoolean());

        // The platform never invents it: a dish nobody declared for says nothing, not "none".
        JsonNode s = publicDish(table, silent);
        assertFalse(s.get("allergenInfoProvided").asBoolean());
        assertEquals(0, s.get("allergens").size());
        assertFalse(s.get("chefPick").asBoolean());
    }

    @Test
    void declaringNoAllergensIsDifferentFromNotDeclaring() throws Exception {
        Shop shop = shop();
        long id = createItem(shop, "Rice", "90.00");
        Table table = table(shop, "T14");
        mockMvc.perform(withBody(put("/api/menu-items/" + id), shop.owner(),
                "{\"name\":\"Rice\",\"price\":90.00,\"categoryId\":" + shop.categoryId() + ",\"allergens\":[]}"))
                .andExpect(status().isOk());
        assertTrue(publicDish(table, id).get("allergenInfoProvided").asBoolean());
    }

    @Test
    void anOlderClientThatSendsNoNewFieldsCannotWipeDeclaredAllergens() throws Exception {
        Shop shop = shop();
        long id = createItem(shop, "Kebab", "300.00");
        Table table = table(shop, "T15");
        mockMvc.perform(withBody(put("/api/menu-items/" + id), shop.owner(),
                "{\"name\":\"Kebab\",\"price\":300.00,\"categoryId\":" + shop.categoryId()
                        + ",\"allergens\":[\"SESAME\"],\"chefPick\":true}")).andExpect(status().isOk());

        // An edit of the price from a client that predates Stage B.
        mockMvc.perform(withBody(put("/api/menu-items/" + id), shop.owner(),
                "{\"name\":\"Kebab\",\"price\":320.00,\"categoryId\":" + shop.categoryId() + "}"))
                .andExpect(status().isOk());

        JsonNode d = publicDish(table, id);
        assertEquals(320.00, d.get("price").asDouble(), 0.001);
        assertEquals("SESAME", d.get("allergens").get(0).get("code").asText());
        assertTrue(d.get("chefPick").asBoolean());
    }

    @Test
    void aNonVegetarianDishCannotBeLabelledVeganOrJain() throws Exception {
        Shop shop = shop();
        long id = createItem(shop, "Rogan Josh", "450.00");
        mockMvc.perform(withBody(put("/api/menu-items/" + id), shop.owner(),
                "{\"name\":\"Rogan Josh\",\"price\":450.00,\"categoryId\":" + shop.categoryId()
                        + ",\"veg\":false,\"dietaryLabels\":[\"VEGAN\"]}")).andExpect(status().isBadRequest());
        mockMvc.perform(withBody(put("/api/menu-items/" + id), shop.owner(),
                "{\"name\":\"Rogan Josh\",\"price\":450.00,\"categoryId\":" + shop.categoryId()
                        + ",\"veg\":false,\"dietaryLabels\":[\"HALAL\"]}")).andExpect(status().isOk());
    }

    @Test
    void anUnknownAllergenCodeIsRefused() throws Exception {
        Shop shop = shop();
        long id = createItem(shop, "Soup", "100.00");
        mockMvc.perform(withBody(put("/api/menu-items/" + id), shop.owner(),
                "{\"name\":\"Soup\",\"price\":100.00,\"categoryId\":" + shop.categoryId()
                        + ",\"allergens\":[\"LAVA\"]}")).andExpect(status().isBadRequest());
    }

    @Test
    void thePopularBadgeComesFromRealOrdersAndNeedsAMinimumOfSales() throws Exception {
        Shop shop = shop();
        long hit = createItem(shop, "Hit", "100.00");
        long quiet = createItem(shop, "Quiet", "100.00");
        Table table = table(shop, "T16");
        String guest = join(table, "Rahul");

        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/cart/items"), null,
                "{\"menuItemId\":" + hit + ",\"quantity\":6}"), guest)).andExpect(status().isOk());
        mockMvc.perform(asGuest(withBody(post("/api/public/tables/" + table.qr() + "/cart/items"), null,
                "{\"menuItemId\":" + quiet + ",\"quantity\":1}"), guest)).andExpect(status().isOk());
        place(table, guest);

        // Read only AFTER the order: the badge is cached for a few minutes per restaurant.
        assertTrue(publicDish(table, hit).get("popular").asBoolean());
        assertFalse(publicDish(table, quiet).get("popular").asBoolean());
    }
}
