# Full-Stack Bug Audit — KashmirMenu

> **Remediation status (14 October 2026).** BUG-001 through BUG-012, plus BUG-015 and BUG-016, have
> since been fixed across four groups — see `CHANGES.txt` phases 13–18. BUG-013 is left alone
> deliberately (it needs profiling evidence before any refactor) and BUG-014 remains an accepted
> trade-off. The findings below are kept as written at audit time: they record what was wrong, not
> what is live now. **None of the fixes has been executed** — 20 integration test cases have never
> been run, and `./gradlew test` is required before relying on any of them.

**No application code was changed for this audit.** The only files added are this report and
`BUG_FIX_PLAN.md`.

**Date:** 14 October 2026
**Scope:** Spring Boot 3.5.4 backend, React 19 frontend, Flyway V1–V25, Docker/nginx, k6 and
observability configuration.

---

## Phase 8 first: what was actually executed

Stating this up front, because it determines how much weight each finding carries.

| Command | Result |
|---|---|
| `./gradlew test` | **Failed to run.** `UnknownHostException: services.gradle.org` — the wrapper cannot download Gradle. No backend compilation or test has been executed. |
| `npm ci` (frontend) | **Partially succeeded, then broke.** Network is intermittent here: 78 packages extracted, then `npm error Exit handler never called!`. `vite` was left as an empty, invalid package. |
| `npm run typecheck` | **Could not complete.** `error TS2688: Cannot find type definition file for 'vite/client'` — a symptom of the broken install, **not** a code defect. Repair attempts failed with `ENOTFOUND registry.npmjs.org`. The partial `node_modules` was then deleted so it cannot mislead later. |
| `npx vitest run` | **Could not run** (same network failure). |
| Pure-logic Java suite (via the JDK compiler API and a JUnit-compatible shim) | **34 of 34 pass** for the calculator, coupon pricing and payment-balance tests; **61 pass** across the full pure suite. |
| Frontend logic suite (chart/format helpers, via `tsx`) | **31 of 31 pass.** |
| `docs/authz_check.py` | **Clean** — authorization matrix, platform-role isolation, duplicate annotations, entity/migration consistency. |
| `docs/promql_check.py` | **Clean** — 66 expressions, 33 metric names. |
| `migration_check.py` | **Clean** — 25 migrations, ordering valid. |
| Java syntax check (`javac` via the compiler module, no classpath) | No syntax errors; dependency-resolution errors only, as expected. |

**Consequence: no finding below is marked Confirmed on the basis of running the application.**
"Confirmed" here means the defect is unambiguous from reading the code path end to end — for example,
a method that writes a value no caller can ever read. Anything needing execution is marked
*Needs Runtime Verification*.

---

## Phase 1: Architecture

- **Backend:** Spring Boot 3.5.4 / Java 21, MySQL 8.4 + HikariCP, Flyway V1–V25, stateless HS256 JWT
  with roles read from the database per request, STOMP/WebSocket for live updates.
- **Frontend:** React 19 + TypeScript + Vite, TanStack Query (`staleTime: 10s`, no window-focus
  refetch), served by nginx proxying `/api` and `/ws`.
- **Roles:** `PLATFORM_OWNER` (platform-wide), `ADMIN`/`RESTAURANT_OWNER`/`MANAGER` (restaurant
  management), `CHEF`/`WAITER`/`CASHIER` (floor), `CUSTOMER`.
- **Tenancy:** per-service, via `CurrentUserService.getCurrentRestaurantId()`. Deny-by-default
  authorization, with `.anyRequest().denyAll()`.
- **Main workflows traced:** diner QR ordering (table → menu → cart → coupon → order → track →
  payment), staff kitchen flow, counter payment collection including split bills and refunds, daily
  reconciliation, coupon lifecycle, stock deduction.

**Features that do not exist** (listed so they are not mistaken for bugs): restaurant approval
workflow, delivery fees, tax calculation (hardcoded zero), commission/settlement model, MFA, order
editing, and the platform-admin read endpoints (phases 3–6 of `PLATFORM_ADMIN_PLAN.md` are not built;
only the role, bootstrap and audit log are).

