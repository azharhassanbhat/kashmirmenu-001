# Security Audit Report — 2026-10-20

Project: Kashmir Menu (Spring Boot + MySQL backend, Vite + React frontend).
This is a new dated file. The earlier `SECURITY_AUDIT_REPORT.md` is unchanged.

**Status: fixes are in source and tested. NOTHING IS DEPLOYED.** The running containers (`kashmirmenu-app-1`, `kashmirmenu-frontend-1`) still contain the old, vulnerable code until `docker compose up -d --build` is run (requires approval).

This report is analytical reasoning to inform decisions, not a system of record. Items not verified are listed under "Not performed".

## 1. Fixes made in source

| # | Severity | Issue | Fix |
|---|---|---|---|
| 1 | High | Any signed-in WebSocket user could publish fake notifications (e.g. "Payment received") into another restaurant's dashboard | `StompAuthChannelInterceptor` now refuses client SEND and transaction frames |
| 2 | High/Critical (dependencies) | Trivy scan of the built images: 42 high/critical findings in the backend jar; 55 OS findings in the frontend image | Spring Boot 3.5.16, Jackson 2.21.7, Tomcat 10.1.57 (`build.gradle`); unused Thymeleaf removed; nginx base `1.30-alpine` (OS findings 55 → 2) |
| 3 | Medium | Sort / page-size abuse on list endpoints | Sort allowlists and page cap of 500 (`PageableUtils`) |
| 4 | Medium | Login brute force | Per-account failure tracking, HTTP 429 after 10 failures in 15 min (`LoginAttemptTracker`); password max 100 chars, login email max 150 |
| 5 | Medium | Payment actions not audited | Collect, status change, refund and settings change audited; UPI IDs format-validated and masked in audit trail |
| 6 | Low | Misc | Fractional values refused for integer fields; review links validated client-side; email no longer logged in `CustomUserDetailsService` and exception message made generic; `security-headers.conf` gained Permissions-Policy and Cross-Origin-Opener-Policy |

## 2. Verified as already sound

Deny-by-default authorization and tenant isolation; JWT handling (forged and `alg: none` tokens rejected, role mass-assignment blocked, logout revokes tokens); uploads (type by content, re-encoded, metadata stripped); customers cannot forge payment status or amount; CSV formula injection handled; prod config (Swagger off, no default JWT secret, Actuator on a separate unpublished port); dedicated non-root DB user; strict CSP raised no violations on admin and customer pages.

## 3. Test results (commands actually run)

Working directory: project root.

| Command | Result |
|---|---|
| `gradlew.bat test --no-daemon` (first attempt) | **Failed — infrastructure error**: `NoSuchFileException` on Gradle's `in-progress-results-generic.bin`. Likely interference from a leftover Gradle daemon; no test failure was reported. |
| `gradlew.bat --stop`, then `gradlew.bat test --no-daemon` | **BUILD SUCCESSFUL**, 2m 21s. 879 tests, 0 failures, 0 errors, 4 skipped (MySQL-only), totals counted from `build/test-results/test/*.xml`. |
| Frontend (earlier in the audit) | 342 tests pass, typecheck clean, build passes, `npm audit` 0 vulnerabilities |

New tests: `ListQuerySecurityIT`, `TenantIsolationIT` (55 cross-tenant attacks, all refused), `PaymentAndOrderSecurityIT`, `AuthHardeningIT`, `LoginAttemptTrackerTest`, `NoPersonalDataInLogsTest`, plus additions to `LiveSocketDeliveryIT` and `StompAuthChannelInterceptorTest`.

Note: `QrCardsIT` failed once in an earlier full run and then passed repeatedly; probably load-related, not confirmed. It passed in this run.

## 4. Remaining risks

- **Tomcat 10.1.57**: critical advisories remain; the fix (10.1.58) was not on Maven Central when checked. Re-check and bump.
- **spring-webmvc 6.2.19**: two critical advisories; the only fix is Spring 7 (major upgrade).
- **Orphan account** `platform-sound@example.test` (privileged, password unknown): remove or rotate. Needs approval.
- **Platform owner password** was shared in chat: change it after first login.
- JWT lifetime 24 h, no MFA.
- No HSTS at nginx (needs TLS).
- Rate limits are per instance.
- App DB user has full rights on its own schema (it runs migrations).
- Registration error reveals whether an email already has an account.

## 5. Deployment and rescan (done, with approval)

`docker compose up -d --build app frontend` (project root) exited 0. `kashmirmenu-app-1` and `kashmirmenu-frontend-1` were recreated and report healthy; the frontend returns HTTP 200 on :3002. MySQL was not touched. Source changes are now live.

Trivy rescan of the rebuilt images (HIGH/CRITICAL, vulnerabilities only):

| Image | Findings |
|---|---|
| `kashmirmenu-app` | 5 critical, 3 high. Critical: Tomcat embed-core 10.1.57 (CVE-2026-65182, -65905, -68525; fixed in 10.1.58, still 404 on Maven Central on 2026-10-20) and spring-webmvc 6.2.19 (CVE-2026-47884, -47890; fixed only in 7.0.9). High: 3 in `usr/bin/pebble` (Go stdlib 1.27.1, CVE-2026-78667, -78669, -97031). Origin of `pebble` in the image not investigated. |
| `kashmirmenu-frontend` | 2 high (Alpine 3.24.2 OS packages). |

The backend jar had 42 high/critical findings before the upgrades; the remaining critical ones are the two open risks in section 4.

### Follow-up: Tomcat bump (2026-10-20, later)

Tomcat 10.1.58 was never published to Maven Central (versions go 10.1.57, 10.1.59, 60, 61), so `tomcat.version` was set to 10.1.61. Full backend suite: BUILD SUCCESSFUL, 879 tests, 0 failures, 0 errors, 4 skipped. Rebuilt image rescanned: the 3 Tomcat criticals cleared; remaining are the 2 spring-webmvc 6.2.19 criticals (fix needs Spring 7, see `SPRING_7_UPGRADE_PLAN.md`) and 3 high in `usr/bin/pebble` (Canonical's Pebble service manager shipped in the `eclipse-temurin:21-jre` base image, unused by the app; removable with `rm /usr/bin/pebble` in the Dockerfile run stage, not yet done). Redeployed with `docker compose up -d --build app` (with approval); app healthy, logs show Apache Tomcat/10.1.61, frontend returns 200. Scan image `aquasec/trivy` and the temporary `kashmirmenu-app:tomcat-check` tag were removed afterwards.

## 6. Not performed

- `mysqlTest` suite.
- Dynamic scanning (ZAP) and Semgrep; load / DoS testing.
- Orphan account removal and password rotation (need owner decision).

## 7. Cleanup (done, with approval)

Removed: Docker images `aquasec/trivy`, `nginx:stable-alpine`, `nginx:mainline-alpine`; volume `trivycache`; four image tarballs (about 330 MB) from the earlier scan scratchpad. Small scan JSON/log files remain in that scratchpad.

## 8. Next steps

1. Bump Tomcat to 10.1.58 when it appears on Maven Central, then rebuild and rescan.
2. Plan the Spring 7 upgrade.
3. Investigate the `pebble` binary in the app image.
4. Decide on `platform-sound@example.test` and rotate the platform owner password.
