# Notification system: production readiness report (phase 3)

Date: 2026-10-21. Test machine: Windows 11, Node 25, JDK 21, in-memory test database. Nothing was deployed and no
production data was touched.

Status words: **PASS** = tested and works; **PARTIAL** = works with a stated gap; **FAIL** = does not work;
**NOT TESTED** = no evidence either way. A "test" below means an automated test that was actually run, unless it says
otherwise.

## 1. What kind of evidence this is

| Kind | Used? |
|---|---|
| Automated frontend tests of the dashboard's decision logic (vitest) | Yes |
| Automated backend tests, including a **real WebSocket** (STOMP over SockJS) against a running test server | Yes |
| A real browser, real speakers, a phone, two physical devices, a real network drop | **No** |
| Listening to the sounds | **No** |

Latency numbers are server-side, in-process on one machine (see section 6). They are not what a restaurant on Wi-Fi
or mobile data will see.

## 2. Features verified

| # | Area | Status | Evidence |
|---|---|---|---|
| 1 | Backend event creates a notification with a structured `eventKind` | PASS | `NotificationEventKindTest` (4), `ServiceRequestIT` (26), `RealtimeNotificationIT` (5) |
| 2 | Event is saved, then published after commit, once | PASS | `RealtimeNotificationIT` (new order, cancel, payment, refund, rating each once) |
| 3 | Delivery over a real WebSocket to the right restaurant only | PASS | `LiveSocketDeliveryIT` (all tests, incl. 10/50/100/500 bursts with two restaurants at once) |
| 4 | Another restaurant, a guest account, no token, a client SEND, and the platform feed are all refused | PASS | `LiveSocketDeliveryIT` + `StompAuthChannelInterceptorTest` (now 12 tests) |
| 5 | Each event maps to its own sound | PASS | `eventMap.test`, `notificationScenarios.test` (logic only, not heard) |
| 6 | Waiter-call and bill sound survive reworded text, a missing `eventKind`, an unknown one | PASS | `eventMap.test`, `notificationScenarios.test` |
| 7 | Role filtering (owner, manager, chef, waiter, cashier, customer) | PASS | `eventMap.test`, `notificationScenarios.test` |
| 8 | Duplicate delivery and a refresh do not replay | PASS | `notificationScenarios.test`, `notificationLoad.test` |
| 9 | Missed-while-disconnected recovery (one notice, recent order or waiter call sounds once) | PARTIAL | logic tested (`planCatchUp`); a real socket drop and reconnect was not exercised |
| 10 | Reminders follow server state, stop on acknowledgement, survive refresh silently | PASS (logic) | `reminders.test`, `notificationScenarios.test`; the React wiring is covered by type check and build only |
| 11 | Mute, per-event switches, volume, theme, reminder interval persist per user | PASS | `soundPrefs.test`, `notificationScenarios.test` |
| 12 | Sound engine releases nodes, uses one context, recovers from a suspended context, disposes cleanly | PASS | `soundEngineCleanup.test` (fake Web Audio, not a real browser) |
| 13 | Equal estimated loudness, no clipping, all sounds distinct, all three themes | PASS (estimate) | `soundQuality.test`. A computed estimate, not a measurement of speakers |
| 14 | How the sounds actually sound | NOT TESTED | nobody listened |
| 15 | Autoplay unlock, locked/suspended audio, silent switch on real browsers and phones | NOT TESTED | no browser or device was driven |
| 16 | Background / closed-tab / locked-phone alerts | FAIL (by design) | no service worker or push exists; not claimed anywhere |

## 3. Bugs discovered

| # | Bug | Severity | Status |
|---|---|---|---|
| B1 | **Ten tables calling the waiter at once showed as ONE toast "x10" with only the last table's name.** The toast key ignored the table. | High | Fixed |
| B2 | **Each live message refreshed about a dozen lists.** A burst of 100 events could trigger over a thousand refetches. | High (peak hours) | Fixed |
| B3 | **A waiter call that arrived with a new order lost its sound.** Collapsing kept only the highest priority, so the waiter heard nothing but the toast. | High | Fixed |
| B4 | **Ten waiter calls could push a waiting bill request out of the sound queue** (the queue kept 6 entries, all of one kind). | Medium | Fixed |
| B5 | Reminder reads paused in a background tab, so a tablet in a background tab could remind about something already answered elsewhere. | Medium | Fixed |
| B6 | After a long idle spell the browser can suspend audio; a sound due then was reported as blocked without trying to resume. | Medium | Fixed (resume is attempted first; on real browsers this is unverified) |
| B7 | Waiter and bill toasts disappeared after 6 s although they are unhandled requests. | Medium | Fixed (60 s, dismissable, "Open requests" button) |
| B8 | In the notification list a waiter call, a bill request and an order ready shared the same bell icon. | Low | Fixed |
| B9 | The dedupe memory held 500 ids; after a very busy spell an old id could look new again. | Low | Fixed (2000) |
| B10 | The staff feed only checked the restaurant, not the role (a guest account attached to a restaurant would have been allowed). Probably not reachable today (a guest account normally has no restaurant, so it is already refused), but it was not verified that no path attaches one. | Low (defence in depth) | Fixed |
| B11 | The load test's timer reported 0 ms because fake timers freeze the clock. | Test bug | Fixed (real clock captured first) |

