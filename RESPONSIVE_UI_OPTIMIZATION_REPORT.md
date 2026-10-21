# Responsive UI optimization report (2026-10-20)

Scope: the React frontend. No backend, API, authentication, payment or database change was made in this task. No new framework or dependency was added (the project rules forbid it without approval).

**Honesty note.** Everything below was tested by browser emulation only (the built-in browser pane, resized to the widths listed, running against the real Docker stack). No physical phone, tablet, foldable, laptop or non-Chromium browser was available. The long lists of brands and models in the request guided what to consider; the layout logic stays viewport- and content-based, as asked, with no per-device CSS.

## 1. Pages and components audited

Programmatic scan (horizontal overflow, elements outside the viewport, controls under 32 px, text under 11 px) of these routes:

- Customer: menu entry, landing (`/menu/:qr/home`), cart, pay (including the UPI panel with a long restaurant name).
- Admin (logged in as a test owner): Dashboard, Live tables, Orders, Menu items, Categories, Tables, Inventory, Coupons, Payments and revenue, Payment settings, Taxes and service charge, Staff, Feedback, Notifications, QR cards, QR landing page.

Widths scanned: customer 320, 360, 540, 768, 1366 (and 375/390 visually); admin 320, 375, 768, 1024, 2560 (and 390, 1920 spot checks). Not every route at every one of the 26 listed widths, and not 3440/3840/5120.

Not audited: login/register pages beyond a glance, the platform-admin area, kitchen display, receipts, modals opened from each page, and every error/empty state.

## 2. Findings

| # | Finding | Severity |
|---|---|---|
| 1 | Horizontal overflow | **None found** on any scanned route and width |
| 2 | A tab left open across a deploy fails on its next navigation ("Something went wrong on this screen"): the old page chunks no longer exist (`Failed to fetch dynamically imported module`). Seen for real during this test | Medium |
| 3 | Admin top bar, content column and sidebar ignored device safe areas (notch, Dynamic Island, home indicator) | Medium |
| 4 | On 2560 px the admin content sat left-aligned with a large empty strip on the right | Low |
| 5 | The "notification sounds are blocked" bar squeezed its message into a one-word-wide column on phones | Low (cosmetic) |
| 6 | KPI card decorative bar glyph overlapped the caption on 2-per-row phone cards | Low (cosmetic) |
| 7 | Touch targets under 44 px: Mark all as read (22 px), Mark read (30 px), table sort buttons (23 px) | Low |
| 8 | Text under 11 px: brand tagline 9.5 px, collapsed-sidebar badge 10 px | Low |
| 9 | Fixed bottom bars could sit on top of an input while the on-screen keyboard is open | Medium for the UPI reference field (not reproduced here, see section 11) |
| 10 | The UPI panel had a fade-in animation near the QR | Low (removed) |
| 11 | Chart tick labels on Payments and revenue render at under 11 px (10 text nodes) | Low, **not changed** |

## 3. Files modified

- `frontend/src/main.tsx`: reload once on `vite:preloadError` (finding 2), guarded to once per 30 s to avoid a loop.
- `frontend/src/dashboard/dashboard.css`: centred, safe-area-aware content column and top bar; max width raised on 2200 px+; 44 px touch targets on coarse pointers; 11 px minimum for the tagline and badge; container query for the KPI glyph; short-landscape rule.
- `frontend/src/customer.css`: keyboard handling, QR sizing, touch targets, foldable block, removed the panel animation.
- `frontend/src/styles.css`: coarse-pointer target size for sort buttons; responsive wrap for the sounds bar.

(The previous UPI work in `components/customer/UpiPanel.tsx` and related files is documented in `UPI_PAYMENT_VERIFICATION_REPORT.md`.)

## 4. Strategy used

Content-driven, not device-driven:
- Existing fluid layout (grid, flex, `clamp()`, `dvh`, existing breakpoints) was kept; the audit showed it already holds from 320 px up.
- New CSS is a small appended layer per file, using media features that describe the device's capabilities rather than its name: `pointer: coarse`, `orientation`, `max-height`, `min-width`, `horizontal-viewport-segments`, and one container query (`@container (max-width: 190px)` on `.kd-kpi`, which follows the card's own width).
- Safe areas via `env(safe-area-inset-*)`. The viewport meta already had `viewport-fit=cover`; zoom is not disabled.

