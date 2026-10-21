# Notification sounds

Live alerts for restaurant staff, the platform owner and (optionally) guests. Visual notifications always work;
sound is an addition the person controls.

## How events travel

* The backend already saved a notification for each event and pushed it, after the transaction committed, on a
  STOMP/SockJS WebSocket (`/ws`): `/topic/restaurants/{id}` for a restaurant.
* New: `/topic/platform`, for the platform owner only (a new restaurant is waiting for approval). Nothing is stored
  for it; the platform dashboard's pending list and count are the durable record.
* The server authenticates the socket's bearer token. A user may subscribe only to their own restaurant's topic, and
  only while that restaurant is APPROVED; only a PLATFORM_OWNER may subscribe to the platform topic.
* The browser keeps **one** shared connection per tab (`lib/topicHub.ts`), however many pages use live updates.

## Events and sounds

| Event (notification type) | Sound | Setting that controls it | Heard by |
|---|---|---|---|
| New order (`ORDER_PLACED`) | New order chime: three rising bell notes | New Order | managers, chef |
| Customer calls waiter (`SERVICE_REQUEST_CREATED`, "Call waiter" / "Assistance") | Service bell: the same high bell struck twice | Waiter Call | managers, waiter |
| Customer asks for the bill (`SERVICE_REQUEST_CREATED`, "Bring the bill") | Soft two-note falling tone | Bill Request | managers, waiter, cashier |
| Other table request (water, cutlery...) | Soft ping | Message | managers, waiter |
| Order ready (`ORDER_READY`) | Four quick rising notes | Order Ready | managers, waiter |
| Order cancelled (`ORDER_CANCELLED`) | Two slow falling bell notes | Cancellation | managers, chef |
| Payment received (`PAYMENT_SUCCESS`) | Short rising two-note | Payment | managers, cashier |
| Payment failed (`PAYMENT_FAILED`) | Soft low descending warning | Payment | managers, cashier |
| Refund processed (`PAYMENT_REFUNDED`) | Soft ping | Payment | managers, cashier |
| Customer review or feedback | Soft ping | Message | managers |
| Request updated | Soft ping | General Notification | managers, waiter |
| Restaurant approved or reactivated | Short rising two-note | General Notification | all staff |
| Restaurant rejected or suspended | Critical (3 pulses) | Master switch only | all staff |
| New restaurant awaiting approval (platform owner) | Soft ping | Platform notifications | platform owner |
| Order confirmed / preparing / served, coupon applied | none for staff (toast only) | n/a | n/a |

Only the sound is filtered by role (`roleHears` in `lib/sound/eventMap.ts`); every staff member still gets the toast.

**Structured event kind.** Every notification carries `eventKind` (`NEW_ORDER`, `WAITER_CALL`, `BILL_REQUEST`,
`TABLE_REQUEST`, `ORDER_READY`, `ORDER_CANCELLED`, `PAYMENT_SUCCESS`...; `NotificationEventKind` on the backend). It is
stored in `notifications.event_kind` (migration V44) and sent on the socket and in `/notifications` responses. The
dashboard picks the waiter / bill sound from it, so rewording or translating a notification cannot change a sound. A
message with no `eventKind` (an older server, or a row saved before V44, which the API fills in from its type) is read
the old way, by the label at the start of its text; an `eventKind` this app does not know falls back the same way.

