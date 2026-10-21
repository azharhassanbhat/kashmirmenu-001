# Remediation — Security Audit

Companion to `SECURITY_AUDIT_REPORT.md`. One section per finding: what changed, and what you must
check before trusting it.

> **Nothing here has been compiled or executed.** This environment has no Spring/Gradle dependencies
> and no database, so every change below was written and reviewed statically. These are invasive
> changes to the authorization layer — **run `./gradlew test` before deploying.** The most likely
> breakage is a legitimate role being refused by a matcher, which `AuthorizationBoundaryIT` should
> surface immediately.

## Breaking changes

| Change | Who is affected | Action |
|---|---|---|
| Profile now defaults to `prod` | Local development | Set `SPRING_PROFILES_ACTIVE=dev` explicitly |
| `JWT_SECRET` has no default in any profile | All environments | Generate one: `openssl rand -base64 48` |
| Placeholder-looking secrets are refused at startup | Anyone reusing the old dev value | Replace it |
| Diner tracking moved to `/api/public/orders/by-token/{token}` | Old diner links (`/order/ORD-14`) | They 404; diners re-open from the QR code |
| `/actuator/info` now requires authentication | Monitoring that scraped it | Use `/actuator/health/{liveness,readiness}` |
| STOMP `CONNECT` requires a bearer token | Any non-browser socket client | Send `Authorization: Bearer …` |
| Migrations V22, V23 | All environments | Apply before starting the new build |

---

## SEC-005 — Insecure default JWT secret (High)

- `application.yaml`: profile defaults to `prod`, so a missing variable fails fast rather than
  starting on the dev placeholder.
- `application-dev.yml`: literal fallback secret removed; `secret: ${JWT_SECRET}`.
- `JwtProperties.validate()`: rejects secrets containing `dev-only`, `change-me`, `insecure`,
  `placeholder`, `example`, `replace-with`, `secret-key`. Length alone could not tell a published
  string from a private one.
- `.env.example`: `JWT_SECRET` is now empty with generation instructions.

**Rotate `JWT_SECRET` in any environment where the old default may have been active.** Rotation
invalidates all existing tokens, which is the point.

## SEC-001 — Admin APIs open to any authenticated user (Critical)

- `SecurityConfig`: explicit role matchers plus **`.anyRequest().denyAll()`** as the fallback. A new
  endpoint is now a 403 until deliberately granted, instead of being reachable by anyone signed in.
  Role groups are named constants (`MANAGEMENT`, `STAFF`, `KITCHEN`).
- Class-level `@PreAuthorize` added to the eleven previously unguarded controllers, so the rule
  travels with the code and survives a filter-chain edit.

Note the two layers must agree. An earlier pass had the filter allowing staff `GET /api/categories`
while the class annotation denied it; both now sit at management level. Diners are unaffected — the
public menu is served from `/api/public/tables/{qr}/menu`.

## SEC-002 — Cross-tenant IDOR (Critical)

- `RestaurantPaymentSettingServiceImpl`: `assertOwnRestaurant(restaurantId)` on create, read and
  update; a mismatch answers "not found", so ids cannot be probed.
- `InventoryServiceImpl`: listing uses `findByRestaurant(ownRestaurant())` instead of `findAll()`;
  every by-id operation goes through `ownInventory(id)`; `createInventory` takes the restaurant from
  the principal and additionally verifies the menu item belongs to it. `CreateInventoryRequest.restaurantId`
  is still accepted but documented as ignored.
- `NotificationController`: `restaurantId` is accepted and ignored (keeping older frontend builds
  working); the restaurant comes from the principal. `markAsRead` and `getOrderNotifications` gained
  ownership checks.

The per-service pattern is what failed here. Consider a Hibernate filter or a tenant-aware repository
base class so this cannot recur.

## SEC-003 — Unauthenticated WebSocket subscription (High)

- New `StompAuthChannelInterceptor`, registered on the inbound channel: `CONNECT` must carry a valid
  bearer token (which becomes the session principal), and `SUBSCRIBE` is allowed only to the
  subscriber's own `/topic/restaurants/{id}`. Any other destination is refused rather than allowed by
  omission.
- Frontend `useRestaurantTopic` sends the token via `beforeConnect`, read per connect so a reconnect
  after a new sign-in uses the current token.

Only the four authenticated staff pages subscribe; diner pages poll, so they are unaffected.

## SEC-004 — Enumerable public order tracking (Medium)

- Migration `V22`: `orders.public_token`, unique, backfilled for existing rows.
- `OrderServiceImpl.newPublicToken()`: 24 bytes from `SecureRandom`, Base64url (192 bits).
- Public endpoints re-keyed to `/api/public/orders/by-token/{token}` for both tracking and diner
  payment selection. `ORD-14` remains the readable label shown to diners and staff.
- `AuthRateLimitFilter` now throttles **GET** on `/api/public/orders/**`; previously only non-GET
  public requests were limited, so a token could have been guessed at speed.
- Frontend stores the token in order history and routes `/order/:token`; history entries saved before
  this change have no token and are hidden rather than linked to a dead URL.

## SEC-006 — No token revocation (Medium)

- Migration `V23`: `users.tokens_valid_from`.
- `POST /api/v1/auth/logout` (authenticated) sets it to now, invalidating every token issued earlier
  for that user, across all devices. No denylist to store or expire.
- `JwtService.isTokenValid` rejects tokens whose `iat` precedes the watermark.
- `JwtAuthenticationFilter` no longer skips `/api/v1/auth/logout`, which it must not, or the endpoint
  would never see a principal.
