# Table visits, guest sessions and split / combined billing

One table -> one QR code -> several guests -> an independent cart and orders each -> pay
separately or together. Built in phases; this file records what was agreed and what exists.

## Decisions

- **Payment confirmation:** staff confirm for now. A payment gateway comes later, behind a single
  confirmation interface so billing logic does not change.
- **Combined bill visibility:** each guest's name and amount only. No item names, and never another
  guest's payment method or transaction details.
- **Idle expiry:** a table visit expires after 3 hours with no cart, order or payment activity.
  A visit with unpaid orders is never auto-closed; it is flagged on the owner dashboard for staff.
  (Not built yet: needs orders linked to visits, phase 2.)
- **Closing a table with unpaid orders:** allowed. It is NOT blocked. Unpaid orders stay visible
  in the order and payment history.
- **Customer chooses in their own UI:** first who pays (Pay My Bill / Pay Entire Table Bill), then
  how (the restaurant's enabled, diner-selectable methods). Choosing records the choice and creates
  a pending payment; it is paid only when staff confirm.

## Model

- `table_sessions`: one visit to a table. At most one OPEN per table (generated-column unique index,
  as V28 does for carts, plus the table-row lock in code).
- `diner_sessions`: one guest in a visit. Identity is a random 256-bit token (header
  `X-Diner-Token`); only its SHA-256 is stored. Names are not unique: "Rahul", "Rahul (2)".
- Later: carts and orders get a diner session and a visit; `settlements` group the per-order
  `payments` rows of one payment act (separate or combined). A combined payment is one `payments`
  row per covered order, so revenue analytics never double count.

## Phases

1. **Done:** schema (V31), join endpoint (`POST /api/public/tables/{qr}/join`), identify endpoint
   (`GET /api/public/tables/{qr}/me`).
2. **Done:** per-guest carts and order ownership (V32).
   - Cart and order endpoints under `/api/public/tables/{qr}/` act as the guest named by the
     `X-Diner-Token` header. No token, a forged token, or another table's token gives 404 "Your table
     session has ended...". A guest only ever reaches their own cart; another guest's cart-item id is
     simply "not found".
   - Orders record `table_session_id`, `diner_session_id` and `diner_name`; the staff order list and the
     order response carry `dinerName` and `dinerSessionId`.
   - **Staff cart:** the staff API (`/api/v1/cart`, `POST /api/v1/orders`) still works on one shared
     cart per table, now a separate cart with no guest. It is never merged with guest carts.
   - **Locking:** a guest's cart operations and checkout lock that guest's row, not the table's, so
     guests at one table do not wait for each other. Lock order: guest, then coupons, then order counter,
     then inventory. Nothing takes the table row while holding a guest row.
   - **Order counter:** the first order a restaurant ever takes used to race (nothing to lock). The
     counter row is now created with an insert-if-absent before it is locked.
   - **Customer app:** asks for a name once per table (stored on the device), then sends the token.
     Everything else about the customer screens is unchanged until phase 5.
3. **Done:** read-only bills. No schema change.
   - `GET /api/public/tables/{qr}/bill` ("my bill"): the caller's own orders with items and what they
     still owe. `GET /api/public/tables/{qr}/bill/table` ("table bill"): the whole visit by guest,
     names and amounts only. Both need `X-Diner-Token`; both answer 404 without a valid one.
   - Every order lands in one of four buckets that add up to its total: **paid** (settled),
     **inProgress** (a payment started, awaiting staff confirmation), **due** (payable now) and
     **awaitingConfirmation** (owed, but the kitchen has not confirmed the order yet).
   - **Only confirmed orders are payable.** PENDING orders are owed but not yet payable; CANCELLED orders
     are billed to nobody. This rule is `BillMath.isPayable` and nowhere else.
   - Figures come from the payment service's own balances (`getBalances`), so a bill cannot disagree
     with what staff see for the same order.
   - The table bill never shows another guest's items, order numbers, tokens, or how/when they paid.
     `paid` appears only as a table-level total.
   - Orders staff place for a table through the staff API belong to no guest and are not on any guest's
     bill. Phase 4 must decide how they are settled.
4. **Done:** settlements (V33): paying separately or together.
   - **Guest endpoints** (all act as the guest in `X-Diner-Token`; 404 without a valid one):
     `POST /api/public/tables/{qr}/settlements` body `{kind: SEPARATE|COMBINED, paymentMethod}` with an
     optional `Idempotency-Key` header; `GET .../settlements` (own, newest first, so a refreshed page can
     find a payment in progress); `GET .../settlements/{code}`; `POST .../settlements/{code}/cancel`.
   - **Staff endpoints** (`/api/v1/settlements`, same roles as payments): `GET /pending` lists settlements
     with a payment awaiting confirmation; `PUT /{id}/status` with `{paymentStatus: SUCCESS|FAILED|CANCELLED,
     transactionId?}` confirms or rejects every pending payment in it.
   - **What a settlement is:** a group of ordinary `payments` rows, one per covered order, each for that
     order's outstanding amount when it started. Revenue, refunds and order balances work unchanged, and
     "which payment covers which order" is a plain fact. There is no status column: the status is derived
     from the payments (`SettlementRules.statusOf`), so it cannot disagree with them.
   - **SEPARATE** covers the payer's own payable orders. **COMBINED** covers everyone's. The guest chooses;
     nothing combines automatically. Orders placed after a settlement starts are not part of it.
   - **Nothing is paid until staff confirm.** Starting a settlement records the choice and creates PENDING
     payments; the guest's bill shows them as `inProgress`. Confirmation reuses `updatePaymentStatus`, so
     notifications and audit fields behave as before. A payment gateway can later confirm through the same
     path (decision deferred).
   - **No double charge.** Each covered order is row-locked in ascending id order and its balance worked
     out under the lock; an order with money received or a payment in progress has nothing left to pay and
     is simply not covered. Two guests starting a combined payment at once: one wins, the other gets 409.
     The same `Idempotency-Key` returns the settlement already made, even for simultaneous requests.
   - **Failure and cancellation:** staff FAILED/CANCELLED, or the guest withdrawing a pending payment,
     releases the orders for a retry. Money already received cannot be withdrawn by a guest.
   - **Setting:** `app.billing.require-confirmed-orders` (env `BILLING_REQUIRE_CONFIRMED_ORDERS`, default
     true). When true an order is payable only once the kitchen confirms it; set false if you do not use
     the confirm step. Bills and settlements both read it.
   - Orders staff place through the staff API belong to no guest and are not covered by any settlement
     (the counter settles those with `POST /api/v1/payments/orders/{id}/collect`, as before).

### Concurrency rules (read before changing any of this)

- **Never write the shared `table_sessions` row from a transaction that inserts carts, orders,
  settlements or payments.** Their foreign keys take a shared lock on that row; updating it afterwards
  needs an exclusive lock, and two guests doing it at once deadlock (MySQL aborts one). A guest's
  activity is recorded on their own `diner_sessions.last_seen_at` instead; a visit's live activity is the
  newest of those. H2, which the tests use, will not show this.
- **Settlement create, cancel and staff update run at READ COMMITTED.** MySQL's default REPEATABLE READ
  fixes a transaction's snapshot at its first plain read, which happens before it waits for an order lock;
  after the wait it would read payments from that older snapshot and miss the one just committed, and
  could charge an order twice. H2 behaves like READ COMMITTED, so the tests cannot show this either.
- Lock order: guest row, then orders by ascending id, then payments by ascending id. Nothing takes the
  table row while holding any of these.
5. **Done:** customer screens for paying (no schema change; one small backend addition).
   - **New screen `/menu/:qr/pay`.** Flow: Scan, enter name, browse, add to my cart, place order, **choose who
     is paying** (Pay My Bill / Pay Entire Table Bill), **choose how** (the restaurant's enabled methods),
     pay, wait for staff, receipt. Neither choice is pre-selected; nothing combines automatically. The
     table option shows the whole table's bill by person (names and amounts only).
   - **Honest states.** Loading (skeletons), sending, **waiting for staff to confirm** (polls every 4s and
     says so; "Cancel this payment" asks twice), **payment received** (opens its receipt by itself), failed or
     cancelled (a note, then try again), partly received, and "nothing to pay" with the reason. A refreshed
     or reopened page finds a payment in progress again from the server.
   - **No double submission.** The button is disabled while sending. Each attempt carries an
     `Idempotency-Key`; after a dropped connection, a timeout or a 5xx the SAME key is reused, so tapping
     again cannot take the money twice, and the screen says so. A definite 4xx answer, or a different
     choice, starts a new attempt (`usePayment`).
   - **Receipt:** restaurant, table, who paid, method, the staff reference, when it was confirmed, per-guest
     amounts, total. Settlement responses now carry `confirmedAt` and `reference` for this.
   - **Order page and orders sheet** point to the pay screen ("Pay my bill"); the order page keeps its old
     per-order method picker only when opened with no table to pay at. A stale line in the "Your table"
     sheet that said everyone shares one cart was corrected.
   - The pay logic that is not drawing anything (`lib/payment.ts`) has unit tests; the screens themselves
     are not covered by automated tests (the frontend test setup has no DOM).
   - Not built: push updates (it polls), a print stylesheet for receipts.
6. **Done:** the owner's live view, closing and reopening tables, idle expiry (no schema change).
   - **Staff endpoints** (`/api/v1/table-sessions`, same roles as payments): `GET /active` (open tables with
     guest count, what is owed, pay state, idle flags), `GET /recent` (closed in the last day),
     `GET /{id}` (guests with their orders and balances, every payment record, the visit's settlements),
     `POST /{id}/close`, `POST /{id}/reopen`.
   - **Pay state** per order, guest and table is `NO_ORDERS | UNPAID | PARTIALLY_PAID | PAID`
     (`BillMath.payState`). Money waiting for staff and orders awaiting the kitchen are not "paid".
   - **Payment history without double counting.** Each payment record is one row against one order, so a
     combined payment is one settlement and one row per order it covered; adding up the rows (1,700) never
     counts it per order (5,100). Each row says whether it was `SEPARATE`, `COMBINED`, or null (counter).
   - **Closing is never blocked by money owing**, as decided. Nothing is deleted or settled: orders and
     payments stay, a payment already waiting for staff can still be confirmed after the table closes, the
     guests lose access, and their unsubmitted carts are withdrawn. The screen says what is owed first.
     Closing twice changes nothing. It is recorded in the audit log.
   - **Reopening** puts the visit back and gives its guests their sessions back; refused if the table has
     since started a new visit (there is only ever one open visit per table).
   - **Idle expiry.** A visit is idle when no guest has acted for `TABLE_SESSION_IDLE_TIMEOUT` (default
     PT3H). A scheduled job (every `TABLE_SESSION_EXPIRY_CHECK_INTERVAL`, default PT10M; switch off with
     `TABLE_SESSION_EXPIRY_ENABLED=false`) closes idle visits that owe nothing. A visit that still owes
     anything (due, a payment waiting for staff, an order the kitchen has not confirmed) is **never**
     closed automatically: it stays open and is flagged `needsStaff`.
   - **Screens.** `/admin/live` (owners and managers) and `/floor` (also waiters and cashiers; a cashier's
     home page). Payments waiting for confirmation sit at the top with "Money received" and "Didn't go
     through", an optional receipt reference, and double-click protection. Below are the open tables,
     tables needing a person first; a table opens to its guests, orders and payment history.
     This is also where staff confirm guests' payments in the browser, which until now was Swagger only.
   - A visit's live activity is the newest `diner_sessions.last_seen_at` among its guests
     (`table_sessions.last_activity_at` is set once; see "Concurrency rules").
   - Known small window: a guest acting in the very instant staff close the table can leave one order in
     the closed table. It is visible in the table's history and settled like any other.
7. **Done:** hardening. No new product behaviour; this phase makes what exists safer to run.
   - **Real-MySQL verification.** `./gradlew mysqlTest` runs the integration tests against a throwaway MySQL
     (`docker-compose.test.yml`) with every migration applied and Hibernate validating the entities against
     the migrated schema, under MySQL's own isolation. `MysqlConcurrencyIT` runs only there. The same
     tests run on either database through `TestProfiles` (`km.test.profile`).
   - **Lock failures are retried, then reported honestly.** `LockRetry` re-runs a guest or staff write up to
     three times when MySQL aborts one of two colliding requests (deadlock, lock wait timeout). That is safe
     because the abort rolls the request back completely, and it wraps the transaction from outside
     (`Retrying` is pure and unit-tested). If every try fails the API answers **503 with `Retry-After: 1`**,
     not a generic 500, and the screens say the table is busy. Settlement creation is idempotent, so the
     guest's retry cannot double charge.
   - **Rate limits that do not punish a dining room.** Every phone in a restaurant shares one wifi address,
     so a per-IP limit shared by all of them let one busy table lock out the rest. Guests are now limited
     by their own token (`PUBLIC_GUEST_RATE_LIMIT`, 60 a minute), with a much higher per-IP ceiling
     (`PUBLIC_RATE_LIMIT`, 600) as the abuse backstop, and order-tracking reads, which every guest's screen
     polls, have their own bucket (`PUBLIC_TRACK_RATE_LIMIT`). The window logic is a small pure class
     (`FixedWindowLimiter`) with a fake-clock test.
   - **Metrics and alerts** for lock contention (`app_lock_retries_total`, `app_lock_failures_total`) and
     floor activity (`app_settlements_started_total`, `app_table_sessions_closed_total`), with two alert
     rules. No metric carries a guest, table, order or restaurant.
   - **A load scenario for the floor** (`k6/tables.js`) that fails on any 5xx, which a latency-only test
     would never do.
   - **Test fixes found by reading.** `CartConcurrencyIT` asserted "exactly one order exists in the whole
     database" and "no active carts anywhere", but every integration test shares one database, so those
     could only pass if that test happened to run first on an empty one. They now count only their own
     restaurant. Two idle-expiry tests asserted exact database-wide counts and now check their own tables.
   - **Operations guide:** `QR_ORDERING_OPERATIONS.md` (settings, deployment checklist, what staff do,
     troubleshooting, known limitations).
   - **Bugs found by a review pass afterwards.**
     - `CartServiceImpl.getCart` was marked `readOnly = true` but creates the guest's cart and takes a row
       lock. MySQL's driver refuses data-modifying statements on a read-only connection, so the first
       cart request every guest makes after joining would have failed on MySQL while passing on H2. It was
       latent in the original code (it only bit when a table had no cart yet); per-guest carts made it the
       first thing every guest does. Fixed, and pinned by `CartTransactionsTest`, which fails on every run
       instead of only on the MySQL one.
     - Deleting a table that had ever been scanned failed with a generic server error, because visits,
       carts and orders reference it. It is now a 409 saying to deactivate it instead.
   - **Static checks** (`docs/*_check.py`, sandbox verifiers with the original project path hard-coded):
     two were added, `arity_check.py` (every service call against the real method's name and argument
     count) and `accessor_check.py` (every getter and setter on an entity or DTO against its real fields).
     Both were mutation-tested, and the accessor checker's first version was found to be partly vacuous by
     exactly that test (it did not see method parameters) and fixed. A check that has never failed has not
     been shown to work.

## Deploying phase 2

- Run in a quiet period if you can. A cart that diners were filling at the moment of deployment
  becomes that table's staff cart (nothing is deleted); diners start fresh carts of their own, and staff
  can still place the old one.
- Open customer pages will be asked for a name on their next cart or order action.
- `TABLE_SESSION_MAX_DINERS` (default 30) caps guests per visit. Raise it for load tests that send many
  virtual users to few tables.
- The public rate limit is per client IP. Everyone on a restaurant's wifi shares one, and joining adds
  one write per guest, so keep `PUBLIC_RATE_LIMIT` comfortably above a busy table's traffic.