---

## Findings

### BUG-001: Double-tapping "Place Order" creates two orders

**Severity:** Critical
**Status:** Confirmed by code inspection
**Affected Layer:** Backend (concurrency)
**Affected File:** `src/main/java/com/kashmirmenu/app/service/impl/OrderServiceImpl.java`
**Affected Lines:** ~74–160 (`placeOrder`)
**Affected Endpoint:** `POST /api/public/tables/{qr}/orders`

**Description:** Nothing prevents the same cart being checked out twice concurrently.

**Root Cause:** `placeOrder` reads the ACTIVE cart with an ordinary `findByRestaurantTableAndStatus`
(no lock), builds an order, and only marks the cart `CHECKED_OUT` at the very end. Two requests that
arrive before either commits both see an ACTIVE cart and both proceed. There is no idempotency key on
this endpoint — unlike the staff collection endpoint, which has one.

**Evidence:**
```java
Cart cart = cartRepository
        .findByRestaurantTableAndStatus(restaurantTable, CartStatus.ACTIVE)   // no lock
        .orElseThrow(() -> new BadRequestException("No active cart found"));
...
cart.setStatus(CartStatus.CHECKED_OUT);     // last statement, after everything else
cartRepository.save(cart);
```
Compare `PaymentServiceImpl.collectPayments`, which locks the order row (`findByIdForUpdate`) and
accepts an `idempotencyKey` — the protection exists in the codebase, just not here.

**Steps to Reproduce (safe, test environment):** add an item to a table's cart, then fire two
simultaneous `POST /api/public/tables/{qr}/orders`:
`seq 2 | xargs -P2 -I{} curl -s -XPOST .../orders -H 'Content-Type: application/json' -d '{}'`

**Expected:** one order; the second request refused.
**Actual (predicted, not observed):** two orders, each with its own number, both deducting stock, and
the coupon claimed twice — `claimOneUse` succeeds for both if the limit allows.

**Impact:** The diner is charged twice for the same food; the kitchen cooks it twice; stock and coupon
counts are both wrong. On a phone with a slow connection, double-tapping is a normal user behaviour,
not an edge case. This is the single most likely defect here to be hit in real trading.

**Recommended Fix:** Lock the cart row for the transaction (`findByRestaurantTableAndStatusForUpdate`
with `PESSIMISTIC_WRITE`) and re-assert it is still ACTIVE after acquiring the lock. Optionally accept
an idempotency key as the staff endpoint does.

**Regression Test:** An IT firing two concurrent placements against one cart, asserting exactly one
order exists and stock moved once.

**Confidence:** High

---

### BUG-002: Cancelling an order can inflate stock above what was ever deducted

**Severity:** High
**Status:** Confirmed by code inspection
**Affected Layer:** Backend (data integrity)
**Affected File:** `src/main/java/com/kashmirmenu/app/service/impl/StockServiceImpl.java`
**Affected Lines:** 31–66 (`consumeForOrder` / `restoreForOrder`)

**Description:** The deduct and restore paths are not symmetric. Restore adds stock back for any item
that *currently* has an inventory row; deduct skipped items that had no row *at the time*.

**Root Cause:** Both methods look up inventory at the moment they run, but an order can be placed
before an item is tracked and cancelled after. `consumeForOrder` takes nothing (no row); then stock
tracking is switched on for that dish; then `restoreForOrder` adds the quantity back. Nothing records
whether the order actually consumed stock.

**Evidence:**
```java
// consumeForOrder: untracked items are skipped entirely
Optional<Inventory> tracked = inventoryRepository.findByMenuItemIdForUpdate(menuItemId);
if (tracked.isEmpty()) { continue; }

// restoreForOrder: adds back unconditionally, if a row exists now
inventoryRepository.findByMenuItemIdForUpdate(item.getMenuItem().getId())
        .ifPresent(inventory -> apply(inventory, available(inventory) + item.getQuantity()));
```