## 4. Bugs fixed, and files changed

**Backend** (`src/main/java/com/kashmirmenu/app/`)
- `config/StompAuthChannelInterceptor.java` (B10)

**Frontend** (`frontend/src/`)
- `lib/sound/soundScheduler.ts` (B3, B4)
- `lib/notificationCenter.ts` (B1, B2, B7, B9)
- `components/notifications/NotificationCenter.tsx` (B2, B5, B6)
- `dashboard/NotificationsPanel.tsx`, `dashboard/Shell.tsx` (B8)

**Tests**
- New: `lib/sound/notificationLoad.test.ts`, `lib/sound/soundEngineCleanup.test.ts`
- Extended: `lib/sound/soundScheduler.test.ts`, `lib/sound/notificationScenarios.test.ts`,
  `src/test/.../config/StompAuthChannelInterceptorTest.java`, `src/test/.../controller/LiveSocketDeliveryIT.java`
  (burst, isolation, latency)

**Docs**: `docs/NOTIFICATION_SOUNDS.md`, this report.

Changed on purpose: the scheduler's collapsing rule (B3). Before, a burst played ONE sound. Now a burst plays one sound
per must-hear kind (new order, waiter call, bill, critical) in priority order, never overlapping. This is a behaviour
change; two existing scheduler tests that encoded the old rule were updated.

## 5. Test results (all actually run)

| Command | Result |
|---|---|
| `npx tsc --noEmit` (frontend) | PASS, no errors |
| `npx vitest run` (frontend) | PASS, **34 files, 456 tests** |
| `npx vite build` (frontend, to a scratch folder) | PASS |
| lint | NOT PRESENT: the project defines no lint script or config |
| `./gradlew test --offline` (full backend suite, test profile) | PASS, **87 classes, 902 tests, 5 skipped, 0 failures** |
| `LiveSocketDeliveryIT` bursts 10/50/100/500 | PASS: every event exactly once per device, none to the other restaurant |
| `./gradlew mysqlTest` against a throwaway MySQL 8.4 (`docker-compose.test.yml`, in-memory, torn down afterwards) | PASS, **42 classes, 430 tests, 0 skipped, 0 failures**. Flyway applied V44 (`success=1`) and `notifications.event_kind` exists as `varchar(40) NULL` |
| `docs/checks/run_all.py` | not re-run in this phase (phase 2: 12 of 17 passed; the 4 failures are pre-existing and unrelated) |

## 6. Notification delivery latency

Measured by `LiveSocketDeliveryIT` over a real local WebSocket: from just before the notification is created (so it
includes saving it in the test database) until the frame reaches the STOMP client. Two restaurants were busy at the
same time (the second with half as many events), and the first restaurant had two devices connected.

| Burst (restaurant A) | p50 | p95 | max |
|---|---|---|---|
| 10 | 13.9 ms | 18.5 ms | 18.5 ms |
| 50 | 11.4 ms | 16.4 ms | 18.5 ms |
| 100 | 24.6 ms | 29.5 ms | 37.0 ms |
| 500 | 55.8 ms | 69.9 ms | 80.9 ms |

Duplicate rate: 0 duplicate frames in every burst (checked on both devices). Cross-restaurant leakage: 0.
These exclude the internet, a real database server, the browser's own handling and the half-second collapse window
before a sound starts. One run on one machine: treat them as "the server is not the bottleneck", not as a promise.

Frontend logic cost (decision pipeline only, same machine, 2 x N deliveries incl. duplicates in scrambled order):

