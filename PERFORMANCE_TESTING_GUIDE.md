# Performance Testing Guide

k6 scripts for the KashmirMenu API, in `k6/`.

## Read this first

**Never run a load, stress, spike or soak test against production.** The scripts enforce this rather
than trusting it: anything heavier than the smoke test calls `assertLoadTestingAllowed()`, which
refuses unless `ENVIRONMENT` is **explicitly set** to a recognised non-production value and
`BASE_URL` does not look like production.

Forgetting the variable refuses the run. That was not true in the first version of the guard —
`ENVIRONMENT` fell back to `local`, so an unset variable *authorised* a load test against whatever
`BASE_URL` pointed at. The guard's logic is now unit-tested, including that case.

Tests **create their own data** through the public registration and ordering APIs — a throwaway
restaurant, owner, dish and table per run, with unique `@k6.invalid` addresses. No real account and
no production data is ever used.

## The scripts

| Script | Shape | Use |
|---|---|---|
| `smoke.js` | 1 VU, 1 iteration | Does it work at all? Safe anywhere, including CI |
| `baseline.js` | 10 VUs, 3 min | **Run this first.** Establishes the real latency profile |
| `stress.js` | steps to 60 VUs | Where does it break? |
| `spike.js` | 5 → 80 → 5 VUs | A queue of diners all scanning at once, and recovery after |
| `soak.js` | 10 VUs, 2 h | Leaks and slow degradation |
| `authenticated.js` | 5 VUs | Staff dashboards and payment analytics |
| `tables.js` | 30 guests + 2 staff, 4 min | **The floor under load**: many tables, guests ordering and paying at once, staff confirming and closing tables. Asserts zero 5xx |

Two journeys are covered, because their cost profiles differ: the **diner path** (table → menu → cart
→ order → track), which is mostly single-row reads with one heavy write, and the **staff path** (sign
in → kitchen board → dashboard → analytics), which aggregates over date ranges.

```bash
# Smoke
BASE_URL=http://localhost:3000 k6 run k6/smoke.js

# Baseline, with results in Grafana
ENVIRONMENT=local BASE_URL=http://localhost:3000 \
  k6 run --out experimental-prometheus-rw k6/baseline.js

# Heavier, with explicit workload
ENVIRONMENT=staging BASE_URL=https://staging.example.com K6_VUS=40 K6_DURATION=5m \
  k6 run k6/stress.js
```

