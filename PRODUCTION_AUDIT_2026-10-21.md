# Production audit (2026-10-21)

This audit builds on the earlier work in this project (security audit 2026-10-20, UPI payment verification, responsive audits, capacity report). It does not repeat their detail; it re-verifies what could be re-verified today, adds the checks that had not been done, and lists what is still open. Nothing here claims the application is bug-free or fully secure.

Labels: **TESTED** (I ran it and saw the result), **READ** (code or configuration inspected, not executed), **NOT VERIFIED** (could not be checked, with the reason).

## 1. Feature verification

| Area | Status | Evidence |
|---|---|---|
| Owner registration, login, logout, token revocation | TESTED | Backend suite (892 tests); live: login, lockout after 10 failures (429), role field ignored at registration |
| Platform approval of new restaurants | TESTED | Live: new restaurants stay PENDING and are refused until approved |
| Staff accounts and roles | TESTED | `StaffIT` in H2 and on MySQL |
| Password recovery for staff | READ | Owner can reset a staff password; **no self-service recovery for owners** (see issue 3) |
| Menu, categories, tables, inventory, coupons | TESTED | Backend integration tests; menu, table and dish creation used live in this session |
| Customer flow: scan, join, cart, order, track | TESTED | Live browser run on a phone-sized view; k6 floor test (thousands of orders) |
| Payments: cash, UPI QR, claim, staff confirm and reject, receipt | TESTED | `UpiQrPaymentIT` (12), live browser run, k6 floor test |
| Multi-restaurant isolation | TESTED | `TenantIsolationIT` (55 attacks refused), UPI two-restaurant tests, live cross-restaurant calls refused |
| Notifications, sounds, live updates | TESTED (earlier session) | Sound system browser-verified; WebSocket capacity not tested |
| Analytics and revenue pages | READ + loaded in browser | Pages load and show data at all widths scanned; accuracy covered by analytics unit tests (in the 892) |
| Image upload | TESTED | Live: HTML disguised as PNG, GIF with script, SVG: all refused (400); 20 MB file refused by nginx (413) |
| Backup and restore | TESTED (new) | Script written, real dump of the live database, restored into a scratch database: same row counts (26 orders, 12 restaurants), scratch database dropped |
| Every button on every admin page by hand | NOT VERIFIED | Pages were scanned for layout and loaded; not every control was clicked |

## 2. Issues found

| # | Severity | Issue | Status |
|---|---|---|---|
| 1 | High | `spring-webmvc` 6.2.19 has two critical advisories (CVE-2026-47884, -47890); the fix exists only in Spring 7 | **Open.** Plan in `SPRING_7_UPGRADE_PLAN.md`; reachability not assessed |
| 2 | High (for a real launch) | No automated database backup existed, and no restore procedure | **Fixed**: `ops/backup-mysql.sh`, `docs/BACKUP_AND_RESTORE.md`. Scheduling it and copying files off the machine is a manual step |
| 3 | Medium | An owner who forgets their password has no way back in (no email service, no recovery flow) | **Open**: needs an email provider and a decision on the flow |
| 4 | Medium | Staff changing the same order at once deadlocked in MySQL (486 errors in a test) | **Fixed** in source and deployed (earlier today); regression test added |
| 5 | Medium | nginx ran out of ports at about 450 requests/s (502 errors) | **Fixed** and deployed (`frontend/nginx.conf`) |
| 6 | Medium | `.env` repeats keys (`PUBLIC_BASE_URL`, `CORS_ALLOWED_ORIGINS`, `FRONTEND_PORT`): the later `localhost` values override the earlier production domain. Table QR codes would encode `localhost` | **Open, config only**; not changed because it is your file. See checklist |
| 7 | Low | Image scan: `libexpat`, `libx11` (frontend image) and 4 `pebble` findings (app image) | **Fixed in the Dockerfiles; images rebuilt and rescanned: frontend 0 high/critical, app only the 2 Spring items. Not yet deployed** |
| 8 | Low | Session lasts 24 hours, no multi-factor sign-in; no HTTPS and so no HSTS on the local stack | **Open**; HSTS needs the TLS layer in front of nginx |
| 9 | Low | Registration reveals whether an email already has an account | **Open** (known) |
| 10 | Low | The 413 "too large" reply from nginx is an HTML page, not the JSON the API uses elsewhere | **Open**, cosmetic |
| 11 | Low | No lint tool in the frontend or backend; MySQL-suite test infrastructure ran out of connections and two tests had MySQL-specific expectations | Tests fixed earlier today (limit raised, tolerance corrected); lint **not added** (a new dev dependency needs your approval) |
| 12 | Info | Earlier this session the k6 scripts could not run on k6 2.x and seeded restaurants with no payment settings | **Fixed** |

## 3. Security findings