**Steps to Reproduce:** place an order for a dish with no inventory row → add an inventory row for
that dish with quantity 10 → cancel the order → the count reads 12 for a two-portion order.

**Expected:** the count stays at 10.
**Actual (predicted):** 12. Stock is credited that was never taken.

**Impact:** Overstated stock leads to overselling, which is the exact failure the stock feature was
added to prevent. Likelihood is moderate: it needs tracking to be switched on between placement and
cancellation, which is plausible in the first weeks of use.

**Recommended Fix:** Record consumption on the order item (a `stock_consumed` boolean or a quantity
column) and restore only what was consumed. Minimal alternative: restore only when the order's
`createdAt` is after the inventory row's `createdAt` — weaker, and I would not prefer it.

**Regression Test:** The reproduction above, asserting the count is unchanged.

**Confidence:** High

---

### BUG-003: Any order status can transition to any other

**Severity:** High
**Status:** Confirmed by code inspection
**Affected Layer:** Backend (business logic)
**Affected File:** `src/main/java/com/kashmirmenu/app/service/impl/OrderServiceImpl.java`
**Affected Lines:** ~227–275 (`updateOrderStatus`)
**Affected Endpoint:** `PUT /api/v1/orders/{orderId}/status?status=…`

**Description:** This endpoint accepts any valid enum value with no check on the current state, while
`KitchenServiceImpl` enforces a transition table for the same underlying field.

**Root Cause:** Two write paths to `Order.status`, only one of them validated. The REST endpoint
simply parses and assigns.

**Evidence:**
```java
target = OrderStatus.valueOf(status.toUpperCase());   // only validation performed
...
order.setStatus(target);
```
`KitchenServiceImpl` has a `validateTransition` helper (`nextStatus == OrderStatus.CANCELLED` etc.)
that this path never calls.

**Consequences, in increasing severity:**
1. `SERVED → PENDING` or `CANCELLED → PREPARING` are accepted, corrupting the kitchen board.
2. **Cancelling an already-served, already-paid order restores stock and releases the coupon** —
   food that left the kitchen is credited back to inventory.
3. Cancellation leaves captured payments untouched (see BUG-004).

**Expected:** only transitions the business allows, and cancellation refused once an order is served
or paid.
**Actual (predicted):** all transitions accepted.

**Impact:** Staff can silently corrupt order history and stock, by accident or otherwise. Any
restaurant role can call it.

**Recommended Fix:** Move `validateTransition` into `OrderServiceImpl` (or a shared
`OrderStatusPolicy`) and call it from both paths; refuse cancellation when the order is `SERVED` or
has captured payments.

**Regression Test:** A parameterised test over the illegal transitions, each asserting 400.

**Confidence:** High

---

### BUG-004: Cancelling an order abandons its payments

**Severity:** High
**Status:** Confirmed by code inspection
**Affected Layer:** Backend (business logic / financial)
**Affected File:** `src/main/java/com/kashmirmenu/app/service/impl/OrderServiceImpl.java` (`updateOrderStatus`)

**Description:** The cancellation path touches the coupon and the stock, but never the payments.
`grep -n "payment" OrderServiceImpl.java` returns nothing.

**Two distinct consequences:**

1. **PENDING payments linger forever.** A diner who chose "Cash" on their phone leaves a PENDING
   payment row. Cancelling the order does not cancel it, so it stays in the reconciliation panel's
   "awaiting confirmation" bucket indefinitely, overstating money expected at the close.
2. **Captured payments stay counted as revenue** with no refund record and no prompt. Net collections
   include money taken for an order that was then cancelled.

**Expected:** cancellation cancels outstanding PENDING payments, and refuses (or explicitly requires a
refund) when money has already been captured.
**Actual (predicted):** both rows survive untouched.

**Impact:** Daily reconciliation never balances for any restaurant that cancels orders, which is
every restaurant. Compounds BUG-003, which permits cancelling a paid order in the first place.

**Recommended Fix:** In the cancellation branch, cancel PENDING payments for the order; if any payment
is captured, refuse the cancellation with a message directing the user to refund first.

