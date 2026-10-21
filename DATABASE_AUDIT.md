# Database and Query Audit — KashmirMenu

> **Remediation status.** All findings are implemented except DB-06 - see `CHANGES.txt` phases 19-20.
> **DB-06 is deliberately not fixed:** it is the one finding whose magnitude depends on measurement,
> and changing lock scope on the hottest write path without evidence would be guesswork. Measure it
> with the k6 baseline first. The findings below are kept as written at audit time.

**No code had been changed when this was written.** The analysis is as-audited.

**Date:** 14 October 2026
**Scope:** 26 Flyway migrations, 19 repositories, 3 Criteria specifications, the JPQL aggregation
layer, transaction boundaries, locking, pagination.

---

## Evidence and its limits — read first

Everything below is derived from **the actual schema DDL and the actual query text** in this
repository. Where I name an index, I have read the `CREATE`/`ALTER` that makes it; where I say one is
absent, I have grepped every migration for it.

**What I could not do:** there is no database in this environment, so **no `EXPLAIN`, no query plan,
no row counts, no timings.** Per your instruction I therefore do not claim any query *is* slow. The
findings are of two kinds, and I have labelled every one:

- **Structural** — provable from DDL and query text alone (a predicate that cannot use an index, a
  missing composite index for a query's exact shape, a lock held across a known scope). High
  confidence.
- **Needs measurement** — a suspected cost whose magnitude depends on data volume. Stated as a
  hypothesis with the `EXPLAIN` that would settle it.

One structural fact used throughout: **InnoDB requires an index on every foreign key column**, so
each `FOREIGN KEY` in the DDL implies a single-column index. I count those as present. InnoDB also
appends the primary key to every secondary index, which matters in finding DB-04.

---

## What the schema actually has

| Table | Indexes beyond the primary key |
|---|---|
| `orders` | `uk_order_number`, `uk_orders_public_token`, `idx_orders_coupon`, FK `restaurant_id`, FK `restaurant_table_id` |
| `order_items` | FK `order_id`, FK `menu_item_id` |
| `payments` | `idx_payments_order`, `idx_payments_restaurant_time`, `idx_payments_restaurant_status_time`, `uk_payments_idempotency`, FKs |
| `payment_refunds` | `idx_refunds_payment`, `idx_refunds_time`, FKs |
| `carts` | FK `restaurant_id`, FK `restaurant_table_id` — **nothing else** |
| `cart_items` | FKs only |
| `coupons` | `UNIQUE (restaurant_id, code)`, FK |
| `inventory` | `UNIQUE (menu_item_id)`, FKs |
| `menu_items` | `idx_menu_restaurant`, `idx_menu_category`, `idx_menu_active`, `idx_menu_available`, FKs |
| `categories` | `UNIQUE (restaurant_id, name)`, `idx_category_restaurant`, `idx_category_name` |
| `notifications` | FK `restaurant_id`, FK `order_id` — **nothing else** |
| `audit_log` | `idx_audit_time`, `idx_audit_actor`, `idx_audit_action` |
| `users` | `UNIQUE (email)`, `idx_user_role`, `idx_user_active` |
| `cash_reconciliations` | `idx_cashrec_restaurant_date`, FKs |
| `restaurant_order_counters` | PK `restaurant_id` only |

The payments and audit tables are well indexed for the queries written against them. `orders`,
`carts` and `notifications` are not.

---

## Findings

### DB-01 — `orders` has no composite index for any of its filtered-and-ordered queries

**Severity:** High · **Type:** Structural · **Confidence:** High

**Evidence.** `V8__Create_Orders.sql` creates `orders` with indexes only on `order_number` (unique),
the two FK columns, and later `public_token` (unique) and `coupon_id`. There is no index on
`(restaurant_id, created_at)` or `(restaurant_id, status, ...)`.

Four queries filter on `restaurant_id` **and** another column, and three of them also sort:

```java
// OrderRepository
@Query("select o from Order o where o.restaurant.id = :restaurantId and o.createdAt >= :since
        order by o.createdAt desc")
List<Order> findSince(...);                                     // kitchen "today" tab

long countByRestaurantIdAndCreatedAtBetween(...);               // dashboard "today's orders"
long countByRestaurantIdAndStatus(...);                         // dashboard status tiles (x4)
List<Order> findByRestaurantAndStatusInOrderByCreatedAtAsc(...);// kitchen live board
```

With only `restaurant_id` indexed, MySQL can narrow to the restaurant and must then evaluate the date
or status predicate per row and sort the survivors. That is structurally certain. **How much it costs
depends on how many orders a restaurant has** — which is the part I cannot measure.

**Note the dashboard calls `countByRestaurantIdAndStatus` four times per page load** (pending,
preparing, ready, served), so this shape is hit repeatedly on a screen staff leave open.

**Proposed indexes:**
```sql
CREATE INDEX idx_orders_restaurant_created ON orders (restaurant_id, created_at);
CREATE INDEX idx_orders_restaurant_status_created ON orders (restaurant_id, status, created_at);
```
The second serves the kitchen board, the status counts and the status-filtered list; the first serves
the date-range queries.

**How to confirm before building them:**
```sql
EXPLAIN SELECT * FROM orders WHERE restaurant_id = ? AND created_at >= ? ORDER BY created_at DESC;
-- expect today: type=ref key=fk_order_restaurant, with "Using filesort" in Extra
EXPLAIN SELECT COUNT(*) FROM orders WHERE restaurant_id = ? AND status = 'PENDING';
```

---

### DB-02 — The coupon lookup cannot use its unique index

**Severity:** Medium · **Type:** Structural · **Confidence:** High

**Evidence.** `coupons` has `UNIQUE (restaurant_id, code)` (`V13`). The lookup is:

```java
@Query("select c from Coupon c where c.restaurant = :restaurant and upper(c.code) = upper(:code)")
Optional<Coupon> findByRestaurantAndCodeIgnoreCase(...);
```

`upper(c.code)` wraps the indexed column in a function, so MySQL cannot use the `code` half of the
index. It can still use `restaurant_id` as a prefix, then applies `upper()` row by row within that
restaurant. Coupon counts per restaurant are small, so the **practical** cost is probably negligible —
but the index is not doing the job it was created for, and the same pattern on a larger table would
matter.

**Root cause is a real requirement**, not carelessness: diners type codes by hand, so matching must be
case-insensitive. I added this query myself for exactly that reason.

**Options, in order of preference:**
1. **Normalise on write.** Store `code` upper-cased (it is already displayed that way, and the admin
   form uppercases as you type), then match exactly: `c.code = :code` with the caller upper-casing.
   Uses the index fully. Needs a one-line migration to upper-case existing codes.
2. Rely on collation. MySQL's default `utf8mb4_0900_ai_ci` is already case-insensitive, so
   `c.code = :code` would match case-insensitively **without** `upper()` — but that silently depends
   on the column's collation, which is not declared in the DDL. I would not rely on an undeclared
   collation for correctness.
3. Leave it. Defensible given the table size; the reason to fix it is consistency, not speed.

**Recommendation: option 1**, because it also makes the stored data consistent with what is displayed.

---

### DB-03 — The audit-log search defeats its own indexes

**Severity:** Medium · **Type:** Structural · **Confidence:** High

**Evidence.** `audit_log` has three well-chosen indexes. The search query then does this:

```java
where (:actions is null or a.action in :actions)
and (:actorEmail is null or lower(a.actorEmail) like lower(concat('%', :actorEmail, '%')))
and (:from is null or a.occurredAt >= :from)
and (:to is null or a.occurredAt < :to)
```

Two distinct problems:

1. **The `:param is null or …` idiom.** MySQL cannot know at plan time which branch applies, so it
   generally cannot use an index for these predicates. This is the standard reason a "one query, all
   filters optional" method scans.
2. **A leading-wildcard `LIKE` on a function.** `like '%x%'` cannot use an index at all, and
   `lower()` would prevent it even without the wildcard.

The audit log is **append-only and unbounded** — it grows with every sign-in, forever. This is the one
table here whose row count only ever increases, so a scanning search is the finding most likely to get
worse on its own.

**Proposed fix:** build the predicates with the Criteria API or Specifications, adding only the filters
actually supplied, as `PaymentSpecifications` already does elsewhere in this codebase. That lets
`idx_audit_action` and `idx_audit_time` be used when those filters are present. For the email search,
either match a prefix (`like 'x%'`, which can use an index) or accept the scan and bound it by always
requiring a date range.

**To confirm:** `EXPLAIN` the generated SQL with only a date filter set and check whether
`idx_audit_time` is chosen.

---

### DB-04 — Where the schema is already right, and why

Stated explicitly so these are not "fixed" unnecessarily:

- **The paged orders query needs no new index.** `findByRestaurantOrderByIdDesc` filters
  `restaurant_id` and orders by `id DESC`. InnoDB appends the primary key to every secondary index, so
  the FK index on `restaurant_id` is physically `(restaurant_id, id)` and can satisfy both the filter
  and the ordering without a sort. This is why I ordered by `id` rather than `created_at` when fixing
  the pagination — ordering by `created_at` there would have needed DB-01's index.
- **`payments` is properly indexed** for the analytics layer: `(restaurant_id, payment_timestamp)` and
  `(restaurant_id, payment_status, payment_timestamp)` match the aggregation queries' exact shapes.
- **All 28 `@ManyToOne` associations are explicitly `LAZY`** — I checked every one. There is no eager
  to-one fetch inflating queries, which is the commonest JPA performance defect and is absent here.
- **The analytics layer aggregates in the database**, returning grouped rows rather than entities.
- **`cash_reconciliations`, `payment_refunds`, `categories`, `menu_items`** all have indexes matching
  their query shapes.

---

### DB-05 — N+1 on the kitchen endpoints

**Severity:** Medium · **Type:** Structural · **Confidence:** High

**Evidence.** Exactly two lazy collections exist: `Cart.items` and `Order.items`. Only one query
fetch-joins `Order.items` — `findWithItemsByIdIn`, used by the paged orders list. The kitchen
endpoints do not:

```java
findByRestaurantAndStatusInOrderByCreatedAtAsc(...)   // live board
findSince(...)                                        // today tab
```

`KitchenOrderResponse` includes `List<OrderItemResponse> items`, so the mapper touches the collection
for every order and triggers one `SELECT` per order.

**Why it matters more than it looks:** the kitchen board is **polled every 20 seconds** by every open
kitchen display, and the "today" tab every 30 seconds. So this is a sustained multiplier, not a
one-off page cost. The live board is naturally small (orders in progress), but the today tab grows
through the day.

**Proposed fix:** the two-query pattern already used for the orders page — fetch the orders, then
`findWithItemsByIdIn` for that set. Do **not** add an `items` `@EntityGraph` to a paged query; see
DB-09.

**To confirm:** enable `spring.jpa.properties.hibernate.generate_statistics` and count queries for one
board refresh, or read the SQL log with `show-sql` on the dev profile.

---

### DB-06 — Order placement serialises per restaurant on the counter row

**Severity:** Medium (scalability) · **Type:** Structural · **Confidence:** High

**Evidence.** `placeOrder` takes, in documented order:

| Lock | Row | Scope of contention |
|---|---|---|
| `restaurant_tables` | the table | one table — correct and narrow |
| `coupons` (conditional UPDATE) | the coupon | one coupon |
| **`restaurant_order_counters`** | **the restaurant** | **every checkout in the restaurant** |
| `inventory` | per dish | one dish, across all tables |

The counter lock is held from mid-transaction until commit, and it is **per restaurant**, so every
concurrent checkout at every table queues behind it. That is a throughput ceiling on the hottest write
path in the application.

**I introduced this** when replacing the random order numbers with a readable per-restaurant sequence.
The trade-off was deliberate — a readable number requires a shared sequence — but the contention scope
is worth stating plainly.

**Is it actually a problem?** Probably not yet: the lock is held for the remainder of a short
transaction, and a single restaurant placing more than a few orders per second is a busy restaurant.
**This is the finding most in need of measurement rather than action.** The signal to watch is
`hikaricp_connections_pending` on the Database dashboard during the k6 baseline run — if checkout
latency rises with concurrent virtual users while CPU stays flat, this is why.

**If it does need fixing**, in increasing order of cost: (a) take the counter lock as late as possible
— it is currently acquired before stock, so reordering to make it the last lock shortens the hold;
(b) move numbering to a per-restaurant-per-day sequence, reducing collisions but not the lock; (c)
accept gaps and use a separate transaction for the counter, which decouples it entirely at the cost of
skipped numbers when an order rolls back.

Also note the pool is `maximum-pool-size: 10`. Lock waits occupy a connection, so sustained contention
here surfaces as pool exhaustion before it surfaces as slow orders.

---

### DB-07 — `carts` has no unique constraint on one active cart per table

**Severity:** Medium · **Type:** Structural · **Confidence:** High

**Evidence.** `V6__Create_Cart.sql` creates `carts` with FK indexes only. `getOrCreateActiveCart`
does find-then-insert on `(restaurant_table, status = ACTIVE)`.

The application-level fix (locking the table row) closes the race, and I verified the lock ordering.
But the **database** still permits two ACTIVE carts for one table, and if one ever appears — a direct
SQL fix, a future code path that forgets the lock, a restore — then
`findByRestaurantTableAndStatus` returns two rows into an `Optional` and throws on **every subsequent
cart read for that table**, permanently.

**Proposed fix:** a partial-uniqueness constraint. MySQL has no filtered indexes, so the usual idiom is
a generated column:

```sql
ALTER TABLE carts
    ADD COLUMN active_table_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN restaurant_table_id END) STORED,
    ADD UNIQUE INDEX uk_carts_one_active_per_table (active_table_id);
```
This makes the invariant impossible to violate rather than merely unlikely. It also gives the
`findByRestaurantTableAndStatus` lookup an index it does not currently have.

**Caution:** this will **fail to apply** if duplicates already exist, so it needs a check first:
```sql
SELECT restaurant_table_id, COUNT(*) FROM carts WHERE status = 'ACTIVE'
GROUP BY restaurant_table_id HAVING COUNT(*) > 1;
```

---

### DB-08 — Unbounded list queries remain on four tables

**Severity:** Medium · **Type:** Structural · **Confidence:** High

**Evidence.** 27 repository methods return a bare `List` with no `Pageable`. Most are legitimately
bounded (a cart's items, an order's payments, one day's reconciliation). Four are not:

| Query | Grows with | Consumer |
|---|---|---|
| `OrderRepository.findByRestaurant` | every order ever | now unused — **dead**, retained after the pagination fix |
| `OrderRepository.findByRestaurantAndStatus` (via `getOrdersByStatus`) | orders in that status | `GET /api/v1/orders/status/{status}` — no frontend consumer |
| `NotificationRepository.findByRestaurantOrderByCreatedAtDesc` | every notification ever | `GET /api/v1/notifications` |
| `InventoryRepository.findByRestaurant` / `CouponRepository.findByRestaurant` | menu size | naturally bounded — not a concern |

`notifications` is the live one: a row is written per order placed, per payment, per status change, and
the full-history read has **no index supporting its `ORDER BY created_at DESC`** either (the table has
FK indexes only). It is the second unbounded-growth table after `audit_log`.

**Proposed:** paginate notifications and `getOrdersByStatus`; delete the now-dead `findByRestaurant`;
add `CREATE INDEX idx_notifications_restaurant_created ON notifications (restaurant_id, created_at);`

---

### DB-09 — Standing trap: fetch-joining a collection with a `Pageable`

**Severity:** Informational · **Type:** Structural · **Confidence:** High

Not a current defect — `docs/paging_check.py` verifies the codebase is clean — but it belongs in a
database audit because it is the single easiest way to make this schema perform badly, and the
"obvious" fix for DB-05 would introduce it.

Fetch-joining a to-many association alongside a `Pageable` means Hibernate cannot apply SQL `LIMIT`; it
reads every matching row and discards the rest in memory (`HHH90003004`). Results stay correct, only
the cost is wrong, so it does not show up in testing. The checker distinguishes to-one fetch joins
(which page fine — `PaymentRepository.findAll` does this correctly) from to-many.

---

### DB-10 — Transaction boundaries

**Severity:** Low · **Type:** Structural · **Confidence:** Medium-High

Reviewed all 18 service implementations. Boundaries are broadly sound: 38 `@Transactional`
annotations, 30 of them `readOnly = true` on read paths, which is the right ratio.

Two observations:

1. **`AuthServiceImpl` has one `@Transactional` and no `readOnly` variant.** `login` performs a read
   plus an audit write, so a write transaction is defensible — but the reads inside it do not benefit
   from the read-only optimisation. Minor.
2. **WebSocket publishing happens inside the notification transaction.**
   `NotificationServiceImpl.publish` calls `notificationPublisher.publish(...)` at line 96, inside a
   class-level `@Transactional`. Network I/O inside a transaction holds the connection — and in
   `placeOrder`'s case, holds it **while the order counter and inventory locks are still held**.
   `SimpMessagingTemplate.convertAndSend` to an in-memory `SimpleBroker` is fast and non-blocking in
   the normal case, so this is unlikely to bite today; it would matter if the broker were ever
   replaced with an external one (RabbitMQ, Redis). Worth knowing, not worth changing now.

**No self-invocation bugs found.** I specifically re-checked `AuditServiceImpl`, where
`@Transactional(REQUIRES_NEW)` sits on the public entry points rather than the private writer — on the
writer it would be bypassed by the proxy and silently join the caller's transaction.

---

### DB-11 — Deadlock exposure

**Severity:** Low · **Type:** Structural · **Confidence:** Medium

Five repositories take `PESSIMISTIC_WRITE` locks. A documented order exists —
`restaurant_tables → coupons → order counter → inventory` — and `docs/authz_check.py`'s companion
lock-order check confirms every multi-lock method follows it.

**Residual risk:** `inventory` rows are locked **per dish, in order-item iteration order**. Two
concurrent orders containing the same two dishes in opposite line order could in principle acquire
them in opposite order and deadlock. Whether `order.getItems()` has a stable order across transactions
is not guaranteed by the mapping (it is a `List` with no `@OrderBy`).

**Proposed fix:** sort by menu item id before locking in `StockServiceImpl.consumeForOrder` — a
two-line change that removes the possibility rather than relying on insertion order. Cheap enough to
do regardless of whether it has ever occurred.

**Cannot be confirmed without execution:** this needs concurrent runs against MySQL and an inspection
of `SHOW ENGINE INNODB STATUS` after a deadlock.

---

## Priority

| ID | Finding | Severity | Type | Fix cost | Needs migration |
|---|---|---|---|---|---|
| DB-01 | `orders` composite indexes absent | High | Structural | S | yes (index only) |
| DB-05 | N+1 on polled kitchen endpoints | Medium | Structural | S | no |
| DB-03 | Audit search defeats its indexes | Medium | Structural | M | no |
| DB-08 | Unbounded notification/status reads | Medium | Structural | M | yes (index only) |
| DB-07 | No DB guarantee of one active cart | Medium | Structural | S | yes |
| DB-02 | Coupon lookup bypasses its index | Medium | Structural | S | yes (data) |
| DB-06 | Per-restaurant checkout serialisation | Medium | **Measure first** | — | — |
| DB-11 | Inventory lock ordering by iteration | Low | Structural | S | no |
| DB-10 | I/O inside a transaction | Low | Structural | S | no |
| DB-04/09 | Already correct / standing trap | Info | — | — | — |

### Recommended order

1. **DB-01 and DB-08's indexes together** — pure additions, no behaviour change, no application code.
   Safest possible first step, and DB-01 is the highest-value item here.
2. **DB-05** — the only finding with a *sustained* cost (polled every 20s), and it reuses a pattern
   already proven in this codebase.
3. **DB-11 and DB-10** — two small hardening changes, no schema impact.
4. **DB-07 and DB-02** — correctness and consistency; both touch data, so they want care.
5. **DB-03** — the largest code change of the set, and the table is still small today.
6. **DB-06** — only after the k6 baseline tells us whether it is real.

### The measurement I would do first

Before any of it, three commands would convert most "needs measurement" into fact:

```sql
-- actual row counts, which decide whether DB-01 is urgent or theoretical
SELECT 'orders', COUNT(*) FROM orders UNION ALL
SELECT 'notifications', COUNT(*) FROM notifications UNION ALL
SELECT 'audit_log', COUNT(*) FROM audit_log UNION ALL
SELECT 'payments', COUNT(*) FROM payments;

-- whether the dashboard's hottest query sorts
EXPLAIN SELECT COUNT(*) FROM orders WHERE restaurant_id = 1 AND status = 'PENDING';
EXPLAIN SELECT * FROM orders WHERE restaurant_id = 1 AND created_at >= '2026-10-14'
        ORDER BY created_at DESC;
```

If `orders` holds a few thousand rows, DB-01 is housekeeping. At six figures it is the first thing to
fix. I cannot tell which from here, and I would rather you ran three queries than have me guess.

---

## Explicitly not claimed

- That any query **is** slow. No plan or timing was obtained.
- That any index is unused. `idx_orders_coupon`, `idx_menu_available` and `idx_audit_actor` have no
  current query that obviously needs them, but proving an index unused needs
  `performance_schema.table_io_waits_summary_by_index_usage`, not inspection.
- Anything about MySQL configuration, buffer pool sizing, or the storage layer.
- That the fixes above are sufficient for a given load. That is what the k6 baseline is for.
