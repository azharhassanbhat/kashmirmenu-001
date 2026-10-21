# QR ordering: running it

How the table-ordering flow behaves in operation: what to configure, how to deploy it, what staff do
day to day, what to watch, and what to do when something looks wrong. For why it is built the way it is,
see [`TABLE_SESSIONS.md`](TABLE_SESSIONS.md).

In one paragraph: a guest scans a table's QR code and enters a name. They get their own cart, orders and
bill. They pay their own bill, or one guest pays the whole table's. **Nothing counts as paid until a
member of staff confirms they have the money.** Staff see the floor on one screen, confirm payments
there, and close tables.

A word on naming: a **settlement** in this flow is one act of paying at a table (a guest paying their
bill, or the table's). It is unrelated to the bank "settlement" in the daily reconciliation, which is a
nested type in `ReconciliationResponse`.

## Settings

All are environment variables; the defaults are what runs if you set nothing.

| Variable | Default | What it does |
|---|---|---|
| `BILLING_REQUIRE_CONFIRMED_ORDERS` | `true` | An order is payable only once the kitchen confirms it. Set `false` if you do not use the confirm step, so guests can pay as soon as they order. |
| `TABLE_SESSION_MAX_DINERS` | `30` | Most guests one table visit admits. Joining needs no login, so this stops anyone with the QR code creating guests without limit. |
| `TABLE_SESSION_IDLE_TIMEOUT` | `PT3H` | How long a table can go with nobody doing anything before it counts as idle (ISO-8601: `PT90M`, `PT3H`). |
| `TABLE_SESSION_EXPIRY_CHECK_INTERVAL` | `PT10M` | How often idle tables are looked for. |
| `TABLE_SESSION_EXPIRY_ENABLED` | `true` | `false` stops tables closing by themselves. Staff can still close them. |
| `PUBLIC_GUEST_RATE_LIMIT` | `60` | Writes per guest per minute. The limit a diner actually meets. |
| `PUBLIC_RATE_LIMIT` | `600` | Writes per IP per minute: a high ceiling against abuse. Every phone in a restaurant shares one wifi address, so this must allow a full dining room. |
| `PUBLIC_TRACK_RATE_LIMIT` | `1200` | Order-tracking reads per IP per minute (guests poll their order screen). |
| `app.table-session.recently-closed-window` | `PT24H` | How far back "recently closed" lists, so a table closed by mistake can be reopened. A property, not an environment variable in `application.yaml`. |

The rate limits are counted per server instance, not shared between instances. Behind several servers each
keeps its own count; that is fine as a first line of defence, not a replacement for a limit at the edge.

## Deploying it

The migrations are **one-way**. After them, an older version of the application will not run against
the database (they replace a unique index and drop a column it depends on), so a rollback means
restoring the backup, not redeploying the old build.

| Migration | What it does |
|---|---|
| `V30` | Order numbers become unique per restaurant, not across all of them. Fixes the second restaurant's first order failing. |
| `V31` | Adds table visits (`table_sessions`) and guests (`diner_sessions`). |
| `V32` | Carts and orders gain an owner. "One active cart per table" becomes one per guest, plus one staff cart per table. |
| `V33` | Adds settlements, and links payments to them. |

1. **Take a backup**, and confirm you can restore it.
2. **Deploy at a quiet time** if you can. A cart a table's diners were filling at that moment is not lost:
   it becomes that table's *staff* cart. Diners start fresh carts of their own, and staff can still place
   the old one. Diners with the menu open are asked for a name on their next action.
3. Start the new version. Flyway applies V30 to V33 on startup; watch the log for `now at version v33`.
4. **Smoke test** with two phones on one table's QR code:
   - join as two different names, add a dish each, place both orders;
   - confirm both orders as staff (Live tables, or the kitchen screen);
   - one guest taps **Pay My Bill**, then you confirm it under *Payments to confirm*; their phone should
     move to the receipt by itself;
   - the other guest pays for the table; the breakdown should show both names and the right amounts;
   - close the table, and check the guests' phones ask for a name again.
5. Check the new alerts loaded: `promtool check rules prometheus/rules/kashmirmenu.yml`, then confirm
   `OrderingLockConflictsGivingUp` and `OrderingLockRetriesElevated` appear under `/api/v1/rules`.

### Before a release that touches orders, payments or tables

Run the integration tests against MySQL as well as H2 (see [Testing](#testing)). Two production-only defects
were found in this flow by reading the code, and neither could ever have shown on H2.

## What staff do

**Live tables** (`/admin/live` for owners and managers, `/floor` for waiters and cashiers):

- **Payments to confirm** sits at the top, because it is what needs doing now. A guest has chosen how to
  pay; press **Money received** once you have it, optionally with a receipt reference, or **Didn't go
  through** if it did not happen. Their phone updates by itself.
- **Open tables** show each table's guests, what is owed and what is paid. A table that has been idle for
  a while *with money still owed* is highlighted: it is never closed for you, because that decision is
  yours.
- Open a table to see each guest's orders and balances and every payment, marked **Own bill**,
  **Whole table** or **At the counter**.
- **Close table** is never blocked by money owing. It tells you what is owed first. Nothing is deleted or
  settled; the guests lose access and their unsubmitted carts are withdrawn. A payment already waiting for
  you can still be confirmed afterwards.
- **Recently closed** lists the last day's tables, each with **Reopen**, which also gives its guests their
  sessions back. A table cannot be reopened once it has started a new visit.

A table nobody has touched for three hours **and that owes nothing** closes itself. One that still owes
anything (an unpaid order, a payment waiting for you, an order the kitchen has not confirmed) never does.

## What to watch

| Signal | Meaning |
|---|---|
| `OrderingLockConflictsGivingUp` | Guests or staff were told "the table is busy". See `MONITORING_ALERTS.md`. |
| `OrderingLockRetriesElevated` | Requests are colliding on locks more than usual, but every one still succeeded. |
| HTTP 503 with `Retry-After: 1` | A lock conflict outlasted every retry. The request changed nothing; repeating it is safe. |
| HTTP 429 | A rate limit. Per guest it means one phone is sending more than a person would. |
| `app_settlements_started_total{kind}` | Payments started, by separate or combined. |
| `app_table_sessions_closed_total{reason}` | Tables closed, by staff or by idle expiry. |

## Troubleshooting

| What you see | Why | What to do |
|---|---|---|
| A guest sees "Your table session has ended" | Staff closed the table, it closed itself after being idle, or the phone's stored session was cleared | Scan again and enter a name. If it was closed by mistake, reopen it under *Recently closed* and their old session works again. |
| A guest cannot pay: "waiting for the kitchen to confirm" | The order is still `PENDING` and `BILLING_REQUIRE_CONFIRMED_ORDERS` is true | Confirm the order. If you never use that step, set the variable to `false`. |
| "This table has reached its limit of guests" | The current visit has 30 guests, usually because an earlier party's visit was never closed | Close the table (it starts a fresh visit on the next scan). Raise `TABLE_SESSION_MAX_DINERS` only if 30 real guests share one table. |
| A payment is stuck on "waiting for staff" | Nobody has confirmed it | Confirm it under *Payments to confirm*. The guest can also cancel it themselves and start again. |
| Two guests tried to pay for the whole table and one saw "nothing left to pay" | The first payment already covers it | Correct. Their breakdown showed it as already being paid. |
| "Too many requests" (429) for one guest | They exceeded `PUBLIC_GUEST_RATE_LIMIT` | Wait a minute. If several guests at one restaurant get it, check `PUBLIC_RATE_LIMIT` was not set low. |
| A table shows as occupied but is empty | Idle expiry has not closed it yet (it waits three hours), or it still owes something | Close it from Live tables. |
| A table is highlighted "needs someone to look at it" | Idle for a while with money still owed | Open it, confirm or reject what is waiting, then close it. |
| "This table has orders or sessions on record, so it cannot be deleted" | A table that has been scanned has history pointing at it | **Deactivate** it instead (its QR code stops working and its history stays). Only a table nobody has ever scanned can be deleted. |
| An order appears in a table you just closed | A guest acted in the same instant you closed it | Known and rare. It is visible in the table's history and settled like any other order. |

## Data kept

Closing a table keeps everything: its guests (their first names, as typed), their orders, and every
payment. Guests' secret tokens are stored only as hashes. Orders reference their guest, so guest rows
cannot simply be deleted; if you need to remove names after a period, that is an anonymising job, which
does not exist yet and is a policy decision for you.

## Testing

```bash
./gradlew test                                       # in-memory H2: fast, every change
docker compose -f docker-compose.test.yml up -d --wait
./gradlew mysqlTest                                  # a real MySQL: before a release
docker compose -f docker-compose.test.yml down -v
```

`mysqlTest` runs the same integration tests with every migration applied to a real MySQL and Hibernate
set to *validate* (so an entity that disagrees with the schema fails at startup), under MySQL's own
default isolation. It also runs `MysqlConcurrencyIT`, which fires simultaneous requests at the cases H2
cannot get wrong: many guests ordering at once, rival payers, closing a table under its guests, and
everything at once. **A 5xx anywhere in it means a lock problem has come back.** Start the database
fresh for each run (`down -v`).

For load, `k6/tables.js` runs many tables, guests ordering and paying, and staff confirming and
closing, and fails on **any** server error. See `PERFORMANCE_TESTING_GUIDE.md`.

## Known limitations

- **Staff confirm payments by hand.** A payment gateway is deliberately not built; confirmation sits behind
  one place in the code (`PaymentService.updatePaymentStatus`, called by `SettlementService.updateStatus`).
- **The screens poll** rather than being pushed updates (every few seconds while something is waiting).
- **Orders staff place through the staff API** belong to no guest and are not on any guest's bill or
  covered by any settlement. The counter settles those with `POST /api/v1/payments/orders/{id}/collect`.
- **Receipts have no print layout.** They are a screen.
- **No retention job** for old guests and visits.
- The frontend screens themselves have no automated tests (the frontend test setup has no DOM); the logic
  behind them does.