**Regression Test:** Cancel an order with a pending payment → assert the payment is CANCELLED and the
reconciliation pending bucket is unchanged. Cancel an order with a captured payment → assert 400.

**Confidence:** High

---

### BUG-005: The admin Orders page loads every order ever placed

**Severity:** High
**Status:** Confirmed by code inspection
**Affected Layer:** Backend + Database (performance)
**Affected File:** `src/main/java/com/kashmirmenu/app/service/impl/OrderServiceImpl.java` (`getOrders`, ~line 184)
**Affected Endpoint:** `GET /api/v1/orders`

**Description:** Unbounded result set plus an N+1 on order items.

**Root Cause:** `orderRepository.findByRestaurant(restaurant)` has no pagination, no date bound and no
fetch join. `Order.items` is a lazy collection that the mapper then touches for every row.

**Evidence:** `grep -n "EntityGraph\|JOIN FETCH" repository/OrderRepository.java` returns nothing, so
no fetch strategy is declared anywhere for this query. `OrderMapper.toOrderResponseList` maps
`items`, forcing one additional SELECT per order.

**Expected:** a page of recent orders.
**Actual (predicted):** one query returning every order in the restaurant's history, plus one query
per order for its items. At 20,000 orders that is 20,001 queries and a large heap allocation on a
page staff keep open all day.

**Impact:** The Orders page degrades steadily with trading volume — fine in a demo, slow after a month,
a connection-pool and heap problem after a year. This is also the most likely trigger of the
`DbPoolExhausted` alert.

**Note on evidence:** I have **not** run `EXPLAIN` or measured this — no database is available. The
absence of pagination and of a fetch join is certain from the code; the performance consequence is a
reasoned prediction, not a measurement.

**Recommended Fix:** Paginate (`Page<Order>` with a server-side default size), add
`@EntityGraph(attributePaths = "items")`, and default to the current business day as the kitchen
endpoint does.

**Regression Test:** Seed 200 orders, assert the response is a page, and assert the query count with
a Hibernate statistics assertion.

**Confidence:** High (defect) / Medium (magnitude)

---

### BUG-006: Prices and availability are not re-checked at checkout

**Severity:** Medium
**Status:** Confirmed by code inspection
**Affected Layer:** Backend (business logic)
**Affected Files:** `service/impl/CartServiceImpl.java` (`addItem` ~line 83, `updateItem` ~line 160),
`service/impl/OrderServiceImpl.java` (`placeOrder`)

**Description:** `unitPrice` is copied onto the cart item when the item is **added**, and the
availability check also happens only then. `placeOrder` copies the stored price straight onto the
order without re-reading the menu.

**Evidence:** `grep -n "setUnitPrice" CartServiceImpl.java` returns a single hit, inside `addItem`.
`updateItem` changes the quantity only. `placeOrder` uses `cartItem.getUnitPrice()`.

**Consequences:**
- A price change after an item is in a cart is not reflected — the order is placed at the old price.
- An item deactivated or marked unavailable after being added is still orderable.

**Expected:** at minimum, re-validate availability at placement and decide the price policy
deliberately.
**Actual (predicted):** stale price, and unavailable items can be ordered.

**Impact:** Moderate and genuinely ambiguous: locking the price a diner was shown is defensible and
arguably *correct*. The availability half is not defensible — the kitchen receives an order for a dish
it has switched off.

**Recommended Fix:** Re-check `active`/`available` for every item in `placeOrder` and refuse with the
dish named. For price, make the policy explicit in code and docs; if it is "the price shown wins",
say so where the staleness is created.

**Regression Test:** Add an item, deactivate it, attempt to place the order → 400 naming the dish.

**Confidence:** High (behaviour) / the price half is a **policy question**, not necessarily a defect

---

### BUG-007: Two diners at one table can lose each other's cart changes

**Severity:** Medium
**Status:** Potential — needs runtime verification
**Affected Layer:** Backend (concurrency)
**Affected File:** `service/impl/CartServiceImpl.java` (`addItem`, `updateItem`, `removeItem`, `recalculateTotals`)

