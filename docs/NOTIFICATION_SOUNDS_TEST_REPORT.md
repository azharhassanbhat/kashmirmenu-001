# Notification sounds: test report (phase 2)

Date: 2026-10-21. Machine: Windows 11, Node 25, JDK 21.

## What was and was not tested

| Layer | Evidence |
|---|---|
| Decision logic (event to sound, roles, dedupe, catch-up, reminders, preferences, scheduler, loudness) | **Executed** with vitest, using realistic simulated order and waiter-call events. |
| Backend (migration, `eventKind` mapping, service-request flow, realtime delivery) | **Executed** with Gradle (test profile). |
| Type check and production build | **Executed** (`tsc --noEmit`, `vite build`). |
| Real browser, real speakers, real phone, two physical devices, real WebSocket drop | **NOT executed.** No browser or device was driven. |
| How the sounds actually *sound* | **NOT assessed by ear.** Loudness is a computed estimate (A-weighted energy, with a headroom check). |

The "dashboard" in the scenario tests (`src/lib/sound/notificationScenarios.test.ts`) runs the same pure functions the
real dashboard calls (`planLiveMessage`, `planCatchUp`, the scheduler, the reminder loop, preferences). The React
provider itself, the socket and Web Audio are replaced by recorders, so the glue in `NotificationCenter.tsx` is
covered by the type check and build only, not by a test.

## Commands run

| Command | Directory | Result |
|---|---|---|
| `npx tsc --noEmit` | `frontend` | passed, no errors |
| `npx vitest run` | `frontend` | **32 files, 433 tests passed** (was 376 before this phase) |
| `npx vite build --outDir <scratch>` | `frontend` | passed (built to a scratch folder; `dist` was not overwritten) |
| `./gradlew test --offline --tests "*ServiceRequest*" --tests "*Notification*" --tests "*RestaurantDecision*"` | project root | **BUILD SUCCESSFUL**: ServiceRequestIT 26, RealtimeNotificationIT 5, ServiceRequestRulesTest 14, NotificationEventKindTest 4 (new), RestaurantDecisionFailureIT 1, RestaurantApprovalIT 1; 0 failures |
| `python docs/checks/run_all.py` | project root | 12 of 17 passed, 7 skipped. Passed, among others: migrations (includes V44), entities vs schema, JSON field contract, unused imports. 4 failed for reasons outside this change: the pure-test shim cannot load on Windows paths, a photo-link runner has the same problem, `ChinarLeaf` props, and one old `payment-analytics` test URL. |

The full Gradle suite and `mysqlTest` (MySQL) were **not** run; only the notification and service-request tests above.

## Scenarios

| Scenario | Expected | Result | How verified |
|---|---|---|---|
| Customer places order | New-order sound | Pass | simulated event, `NEW_ORDER` plays |
| Customer calls waiter | Unique waiter-call sound | Pass | `WAITER_CALL`; differs from new order; text reworded or absent still correct |
| Customer requests bill | Billing sound | Pass | `BILL_REQUEST`; older server (no `eventKind`) still correct |
| Kitchen marks order ready | Order-ready sound | Pass | `ORDER_READY` (waiter and manager hear it; chef does not) |
| Multiple orders arrive | Controlled playback | Pass | 3 orders + 2 waiter calls: highest priority first, repeats collapsed, no overlap (700 ms gap), a new order never dropped, 5 toasts still shown |
| Waiter acknowledges request | Reminder stops | Pass | reminder loop stops when the next server read no longer lists it as pending |
| Dashboard reconnects | Missed active events recovered | Pass (logic) | missed items become one notice and are marked seen; a recent order or waiter call sounds once, older ones are silent. The real socket reconnect was not exercised |
| User changes sound theme | Selected theme plays correctly | Pass (logic) | theme is saved, reloaded, validated, and changes pitch/timbre but never timing. Not heard |
| Dashboard refreshes | No unnecessary replay | Pass | baseline from the stored list: older ids are history, silent; reminders rebuild from server state and do not sound at load |
| Two restaurants active | Complete isolation | Pass | every message naming another restaurant is dropped with no sound, toast or seen-id; reminders only ever come from the caller's own server-scoped lists. Server-side topic authorisation is covered by the existing `RealtimeNotificationIT` |

Also covered by new tests: every event-to-sound mapping, role filtering (owner, manager, chef, waiter, cashier,
customer), duplicate delivery, two tabs (Web Lock claim), two devices and two waiters (each plays), repeated waiter
calls, sign-out clearing reminders and queued sounds, preference persistence per user and upgrade of old records,
loudness consistency and no-clipping for every sound in every theme.

## Known gaps

* No browser-level (Playwright or similar) test exists in this project; autoplay, locked/suspended audio and
  silent-mode behaviour were not exercised on any real device.
* The reminder count restarts after a refresh (bounded by the 30-minute age limit).
* "Respect assignment boundaries": a table request is not assigned to a waiter when it is raised, so every waiter of
  the restaurant hears it. Per-waiter or per-section routing would need assignment data the app does not have.
* A new order is reminded about until it is confirmed; if staff never confirm orders in your workflow, set reminders
  to Never for that role.
