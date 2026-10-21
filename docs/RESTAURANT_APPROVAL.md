# Restaurant approval

A restaurant that registers starts **PENDING**. It cannot operate until the platform owner approves it.

## Statuses and moves

| From | To | Needs |
|---|---|---|
| PENDING | APPROVED | nothing |
| PENDING | REJECTED | a reason (5-500 characters) |
| REJECTED | APPROVED | nothing (the platform owner may reconsider) |
| APPROVED | SUSPENDED | a reason (5-500 characters) |
| SUSPENDED | APPROVED (reactivate) | nothing |

Any other move is refused with 409. Decisions row-lock the restaurant, so two simultaneous clicks produce
one success and one 409. The rules live in `platform/RestaurantLifecycle`.

## What each status can do

* **APPROVED**: everything, as before.
* **PENDING / REJECTED / SUSPENDED**:
  * Staff of the restaurant are refused on every restaurant API with `403` and code
    `RESTAURANT_NOT_APPROVED`. Only `GET /api/v1/me` and `/api/v1/auth/**` stay open, so the owner can sign
    in, read the status and the reason (`restaurantStatus`, `restaurantStatusReason`), and sign out.
  * The public QR flow (`/api/public/tables/{qr}/**`) answers 404, the same as an unknown QR code.
  * The restaurant is hidden from `GET /api/restaurants` listings, search and `GET /api/restaurants/{id}` for other users.
  * Its staff cannot subscribe to its live WebSocket feed (`/topic/restaurants/{id}`).
  * Not blocked: guests settling or tracking an order they already placed (`/api/public/orders/by-token/**`).

Enforcement is `security/filter/RestaurantApprovalFilter`. It reads the status on each request, so a
suspension applies on the very next call.

## Platform owner API (`/api/platform-admin`, role PLATFORM_OWNER only)

| Method | Path | |
|---|---|---|
| GET | `/overview` | counts by status |
| GET | `/restaurants?q=&status=&page=&size=&sortBy=createdAt\|name\|status&direction=` | paged search |
| GET | `/restaurants/{id}` | full detail |
| POST | `/restaurants/{id}/approve` | |
| POST | `/restaurants/{id}/reject` | body `{"reason": "..."}` |
| POST | `/restaurants/{id}/suspend` | body `{"reason": "..."}` |
| POST | `/restaurants/{id}/reactivate` | |

Every decision records who and when on the restaurant (`reviewed_by_id`, `reviewed_at`), writes an
`audit_log` row (`RESTAURANT_REGISTERED / APPROVED / REJECTED / SUSPENDED / REACTIVATED`) and creates a
notification for the restaurant (visible to the owner once they can sign in to the dashboard).

## Setup

* Migration `V42__restaurant_approval.sql` adds the columns. Every restaurant that already exists is
  backfilled **APPROVED**, so nothing that works today stops working.
* `RESTAURANT_APPROVAL_REQUIRED` (default `true`). Set `false` to let new registrations start APPROVED, as before.
  Existing statuses are enforced either way.
* Provision the platform owner once with `PLATFORM_OWNER_EMAIL` (see `.env.example` and `PLATFORM_ADMIN_PLAN.md`),
  then claim it with `POST /api/platform-admin/claim`. The dashboard is at `/platform-admin`.
* The test profiles set `required: false` so older integration tests that register and use a restaurant straight
  away are unchanged; `RestaurantApprovalIT` switches it on.

## Known limits

* Restaurants have no description or uploaded-documents fields yet, so the detail page shows none (`documents` is always empty).
* Users have no phone number, so the owner's phone is not shown.
* A WebSocket (STOMP) connection that is already open when a restaurant is suspended is not closed, so it can keep receiving pushes it was already subscribed to. New REST calls are refused immediately and new subscriptions are refused too.
* `GET /api/public/images/{key}` serves an image by its unguessable key whatever the restaurant's status.
* On the real server, a signed-in user denied by the filter chain's own rules (including a non-platform user calling `/api/platform-admin/**`) gets 401 rather than 403; the request is refused either way. This was already the case before approval existed.
