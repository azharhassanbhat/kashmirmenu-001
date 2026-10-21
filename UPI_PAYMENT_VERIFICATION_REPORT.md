# UPI payment QR: verification report (2026-10-20)

Scope: connect the restaurant owner's uploaded UPI QR to the existing customer checkout, with guest "I've completed payment" claims and staff verification. Implemented inside the existing project; no new payment system, no new framework.

## 1. Existing implementation findings

Reused as-is: the settlement flow (a guest starts paying, PENDING payments are created, staff confirm through the existing payment-status code, which already audits and notifies); `restaurant_payment_settings` (UPI on/off, UPI ID, merchant name); the `stored_images` table and upload endpoint (images live in MySQL, re-encoded, quota-limited); the staff "Payments to confirm" card on Live tables; tenant checks on settings and settlements.

Missing before this work: no field for a QR image; the customer saw only a bare "UPI" choice with no QR or payee; no guest "I've paid" step; no "verification pending" or "rejected" wording; bill breakdown (tax, service charge, discount) not shown to the guest; the cleanup job would have deleted an unreferenced QR photo after 24 h.

## 2. Bugs and gaps found and fixed

| # | Finding | Fix |
|---|---|---|
| 1 | No place to store the QR | New column `restaurant_payment_settings.upi_qr_url` (migration V43), reusing `stored_images` |
| 2 | Image cleanup job would delete a saved QR | Cleanup query now also treats the payment-settings QR as "referenced" |
| 3 | An owner could in principle point at another restaurant's photo or an external image | Server accepts only our own photo address AND only one this restaurant uploaded (`existsByPublicKeyAndRestaurantId`); otherwise 400 |
| 4 | UPI could be started with no QR/UPI ID | UPI is only offered on the public table page when enabled, with UPI ID and QR; starting a UPI settlement without them is refused (400) |
| 5 | No "customer says paid" state | `settlements.customer_confirmed_at` / `customer_reference`; new endpoint `POST /api/public/tables/{qr}/settlements/{code}/claim-paid`. Claim only: payments stay PENDING |
| 6 | Double tap could overwrite the reference | First claim wins; repeats change nothing and create no extra records |
| 7 | Rejected UPI payment said "didn't go through" | UPI-specific "Payment rejected" copy |
| 8 | Bill showed order totals only | Server now sends subtotal, tax, service charge, discount per order; the pay page shows them, summed from server figures |
| 9 | Method label showed "Upi" | Labels now read "UPI" (guest pay page, receipt, staff card) |
| 10 | "Download QR" wrapped onto two lines on a 375 px phone | Buttons no longer wrap |
| 11 | A guest would not see a replaced/removed QR until reload | Pay page re-reads the table every 30 s |
| 12 | Existing tests expected old UPI pending wording | Updated; new copy tested |

## 3. Files modified

Backend (`src/main`): `resources/db/migration/V43__upi_qr_and_customer_payment_claim.sql` (new); `entity/RestaurantPaymentSetting.java`, `entity/Settlement.java`; `dto/request/CreateRestaurantPaymentSettingRequest.java`, `UpdateRestaurantPaymentSettingRequest.java`, `ClaimPaidRequest.java` (new); `dto/response/RestaurantPaymentSettingResponse.java`, `PublicTableResponse.java`, `SettlementResponse.java`, `SettlementStaffResponse.java`, `BillOrderResponse.java`; `repository/StoredImageRepository.java`; `service/impl/RestaurantPaymentSettingServiceImpl.java`, `service/impl/PaymentServiceImpl.java`, `service/PublicOrderingService.java`, `service/SettlementService.java`, `service/BillService.java`; `controller/PublicSettlementController.java`.

Frontend (`frontend/src`): `api/types.ts`, `api/index.ts`; `lib/payment.ts`, `lib/payment.test.ts`; `components/customer/UpiPanel.tsx` (new); `pages/customer/PayPage.tsx`, `pages/customer/usePayment.ts`; `pages/admin/PaymentSettingsPage.tsx`, `pages/admin/LiveTablesPage.tsx`; `customer.css`, `styles.css`.

Tests: `src/test/.../controller/UpiQrPaymentIT.java` (new, 12 tests).

Other: `UPI_PAYMENT_VERIFICATION_REPORT.md` (this file).

## 4. Security controls (and how each was checked)

