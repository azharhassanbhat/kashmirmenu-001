# Table service requests (Stage A)

Guests ask for something at the table (waiter, water, bill, ...) without ordering; staff work the requests from a live board.

**Status: written but not yet run.** Nothing here has been built against Spring or MySQL. Run the checks below before relying on it.

## Run the automated checks

```bash
./gradlew test                                   # H2: includes ServiceRequestRulesTest and ServiceRequestIT
docker compose -f docker-compose.test.yml up -d --wait
./gradlew mysqlTest                              # real MySQL: also proves migration V34 and the entity mapping agree
docker compose -f docker-compose.test.yml down -v
cd frontend && npm install && npm run typecheck && npm test
```

`mysqlTest` matters most: production runs `ddl-auto: validate`, so a mismatch between `ServiceRequest` and V34 would stop the app at startup. H2 cannot catch that, nor the unique `open_dedupe_key` guard.

## Database change (additive only)

`V34__service_requests.sql` creates `service_requests` and `service_request_events`. No existing table or column changes. Flyway applies it on the next start. To undo before any data matters: `DROP TABLE service_request_events; DROP TABLE service_requests; DELETE FROM flyway_schema_history WHERE version = '34';`

## API

| Who | Method and path | Purpose |
|---|---|---|
| Guest (header `X-Diner-Token`) | `POST /api/public/tables/{qr}/service-requests` body `{"type":"DRINKING_WATER","note":null}` | Raise. `201` new, `200` if the same thing is already open |
| Guest | `GET /api/public/tables/{qr}/service-requests` | This visit's requests and statuses |
| Guest | `POST /api/public/tables/{qr}/service-requests/{number}/cancel` | Withdraw, only while PENDING |
| Staff (Bearer) | `GET /api/v1/service-requests` | Board: open requests oldest first, plus finished in last 2h |
| Staff | `GET /api/v1/service-requests/{id}` | One request with history |
| Staff | `PUT /api/v1/service-requests/{id}/status` body `{"status":"ACKNOWLEDGED"}` | Move along the lifecycle |

Types: `CALL_WAITER, DRINKING_WATER, REQUEST_BILL, CUTLERY, NAPKINS, EXTRA_PLATES, CLEANING, ASSISTANCE, OTHER` (`OTHER` needs a note, max 3 open per guest visit).
Lifecycle: `PENDING -> ACKNOWLEDGED -> IN_PROGRESS -> COMPLETED`, or `CANCELLED` from any open state. `PENDING -> COMPLETED` is refused (409).
Limits: 200-character notes; at most 8 open requests per table visit.

## Manual test (browser)

1. Sign in as owner, create a table, open its QR link on a phone (or a second browser window), enter a name.
2. On the menu tap **Need something?**, then **Drinking water**. The button turns into "Sent. Waiting for staff to see it". Tap nothing else.
3. In another window signed in as owner/waiter open **Table requests** (admin sidebar, or `/requests`). The card appears under *Waiting for staff* within seconds, showing the table, "From <name>", and a wait timer.
4. Tap **Acknowledge**. On the phone, within about 6 seconds, the status reads "Staff have seen your request". Tap **Start**, then **Complete**: phone shows "Done".
5. Open **History** on the card: created, acknowledged, in progress, completed, each with who did it.
6. Duplicate check: on the phone tap **Call waiter** twice quickly. Only one card appears on the board.
7. Second guest: join the same table in a private window, open the sheet: the first guest's request is listed as "someone at your table" with no note, and its button is disabled.
8. Cancel: raise **Cutlery**, tap **Cancel** before staff act: card moves to Done as Cancelled. Raise another, acknowledge it as staff, try Cancel on the phone: the Cancel link is gone (API answers 409).
9. Invalid QR: open `/menu/not-a-real-code` and confirm the friendly error; `curl -X POST .../api/public/tables/not-a-real-code/service-requests -H 'Content-Type: application/json' -d '{"type":"CALL_WAITER"}'` returns 404.
10. Isolation: register a second restaurant; its board must stay empty and `GET /api/v1/service-requests/<id from restaurant 1>` must return 404.

## curl walkthrough

```bash
BASE=http://localhost:8080
QR=<table qrIdentifier>      OWNER=<owner bearer token>

TOKEN=$(curl -s -X POST $BASE/api/public/tables/$QR/join -H 'Content-Type: application/json' -d '{"name":"Rahul"}' | jq -r .dinerToken)

curl -s -X POST $BASE/api/public/tables/$QR/service-requests -H "X-Diner-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"type":"DRINKING_WATER"}'          # 201, status PENDING, requestNumber SR-XXXXXX
curl -s -X POST $BASE/api/public/tables/$QR/service-requests -H "X-Diner-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"type":"DRINKING_WATER"}'          # 200, alreadyRequested true, same number

ID=$(curl -s $BASE/api/v1/service-requests -H "Authorization: Bearer $OWNER" | jq '.[0].id')
curl -s -X PUT $BASE/api/v1/service-requests/$ID/status -H "Authorization: Bearer $OWNER" \
  -H 'Content-Type: application/json' -d '{"status":"COMPLETED"}'             # 409: must acknowledge first
curl -s -X PUT $BASE/api/v1/service-requests/$ID/status -H "Authorization: Bearer $OWNER" \
  -H 'Content-Type: application/json' -d '{"status":"ACKNOWLEDGED"}'          # 200
```

## Metrics (Prometheus)

`app_service_requests_created_total{type}`, `app_service_requests_duplicates_total{type}`, `app_service_requests_transitions_total{to}`, `app_service_requests_response_time_seconds` (submission to first acknowledgement). No table, guest, note or restaurant labels.

## Known limits

- Guests see status changes by polling every 6 seconds (diners have no websocket). Staff boards use the websocket with an 8-second poll fallback.
- No sound or vibration alert on the staff board yet.
- No analytics page for request counts or response times yet (the metrics above exist).
- The entry point is a banner on the menu/order pages plus the More sheet; I have not seen it rendered, so check the layout on a 320px-wide phone.
