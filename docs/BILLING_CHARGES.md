# Service charge and tax (Stage C)

Each restaurant sets its own tax and service charge. **Nothing is built in and every rate defaults to 0**, so a restaurant that never opens the settings is charged exactly its menu prices, as before.

**Status: written but not yet run.** Apply Stages A and B first. Kashmir Menu applies the rates it is given; it does not decide which taxes apply to a restaurant, or whether its prices should include them. That is for the restaurant and its accountant.

## Run the automated checks (when you can)

```bash
./gradlew test                                   # OrderChargesTest, BillingChargesIT (+ earlier stages)
docker compose -f docker-compose.test.yml up -d --wait && ./gradlew mysqlTest   # proves V36 matches the entities
cd frontend && npm install && npm run typecheck && npm test
```

## How a bill is worked out (each step rounded to 2 decimals, half up)

1. **net** = subtotal - discount (a coupon reduces what is taxed, never the reverse; never below 0)
2. **service charge** = net x service rate
3. **Prices do NOT include tax:** tax = (net + service charge) x tax rate, added on top. The service charge is taxed too.
   **Prices DO include tax:** nothing is added; the tax inside the dishes is shown for information: net x rate / (100 + rate). A service charge on inclusive prices is added untaxed; if your service charge is taxable, choose "do not include tax".
4. **total** = net + service charge + tax

Example, subtotal 1000, service 10%, tax 5%: service 100, tax 55 (5% of 1100), total 1155. With a 200 coupon: net 800, service 80, tax 44, total 924.

## Rules that protect guests and the restaurant

- **What the guest was shown is what they pay.** The rates are copied onto the cart whenever it changes, and onto the order when placed. If the owner changes the rates while a guest is still choosing, that guest pays the rates they saw; the next cart uses the new ones.
- **A placed order never changes.** Rates and amounts are stored on the order (`tax_name`, `tax_rate`, `service_charge_rate`, `prices_include_tax`, `service_charge`, `tax`, `tax_included`), so a bill can always be reproduced.
- **A coupon that expires between "apply" and "place order" is dropped, and tax and service charge are recalculated on the new (higher) amount**, so the total is always internally consistent.
- **Payments, bills and reports need no change**: they all read `order.total`, which now includes the charges. The amount a guest must pay and the amount staff confirm are the same figure.
- Impossible rates (below 0, above 100, more than 2 decimals, blank tax name) are refused and nothing is saved. Each restaurant's settings are its own; the restaurant is taken from the signed-in user, never from the request.

## Database change (additive only)

`V36__billing_settings_service_charge_tax.sql`: new table `restaurant_billing_settings` (one row per restaurant, check constraints 0 to 100); new columns on `carts` and `orders`: `service_charge`, `tax_included`, `tax_name`, `tax_rate`, `service_charge_rate`, `prices_include_tax`. Existing rows get 0 / false and behave as before.
Rollback before it matters: `DROP TABLE restaurant_billing_settings;`, drop the six added columns from `carts` and from `orders`, delete the `flyway_schema_history` row for version 36.

## API

| Who | Method and path | Purpose |
|---|---|---|
| Management | `GET /api/v1/billing-settings` | This restaurant's rules (zeros if never set) |
| Management | `PUT /api/v1/billing-settings` `{"taxName":"GST","taxRate":5,"serviceChargeRate":10,"pricesIncludeTax":false}` | Set them. All four fields are required |
| Guest | cart, order and order-tracking responses | Now include `serviceCharge`, `tax`, `taxIncluded`, `taxName`, `taxRate`, `serviceChargeRate`, `pricesIncludeTax` |

## Manual test (browser)

1. Owner: **Taxes & service charge** in the admin menu. Everything reads 0. Enter tax name GST, tax 5, service charge 10, "do not include tax". The example shows service 100, GST 55, guest pays 1155. Save.
2. Guest (table QR): add dishes worth 800. The cart bill shows Items 800, Service charge (10%) 80, GST (5%) 44, Total 924.
3. Place the order. The order page shows the same lines under "Subtotal", and the amount to pay is 924.
4. Owner: change GST to 18 and save. The order from step 3 is unchanged (924, GST 5%). A new cart now uses 18%.
5. Start a cart as a guest, change the rates as owner, then place the order: the guest pays what they were shown.
6. Switch to "already include tax" with 0 service charge: a 800 cart totals 800 and shows "Includes GST of ₹38.10".
7. Set tax 101, or leave the name blank: refused with a message, nothing saved.
8. Apply a 10% coupon with the 5%/10% settings on an 800 cart: discount 80, service charge 72, GST 39.60, total 831.60.

## Known limits (please read)

- **"Gross sales" and "collected" still include tax and service charge**, because that is what guests were billed and what they paid. The Payments & revenue page now also shows **"Sales excluding tax"** (gross sales less tax added on top and tax already inside prices; the service charge stays in) and notes how much of gross sales was tax. Collections and refunds figures remain whole-bill amounts and are not split into tax.
- **The owner's Orders table, the live tables view and the payment screens show the total, not the line breakdown.** The amount is right; the detail is only on the guest's cart and order page.
- **No printable receipt yet**, so there is no tax invoice. Do not treat this as GST-compliant invoicing: no GSTIN, invoice numbering or HSN codes.
- **One tax rate per restaurant.** Dishes with different rates (for example, packaged goods) are not supported.
- Carts and orders from before this change have no stored rates and are treated as 0, which is what they were charged.
- I have not seen the new screens rendered; check the cart bill and the settings example on a narrow phone.

## Reporting of tax (added after the first version)

`GET` of the payment analytics overview now carries, under `sales`: `taxCollected` (tax added on top), `taxIncluded` (tax inside menu prices), `serviceCharges` and `salesExcludingTax` (= gross sales - taxCollected - taxIncluded, never below zero). They are summed from the amounts stored on each non-cancelled order in the period, so the parts always add up to the whole. Restaurants that charge no tax see no change (the new tile is hidden and `salesExcludingTax` equals gross sales). Verified by `SalesTaxBreakdownTest` (10 tests, run for real by `docs/checks/run_junit.py`).
