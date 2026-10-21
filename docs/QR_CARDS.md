# Printable QR cards (Stage J)

**Status:** the rules (QR address, image size, table order, sheet layout) are compiled and executed for real. The endpoints, the page and the QR drawing itself have **not** been run: no ZXing library is available on the machine where this was built. Apply Stages A to I first.

```bash
python3 docs/checks/run_all.py     # today: runs QrRulesTest (17), qrSheet.test.ts (13) and the print-rule check
./gradlew test                     # later: also QrCardsIT (12), which DECODES the images with ZXing
```

## What it does

**Admin, Tables & QR codes, Print QR cards** opens a page that lays out a card for each table: the restaurant's name, the table's name, its QR code, and "Scan with your phone camera to view the menu and order".

- **Cards per page:** 6 (2 by 3), 4 (2 by 2), 12 (3 by 4, small stickers), or 1 large per page (a table stand or poster). A4 paper. Dashed lines mark where to cut.
- **Which tables:** all active tables by default; tick the ones you want, or Select all / Select none. A deactivated table is never offered.
- **Order:** tables are listed the way a person expects ("Table 2" before "Table 10", and "Garden" and "Terrace" grouped), so a printed pile is in order.
- **Sharp on paper:** the server draws each QR at a size matched to the layout (600 to 1200 pixels instead of the old fixed 300), and the page keeps the squares crisp when scaled.
- Only the sheets reach the printer; the app's menus and buttons are hidden when printing.

## What changed behind it

- **The QR now uses error-correction level M** (about 15% of the code can be damaged and it still scans) instead of the default L (7%). Cards on tables get wiped and splashed. **The same address is encoded, so cards you already printed keep working;** only the dot pattern is a little denser.
- **The address is tidied before it is encoded.** It was built by plain joining, so a `PUBLIC_BASE_URL` configured with a trailing slash would have printed `https://site.com//menu/...` on every card. Trailing slashes are now removed. **Printed codes cannot be recalled, so check your `PUBLIC_BASE_URL` is your real public address.**
- `GET /api/v1/tables/{id}/qr` accepts an optional `?size=` (200 to 1200; anything unreadable means the default, never an error).
- New `GET /api/v1/tables/qr-cards`: the restaurant's name and its active tables in natural order. Management only, like the rest of the tables endpoints.
- **No database change.**

## A fix to an earlier stage, found while building this

My receipt-printing styles (Stage I) hid everything on the page except the receipt using an `!important` rule that was not limited to the receipt page. That would have **overridden the existing QR dialog's Print button and made it print a blank page.** Receipt printing is now scoped to a class that exists only while a receipt is open, as the cards page is. A new check, `print_scope_check.py`, fails if a second unscoped print rule is ever added; it catches the exact mistake and a sneakier variant.

## Manual test

1. Add tables named "Table 1", "Table 2" and "Table 10". Open **Print QR cards**: the order is 1, 2, 10.
2. Choose 6 per page: you see a sheet of cards, each with the restaurant name, table name and QR code. Choose **1 per page**: the QR is large.
3. **Scan a card on screen with a phone** before printing: it must open that table's menu. (This is the check no test here could do.)
4. Press **Print**, choose A4, **100% / actual size**, not "fit to page". Only the cards print, with cut guides.
5. Untick a table: it disappears from the sheet and from the page count.
6. Use the old **QR code** button on one table and press its **Print**: it must still print that table's QR (it was at risk from my receipt styles, now fixed).
7. Deactivate a table: it is no longer offered for printing.

## Known limits

- **A4 only**, and the card design is fixed (no logo, no brand colour, no custom wording).
- The page asks for one image per table; with many tables it loads four at a time, so a sheet of 50 takes a few seconds.
- Whether printed squares are sharp depends on the printer and on printing at 100%; test one card first.
- The text says "view the menu and order": it assumes your guests order from their phones, as this app does.
- I have not seen the page rendered or printed, and the QR drawing was not run here (see the status line). `QrCardsIT` decodes the real image and checks the address, the error-correction level and the sizes, but it has not run.