**Description:** The cart is shared per table, by design, and the QR flow is unauthenticated — so two
phones at the same table are two concurrent writers to one cart, with no lock.

**Root Cause:** Each mutation loads the cart, mutates items, recomputes totals and saves. Two
overlapping transactions can each compute totals from a basket that excludes the other's item.

**Expected:** both items present and the total consistent with them.
**Actual (unverified):** a lost update, or a total that does not match the items. Whether this
actually happens depends on isolation level and timing, which is why it is marked Potential rather
than Confirmed.

**Impact:** A wrong total on a shared bill — bad, though self-correcting on the next cart change,
since totals are recomputed from items each time.

**Recommended Fix:** Lock the cart row for the duration of each mutation, which also fixes BUG-001.
A `@Version` optimistic lock would surface the conflict instead, but a retry on a diner tap is a worse
experience than a brief wait.

**Regression Test:** Concurrent adds of two different dishes to one cart, asserting both land and the
total matches.

**Confidence:** Medium

---

### BUG-008: An expired coupon still discounts the order

**Severity:** Medium
**Status:** Confirmed by code inspection
**Affected Layer:** Backend (business logic)
**Affected File:** `service/impl/OrderServiceImpl.java` (`placeOrder`, coupon block)

**Description:** `placeOrder` claims a use of the coupon but never re-evaluates it. The discount is
whatever `cart.getDiscount()` held from the last cart mutation.

**Root Cause:** Validity is re-checked inside `recalculateTotals`, which only runs when the cart
changes. A cart that sits untouched across the coupon's expiry keeps the discount.

**Evidence:** The block reads `cart.getCoupon()` / `cart.getDiscount()` and calls `claimOneUse`; there
is no `CouponPricing.evaluate` call on this path. `claimOneUse` checks only the usage limit, not dates
or the active flag.

**Steps to Reproduce:** apply a coupon expiring in two minutes, wait three, place the order without
touching the cart.

**Expected:** the coupon is dropped and the order placed at full price.
**Actual (predicted):** the discount is applied after expiry, and a use is consumed.

**Impact:** Small per occurrence, systematic across a busy service. Also means an owner who
deactivates a coupon mid-service does not stop carts already holding it.

**Recommended Fix:** Call `CouponPricing.evaluate` in `placeOrder` before claiming, and fall back to
full price on refusal — the same fallback the usage-limit path already uses.

**Regression Test:** Already nearly present in `CouponFlowIT`; add a case with a coupon that expires
between application and placement.

**Confidence:** High

---

### BUG-009: The Orders page issues one request per row, every 30 seconds

**Severity:** Medium
**Status:** Confirmed by code inspection
**Affected Layer:** Frontend (performance)
**Affected File:** `frontend/src/pages/admin/OrdersPage.tsx` (`PaymentCell`)

**Description:** Each row mounts its own query for the order's payment summary, with
`refetchInterval: 30_000`.

**Evidence:**
```tsx
const summary = useQuery({
  queryKey: ['order-payment-summary', order.id],
  queryFn: () => orderApi.paymentSummary(order.id),
  retry: false,
  refetchInterval: 30_000,
});
```

**Impact:** With BUG-005 returning every order ever placed, this is one HTTP request per order every
30 seconds from every open Orders tab. The two bugs multiply: 500 orders is 500 requests per
half-minute per user. **This is my own code from an earlier phase**, and it was the wrong shape.

**Recommended Fix:** Return the payment summary with the orders page from the server (the join is
cheap there), or batch it into a single request keyed by order ids.

**Regression Test:** A frontend test asserting the page issues one request regardless of row count.

**Confidence:** High

---

### BUG-010: Requests can hang forever

**Severity:** Medium
**Status:** Confirmed by code inspection
**Affected Layer:** Frontend (integration)
**Affected File:** `frontend/src/api/client.ts`

**Description:** No timeout anywhere in the HTTP client. `grep -n "AbortController|signal|timeout"`
returns nothing.

