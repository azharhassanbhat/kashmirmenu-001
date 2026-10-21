# Private customer feedback (Stage H)

**Status:** the rules (rating, comment, window, statistics) are compiled and executed for real. The endpoints and screens have **not** been run. Apply Stages A to G first.

```bash
python3 docs/checks/run_all.py     # today: runs FeedbackRulesTest (16), FeedbackStatsTest (7), feedback.test.ts (8), V39 on real rows
./gradlew test                     # later: also FeedbackIT (16)
```

## What it does

Once an order is **served**, the guest's order page asks "How was your meal?": one to five stars and an optional comment. It is **private**: it goes to the restaurant, not to Google, and the guest is told "Only the restaurant sees this". The Google review prompt moves below it and is reworded "Enjoyed it? Tell others on Google". Management reads it at **Admin, Customer feedback**:

- the average (one decimal, or a dash when nobody has rated, never "0.0"), the number of ratings, and how many were 1, 2, 3, 4 and 5 stars;
- the share of **low** ratings (1 or 2 stars), highlighted when it reaches 20 percent;
- the list, newest first, with order number, table and comment, and a **Low ratings only** filter;
- a period selector (7, 30, 90 days, a year).

## Rules the server enforces

- Only a **served** order can be rated (a cancelled or unfinished one cannot), and only within **3 days** of being placed, so an old link cannot rate an old visit.
- The rating is a **whole number from 1 to 5**. 4 and 4.0 are fine; **2.5 is refused**, not quietly rounded down (JSON parsers round down by default, which would give a caller a rating they did not send). The database also refuses anything outside 1 to 5.
- The comment is optional, cleaned of control characters, and at most **500 characters** (counted as characters, so emoji and other scripts count fairly). Any script is accepted.
- **One rating per order.** Sending again replaces it, and an order can never count twice in an average (a database rule, not only application logic). Two simultaneous first submissions get a clear "try again" instead of an error.
- The guest's credential is their order's random token, exactly as for tracking. It can rate that one order and nothing else, and an unknown token answers a bare "not found".
- Only **owners, admins and managers** can read feedback. Floor and kitchen staff get 403.
- One restaurant never sees another's feedback. **No guest name, phone or device is stored**, so none can be shown.
- The guest's own page shows only their own rating and comment, never anyone else's.

## Database (V39, additive only)

New table `order_feedback`, one row per order. Rollback before it matters: `DROP TABLE order_feedback;` and delete the `flyway_schema_history` row for version 39.

## API

| Method and path | Purpose |
|---|---|
| `PUT /api/public/orders/by-token/{token}/feedback` `{"rating":4,"comment":"..."}` | The guest rates (or changes) their rating |
| `GET /api/v1/feedback?maxRating=2&page=0&size=20` | The list, newest first (management) |
| `GET /api/v1/feedback/summary?days=30` | Average, distribution, low share (management); `days` clamped to 1 to 365 |
| order tracking (guest) | now carries `feedbackRating` and `feedbackComment` once served |

## Manual test

1. Place an order from a table QR and take it through confirm, prepare, ready, served from the kitchen board.
2. On the guest's order page "How was your meal?" appears with five stars. Tap 2 stars, type a comment, **Send feedback**. It says "Thank you" and shows 2 out of 5, with **Change my rating**.
3. Before the order is served the card must not appear. Try it on a cancelled order: it must not appear.
4. Admin, **Customer feedback**: the rating and comment are listed. Tick **Low ratings only**: it is still there. Change the guest's rating to 5: the list now has one entry (not two) and the average is 5.0.
5. Sign in as a waiter: the Customer feedback page must be refused.

## Known limits

- No reply to a guest, no email or push alert for a low rating (the owner must open the page), and no per-dish ratings: the rating is for the whole order.
- Averages are over ratings given, with no weighting, and the period is fixed to "ending now".
- A decimal rating such as 2.5 is refused; the screen only ever sends whole numbers.
- I have not seen the screens rendered.