| Control | Evidence |
|---|---|
| A's customers see only A's QR; B's only B's | Integration test (two restaurants) and browser (A: Alpha Kitchen / alphatest@okbank; B: Beta Bistro / betatest@okbank, different QR images) |
| Owner cannot use another restaurant's or an external QR | Integration test: 3 refused cases (400) |
| Restaurant B cannot change A's settings | Integration test: 404; unauthenticated: 401/403 |
| Other restaurant's staff cannot list or decide A's payments | Integration test: empty list, 404 on decide |
| Guests cannot mark anything paid | Integration test: guest request to staff endpoint 401/403; claim leaves status PENDING and `confirmed` 0 |
| Amount is server-computed; client names no amount | Settlement request has only kind + method; claim request has only a reference; totals from server in tests and browser (₹450, ₹900) |
| Reference validated (6 to 35 of letters, digits, `- _ /`) | Integration test: `<script>` and too-short refused (400); also validated in the browser form |
| Only own, still-pending UPI settlement can be claimed | Tests: other guest 404, cash 400, withdrawn/rejected/paid 409 |
| Two staff deciding the same claim | Test: repeat confirm is a no-op, contradictory decision 409, paid once |
| Audit of verification | Confirm/reject runs through the existing payment-status code, which is audited (not re-tested separately here) |
| UPI link built safely | `upiPayLink` unit tests: invalid UPI ID refused, values URL-encoded |

## 5. Tests

| Check | Result |
|---|---|
| `UpiQrPaymentIT` (new) | passed, 12 tests |
| Backend full suite before the last small changes (`gradlew test`) | passed: 888 tests, 0 failures, 4 skipped (MySQL-only) |
| Backend full suite, final run | passed: 891 tests, 0 failures, 4 skipped (section 5a) |
| Frontend `vitest` | passed: 351 tests |
| Frontend `tsc --noEmit` and production build | passed |
| `npm audit` | 0 vulnerabilities |
| Lint | not present (the project has no lint script), not run |
| `mysqlTest` suite | not run |

5a. Final backend run after the last backend change (`gradlew test --no-daemon`): BUILD SUCCESSFUL, 891 tests, 0 failures, 0 errors, 4 skipped.

### Browser test (real stack, mobile 375x812, mock data only, no real payments)

Tested live against `http://localhost:3002` with two new test restaurants:
- Owner A, through the real settings page: ticked UPI, entered UPI ID and recipient, uploaded a QR image (preview, Replace and Remove controls shown), saved.
- Guest at Table 05 of A: joined, added a dish, placed the order; the owner confirmed it; Pay My Bill, then UPI. Panel showed restaurant, table, ₹450, QR on a white tile, recipient, UPI ID, Download QR, Copy UPI ID, Open UPI app, a reference field. Image was A's, loaded, no horizontal overflow.
- Entered a mock reference, tapped "I've completed payment": page showed "Verification pending" with the "restaurant will verify your payment shortly" message. Reloading kept that state.
- Owner dashboard (Live tables): card showed Table 05, ₹450, "Customer says they have paid", the reference. "Payment received" then made the guest's page switch to the "Payment received" receipt with the reference; the database showed one UPI payment, SUCCESS, 450.00.
- Restaurant B: its guest saw B's own QR, "Beta Bistro", betatest@okbank, ₹900.
- Rejection (claim, then staff reject, via API for B's guest): the guest page showed "Payment rejected".
- Owner removed B's QR: UPI disappeared from B's table page (methods: CASH only).
- Restarted the app container: A's QR image (200, 26,844 bytes) and settings were still served.

## 6. Remaining limitations and not verified

- Not tested: an actual UPI app scanning the QR or the `upi://` link (no real payments were made, by design); the uploaded QR is static and does not contain the bill amount, and the screen says so. No payment-provider integration exists, so "Paid" always needs a staff decision.
- Not browser-tested: owner replacing the QR while a guest is mid-checkout (covered by the 30 s refetch and by the integration test for "latest upload used"), network drop mid-submit, and the owner UI Reject button (rejection tested through the API; the button's wiring is unchanged existing code). Order cancelled before payment is covered by the integration test for withdrawn settlements, not by a browser run.
- "Review past decisions": staff can see the decided payments in a table's visit detail (existing screen); there is no separate payment-history page, and none was added.
- The 30 s refresh means a guest could see a just-replaced QR for up to 30 s; the claim is still only verified by staff against their own bank.
- The restaurant name appears on the guest and staff screens as the restaurant's own; a "restaurant name" column was not added to the staff card because staff only see their own restaurant.
- Direct URL loads of admin pages (for example `/admin/payments`) sent the test session back to login; navigation by clicking worked. This is existing behaviour (the session is not restored on a full page load) and was not changed.
- The platform database now contains two test restaurants, "UPI Test A" (id 145) and "UPI Test B" (id 146), with orders, a payment each and test guests. They were left in place; removal is the owner's call.

## 7. Manual configuration required

- Per restaurant: Dashboard, Payment settings: enter a valid UPI ID, upload the QR image, tick UPI, Save. Until all three exist, customers are not offered UPI.
- Migration V43 was applied to the real MySQL on 2026-10-20 (three nullable columns, additive).
- Staff must check their bank or UPI app before pressing "Payment received"; the guest's reference is a hint, not proof.

## 8. Unrelated dashboard fix made in the same session

The date-range menu ("Last 7 days") on the owner dashboard opened behind the KPI cards, because the hero section and the sections after it shared the same stacking level. `.kd-hero` now has `z-index: 20` in `frontend/src/dashboard/dashboard.css`. Checked in the browser by testing that the opened menu is the topmost element at three points; the frontend image was rebuilt and redeployed.