### Parameters

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:3000` | Target origin |
| `ENVIRONMENT` | *unset* | **Must be set** for anything but smoke |
| `K6_VUS` | per script | Peak virtual users |
| `K6_DURATION` | per script | Duration of the sustained stage |
| `THINK_TIME` | 2–5s | Pause between iterations |
| `THRESHOLD_MENU_MS`, `THRESHOLD_CART_MS`, `THRESHOLD_ORDER_MS`, `THRESHOLD_STAFF_MS` | see below | Latency thresholds |
| `THRESHOLD_ERROR_RATE` | `0.01` | Acceptable failure ratio |

## Thresholds are hypotheses, not SLOs

The defaults are **starting points chosen from the shape of each operation**, not measurements. They
have never been validated against this application, because that requires running it.

| Journey | Provisional P95 | Reasoning |
|---|---|---|
| `public:menu` | 400 ms | A read a diner is standing there waiting for |
| `public:cart-add` | 600 ms | A write plus a cart recalculation, on a phone network |
| `public:order-place` | 1200 ms | The heaviest write: locks the order, the number counter, stock and the coupon in one transaction |
| `staff:*` | 1500 ms | Aggregates over a date range; a manager at a desk tolerates more |
| error rate | < 1% | — |

**Calibrate them like this:**

1. Seed a test environment with realistic volume — a few hundred orders, not three. Analytics
   endpoints are fast on an empty database and that number tells you nothing.
2. Run `baseline.js` and record the observed P95 per endpoint.
3. Set each threshold to roughly 1.5× the observed P95 — tight enough to catch a regression, loose
   enough to survive normal variance.
4. Put those numbers in your CI environment, not in `lib/config.js`, so each environment can differ.
5. Revisit after any change to the order, payment or analytics paths.

Until step 2 is done, a failing threshold means "slower than guessed", not "too slow".

## Reading the results

Throughput is the least interesting number. What matters is what the load **cost**:

- **Database dashboard → threads awaiting a connection.** The first thing to saturate in this
  application. Anything above zero means request threads are blocked on the pool.
- **JVM dashboard → heap utilisation and mean GC pause.** Rising mean pause with flat allocation is
  heap pressure.
- **API dashboard → slowest endpoints.** Finds *which* endpoint degraded, which an overall P95 hides.
- **Load Testing dashboard.** Compares k6's client-side latency with the server's own. A gap between
  them is network or client overhead, not application slowness.

## Soak tests write a lot of data

A two-hour soak at 10 VUs places thousands of orders, each with payments and audit rows. On a shared
test database that is a disk-space and data-hygiene problem. Before running one overnight:

- Prefer a disposable database (`docker compose down -v` afterwards).
- If the environment is shared, the seeded restaurants are identifiable — their owner addresses end
  `@k6.invalid` and names begin `Load Test` — so they can be found and removed afterwards.
- Watch `hikaricp_connections_pending` and heap utilisation across the whole run, not just at the end.

## CI

`smoke.js` is the only script suitable for CI: one virtual user is not load. Add it as a step after
the stack starts, against an ephemeral environment. Load, stress, spike and soak stay manual and
deliberate — a load test on every pull request is a way to make CI slow and flaky, not a way to find
regressions.

## What was and was not verified

**Executed here:** every script parses; the production guard's decision logic was unit-tested across
seven cases, which found and fixed the fail-open bug described above.

**Not executed:** no k6 run of any kind. There is no k6 binary, no running application and no network
in the environment these were written in. The request shapes were written against the actual
controllers and DTOs in this repository, but **no response has ever been observed**. Expect to fix
small mismatches on your first smoke run — that is what the smoke test is for.

## The floor scenario (`tables.js`)

The ordering flow gives every guest their own cart and lets guests pay their own bill or the whole
table's. The scripts above send every virtual user to **one table**, which is not what that flow looks
like. `tables.js` is: it seeds a restaurant with several tables (`TABLES`, default 12), runs guests who
join a table, order, wait for the kitchen, then pay (one in five tries to pay for the whole table), and a
staff loop (`STAFF_VUS`, default 2) that confirms orders, confirms payments and closes tables that are paid
up and quiet.

```bash
ENVIRONMENT=staging BASE_URL=https://staging.example.com K6_VUS=60 TABLES=15 \
  k6 run k6/tables.js
```

**What it asserts, beyond latency.** `km_server_errors` must be **zero**. A lock or isolation problem
shows up as a 5xx, so this is the threshold that matters most, and it is the one a latency-only test
would never fail. Two guests racing to pay for a table is expected to produce a 409 for one of them; that
is counted (`km_combined_conflicts`) and is not a failure, and neither is a guest finding their table
closed (`km_sessions_ended`).

| Variable | Default | Meaning |
|---|---|---|
| `TABLES` | `12` | Tables in the restaurant; guests spread across them |
| `STAFF_VUS` | `2` | Staff loops confirming orders and payments |
| `STAFF_DURATION` | `5m` | Must cover the guests' ramp up, hold and ramp down |
| `CONFIRM_TIMEOUT_S`, `PAY_TIMEOUT_S` | `30`, `60` | How long a guest waits for staff before the journey fails its check |
| `CLOSE_AFTER_QUIET_MS` | `15000` | A paid-up table must be quiet this long before staff close it |
| `THRESHOLD_CONFIRM_WAIT_S`, `THRESHOLD_PAY_WAIT_S` | `15` | p95 time for staff to confirm an order / a payment |

**Run it against MySQL.** H2 cannot show the problems this exists to find. See
`docs/QR_ORDERING_OPERATIONS.md` for running the integration tests the same way.

### A visit admits a limited number of guests

Each table visit admits `TABLE_SESSION_MAX_DINERS` guests (default 30) and every iteration of the older
scripts joins as a new guest at the same single table, so they start failing the "table joined" check
once the visit is full. That is the cap working. For those scripts raise it on the server under test (for
example `TABLE_SESSION_MAX_DINERS=100000`); `tables.js` instead turns tables over by closing them, as a
real restaurant does, though a long or heavy run may still want the cap raised.
