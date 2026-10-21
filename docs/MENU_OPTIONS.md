# Menu richness (Stage B): options, allergens, flags, popularity

Portion sizes, add-ons and spice levels, declared allergens and dietary labels, chef's pick, today's special, and a "Popular" badge from real orders.

**Status: written but not yet run.** Apply Stage A first (Stage B edits some of the same CSS and API files). Nothing here has been built against Spring or MySQL.

## Run the automated checks

```bash
./gradlew test                                   # H2: OptionPricingTest, PopularityAndTagsTest, MenuOptionsIT (+ Stage A tests)
docker compose -f docker-compose.test.yml up -d --wait
./gradlew mysqlTest                              # proves migration V35 matches the entities
docker compose -f docker-compose.test.yml down -v
cd frontend && npm install && npm run typecheck && npm test
```

## How it works

**One option model.** A dish has *option groups*; each group is SINGLE (pick one) or MULTI (pick several, optional maximum), optionally *required*; each option has a *price change* from the dish's base price (negative for a smaller portion).
- Portion size: SINGLE, required: Half -80, Full 0, Family +250
- Add-ons: MULTI, optional, up to 2: Raita +30, Egg +20
- Spice level: SINGLE, optional: Mild 0, Hot 0

**The server decides the price.** A guest sends only option ids. The server loads the dish's groups, enforces every rule (required, one-vs-many, maximum, sold out, belongs to this dish), and works out `unitPrice = base + sum of changes`. Names and prices are copied from the server's own rows. A client cannot invent an option or name its own price (extra fields in the request are ignored).

**Lines.** A cart line is the dish plus its exact options. Same choices (in any order) merge and add quantity; different choices are separate lines. Stock is counted across all lines of the same dish.

**Frozen at order time.** Choices are copied onto the cart line, then onto the order line (name and price change as they were). Editing or deleting an option later never changes a placed order. Placement re-checks each chosen option is still available; if one was sold out or deleted since it was added, the order is refused with a message naming it, and the cart is left intact.

**Saving options** is a full replace reconciled by id, so ids stay stable. Refused with a clear message: duplicate group or option names, two pre-selected options in a single-choice group, more than 10 groups / 60 options, and any set whose cheapest combination would cost zero or less.

**Allergens and dietary labels** come from fixed lists (Allergen: milk, egg, peanut, tree nuts, gluten, soy, fish, shellfish, sesame, mustard. Dietary: vegan, Jain, gluten free, dairy free, nut free, halal). They are only ever what the restaurant declares. A dish nobody declared shows nothing; a dish declared with none shows "No allergens declared". A non-vegetarian dish cannot be labelled vegan or Jain. Requests that omit these fields leave stored values unchanged, so an older client cannot wipe them.

**Popular** = top 3 dishes by units sold in the last 30 days (cancelled orders excluded), minimum 5 units; cached 5 minutes per restaurant. It carries no money, so a stale value is harmless. Prices are never cached.

## Database change (additive only)

`V35__menu_options_allergens_flags.sql`: new columns on `menu_items` (`chef_pick`, `todays_special`, `allergens`, `dietary_labels`) and on `cart_items` (`options_key`, default ''); new tables `menu_option_groups`, `menu_options`, `cart_item_options`, `order_item_options`. Existing rows get safe defaults and behave as before.
Rollback before it matters: drop the four new tables, drop the added columns, delete the `flyway_schema_history` row for version 35.

## API

| Who | Method and path | Purpose |
|---|---|---|
| Guest | `POST /api/public/tables/{qr}/cart/items` `{"menuItemId":1,"quantity":2,"optionIds":[11,20]}` | `optionIds` is new and optional. 400 with a plain message if the choices break a rule |
| Guest | `GET /api/public/tables/{qr}/menu` | Items now carry `optionGroups`, `chefPick`, `todaysSpecial`, `allergens`, `dietaryLabels`, `allergenInfoProvided`, `popular` |
| Management | `GET /api/menu-items/{id}/options` | The dish's groups, including sold-out options |
| Management | `PUT /api/menu-items/{id}/options` `{"groups":[...]}` | Replace the whole set (ids echoed back keep their identity) |
| Management | `PUT /api/menu-items/{id}/options/{optionId}/availability` `{"available":false}` | Mark one option sold out / back |
| Management | `PUT /api/menu-items/{id}` (existing) | Now also accepts `chefPick`, `todaysSpecial`, `allergens`, `dietaryLabels` |

Cart, order, tracking and kitchen responses now include `options: [{groupName, optionName, priceDelta}]` per line.

## Manual test (browser)

1. Owner: **Menu items**, click **Options** on a dish. Add **+ Portion sizes** (Half -80, Full 0 pre-selected, Family +250), **+ Add-ons** (Raita 30, Egg 20, max 2). Save.
2. Try to save a Half of -999 on a 320 dish: refused with a message. Nothing is saved.
3. **Edit** the dish: tick Chef's pick and Today's special, dietary label Jain, declare allergens Milk + Soy. Save. A non-veg dish with Vegan ticked is refused.
4. Guest (phone, table QR): the dish shows badges, "Jain", "Contains: Milk, Soy", and "from ₹240". Tap **Choose**: the picker shows Required / Optional rules and the total updates live. Choose Family + Raita: button reads Add · ₹600.
5. The cart shows "Family · Raita"; add Full with no extras: a second line. Add Family + Raita again: quantity goes up on the first line.
6. Place the order. Kitchen board shows "Family · Raita" under the dish in bold; the guest's order page shows it too.
7. Owner: raise Raita to ₹90 and save. The placed order still shows 600 and Raita at 30.
8. Add Raita to a cart, then (as owner) mark Raita sold out. Place the order: refused, message names Raita, cart still intact.
9. A dish nobody declared allergens for shows no allergen line. Declare "none" (declare, tick nothing): it shows "No allergens declared".
10. Order 5+ of a dish, reload the menu after a few minutes: it carries Popular.

## Known limits

- Live tables (owner floor view) and the printed/bill view do not list option choices yet; the price is right, the detail is missing.
- Popular is cached up to 5 minutes, so it can lag new orders.
- A required group with every option sold out makes the dish unorderable; the picker says so, but staff must restore an option.
- Image upload is not in this stage (images are still URLs). It needs a new storage volume in Docker Compose, so it is proposed as its own stage.
- I have not seen any of the new screens rendered; check the picker and dish cards on a 320px-wide phone.