Guests (the order page's optional **Sound alerts**) hear a different short sound for each step: accepted, preparing,
ready (the bright order-ready chime), served and cancelled. The app has no customer payment notification, so there is
no customer payment sound.

## Themes, reminders

* **Sound theme** (Bell, Crystal, Warm) changes pitch and timbre only; each event keeps its own melody and timing.
* **Reminders** (Never, 30 s, 1, 2, 5 min; default 1 min) follow the **server's** state, not browser memory. The
  dashboard reads the staff board and the kitchen's active orders on load, after every live message, on reconnect and
  every 30 s; a table request still `PENDING` (nobody acknowledged it) or an order still `PENDING` (nobody confirmed it)
  is "unanswered". The moment the next read no longer lists it, on any device, its reminder stops. A refresh just reads
  the same state again, so reminders survive it, but nothing plays at load.
  * Several open requests make **one** sound per reminder moment (the most important), never a pile.
  * The first reminder comes one full interval after the request is first seen (its live sound already played).
  * At most 5 reminders per request, and none for a request older than 30 minutes (it stays on the board, silent).
    The count restarts after a refresh; the 30-minute limit bounds that.
  * Only roles that hear the original event are reminded; ordinary requests (water, cutlery) are never repeated.
  * Muting, signing out or setting Never stops them all.

## Rules the dashboard follows

* **No sound for history.** On start it records the highest notification id already stored; only messages above
  that, not seen before (a bounded id set), can sound. Refetching a list never sounds.
* **Offline and reconnect.** On reconnect and when the tab becomes visible, unread notifications are fetched and, if
  there are new ones, shown as one "While you were away: N new notifications" notice, with no sound. The one
  exception: if that notice includes a new order placed in the last 2 minutes (the connection was still starting
  or blipped), the new-order chime plays once. Anything older is history and stays silent.
* **Muting takes effect at once.** Switching the master switch (or a category) off, or dragging the volume to 0,
  silences the sound that is playing and drops the ones waiting; a new volume applies to the sound already playing.
* **Background tabs.** A hidden tab skips the short collapse delay (its timers are throttled by the browser), so a
  new-order chime starts as soon as the message arrives. The browser decides whether a background tab may make
  sound at all; the context stays running once it has been unlocked.
* **Missed while disconnected.** On every reconnect the unread list is fetched; everything newer than the baseline
  becomes one notice. If it includes a new order or a waiter call from the last 2 minutes, its sound plays once
  (anything older is history). Still-unanswered requests are picked up again by the reminder read above.
* **Several tabs, one sound.** Tabs of one browser that hear the same event claim it through a Web Lock named after
  the event (`lib/sound/soundClaims.ts`): the first tab to ask plays, the others stay silent but still show the toast
  and refresh their data. The lock is held for 8 seconds and the browser drops it when a tab closes, so another tab
  takes over at once. A tab whose audio is still blocked does not take part. Without the Web Locks API every tab
  plays. Different restaurants and the platform feed use different lock names. Separate devices always each play.
* **Messages without a database id.** A service request update is sent live only (never stored), so it has no id; it
  is treated as happening now, and keyed by its timestamp and text for the cross-tab claim.
* **Dead sessions are noticed.** When the live connection drops, the dashboard re-reads `/me` (at most every 30 s). An
  expired or revoked token signs the person out; a suspended restaurant gets its status screen.
* **Enable button.** The page-wide "first click unlocks audio" listener ignores presses on controls marked
  `data-sound-activator` (the Enable buttons): they unlock audio and play their one confirmation themselves.
* **Wrong restaurant** messages are dropped on the client as well as refused by the server.
* **Floods.** Sounds never overlap (a 700 ms minimum gap, and a long sound finishes first). Events close together
  collapse to the highest-priority sound, except that each "must hear" kind that lost (new order, waiter call, bill
  request, critical) still gets its own turn right after, ONE sound per kind: a waiter call that lands with a new
  order is queued behind its chime, and ten waiter calls in one moment are one bell. Ordinary pings are collapsed.
  A request for a sound already waiting adds nothing. Every event still gets a toast; at most 4 are shown (the rest
  are counted as "+N earlier" and remain in the bell and on the requests board).
* **Waiter and bill toasts stay up.** A waiter call or bill request toast is one per request (table), stays for a
  minute unless dismissed, and has an "Open requests" button; repeats from the same table merge into "x2".
* **Bursts do not become request storms.** Live messages refresh the dashboard's lists at most twice per burst (at
  once, and once more when it ends), not once per event.
* **Browser autoplay.** Audio starts only after a real user gesture. Until then the app says so ("Notification sounds
  are blocked by your browser", with an Enable button) and never claims otherwise. The browser Notification
  permission is not requested.
* A decision about a restaurant (approved, suspended...) sounds once even if two paths notice it.

## Settings

Page: **Alerts & sound** (`/admin/notifications`); the kitchen and floor screens have a **Sound** button for staff who
cannot open /admin; the platform owner's **Settings** page has its own section.
Controls: master switch (overrides the rest), New Order, Waiter Call, Bill Request, Order Ready, Payment, Cancellation,
Message, General Notification, theme, reminder interval, volume 0-100, Test Notification Sound, per-category Preview.

Preferences are stored in the browser's `localStorage` per signed-in user (`kmenu.sound.v1.<userId>`; guests use
`kmenu.sound.v1.guest`). The application has no per-user server-side settings, so they follow the device and the
account on that device, not the account across devices. Defaults: everything on, volume 70 (guests: off, quiet).

## Guests

The order page has a **Sound alerts** toggle (off by default). When on, one quiet chime plays when the order's status
moves to a later step while the page is open; never on first load, never for a repeated status.

## Known limits

* The sound is synthesised in the browser (Web Audio), so there are no audio files; how it sounds depends on the device.
  Each sound is level-matched by a computed trim (weighted loudness, with headroom so nothing clips); this is an
  estimate, not a measurement of real speakers.
* Coordination between tabs works inside one browser profile only; two browsers or two devices each play.
* A restaurant reactivated while its owner sits on the status screen is noticed by that screen's 20-second poll, so
  its sound can come up to 20 s late.
* The socket checks the token and the restaurant's approval when it subscribes, not on every message: a session
  already subscribed keeps hearing its restaurant until it reconnects (a suspension takes effect then).
* A cancellation or order made by staff on that same dashboard also sounds there (events do not say who caused them).
* A toast that arrives while the Sound dialog is open is hidden behind it until it closes.
* Platform events are not stored: after being offline the platform owner sees the updated pending list, not a replay.
* Only the platform owner's pending-registration event exists at platform level; no other platform alerts exist yet.

## Background and push notifications (evaluated, not added)

The app has no service worker, no web-push subscription store and no VAPID keys on the server. Sounds therefore play
only while a dashboard tab is open and its audio has been unlocked; a phone that is locked, or a browser that is
closed, hears nothing from this app. Adding Web Push would need: a service worker, a push-subscription table and
endpoints, VAPID key management, a sender on the backend, per-role delivery rules, and HTTPS everywhere; push
notifications use the operating system's sound, not these. That is a separate project with its own privacy and
operations decisions, so it was left out. Until then a kitchen or floor device should keep the dashboard open on
a screen that stays awake, with the tab unmuted.

## Platform notes

* Browsers need one tap before audio can start; the "Enable Notification Sounds" bar and button do that. A
  suspended or (iOS) interrupted audio context is resumed when the tab returns to view and on the next tap.
* Phones follow the ring/silent switch and media volume: a silenced phone may play nothing, and the app cannot tell.
* The browser's own Notification permission is never requested; no OS-level notification is shown.
