# Bug Fix Plan

Companion to `FULL_STACK_BUG_AUDIT.md`. **Nothing here has been implemented.** Awaiting approval.

Complexity is given as S / M / L: **S** is an hour or so with its test, **M** is half a day, **L**
needs design discussion first.

---

## Fix 1 — Cart locking (BUG-001 Critical, BUG-007 Medium)

**Files:** `repository/CartRepository.java`, `service/impl/OrderServiceImpl.java`,
`service/impl/CartServiceImpl.java`

**Change:** add a locking finder and use it on every path that mutates a cart or checks it out.

```java
// CartRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Cart c where c.restaurantTable = :table and c.status = :status")
Optional<Cart> findForUpdate(@Param("table") RestaurantTable table, @Param("status") CartStatus status);
```
`placeOrder` then uses `findForUpdate` and re-asserts the status after acquiring the lock. The second
of two concurrent checkouts waits, finds the cart `CHECKED_OUT`, and fails with "No active cart found"
— which is already the correct message for that state.

**Side effects:** cart mutations serialise per table. That is the intent; the lock is held for
milliseconds. Note the lock orders matter — `placeOrder` already locks the restaurant's order counter
and inventory rows, so take the cart lock **first** and keep that order consistent everywhere, or two
concurrent checkouts on different tables could deadlock on inventory.

**Migration:** none.

**Tests:** an IT firing two concurrent placements at one cart, asserting exactly one order and one
stock deduction; a concurrent-add test for BUG-007.

**Complexity:** S (the deadlock-ordering review is the careful part)

**Depends on:** nothing. Do this first.

---

## Fix 2 — The cancellation cluster (BUG-003 High, BUG-004 High, BUG-002 High)

Fix as one change; separately they leave each other's holes open.

**Files:** `service/impl/OrderServiceImpl.java`, `service/impl/StockServiceImpl.java`,
`service/PaymentService.java` + impl, new `service/OrderStatusPolicy.java`, `entity/OrderItem.java`,
new migration

### 2a. One transition policy

Extract `KitchenServiceImpl.validateTransition` into an `OrderStatusPolicy` used by both write paths.
Refuse cancellation when the order is `SERVED`, or when any payment on it is captured.

### 2b. Cancellation handles payments

Add `PaymentService.cancelPendingPaymentsForOrder(order)`; call it from the cancellation branch.
Captured payments are *not* auto-refunded — refunds must stay a deliberate act — so cancellation is
refused with a message directing the user to refund first.

### 2c. Stock restores only what it took

```sql
-- V26__order_item_stock_consumed.sql
ALTER TABLE order_items ADD COLUMN stock_consumed INT NOT NULL DEFAULT 0;
```
`consumeForOrder` records what it actually deducted per line; `restoreForOrder` adds back exactly
that and nothing else.

**Backfill decision, needs your input:** existing rows default to `0`, meaning *cancelling an
old order restores nothing*. That is the safe direction (understating stock beats overselling), but it
differs from today's behaviour for orders placed before the migration. The alternative — backfilling
`stock_consumed = quantity` where the dish is tracked — reproduces the current bug for historical
rows. I recommend `0`.

**Side effects:** staff lose the ability to cancel a served order, which is the point. If they
currently rely on that as an undo, they will need the refund flow instead — worth confirming before
shipping.

**Tests:** illegal transitions each → 400; cancel with a pending payment → payment CANCELLED and
reconciliation unchanged; cancel with a captured payment → 400; the BUG-002 reproduction → count
unchanged.

**Complexity:** M

**Depends on:** nothing, but do after Fix 1 so the concurrency ground is stable.

---

## Fix 3 — Guard the order-placed navigation (BUG-011 Medium)

**Files:** `frontend/src/api/types.ts`, `frontend/src/pages/customer/CheckoutPage.tsx`

Make `publicToken` required in the `Order` type (it is non-null server-side) and guard the navigation:
if it is absent, go to the menu with a message naming the order number rather than to
`/order/undefined`.

**Side effects:** none. **Migration:** none. **Complexity:** S

**Do this early** — it is minutes of work and prevents a diner losing a real order during any future
deploy where the frontend leads the backend.

---

## Fix 4 — Paginate orders and stop the per-row fetch (BUG-005 High, BUG-009 Medium)

**Files:** `repository/OrderRepository.java`, `service/OrderService.java` + impl,
`controller/OrderController.java`, `dto/response/OrderResponse.java`,
`frontend/src/api/index.ts`, `frontend/src/pages/admin/OrdersPage.tsx`

**Backend:** `Page<Order> findByRestaurant(Restaurant, Pageable)` with
`@EntityGraph(attributePaths = "items")`, a default page size and a default date window matching the
kitchen's business-day scope. Include the payment summary fields (`settled`, `outstanding`, `status`)
on `OrderResponse`, computed in the same query path.