**Root Cause:** `fetch` has no default timeout. A connection that opens and then stalls — common on a
phone moving between cells, which is precisely the diner's situation — never settles, so TanStack
Query never resolves and the spinner spins indefinitely.

**Impact:** Infinite loading states on exactly the screens where the network is least reliable. The
brief asks about "infinite loading states" specifically; this is the mechanism for them.

**Recommended Fix:** An `AbortController` with a default timeout (10s for reads, longer for order
placement) in the shared `request()` function, surfaced as a retryable error.

**Regression Test:** A client test with a never-resolving mock `fetch`, asserting rejection.

**Confidence:** High

---

### BUG-011: A diner is sent to `/order/undefined` if the backend predates the token change

**Severity:** Medium
**Status:** Confirmed by code inspection
**Affected Layer:** Integration (frontend/backend contract)
**Affected Files:** `frontend/src/pages/customer/CheckoutPage.tsx`, `frontend/src/api/types.ts`

**Description:** `publicToken` is declared optional (`publicToken?: string`) but used unguarded in
navigation.

**Evidence:**
```ts
publicToken?: string;                                   // types.ts
navigate(`/order/${order.publicToken}?t=${qr}&placed=1`) // CheckoutPage.tsx
```

**Impact:** If the frontend is deployed ahead of the backend — the exact situation that has occurred
during this project — the diner places a real order and is then sent to a dead URL with no way back to
it. The order exists and is lost to them.

**Recommended Fix:** Make the field required in the type (it is non-null server-side), and guard the
navigation: fall back to the menu with a message that names the order number.

**Regression Test:** A test that places an order whose response omits `publicToken` and asserts no
navigation to `undefined`.

**Confidence:** High

---

### BUG-012: `existsByRoleName` may not resolve at startup

**Severity:** Medium
**Status:** Needs runtime verification
**Affected Layer:** Backend (Spring Data)
**Affected File:** `src/main/java/com/kashmirmenu/app/repository/UserRepository.java`

**Description:** The derived query `existsByRoleName(RoleName name)` relies on Spring Data splitting
`RoleName` into `role.name`. `User` has no `roleName` property, so resolution depends on the
property-traversal fallback.

**Why it matters:** if it fails, it fails at **context startup**, not at call time — the application
does not boot. The platform-owner bootstrap is the only caller.

**Recommended Fix:** Rename to `existsByRole_Name(RoleName name)`, which states the traversal
explicitly and cannot be mis-parsed.

**Regression Test:** Any `@DataJpaTest` touching the repository fails fast if resolution breaks.

**Confidence:** Medium — this usually resolves correctly; I flag it because the failure mode is total
and I cannot run the context to check.

---

### BUG-013: `getCurrentUser()` re-queries the database several times per request

**Severity:** Low
**Status:** Potential — needs profiling
**Affected Layer:** Backend (performance)
**Affected File:** `security/service/impl/CurrentUserServiceImpl.java`

**Description:** Every call runs `userRepository.findByEmail(...)`. Several services call it more than
once per request (`PaymentServiceImpl` calls `assertCurrentRestaurant` per operation; the audit writer
reads the principal again).

**Why only Low:** the row is almost certainly in Hibernate's first-level cache within one transaction,
so the extra calls may cost nothing. Whether they do needs measurement, which is why this is Potential
and not Confirmed. **I will not claim a query is slow without evidence.**

**Recommended Fix:** If profiling shows repeated SELECTs, take the already-loaded `User` from
`CustomUserDetails` on the authentication, which the JWT filter has already placed in the context.

**Confidence:** Low

---

### BUG-014: CSV export is assembled entirely in memory

**Severity:** Low
**Status:** Confirmed by code inspection
**Affected File:** `controller/PaymentAnalyticsController.java` (`export`)

**Description:** Up to `MAX_EXPORT_ROWS` (50,000) rows are built into a `StringWriter` before
anything is sent.

**Trade-off, stated deliberately in the code comment:** this was chosen so a mid-stream failure
produces a clean error rather than a truncated download. The cost is a memory spike of roughly
10–15 MB per concurrent export.