**Probed live against the running stack (TESTED):**
- Headers present on the web page: Content-Security-Policy (no inline scripts, no external sources), `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`. No HSTS (needs HTTPS).
- Private endpoints (`/api/v1/orders`, `settlements`, `me`, `staff`, `payment-settings`, `images`, platform admin) return 401 without a token.
- Another restaurant's token gets 404 on this restaurant's payment settings (read and write) and sees an empty pending-payments list.
- Actuator is on a separate port that is not published; Swagger and API docs are not served; `/h2-console`, `/.env` and similar paths return only the web app's start page (the "200" responses are the single-page-app fallback, 754 bytes of HTML, not real files).
- CORS: a request from another origin receives no `Access-Control-Allow-Origin`.
- Error bodies are short JSON messages: no stack traces, SQL or class names seen for bad JSON, unknown tables, wrong passwords, or a path containing SQL text.
- Login lockout: 10 failures, then 429.
- Registration with `"role":"PLATFORM_OWNER"` produced an ordinary `RESTAURANT_OWNER`; the platform-admin area refused the new token (401).
- Uploads: the type is decided by the bytes; disguised files and SVG refused.

**Read, not executed:** secrets: none found in source, configuration files, or the built frontend; `.env` is ignored by git and Docker and was left out of the zip; `.env.example` holds placeholders only. CSRF: the API uses bearer tokens in a header (not cookies), so classic CSRF does not apply. XSS: React escapes output and the CSP blocks inline script.

**Dependency advisories:** `npm audit` 0 vulnerabilities (production and development); image scan as in issue 7; Gradle dependencies covered by the image scan (Tomcat 10.1.61 and Jackson patched earlier; Spring issue open).

**Remaining risks:** issues 1, 3, 8, 9 above; the platform-owner password that was typed in chat earlier should be changed; the old `platform-sound@example.test` account still exists (your decision); rate limits are per instance and in memory.

## 4. Performance and reliability

Measured earlier today (`CAPACITY_REPORT_2026-10-21.md`): about 730 requests per second with zero failures at 1,000 concurrent guests on this laptop; deadlock and proxy fixes removed all server errors. Frontend: JavaScript 160 KB transferred on the customer menu, load 78 ms on localhost, no layout shift, no long tasks; route-based code splitting already present. Network failure handling: the pay flow keeps idempotency keys across uncertain outcomes (tested in unit and integration tests). Memory leaks: not profiled (NOT VERIFIED).

## 5. Tests run today

| Command | Result |
|---|---|
| `gradlew test` | 892 tests, 0 failures, 5 skipped (MySQL-only) |
| `gradlew mysqlTest` (second full run) | 426 tests, 2 failed; both fixed and re-run: 32 tests in those classes passed. **No clean full re-run afterwards** |
| Frontend `tsc`, `vitest`, production build | passed; 351 tests |
| `npm audit` | 0 vulnerabilities |
| k6 smoke, baseline, floor tests (isolated stack) | see the capacity report |
| Image scans before and after the Dockerfile changes | frontend 2 to 0, app 6 to 2 (high/critical) |
| Lint | not present, not run |
| Playwright or similar end-to-end tool | not present, not run; browser checks were manual and scripted in the built-in browser |

## 6. Unverified areas

Real phones, tablets, Safari, Firefox, screen readers, the on-screen keyboard; WebSocket load; photo-heavy menus; the full admin click-through; analytics on large history; behaviour behind real TLS and a real domain; the two Spring advisories' reachability; password recovery (does not exist).

## 7. Deployment readiness

**Ready for a pilot** on one server for a few restaurants, after the checklist below. **Not ready to call production-ready** until backups are scheduled and tested off the machine, the `.env` values are correct for the real domain, HTTPS is in front, and the Spring advisories are either upgraded or assessed. The rebuilt images (Dockerfile fixes) are built locally but **not deployed**; the deployed stack still has the earlier fixes only.

## 8. Production deployment checklist

1. Set `.env` once, with no duplicate keys: real `PUBLIC_BASE_URL` (the https domain; it is encoded in every table QR code), matching `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE=prod`, strong unique `JWT_SECRET` (32+ characters), `DB_PASSWORD`, `DB_ROOT_PASSWORD`; keep `RESTAURANT_APPROVAL_REQUIRED=true`.
2. Put HTTPS in front of nginx (a reverse proxy or the host's TLS) and then add `Strict-Transport-Security`; keep `TRUST_FORWARDED_HEADER=true` only if that proxy overwrites `X-Forwarded-For`.
3. Do not publish MySQL (it is bound to localhost) or the actuator port (it is not published).
4. Schedule `ops/backup-mysql.sh` nightly, copy the files off the machine, and do one practice restore (`docs/BACKUP_AND_RESTORE.md`).
5. Rebuild and deploy the images so the Dockerfile fixes take effect (`docker compose up -d --build`), then rescan.
6. Change the platform-owner password; decide about the `platform-sound@example.test` account; delete or reject test restaurants ("UPI Test A", "UPI Test B", "x" registered during the role-escalation probe).
7. Run `gradlew test`, `gradlew mysqlTest` (full), and the frontend checks one last time on the release build.
8. Watch `/actuator/health` (readiness) and the Prometheus panels on first days; set up alerts from `MONITORING_ALERTS.md`.
9. Decide how owners will recover forgotten passwords before real users sign up (issue 3).

## 9. Recommendations by impact

1. Schedule and test backups off the machine (done in tooling, needs your action).
2. Fix the `.env` duplicates and set the real domain, then deploy behind HTTPS.
3. Plan the Spring 7 upgrade (and assess whether the advisories are reachable in the meantime).
4. Add an owner password recovery flow (requires an email provider).
5. Replace polling with pushed updates and cache the public menu before growing past a few hundred active diners (capacity report).
6. Add linting and an end-to-end browser test suite (needs your approval for new dev dependencies).