- Frontend calls logout before clearing local state — not awaited, errors swallowed, so signing out
  cannot fail or hang.

**Known limitation:** `iat` has one-second precision, so a token minted in the same second as the
logout can survive it. The alternative would reject a fresh sign-in in that second, which is worse.

Access tokens still last 24 hours by default (`JWT_ACCESS_TOKEN_EXPIRATION`). Consider shortening
them now that revocation exists.

## SEC-008 — PII in logs (Low)

`AuthServiceImpl` logs user and restaurant ids instead of email addresses; the "user not found" and
"email already exists" lines no longer echo the submitted address, which removes the enumeration
oracle. The login line dropped to DEBUG.

## SEC-010 — Pre-auth exposed surfaces (Low)

`websocket-test.html` deleted along with its matcher and the filter's special case. Actuator permits
narrowed from `/actuator/health/**` plus `/actuator/info` to just `/actuator/health/liveness` and
`/actuator/health/readiness` — the two the Docker healthcheck and orchestrators need.

## SEC-007, SEC-009, SEC-011, SEC-012 — not changed in code

- **SEC-007 (login throttling):** per-IP limiting remains. Per-account lockout needs a counter and
  lock-until on the user row, and the limiter should move to a shared store before running more than
  one instance. Not done.
- **SEC-009 (dependencies):** still unaudited. Run `./gradlew dependencyCheckAnalyze` and
  `npm audit --omit=dev` in CI, failing on High/Critical.
- **SEC-011 (`localStorage` token):** accepted trade-off, documented in the report. Unchanged, and
  now bounded by real revocation. If you ever move to `HttpOnly` cookies, re-enable CSRF in the same
  change.
- **SEC-012 (TLS):** needs your deployment answer. If TLS does not terminate upstream of nginx,
  terminate it there and redirect port 80.

---

## What was actually run, and what was found

`./gradlew test` **cannot run in this environment** — the Gradle wrapper cannot download Gradle
itself, there is no dependency cache and no database. Rather than stop there, the two changes most
likely to break the application were checked mechanically. Both scripts are in `docs/`.

**`docs/authz_check.py`** resolves every controller endpoint against the SecurityConfig matcher list
in declaration order and against the class-level `@PreAuthorize`, then reports the effective role set
per endpoint. It flags three failure classes: an endpoint that fell through to `denyAll`, a
contradiction between the filter and method security, and any non-public path a `CUSTOMER` can still
reach. It also checks for duplicated non-repeatable annotations, and that every `@Table`/`@Column`
appears in the migrations (`ddl-auto: validate` makes a mismatch a startup failure).

**`docs/migration_check.py`** checks version uniqueness and that no migration alters or writes to a
table before it is created.

Each check was mutation-tested — a deliberate break was introduced and the script confirmed to catch
it — because a verifier that passes on the first run proves nothing until it has been shown to fail.

### Bug found and fixed

**`PaymentController` had two class-level `@PreAuthorize` annotations.** The bulk edit that added
class-level rules to the eleven unguarded controllers did not notice that this one already had one.
`@PreAuthorize` is not repeatable, so this would have **failed to compile**. The pre-existing
annotation was also the better policy — cooks do not handle money, so `CHEF` is correctly absent from
the payment endpoints — so the added one was removed and the original kept. The duplicate-annotation
check was then added to the verifier and mutation-tested.

Two further issues were corrected while reviewing the generated matrix:

- `POST /api/v1/auth/logout` was protected only by method security, because `/api/v1/auth/**` is
  `permitAll` in the chain. An explicit `.requestMatchers("/api/v1/auth/logout").authenticated()`
  now precedes that block, so the chain states the rule too.
- The filter briefly allowed staff `GET` on `/api/categories` while the class annotation denied it.
  Both now sit at management level.

A repository-wide scan for stray control characters (a hazard of the scripted edits used throughout
this work) found none.

### Still not verified

Everything that needs a JVM with Spring on the classpath or a running database: whether the
application context starts, whether the JPQL compiles, whether migrations V22/V23 apply cleanly,
whether `AuthorizationBoundaryIT` passes, and whether the STOMP interceptor behaves as intended
against a real client. **`./gradlew test` remains required.**

## Verification

Added `src/test/java/com/kashmirmenu/app/controller/AuthorizationBoundaryIT.java` — ten negative
tests, each of which fails without its corresponding fix:

- anonymous access to every business endpoint → 401
- self-registered `CUSTOMER` against staff and admin endpoints → 403
- customer attempting writes and deletes → 403, owner data intact
- unmapped path and `/actuator/info` → 403 (proves the `denyAll` fallback)
- cross-tenant payment-settings read and write → 404, victim's payment methods unchanged
- inventory list, read, stock update and delete across tenants → scoped / 404
- `restaurantId` in query and body cannot redirect whose data is returned
- order-number guessing against the tracking endpoint → 404; token length ≥ 32
- malformed, foreign-signed and missing tokens → 401

The three existing ITs were re-keyed to the public token.

Still worth adding: a reflection test asserting every `@RestController` outside `/api/public/**` and
`/api/v1/auth/**` carries an authorization annotation. That is the test that would have caught
SEC-001 as the controllers were written.

## Suggested order of work

1. `./gradlew test` — expect to fix a role matcher or two.
2. Apply V22 and V23 to a copy of production data and confirm the backfills.
3. Set `SPRING_PROFILES_ACTIVE` and rotate `JWT_SECRET`.
4. Manually verify: kitchen display still receives live updates (SEC-003 touches that path), and a
   diner can still place an order and track it (SEC-004 re-keys that path).
5. Then SEC-007 and SEC-009.