## 5. Mobile (customer QR menu and UPI checkout)

- Menu, cart and pay pages: no horizontal scroll at 320 px.
- UPI panel at 320 px: QR 222 px square on a plain white tile, never cropped, no filter or opacity, shrinks with `min(260px, 70vw, 62dvh)` (so it also fits a 360 px-high landscape phone); all three action buttons 44 px tall; long restaurant name tested.
- Bottom navigation and cart bar step aside while a text field has focus on touch devices.
- Removed the entrance animation on the payment panel.

## 6. Tablet / 7. Laptop / 8. Desktop and ultrawide

- 768 and 1024 px: no overflow on any scanned route.
- 1366 px: customer routes clean; admin dashboard unchanged apart from the shared fixes.
- 2560 px: the admin column is now centred with a 1880 px cap (1640 px below 2200 px), instead of left-aligned. 3440, 3840 and 5120 px were not tested.

## 9. Foldables

A guarded `horizontal-viewport-segments: 2` block keeps the customer page inside the first screen segment so controls do not cross a reported hinge. Browsers that do not expose segments ignore it. **Untested** (no foldable or emulator with segments was available); verify on a real device.

## 10. Browser compatibility

Only the Chromium-based browser pane was used. Safari/iOS, Firefox, Samsung Internet, Opera Mini and in-app browsers were **not tested**. `:has()` (used for the keyboard rule) and container queries are supported by current versions of all major browsers; the keyboard rule simply does nothing where `:has()` is missing, and the page stays usable.

## 11. Performance

Not measured. No Lighthouse, Web Vitals or bundle analysis was run, so no LCP/INP/CLS claim is made. Production build is clean; the largest chunks are the main bundle (about 448 kB, 140 kB gzipped) and the dashboard chart bundle (about 405 kB, 119 kB gzipped). No image-format or image-size work was done in this task.

## 12. Accessibility

Improved: 44 px touch targets on touch devices, minimum 11 px for two small texts, no zoom lock, focus and reduced-motion handling left as they were (the payment panel animation was removed). Not done: contrast measurements, screen-reader pass, heading audit, chart tick label sizes.

## 13. Tests

| Check | Result |
|---|---|
| `tsc --noEmit` | passed |
| Frontend `vitest` | passed, 351 tests |
| Production build | passed |
| Lint | not present (no lint script in the project), not run |
| Backend suite | not re-run: no backend file changed in this task (last full run 891 tests, 0 failures, 4 skipped) |
| Playwright / automated screenshot tests | not available (not installed; adding it needs approval), so no screenshot-diff tests exist |
| Responsive scans | manual, scripted in the browser console; results in section 1 |
| Console errors | the only errors seen were from the stale-tab chunk failure (finding 2, now handled) and 502s while containers were restarting |

## 14. Remaining limitations

- Nothing verified on real devices or other browsers (see sections 9 and 10).
- On-screen keyboard behaviour (finding 9) could not be reproduced in emulation; the fix is untested on a real phone.
- Chart tick labels under 11 px, and the platform-admin, kitchen and receipt screens, were not reviewed.
- The "sounds are blocked" bar appeared on the customer page only because the same browser also held a staff login; ordinary diners do not have one.
- Opening an admin URL directly (a full page load) briefly returned to the login page in one test and worked in another; this predates the work and was not investigated.
- The test restaurants "UPI Test A/B" (ids 145 and 146) and their orders remain in the database.

## 15. Manual device checks recommended

An iPhone (Safari) and a mid-range Android phone (Chrome): scan a table QR, add items, open UPI, type a reference with the keyboard open, check the bottom bar and the notch. A Samsung Fold or Pixel Fold folded and unfolded. An iPad in portrait and landscape. A 4K and an ultrawide monitor for the admin dashboard. Firefox and Samsung Internet for the same flows.