**Recommended Fix:** Acceptable as is for a single-restaurant export. If platform-wide exports arrive
(they are planned), switch to `StreamingResponseBody` and accept the truncation trade-off.

**Confidence:** High

---

### BUG-015: Dead code — `findUnclaimedPlatformAccounts`

**Severity:** Low
**Status:** Confirmed
**Affected File:** `repository/UserRepository.java`

Added for the platform bootstrap and never called: the bootstrap uses `existsByRoleName` and
`existsByEmail` instead. Harmless, but it invites a future caller to assume there is a supported way
to enumerate unclaimed accounts.

**Recommended Fix:** Remove it.

---

### BUG-016: MySQL is published on all interfaces

**Severity:** Medium (deployment)
**Status:** Confirmed
**Affected File:** `docker-compose.yml`

`ports: "3306:3306"` binds `0.0.0.0`, while the app correctly binds `127.0.0.1:8080`. Previously
flagged in the observability work and left untouched as out of scope; repeating it here because this
audit is the right place for it.

**Recommended Fix:** `"127.0.0.1:3306:3306"`.

---

## Phase 10: Prioritised summary

| Metric | Count |
|---|---|
| Total findings | 16 |
| Confirmed by code inspection | 12 |
| Potential / needs runtime verification | 4 (BUG-007, 012, 013, and the magnitude of 005) |
| **Confirmed by execution** | **0 — nothing could be executed; see Phase 8** |
| Critical | 1 (BUG-001) |
| High | 4 (BUG-002, 003, 004, 005) |
| Medium | 7 |
| Low | 4 |
| Frontend | 3 (BUG-009, 010, 011) |
| Backend / business logic | 9 |
| Database / query | 2 (BUG-005, and the locking half of 001/007) |
| Integration | 2 (BUG-011, 012) |
| Performance risks | 4 (BUG-005, 009, 013, 014) |

### Grouped by root cause

- **Missing pessimistic locking on the cart** → BUG-001 and BUG-007. One fix addresses both.
- **Order cancellation is incomplete** → BUG-002 (stock), BUG-003 (transitions), BUG-004 (payments).
  These should be fixed together; fixing cancellation without fixing transitions leaves the hole open.
- **Unbounded listing amplified by per-row queries** → BUG-005 and BUG-009 multiply each other.

### Missing test coverage

The gap is concurrency and the cancellation path. There is no test anywhere that fires two
simultaneous requests, and none that cancels an order with payments or stock attached. Both
areas hold the most severe findings here, which is not a coincidence.

### Recommended remediation order

1. **BUG-001** — cart locking. Highest user impact, small and well-understood fix.
2. **BUG-003 + BUG-004 + BUG-002** — the cancellation cluster, as one change.
3. **BUG-011** — one-line guard preventing a diner losing a real order.
4. **BUG-005 + BUG-009** — pagination and the per-row query, together.
5. **BUG-010** — client timeout.
6. **BUG-006 (availability half)**, **BUG-008** — correctness at placement.
7. **BUG-012** — rename before it bites at startup.
8. **BUG-016** — one character.
9. **BUG-013, 014, 015** — only with evidence, or as cleanup.

---

## What could not be verified

- Nothing in the Spring layer was compiled or run. No endpoint has been called, no query executed, no
  migration applied.
- No `EXPLAIN`, no query plan, no index evidence — so **no claim here asserts a measured performance
  figure**. BUG-005's magnitude is reasoned from the absence of pagination, not observed.
- The frontend typecheck and Vitest suite could not complete because the dependency install broke
  partway (intermittent network). The TS error seen is an artefact of that broken install, not a code
  defect, and the partial `node_modules` was deleted.
- No concurrency behaviour was observed; BUG-001 and BUG-007 are reasoned from the absence of locks.
- Frontend rendering, responsiveness and accessibility were reviewed only as code, not in a browser,
  beyond the static renders done in earlier phases.

`BUG_FIX_PLAN.md` contains the per-fix plan. **No code has been changed, and none will be until you
approve it.**
