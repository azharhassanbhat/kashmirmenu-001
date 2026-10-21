# Responsive UI V2 audit (2026-10-20)

Second pass after `RESPONSIVE_UI_OPTIMIZATION_REPORT.md`. Frontend only: no backend, API, payment, ordering or authentication logic changed. No dependency added.

**Everything here was tested with browser emulation (the built-in browser pane resized to each width, against the real Docker stack). No physical phone, tablet, foldable, laptop or non-Chromium browser was available.** Screenshots were taken at a few sizes and looked at; the width sweeps are scripted measurements, not screenshot comparisons.

## 1. Pages reviewed

- Admin, scripted sweep on all 17 routes: Dashboard, Live tables, Table requests, Orders, Menu items, Categories, Tables, Inventory, Coupons, Payments and revenue, Payment settings, Taxes and service charge, Staff, Feedback, Notifications, QR cards, QR landing page.
- Customer (Restaurant B's table): menu, landing, cart, pay with the UPI panel; menu with long dish names and large prices.
- Interaction checks: admin navigation drawer; dashboard charts and cards at 390 px.
- Not reviewed this time: authentication screens (looked at, not scanned), kitchen display, receipts, order tracking page (`/order/:token`), platform-admin area, every modal and dropdown.

## 2. Widths tested

Admin sweep, every route at: 320 (568 high), 393 (667), 430, 540, 640, 720, 912, 1180, 1280, 1536, 3440, 3840. Combined with the first report's sweeps this covers 320, 360, 375, 390, 393, 412, 430, 480-540, 640, 720, 768, 912, 1024, 1180, 1280, 1366, 1536, 1920, 2560, 3440, 3840 on at least some pages. Not tested: 412, 480, 600, 820, 1440, 1600 on every page; heights other than the ones above; intermediate widths other than those listed.

Customer pages: 320, 360, 540, 768, 1366 (previous pass), plus 320 for the UPI panel and the long-name menu this pass.

## 3. Results of the sweep

- **Page-level horizontal overflow: none** on any admin or customer route at any width tested.
- **Clipped text:** none (the only detections were visually hidden screen-reader text on the revenue chart).
- **Small controls:** only compact controls with a mouse (Mark read, table sort buttons); these are now at least 32 px tall, and 44 px on touch.

## 4. Bugs found and fixed this pass

| # | Finding | Fix |
|---|---|---|
| 1 | Navigation drawer: the page behind it still scrolled; focus stayed on the page; while closed, its off-screen links were still reachable with Tab; closing did not return focus to the menu button | `AdminLayout.tsx` and `Shell.tsx`: scroll lock while open (`html.kd-lock`), focus moves into the drawer, Tab and Shift-Tab wrap inside it, Escape returns focus to the menu button, and the closed drawer is `inert` |
| 2 | Dish cards at 320 px: photo column took about 36 % of the card, squeezing the dish name into a 107 px column | `customer.css`: photo column `clamp(96px, 31vw, 168px)` |
| 3 | Small controls with a mouse (23 to 30 px) | `min-height: 32px` for table sort buttons, text buttons and compact buttons |

Already fixed in the first pass and re-verified live: chunk-load reload after a deploy, safe-area padding, ultrawide centering, 44 px touch targets, keyboard rule for fixed bars, KPI mini-bar container query, phone layout of the sounds bar, UPI panel (QR on solid white at 222 px at 320 px, no filter or opacity, no entrance animation).

Checked and found acceptable (no change): KPI cards (values and labels do not overflow at 390 px, mini bars hidden on narrow cards); dashboard Live Orders, Orders by Status donut, Popular Items and Table Status stack cleanly at 390 px; the donut chart is 168 px wide inside a 168 px container; the revenue line chart is 480 px wide inside its own horizontal scroller on phones (by design, no page overflow); long Latin dish names, long category names and large prices (₹12,345.50 ×3 = ₹25,141) wrap without breaking the card; the quantity steppers are 42 × 43 px.

## 5. Files modified this pass

`frontend/src/pages/admin/AdminLayout.tsx`, `frontend/src/dashboard/Shell.tsx`, `frontend/src/dashboard/dashboard.css`, `frontend/src/customer.css`, `frontend/src/styles.css`.

## 6. Navigation drawer verification (emulated, 390 px, fresh page load)

Verified by script: closed = `inert`; open = scroll locked, scrim shown, focus on the first link; Tab from the last link wraps to the first; Escape, scrim click and choosing a link each close it and unlock scrolling. Not verified: that focus lands on the menu button after Escape in a real browser (the script clicked the button without focusing it, and the fix looks the button up explicitly, so it should), and a real screen reader.

## 7. Performance (measured, localhost, no throttling, one run)

Customer menu at 320 px: DOMContentLoaded 77 ms, load 78 ms, time to first byte 5 ms; JavaScript 160 KB transferred (2 files); CSS 25 KB; no long tasks; layout shift 0. **LCP could not be captured** (the pane did not report it), and no throttled mobile run was done, so no Core Web Vitals claim is made. The page loads 8 API calls; two of them (`/api/v1/me`, notifications) happen only because the test browser also held a staff login. No image or font optimisation was done; the test menu had no dish photos.

## 8. Tests

| Check | Result |
|---|---|
| `tsc --noEmit` | passed |
| Frontend `vitest` | passed, 351 tests |
| Production build | passed |
| Lint | not present (no lint script), not run |
| Playwright / automated screenshots | not installed (adding it needs approval), not run |
| Backend suite | not run: no backend file changed this pass |
| Console errors during the sweep | none |

## 9. Remaining issues and limits

- Real devices, Safari/iOS, Firefox, Samsung Internet and foldables were not tested; the foldable `horizontal-viewport-segments` rule remains unverified.
- On-screen keyboard behaviour could not be reproduced in emulation.
- Chart axis labels render at under 11 px on the revenue page.
- No dish photos exist in the test data, so photo stretching was not exercised (the photo box has a fixed shape and `object-fit`, but this was not seen with real photos).
- The first-open drawer animation looked mid-transition in a screenshot; transitions are slow in the hidden/throttled pane, so animation smoothness was not judged.
- The test restaurants "UPI Test A/B" (ids 145, 146) and their test dishes, orders and payments remain in the database.

## 10. Manual checks to run on devices

iPhone Safari and an Android Chrome phone through the whole flow (scan, order, UPI, reference field with keyboard open, receipt); a Galaxy Fold or Pixel Fold folded and unfolded; an iPad in both orientations; an ultrawide monitor for the admin dashboard; Firefox and Samsung Internet for the drawer and dropdowns.
