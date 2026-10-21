# Printable receipts (Stage I)

**Status:** the wording rules and the registration-number rule are compiled and executed for real. The endpoints and screens have **not** been run. Apply Stages A to H first.

```bash
python3 docs/checks/run_all.py     # today: runs ReceiptRulesTest (16), TaxRegistrationTest (8), receipt.test.ts (10), V40 on real rows, parity (61 inputs)
./gradlew test                     # later: also ReceiptIT (15)
./gradlew mysqlTest                # later: proves V40 matches the entity
```

## What it does

- **Guests:** on their order page, **View receipt** (any order that was not cancelled) opens a clean bill they can print or save as PDF from the browser's print dialog.
- **Staff:** on **Admin, Orders**, a **Receipt** button on each order opens the same bill to print for the table.
- **Paper:** choose **A4 / letter** or an **80 mm till roll**. Only the receipt reaches the printer; the app's menus and buttons are hidden when printing.
- **What is on it:** the restaurant's name, address, phone and tax registration number; the order number, table, date and time; every item with its chosen options; the discount, service charge and tax as billed; the total; then a payment section: **PAID**, **PART PAID**, **UNPAID**, **REFUNDED** or **NO PAYMENT DUE**, each payment received (for example "UPI (Google Pay)", "Cash"), anything refunded, and the balance still owed.
- **Tax registration number:** set at **Admin, Taxes & service charge**. It is printed exactly as entered, labelled with your tax name ("GST No: ...").

## What is guaranteed

- **A receipt prints what the guest was billed, never a recalculation.** Prices, rates, discount and total are the ones recorded on the order when it was placed. If you change your tax rate tomorrow, yesterday's receipt still shows yesterday's tax. (`ReceiptIT` changes the rate after ordering and checks.)
- The charge rows are built by the same helper as the guest's order page, so the two describe a bill identically.
- **No guest name, phone or feedback appears** on a receipt.
- A **cancelled order has no receipt** (a clear message, not an empty page).
- A guest reaches only **their own** receipt, through their order's random private token. Staff reach it by order id, **scoped to their restaurant**; another restaurant's order answers "not found".
- The registration number is tidied (spaces of every kind removed, upper case) and checked only for being **safe to print**: 5 to 30 letters, digits, hyphens or slashes. Markup, quotes and anything else are refused.

## What this is NOT

- **It is a receipt, not a tax invoice.** The registration number is **not verified** against any tax authority, and nothing here claims to meet the invoice rules of any country (GST invoice numbering, HSN codes, place of supply, and so on). If your law requires a formal tax invoice, check with your accountant before relying on this. I am not a lawyer or tax advisor.
- The registration number is the owner's responsibility: the app prints what you enter.

## Database (V40, additive only)

One nullable column, `restaurant_billing_settings.tax_registration_number`. Every existing restaurant is unchanged (nothing is printed). Rollback: `ALTER TABLE restaurant_billing_settings DROP COLUMN tax_registration_number;` and delete the `flyway_schema_history` row for version 40.

**One behaviour to know:** saving the billing settings is a full replacement, so a client that does not send `taxRegistrationNumber` clears it. The app's own page always sends it.

## API

| Method and path | Purpose |
|---|---|
| `GET /api/public/orders/by-token/{token}/receipt` | The guest's receipt (their token); 400 if cancelled; 404 if unknown |
| `GET /api/v1/orders/{orderId}/receipt` | The same bill for staff, in their own restaurant |
| `PUT /api/v1/billing-settings` | now also takes `taxRegistrationNumber` (optional) |

## Manual test

1. Admin, **Taxes & service charge**: set GST 5%, service charge 10%, and the registration number `29 abcde 1234 f1z5`. Save. It reads back as `29ABCDE1234F1Z5`.
2. Place an order of a 400 dish from a table QR. Open the order page, **View receipt**: 400 + 40 service + 22 GST = 462, **UNPAID**, balance 462.
3. In **Orders**, record a cash payment of 200: reload the receipt, **PART PAID**, balance 262. Record 262: **PAID**.
4. Change the GST rate to 18% and reload the receipt: still shows 22. That is correct.
5. Press **Print receipt** with **A4**, then **80 mm till roll**: only the receipt prints, and the second is narrow.
6. Paste a registration number copied from a web page or WhatsApp: it is accepted.
7. Cancel a different order: its receipt link disappears from the guest page and the staff list, and opening the address shows "A cancelled order has no receipt."

## Known limits

- The receipt is one page of the bill; there is no emailing or sharing button (the browser's print dialog can save a PDF).
- No receipt/invoice number series beyond the order number, no logo, no QR code for UPI on the receipt.
- Phone and address come from the restaurant's profile, which has no screen yet in this app to edit them (they were entered at sign-up).
- The 80 mm mode relies on the browser honouring the page-size rule; some print dialogs need "paper size" set to the roll once.
- I have not seen the screens rendered or printed.
