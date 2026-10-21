# QR landing page

**Status:** the rules (links, Wi-Fi, hours, rewards, feedback), the migration's constraints and the frontend helpers are executed for real (`python3 docs/checks/run_all.py`). The endpoints, screens and `LandingIT` have **not** been run: no Gradle or npm was available where this was built. Run `./gradlew test`, `./gradlew mysqlTest` and `cd frontend && npm run typecheck && npm test && npm run build` first.

## What it does

Scanning a table's QR code opens a mobile-first page for that restaurant instead of going straight to the menu: logo, name, tagline, cover image, an announcement, and cards for **Start earning rewards**, **Leave a Google Review**, **View Menu**, **Connect to Wi-Fi** and **Call a waiter**, plus Call, Directions, Website, opening hours (open or closed now) and Instagram, YouTube and Facebook links. Every card is optional; one that is not set up is hidden, never shown dead.

**Printed QR codes keep working.** The QR still encodes `/menu/{qrIdentifier}`. That address shows the landing page first when the owner switched it on, and once a guest taps View Menu (or the address has `?view=menu`) it shows the menu for the rest of that browser session, so every existing link inside the app is unchanged. `/menu/{qr}/home` always shows the landing page. A restaurant that never saved landing settings sees no change at all.

## Owner setup: Admin, QR landing page

Tagline, cover image (upload a JPEG/PNG or paste an https link; uploads are stored like dish photos), announcement, opening hours, links, guest Wi-Fi, the call-a-waiter switch, rewards rules, and the order and visibility of the cards. Hours are plain lines (`Mon-Fri 09:00-22:00`, `Sat,Sun 10:00-23:30`, `Wed closed`; `22:00-02:00` runs past midnight; `24:00` means midnight, so `00:00-24:00` is open all day). Brand colour comes from the restaurant's existing `primaryColor`.

### Google review link (no Place ID)

Paste the link from Google Business Profile ("Get more reviews"), e.g. `https://g.page/r/.../review`, into the **Google review link** field on Admin, QR landing page. It is stored exactly as given and opened in a new tab with `rel="noopener noreferrer"`. Only https links to Google are accepted (look-alike hosts, embedded credentials and Google redirectors are refused). Blank hides the button. Guests see it as the **Leave a Google Review** card on the landing page and as the "Write a review" button, review section and callouts on the menu and order pages. There is no separate "Google reviews" page or dashboard banner for the restaurant any more; this one field is the only place to set it.

> **Removed: anonymous feedback.** The landing page's anonymous feedback card and form, the admin switch and the inbox are gone from the app. The database table and server endpoint still exist. The app ignores the `FEEDBACK` card the server may still list, and saving the landing settings once switches anonymous feedback off, after which the server refuses new anonymous feedback. Guests still rate a served order privately (see CUSTOMER_FEEDBACK.md).

## Guests

- **Wi-Fi.** Name and password with Copy, and the standard `WIFI:` QR (drawn by the existing ZXing service) that phone cameras offer to join. The password is returned only by `GET /api/public/tables/{qr}/wifi` (never cached), never by the admin read, the landing response or the table response. Saving without a password keeps the saved one. It is stored as plain text because guests must be shown it; anyone holding a table's QR address can fetch it, so use a guest network.
- **Rewards (off by default).** A guest's phone makes a secret; the server keeps only its hash and returns an 8-character member code. Points are added only for an order of that restaurant that is **served and paid in full**, worked out on the server, **once per order** (a database rule), with a refunded order earning nothing. Opening the page or tapping the review button earns nothing. Staff look a member up by code (balance and whether a reward is due), then redeem, at Admin, QR landing page; the balance is spent with one conditional UPDATE and cannot go below zero. Memberships live on the phone: clearing browser data starts over.

## Database (V41, additive only)

`restaurant_landing_settings` (Wi-Fi password lives here, not on `restaurants`), `anonymous_feedback`, `reward_members`, `reward_ledger`. Rollback: `DROP TABLE reward_ledger, reward_members, anonymous_feedback, restaurant_landing_settings;` and delete the `flyway_schema_history` row for version 41.

## API

| Method and path | Who |
|---|---|
| `GET /api/public/tables/{qr}/landing` | Guest |
| `GET /api/public/tables/{qr}/wifi`, `/wifi/qr` | Guest (404 if Wi-Fi is off) |
| `POST /api/public/tables/{qr}/anonymous-feedback` | Guest, rate limited |
| `GET /api/public/tables/{qr}/rewards`, `POST .../rewards/join`, `POST .../rewards/claim` | Guest, header `X-Reward-Token` |
| `GET, PUT /api/v1/landing`, `GET /api/v1/landing/feedback` | Owner, manager, admin (their own restaurant only) |
| `GET /api/v1/rewards/members/{code}` (balance), `POST /api/v1/rewards/redeem` | Owner, manager, admin, waiter, cashier |

## Configuration

Optional env vars: `LANDING_FEEDBACK_RATE_LIMIT` (5), `LANDING_REWARDS_RATE_LIMIT` (20), `LANDING_RATE_LIMIT_WINDOW_SECONDS` (600). `PUBLIC_BASE_URL` must be your real public address, as for every printed code. The CSP is unchanged: images from `https:`, the Wi-Fi QR as a `blob:`.

## Manual test

1. Admin, **QR landing page**: tick the switch, set a tagline and a Google review link, Save. **Preview**.
2. Scan a table's printed QR (or open `/menu/{qr}`): the landing page shows. Tap **View Menu**: the menu opens; its links still work. Open a fresh private tab: the landing page shows again.
4. Turn on Wi-Fi with a short password: refused. Use 8+ characters: the card appears, the sheet shows name, password, Copy and a QR a phone camera can join. Re-save without typing a password: the password is kept.
5. Send feedback from a phone without signing in; it appears in the inbox without any contact detail. Send six quickly: the later ones are refused.
6. Turn on rewards, start earning, place an order, serve it, then try **Add points**: refused until paid. Collect payment, **Add points**: points appear; pressing again is refused. Enter the member code under **Redeem a reward**, press **Check balance** (it shows the points and whether a reward is due), then **Redeem**.
7. Second restaurant with different settings: each QR shows only its own.

## Not included

- Sudoku or any mini-game, and a digital waitlist (optional in the brief; the page does not need them). "Call a waiter" reuses the existing table requests, which need the guest's name, so it goes through the menu's name step.
- Per-restaurant brand colours beyond accents, custom card icons, localisation (the app is English only).
- Rewards expiry, tiers, or points for anything other than paid orders.
