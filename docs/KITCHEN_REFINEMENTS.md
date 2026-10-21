# Kitchen refinements (Stage F): cancellation reasons, order history, delayed orders

**Status:** the rules (reason validation, timing) are compiled and executed for real; the endpoints and screens have **not** been run. Apply Stages A to E first.

```bash
python3 docs/checks/run_all.py     # today: executes CancellationRulesTest (8), OrderTimingTest (11), cancelReasons tests (9)
./gradlew test                     # later: also OrderHistoryIT (15)
./gradlew mysqlTest                # later: proves V37 matches the entities
```

## What changed for the restaurant

- **Cancelling an order always asks why.** Choose a reason (a dish is out of stock; the kitchen cannot make it; the guest asked; ordered twice; ordered by mistake; another reason) and optionally add a note. "Another reason" needs a note.
- **The guest sees why**, as the reason in words on their order page ("A dish is out of stock. Please speak to a member of staff."). **The note is never shown to the guest**: it is for the restaurant's own records and may be internal.
- **Every order has a history** (Orders page, **History**): when it was placed, confirmed, prepared, ready, served or cancelled, who did each step (a staff email, or the guest's name for placing it), and the reason and note for a cancellation.
- **The kitchen board flags late orders.** Each ticket shows how long it has been open against how long it should take ("12 of 15 min"), and turns red with "Delayed 7 min" once over. The target is the slowest dish's preparation time (15 minutes if no dish has one). Late means open longer than the target and not yet **ready**: a pending (not yet accepted), confirmed or preparing order can be late; a ready, served or cancelled one never is. The clock is the server's, so a tablet with the wrong time cannot show the wrong thing.

## Rules the server enforces

- A reason is **optional at the API**, so older callers and every existing test, which cancel without one, still work (the order records no reason). The screens always ask.
- A given reason must be on the list; "other" needs a note; a note is at most 200 characters (control characters are stripped).
- A reason or note on anything other than a cancellation is refused (400). Checked **before** anything changes, so a refused request leaves the order, its stock, coupon and payments exactly as they were.
- A retried tap records nothing: confirming twice, or cancelling twice with the same reason, appears once in the history.
- Illegal steps (for example served straight from pending) are refused and leave no trace.
- Another restaurant's order answers "not found" for both reading its history and cancelling it.
- History is recorded from all five places a status can change: placing, the four kitchen steps, and the status endpoint.

## Database (V37, additive only)

`orders` gains `cancel_reason`, `cancel_note`, `cancelled_at`, `cancelled_by` (all NULL for existing orders). New append-only table `order_status_events`. **Orders placed before this migration have no history rows**; the screen says so instead of inventing one.
Rollback before it matters: `DROP TABLE order_status_events;`, drop the four columns from `orders`, delete the `flyway_schema_history` row for version 37.

## API

| Method and path | Purpose |
|---|---|
| `PUT /api/v1/orders/{id}/status?status=CANCELLED&reason=OUT_OF_STOCK&note=...` | `reason` and `note` are new and optional; only valid with `CANCELLED` |
| `GET /api/v1/orders/{id}/history` | `[{from, to, actor, reason, reasonLabel, note, at}]`, oldest first; `from` is null for the order being placed |
| kitchen order responses | now carry `prepTargetMinutes`, `elapsedMinutes`, `delayed`, `overdueMinutes` |
| order tracking (guest) | now carries `cancelReasonLabel` (null if not cancelled or no reason given) |

Reason codes: `OUT_OF_STOCK, KITCHEN_CANNOT_MAKE, GUEST_REQUEST, DUPLICATE_ORDER, ORDERED_BY_MISTAKE, OTHER`.

## Manual test

1. Place an order from a table QR. Owner, **Orders**, **History**: one line, "Placed", by the guest's name.
2. Kitchen board: **Confirm**, **Start preparing**, **Mark ready**. History shows each step with your email. Double-tap a button: no duplicate line.
3. Place another order. On the kitchen board **Cancel**: you must pick a reason; choose "Another reason" with no note and it refuses. Choose "A dish is out of stock", add a note, confirm.
4. On the guest's order page: "A dish is out of stock. Please speak to a member of staff." The note does not appear anywhere on that page.
5. Owner, **Orders**, **History** on the cancelled order: "Cancelled: A dish is out of stock", with your note and your email.
6. Set a dish's preparation time to 1 minute, order it and wait 2 minutes without accepting: the ticket turns red, "Delayed 1 min". Mark it ready: the flag goes away.

## Known limits

- Reasons are not reported on yet (no "why orders were lost" analytics); they are stored and shown per order.
- No customer notification beyond the order page: the guest sees the reason when they look (the page refreshes itself).
- A refund-then-cancel for an already-paid order is still the existing two-step flow; the reason applies only when the cancellation succeeds.
- "Delayed" is per order, not per dish, and does not escalate or alert (no sound or push).
- I have not seen the screens rendered.