| Events | Live | Sounds | Toasts shown | Cost per delivery |
|---|---|---|---|---|
| 10 | 10 | 3 | 4 | ~60 us (cold start) |
| 50 | 50 | 3 | 4 | ~3 us |
| 100 | 100 | 3 | 4 | ~2 us |
| 500 | 500 | 3 | 4 | ~1.5 us |

10,000 events grew the heap by about 2.4 MB with the dedupe set capped at 2000 and toasts at 4. Not measured: real
page rendering, DOM cost, or a browser's memory.

## 7. Supported browsers and devices

| Platform | Status |
|---|---|
| Any modern desktop or Android browser with Web Audio | NOT TESTED (code uses the standard API with the `webkit` fallback) |
| iPhone / iPad Safari | NOT TESTED (the unlock path plays a silent buffer, which iOS needs; unverified) |
| Locked phone, closed browser, tab discarded by the OS | Not supported |

No browser or device was driven in this project's testing, so no browser is claimed as verified.

## 8. Security and isolation

| Check | Status |
|---|---|
| Restaurant A never receives B's events (REST scoped, socket topic per restaurant) | PASS (real socket, bursts) |
| Cannot subscribe to another restaurant, the platform feed, or any unknown topic | PASS |
| Cannot connect without a valid token; cannot publish (forged "Payment received") | PASS |
| A guest account cannot take the staff feed | PASS |
| Client-side events cannot cause actions | PASS: the dashboard only shows toasts and plays sounds from messages; the server ignores anything a client sends |
| Staff receive only their authorised categories | **PARTIAL**: the socket topic is per restaurant, so every staff role's browser receives every event for that restaurant; the role filter decides only what they see and hear. A chef could read payment or table-request text in their browser's developer tools. Per-role topics would fix it and are a larger change |
| Payload exposes no unnecessary customer data | PASS with a note: payloads carry order number, amounts, table code and the guest's free-text request note (up to 500 characters). No names, phones or emails. The note goes to every staff role of that restaurant |
| Token expiry on a socket that is already open | PARTIAL: the token and approval are checked at connect and subscribe, not on every message; a session stays subscribed until it reconnects |

## 9. Known limitations

- No background or locked-device alerts (no service worker, no Web Push).
- Sounds are synthesised; how they sound depends on the device. Loudness balancing is an estimate.
- Preferences live in the browser, per signed-in user, per device.
- Reminder counts restart after a refresh (bounded by the 30-minute age limit).
- Table requests are not assigned to a waiter when raised, so every waiter of the restaurant hears them.
- A waiter-call toast is not removed automatically when the request is acknowledged; it stays up to a minute (the
  reminder, however, stops at once).
- Mute stops sound only. Toasts and the bell always show. This is intended.

## 10. Remaining production risks

1. **No real-browser or real-device testing** of autoplay, suspended audio, silent mode, or reconnection. Highest risk.
2. **Nobody has listened** to the sounds on speakers in a noisy room.
3. Role privacy on the shared restaurant topic (section 8).
4. Reminders for pending orders assume staff confirm orders; if they do not, they will repeat 5 times per order.
5. `V44` has been applied to a fresh throwaway MySQL 8.4 and passed the MySQL suite, but not to a copy of your real data; do that on staging first.
6. Latency under a real network and a real database is unmeasured.

## 11. Final production-readiness assessment

**Ready for a staged or pilot rollout; not yet ready to be called production-ready.**

The server side is in good shape: events are stored with a structured kind, delivered once, to the right restaurant
only, in well under a tenth of a second on a test machine, even with 750 events in flight across two restaurants, and
the whole backend suite passes. The dashboard's decision logic is thoroughly tested, and the audit found and fixed the
real problems it could (merged waiter-call toasts, refetch storms, lost waiter sounds in a rush).

What stops a "production-ready" label is what only real use can show: how the sounds sound, and whether browsers and
phones unlock, keep and resume audio the way the code assumes. Before go-live:

1. Apply `V44` to a staging MySQL copy of real data first (the full suite and `mysqlTest` already pass).
2. On real hardware (a kitchen tablet, a waiter's phone with the silent switch on and off, a desktop): unlock audio,
   leave it idle for an hour, then place an order and call a waiter; confirm both sounds are heard and are different.
3. Pull the network for a minute, restore it, and confirm one catch-up notice and no replays.
4. Listen to every sound in all three themes at your typical volume and adjust any that disappoint.
5. Decide whether per-role topics are needed for your privacy requirements.
6. Keep the dashboard open on a device that stays awake: it cannot alert you in the background.