**Frontend:** delete `PaymentCell`'s per-row query and read the new fields from the row.

**Side effects:** this is a **breaking API change** — `GET /api/v1/orders` returns a page rather than
an array. The only consumer is `orderApi.list()`, so the blast radius is one file, but the shape
change must land in both halves together.

**Migration:** none, though this is the point to check that `orders(restaurant_id, created_at)` is
indexed — `V8` should be reviewed before assuming it is.

**Tests:** seed 200 orders, assert a page comes back and that the query count does not scale with row
count (Hibernate statistics).

**Complexity:** M

**Depends on:** Fix 2, since the response gains payment-derived fields that Fix 2 also touches.

---

## Fix 5 — HTTP timeout (BUG-010 Medium)

**File:** `frontend/src/api/client.ts`

An `AbortController` in the shared `request()`: 10s default, 30s for order placement and payment
collection (they do real work under lock), surfaced as a distinct retryable error so TanStack Query
treats it as a failure rather than hanging.

**Side effects:** a slow-but-working request now fails where it previously completed. The 30s
allowance for writes exists for exactly that reason; worth reviewing the threshold against the
baseline k6 run.

**Tests:** a client test with a never-resolving mock `fetch`, asserting rejection within the timeout.

**Complexity:** S

---

## Fix 6 — Re-validate at placement (BUG-006 Medium, BUG-008 Medium)

**File:** `service/impl/OrderServiceImpl.java` (`placeOrder`)

Before building the order:
1. Re-check each item's `active` and `available`; refuse naming the dish.
2. Call `CouponPricing.evaluate` and fall back to full price if it refuses — reusing the fallback the
   usage-limit path already has.

**Price policy, needs your decision:** currently the price shown when the item was added wins.
I think that is defensible and would **keep** it, documenting it where the staleness is created rather
than "fixing" it into a surprise charge. Say if you want current prices to win instead.

**Side effects:** an order can now be refused at the last step, so the checkout screen needs to show
that error well — it currently assumes placement succeeds.

**Tests:** extend `CouponFlowIT` with a coupon expiring between application and placement; a new case
deactivating an item after it is in the cart.

**Complexity:** S

---

## Fix 7 — Rename the derived query (BUG-012 Medium)

**File:** `repository/UserRepository.java`

`existsByRoleName` → `existsByRole_Name`, and update the single caller in `PlatformOwnerBootstrap`.
Explicit traversal cannot be mis-parsed, and the failure mode today is a context that will not start.

**Complexity:** S. **Do this before the next deploy**, since it costs nothing and the downside is total.

---

## Fix 8 — Bind MySQL to localhost (BUG-016 Medium)

**File:** `docker-compose.yml` — `"3306:3306"` → `"127.0.0.1:3306:3306"`.

**Side effect:** anyone connecting to the database from another host loses access. If a teammate
relies on that, they will need an SSH tunnel.

**Complexity:** S

---

## Fix 9 — Cleanup, only with evidence (BUG-013, 014, 015)

- **BUG-015** (dead repository method): delete. S.
- **BUG-013** (repeated `getCurrentUser`): **do not change without profiling.** If Hibernate's
  first-level cache is absorbing the calls, a refactor adds risk for no gain. Measure first with the
  new metrics.
- **BUG-014** (in-memory CSV): leave until platform-wide exports exist, then switch to
  `StreamingResponseBody` and accept the truncation trade-off deliberately.

---

## Suggested sequencing

| Order | Fixes | Why here |
|---|---|---|
| 1 | Fix 1 (cart locking) | Highest impact, self-contained, unblocks safe concurrency elsewhere |
| 2 | Fix 3, Fix 7, Fix 8 | Minutes each, no dependencies, all prevent a bad outcome |
| 3 | Fix 2 (cancellation cluster) | The largest correctness gain; needs the migration decision |
| 4 | Fix 6 | Small, and benefits from Fix 2's policy class |
| 5 | Fix 4 | Breaking API shape; land both halves together |
| 6 | Fix 5 | Independent; calibrate the write timeout after a baseline k6 run |
| 7 | Fix 9 | Cleanup, and only where evidence supports it |

## Cross-cutting test work

Two gaps enabled the worst findings here:

1. **No concurrency test anywhere.** A small helper for firing N simultaneous requests in an IT would
   have caught BUG-001 and BUG-007.
2. **No test cancels an order that has payments or stock attached**, which is why BUG-002 and BUG-004
   survived three phases of work on those exact subsystems.

I would add both as part of Fix 1 and Fix 2 rather than afterwards.

## Standing limitation

Nothing in this repository's Spring layer can be compiled or run in my environment, and the frontend
dependency install is unreliable here. Every fix above will need `./gradlew test` and
`npm run typecheck && npm test` on your side; I will deliver them with static verification and
mutation-tested checkers as before, and will say plainly which parts remain unexecuted.
