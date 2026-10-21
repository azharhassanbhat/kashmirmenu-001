# Security Audit Report — KashmirMenu

> **Remediation status (14 October 2026).** All twelve findings have since been addressed in code,
> except SEC-012 which is a deployment question. The findings below are kept as written at audit time
> — they are the record of what was wrong, not of what is live now. See `REMEDIATION.md` for what
> changed per finding, and note that none of the fixes has been compiled or executed in this
> environment: `./gradlew test` is required before relying on any of them.

**Date:** 14 October 2026
**Scope:** Spring Boot 3.5.4 backend, React 19 frontend, Flyway migrations, Docker/nginx configuration
**Method:** Static code review of the repository at `/home/claude/kashmirmenu`. No code was modified.

## Limitations — read this first

This audit is **static only**. The Spring layer was never compiled or run during this review: the
environment has no network access, so no Gradle/Maven dependencies could be resolved, no database was
available, and no request was ever sent to a running instance. Consequently:

- **No finding below was confirmed by execution.** Where a finding is marked *Confirmed*, that means
  the code path is unambiguous on inspection (an endpoint with no authorization annotation calling a
  service that never consults the authenticated user). It does **not** mean an exploit was run.
- **No dependency vulnerability scan was performed.** `./gradlew dependencyCheckAnalyze`, `npm audit`
  and OSV/NVD lookups all require network access. This report therefore makes **no claim** about CVEs
  in the declared dependencies. Treat "dependencies" as *unaudited*, not *clean*.
- Runtime-only properties were not observed: actual TLS termination, real environment variable values,
  reverse-proxy behaviour in front of nginx, and database user privileges.

**This application must not be considered production-secure on the strength of this review alone.**
Findings SEC-001 through SEC-005 should be verified with the integration tests in Phase 9 before and
after remediation.

---

## Phase 1: Architecture

**Backend.** Spring Boot 3.5.4 (`build.gradle:3`), Java 21, Spring Security with
`@EnableMethodSecurity` (`SecurityConfig.java:21`), Spring Data JPA/Hibernate with
`ddl-auto: validate`, Flyway migrations V1–V21, MySQL 8, STOMP-over-WebSocket for live updates.

**Frontend.** React 19 + TypeScript + Vite, served by nginx which reverse-proxies `/api` and `/ws`
to the backend (`frontend/nginx.conf:15-31`). Single-origin deployment.

**Authentication.** Stateless JWT (HS256), `SessionCreationPolicy.STATELESS`. Tokens are minted at
register/login (`AuthServiceImpl.java:76,103,134`) and validated per request by
`JwtAuthenticationFilter`. The token carries only `sub` (email), `iat` and `exp`
(`JwtService.java:30-37`); **roles are re-read from the database on every request** via
`UserDetailsService`, which is a genuinely good design — a role revocation takes effect immediately
and roles cannot be forged in the token.

**Roles.** `RoleName`: `ADMIN`, `RESTAURANT_OWNER`, `MANAGER`, `CHEF`, `WAITER`, `CASHIER`,
`CUSTOMER`. Roles are assigned server-side at registration (`AuthServiceImpl.java:45,56`) — a client
cannot choose its own role, so there is no mass-assignment privilege escalation at registration.

**Tenancy.** The application is multi-tenant: every `User` belongs to a `Restaurant`, and almost all
data is restaurant-scoped. Tenant isolation is implemented *per service*, by calling
`CurrentUserService.getCurrentRestaurantId()` — there is no global tenant filter, no Hibernate filter,
and no architectural enforcement. **This is the root cause of the most serious findings below:
isolation is opt-in, and several services simply never opted in.**

**Trust boundaries.**
1. Anonymous internet → `/api/public/**` (diner QR flow), `/api/v1/auth/**`, `/ws/**`.
2. Authenticated non-staff (`CUSTOMER`, self-registerable) → everything under `.anyRequest().authenticated()`.
3. Restaurant staff (`CHEF`, `WAITER`, `CASHIER`) → same as above.
4. Restaurant management (`MANAGER`, `RESTAURANT_OWNER`, `ADMIN`) → financial reporting.

Boundaries 2, 3 and 4 are **not meaningfully separated** for most endpoints. See SEC-001.

---

## Findings

### Finding SEC-001: Broken Function Level Authorization — admin APIs accept any authenticated user

**Severity:** Critical
**Confidence:** High
**Status:** Confirmed by code inspection (not executed)
**Affected Files:** `src/main/java/com/kashmirmenu/app/config/SecurityConfig.java:65`, and the
controllers listed below
**Affected APIs:** `/api/categories/**`, `/api/menu-items/**`, `/api/v1/tables/**`,
`/api/v1/inventory/**`, `/api/v1/dashboard/**`, `/api/v1/orders/**`, `/api/v1/cart/**`,
`/api/v1/notifications/**`, `/api/v1/restaurants/{id}/payment-settings/**`

**Description:**
The filter chain ends with `.anyRequest().authenticated()` — authentication is required, but no role
is. Authorization is then expected at the method level via `@PreAuthorize`. An inventory of every
controller shows that most do not carry one:

| Controller | Base path | `@PreAuthorize` present |
|---|---|---|
| `CategoryController` | `/api/categories` | **none** |
| `MenuItemController` | `/api/menu-items` | **none** |
| `RestaurantTableController` | `/api/v1/tables` | **none** |
| `InventoryController` | `/api/v1/inventory` | **none** |
| `DashboardController` | `/api/v1/dashboard` | **none** |
| `OrderController` | `/api/v1/orders` | **none** |
| `CartController` | `/api/v1/cart` | **none** |
| `NotificationController` | `/api/v1/notifications` | **none** |
| `RestaurantPaymentSettingController` | `/api/v1/restaurants/{id}/payment-settings` | **none** |
| `CouponController` | `/api/v1/coupons` | class-level (manager+) |
| `PaymentAnalyticsController` | `/api/v1/payment-analytics` | class-level (manager+) |
| `KitchenController` | `/api/v1/kitchen/orders` | class-level (kitchen roles) |
| `RestaurantController` | `/api/restaurants` | 8 method-level |

`/api/v1/auth/register` is public and self-service (`SecurityConfig.java:50`), creating a `CUSTOMER`
(`AuthServiceImpl.java:45`). So **anyone on the internet can obtain a valid token in one request** and
then reach every endpoint in the "none" rows above.

**Evidence:**
```java
// SecurityConfig.java:64-65
                        ).permitAll()
                        .anyRequest().authenticated())
```
```java
// InventoryController.java:18-27 — no @PreAuthorize anywhere in the class
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService inventoryService;

    @PostMapping
    public ApiResponse<InventoryResponse> createInventory(
            @Valid @RequestBody CreateInventoryRequest request) {
```

**Attack Scenario:**
1. `POST /api/v1/auth/register` with any email → receive a JWT. No approval, no email verification.
2. `GET /api/v1/dashboard/summary` → today's revenue and order counts.
3. `DELETE /api/menu-items/{id}` → destroy a restaurant's menu.

Note that for the *tenant-scoped* services (categories, menu items, tables, orders) a bare `CUSTOMER`
has no restaurant, so `getCurrentRestaurantId()` dereferences `null`
(`CurrentUserServiceImpl.java:41`) and the request fails with a 500 rather than returning data. That
accident provides partial protection against the anonymous-registration path, **but none at all
against the more likely attacker: a real staff account.** A `WAITER` or `CHEF` has a restaurant, so
for them every "none" row is fully functional — they can delete menu items, create tables, read the
revenue dashboard, and change payment settings. That is vertical privilege escalation within the
tenant, and it is the confirmed, non-speculative impact of this finding.

**Business Impact:** Any staff member with the lowest-privilege login can read the restaurant's
financial dashboard and destroy or alter menu, table and inventory data. Combined with SEC-002 the
blast radius crosses tenants.

**Recommended Fix:** Make authorization deny-by-default rather than opt-in. Two complementary steps:

1. Tighten the filter chain so unannotated endpoints are not reachable by `CUSTOMER`:
```java
// SecurityConfig.java, inside authorizeHttpRequests
    .requestMatchers("/api/v1/me").authenticated()
    .requestMatchers("/api/v1/kitchen/**").hasAnyRole("CHEF","WAITER","MANAGER","RESTAURANT_OWNER","ADMIN")
    .requestMatchers("/api/v1/payment-analytics/**","/api/v1/coupons/**")
        .hasAnyRole("MANAGER","RESTAURANT_OWNER","ADMIN")
    .requestMatchers(HttpMethod.GET, "/api/categories/**","/api/menu-items/**")
        .hasAnyRole("CHEF","WAITER","CASHIER","MANAGER","RESTAURANT_OWNER","ADMIN")
    .requestMatchers("/api/categories/**","/api/menu-items/**","/api/v1/tables/**",
                     "/api/v1/inventory/**","/api/v1/dashboard/**")
        .hasAnyRole("MANAGER","RESTAURANT_OWNER","ADMIN")
    .anyRequest().denyAll()
```
2. Add class-level `@PreAuthorize` to each controller in the "none" list, so the rule travels with the
   code and survives a filter-chain refactor.

`.anyRequest().denyAll()` is the important half: it converts "a forgotten annotation is public" into
"a forgotten annotation is a 403", which fails safe and shows up immediately in tests.

**Verification Steps:** Phase 9 test `customerCannotReachStaffEndpoints`, plus a parameterised test
asserting every controller class carries an authorization annotation.

**References:** OWASP API Security Top 10 API5:2023 (Broken Function Level Authorization);
OWASP Top 10 A01:2021; CWE-285; CWE-862.

---

### Finding SEC-002: Cross-tenant IDOR — payment settings, notifications and inventory

**Severity:** Critical
**Confidence:** High
**Status:** Confirmed by code inspection (not executed)
**Affected Files:**
- `src/main/java/com/kashmirmenu/app/service/impl/RestaurantPaymentSettingServiceImpl.java:29-85`
- `src/main/java/com/kashmirmenu/app/controller/NotificationController.java:22-41`
- `src/main/java/com/kashmirmenu/app/service/impl/InventoryServiceImpl.java:34-45,68-72`

**Affected APIs:** `GET|POST|PUT /api/v1/restaurants/{restaurantId}/payment-settings`,
`GET /api/v1/notifications?restaurantId=`, `GET|POST|PUT|DELETE /api/v1/inventory/**`

**Description:**
Three services take a restaurant identifier **from the request** and never check it against the
authenticated user. There is no ownership validation at all — not in the controller, not in the
service.

**Evidence:**
```java
// RestaurantPaymentSettingServiceImpl.java:53-57 — restaurantId comes from the URL path
    public RestaurantPaymentSettingResponse getPaymentSetting(
            Long restaurantId) {
        RestaurantPaymentSetting paymentSetting =
                paymentSettingRepository.findByRestaurantId(restaurantId)
```
The string `currentUserService` does not appear anywhere in that file.

```java
// NotificationController.java:21-25 — restaurantId is an attacker-controlled query parameter
    public ... getAllNotifications(
            @RequestParam Long restaurantId) {
        return ... notificationService.getAllNotifications(restaurantId));
```

```java
// InventoryServiceImpl.java:68-72 — no tenant predicate at all
    public List<InventoryResponse> getAllInventory() {
        return inventoryRepository.findAll()
                .stream()
```
`InventoryRepository` *does* expose `findByRestaurant(...)` (line 17), so the scoped query exists and
is simply not used. `createInventory` (line 34) takes `request.getRestaurantId()` straight from the
body.

**Attack Scenario:**
A restaurant owner on the platform registers legitimately, notes their own restaurant id *n* from
`/api/v1/me`, then iterates ids:
1. `GET /api/v1/inventory` → **every restaurant's** stock levels in one call, no iteration needed.
2. `GET /api/v1/notifications?restaurantId=<competitor>` → live order notifications containing order
   numbers and payment amounts (`NotificationMessage` carries `title`, `message`, `orderId`).
3. `PUT /api/v1/restaurants/<competitor>/payment-settings` with `{"cashEnabled":false,
   "upiEnabled":false,"cardEnabled":false,"walletEnabled":false}` → the competitor's diners can no
   longer select any payment method. `PublicOrderingService` derives the diner's payment options from
   exactly this record, so the sabotage is immediately visible to their customers.

Restaurant ids are small sequential integers, so enumeration is trivial.

**Business Impact:** Confidentiality breach across tenants (stock, order flow, revenue signals) and a
one-request denial of service against a competitor's checkout. On a multi-restaurant SaaS this is the
most commercially damaging finding in the report.

**Recommended Fix:** Stop accepting a tenant identifier from the client. Derive it from the principal.

```java
// RestaurantPaymentSettingServiceImpl — add the dependency, then gate every method
private final CurrentUserService currentUserService;

private void assertOwnRestaurant(Long restaurantId) {
    Restaurant current = currentUserService.getCurrentRestaurant();
    if (current == null || !current.getId().equals(restaurantId)) {
        // Same answer as "no such restaurant", so ids cannot be probed.
        throw new ResourceNotFoundException("Restaurant not found.");
    }
}
```
```java
// InventoryServiceImpl.getAllInventory
    return inventoryRepository
            .findByRestaurant(currentUserService.getCurrentRestaurant())
            .stream().map(inventoryMapper::toResponse).toList();
```
```java
// NotificationController — drop the parameter entirely
    public ... getAllNotifications() {
        return ... notificationService.getAllNotifications(
                currentUserService.getCurrentRestaurantId()));
```
For `getInventoryById`/`updateInventory`/`deleteInventory`, load the row then compare
`inventory.getRestaurant().getId()` with the caller's and throw the same not-found on mismatch.

Longer term, the per-service approach is what failed here. Consider a Hibernate `@Filter` enabled once
per request, or a `TenantAwareRepository` base class, so isolation is structural rather than a thing
each author must remember.

**Verification Steps:** Phase 9 test `crossTenantAccessIsRefused`.

**References:** OWASP API1:2023 (BOLA); OWASP A01:2021; CWE-639; CWE-566.

---

### Finding SEC-003: Unauthenticated WebSocket subscription leaks live order data across tenants

**Severity:** High
**Confidence:** Medium-High
**Status:** Needs runtime verification
**Affected Files:** `src/main/java/com/kashmirmenu/app/config/SecurityConfig.java:56`,
`src/main/java/com/kashmirmenu/app/config/WebSocketConfig.java:20-30`,
`src/main/java/com/kashmirmenu/app/dto/websocket/NotificationPublisher.java:15-18`

**Affected API:** STOMP `SUBSCRIBE /topic/restaurants/{restaurantId}` over `/ws`

**Description:**
`/ws/**` is `permitAll` and `JwtAuthenticationFilter` explicitly skips it
(`JwtAuthenticationFilter.java:37`). The broker is a `SimpleBroker` on `/topic` with **no
`configureClientInboundChannel` interceptor and no `AuthorizationManager` for message destinations**.
Nothing ties a subscription to a restaurant. Any client that can open the socket may subscribe to any
restaurant's topic by id.

**Evidence:**
```java
// WebSocketConfig.java:18-23 — no inbound channel authorization
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
```
```java
// NotificationPublisher.java:15-18 — destination is just the restaurant id
        messagingTemplate.convertAndSend(
                "/topic/restaurants/" + message.getRestaurantId(),
                message
        );
```

**Attack Scenario:** An attacker connects with `wscat`/a STOMP client directly to `/ws` and subscribes
to `/topic/restaurants/1..N`, receiving every order and payment notification for every restaurant in
real time, without ever authenticating.

`setAllowedOrigins(...)` (`WebSocketConfig.java:29`) restricts *browsers* via the Origin header, but
a non-browser client sets any Origin it likes, so it is not an access control. This is why the
finding is High rather than Critical-confirmed: the origin check plus SockJS handshake specifics mean
the exact exploitability should be verified at runtime before the severity is finalised.

**Business Impact:** Continuous, passive exfiltration of order volume and payment events for all
tenants — commercially sensitive and hard to detect.

**Recommended Fix:** Authenticate the STOMP CONNECT and authorize SUBSCRIBE by tenant.

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor == null) return message;

            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                String bearer = accessor.getFirstNativeHeader("Authorization");
                // Validate with JwtService + UserDetailsService, then:
                accessor.setUser(authenticatedPrincipal);
            }
            if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                String destination = accessor.getDestination();
                // Reject unless destination ends with the principal's own restaurant id.
            }
            return message;
        }
    });
}
```
The frontend already holds a token; `useRestaurantTopic` would pass it as a CONNECT header.

Also remove `System.out.println("Message Sent")` (`NotificationPublisher.java:20`) — unstructured
stdout logging that bypasses the configured logger and request-id MDC.

**Verification Steps:** Attempt an unauthenticated subscribe to another restaurant's topic from a
non-browser STOMP client and assert the frame is rejected.

**References:** OWASP A01:2021; CWE-306; Spring Security WebSocket authorization reference.

---

### Finding SEC-004: Sequential order numbers make the public tracking endpoint enumerable

**Severity:** Medium
**Confidence:** High
**Status:** Confirmed by code inspection (not executed)
**Affected Files:** `src/main/java/com/kashmirmenu/app/service/impl/OrderServiceImpl.java` (method
`nextOrderNumber`), `src/main/java/com/kashmirmenu/app/controller/PublicController.java:109`
**Affected API:** `GET /api/public/orders/{orderNumber}`

**Description:**
Order numbers were recently changed from `ORD-20261013-290F2080CCD3` (date + 12 random hex characters)
to a per-restaurant sequence: `ORD-1`, `ORD-2`, `ORD-3`. The public tracking endpoint takes an order
number as its only credential and is unauthenticated by design, so the order number functions as a
bearer token for that order's data. Making it sequential makes it guessable.

This weakness was **introduced deliberately** in response to a usability requirement (diners could not
read the long number back to staff). The usability goal is legitimate; the security consequence was
not accounted for.

**Evidence:**
```java
// OrderServiceImpl.nextOrderNumber
        long number = counter.getNextNumber();
        counter.setNextNumber(number + 1);
        orderCounterRepository.save(counter);

        return "ORD-" + number;
```
```java
// PublicController.java:109 — no authentication, no table binding
    @GetMapping("/orders/{orderNumber}")
```
`OrderTrackingResponse` exposes `tableName`, line items, `subtotal`, `discount`, `couponCode`,
`total`, `amountPaid`, `balanceDue` and `paymentStatus`.

**Attack Scenario:** An attacker walks `GET /api/public/orders/ORD-1` … `ORD-500` and harvests the
order history of a restaurant: what every table ate, what it cost, and whether it has been paid. The
`/api/public/**` rate limiter does **not** apply, because it only buckets non-GET methods
(`AuthRateLimitFilter.java:15-19` of the excerpt: `&& !"GET".equals(request.getMethod())`), so the
enumeration is unthrottled.

A second-order risk: the same endpoint is the one a diner uses to attach a payment
(`POST /api/public/orders/{orderNumber}/payments`), so a guessed order number also allows an attacker
to mark someone else's order as "diner chose cash", creating a pending payment on it.

**Business Impact:** Disclosure of per-table order and payment history for all diners; nuisance
interference with other tables' checkout.

**Recommended Fix:** Decouple the *display* number from the *lookup* key. Keep `ORD-14` as the human
label, and give each order an unguessable public token used for tracking URLs.

```java
// Order entity
@Column(name = "public_token", nullable = false, unique = true, length = 32)
private String publicToken;   // e.g. Base64Url of 16 random bytes from SecureRandom
```
Route tracking by the token (`/api/public/orders/by-token/{token}`) and keep `ORD-14` for staff and
for the diner to read aloud. The QR-flow frontend already stores what it needs in
`lib/orderHistory.ts`, so it can store the token instead of the number.

If that is too large a change for now, the minimum mitigations are: (a) extend the rate limiter to
GET requests on `/api/public/orders/**`, and (b) require the table's `qrIdentifier` as a second factor
on the tracking call, binding the lookup to physical presence at the table.

**Verification Steps:** Assert that fetching a tracking record for an order belonging to another
table/restaurant without its token returns 404, and that repeated GETs are throttled.

**References:** OWASP API1:2023; CWE-330 (Use of Insufficiently Random Values); CWE-200.

---

### Finding SEC-005: Insecure default JWT secret reachable through profile misconfiguration

**Severity:** High
**Confidence:** High
**Status:** Confirmed (configuration), conditional on deployment
**Affected Files:** `src/main/resources/application.yaml:6`,
`src/main/resources/application-dev.yml:36`
**Affected API:** All authenticated endpoints

**Description:**
The active profile defaults to `dev` when `SPRING_PROFILES_ACTIVE` is unset
(`application.yaml:6`), and the `dev` profile supplies a **hardcoded fallback JWT signing secret**
(`application-dev.yml:36`). A deployment that forgets the environment variable therefore starts
successfully using a secret that is public in the repository.

**Evidence:**
```yaml
# application.yaml:5-6
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```
```yaml
# application-dev.yml:33-37  (value redacted)
jwt:
  secret: ${JWT_SECRET:dev-only-insecure-...-REDACTED}
```
The `prod` profile does this correctly — `secret: ${JWT_SECRET}` with no default
(`application-prod.yml:41`), so it fails fast. `JwtProperties.validate()` enforces ≥32 bytes but
cannot tell a known-public string from a secret one.

**Attack Scenario:** The app is deployed with `docker compose up` but without
`SPRING_PROFILES_ACTIVE=prod`. It boots on the `dev` profile. An attacker who has read the public
repository signs a token `{"sub":"owner@victim-restaurant.com","exp":<future>}` with the published
secret. Because `JwtAuthenticationFilter` resolves roles from the database for whatever subject the
token names (`JwtAuthenticationFilter.java:61-70`), the forged token yields that owner's full
privileges. This is complete authentication bypass.

The `dev` profile additionally enables `show-sql: true` and
`org.springframework.security: DEBUG` (`application-dev.yml:14,30`), leaking SQL and security
internals into logs in the same misconfiguration.

**Business Impact:** Full account takeover of every account on the platform.

**Recommended Fix:** Remove the ability to fail open.
1. Default the profile to `prod`, so a missing variable yields a hard failure rather than a weak one:
   `active: ${SPRING_PROFILES_ACTIVE:prod}`.
2. Delete the literal fallback from `application-dev.yml` (`secret: ${JWT_SECRET}`) and document
   generating one locally: `openssl rand -base64 48`.
3. Optionally, reject known placeholder values in `JwtProperties.validate()`:
```java
if (secret.startsWith("dev-only-")) {
    throw new IllegalStateException("Refusing to start with the development placeholder jwt.secret.");
}
```
4. Rotate `JWT_SECRET` in any environment where the default may ever have been active. Rotation
   invalidates all existing tokens, which is the desired effect.

**Verification Steps:** Start the app with no `JWT_SECRET` and assert it fails to start.

**References:** OWASP A05:2021 (Security Misconfiguration); A02:2021; CWE-798; CWE-1188.

---

### Finding SEC-006: No token revocation — logout is client-side only

**Severity:** Medium
**Confidence:** High
**Status:** Confirmed by code inspection
**Affected Files:** `src/main/java/com/kashmirmenu/app/controller/AuthController.java` (no logout
endpoint), `src/main/java/com/kashmirmenu/app/security/jwt/JwtService.java:71-79`,
`src/main/resources/application-prod.yml:42`
**Affected API:** All authenticated endpoints

**Description:**
There is no logout endpoint, no refresh-token mechanism and no denylist. `AuthController` exposes
only `register`, `register-owner` and `login`. The frontend "Sign out" clears `localStorage`
(`frontend/src/api/client.ts:25-26`), which removes the client's copy but leaves the token valid
server-side. `isTokenValid` checks only subject match and expiry.

Default access-token lifetime is **86,400,000 ms = 24 hours** (`application-prod.yml:42`).

**Attack Scenario:** A token captured from a shared kitchen tablet, a browser left signed in, or a
logged URL remains usable for up to 24 hours after the user "signs out". Disabling a user account also
has no effect until expiry, unless `UserDetailsService` checks an enabled flag — which should be
verified.

**Business Impact:** Stolen or shared credentials cannot be revoked; incident response has no
"sign out everywhere" capability.

**Recommended Fix:** Shorten access tokens to 15 minutes and add refresh tokens that are stored
server-side and revocable; or, as a smaller step, add a `tokens_valid_from` timestamp per user, set it
to now on logout/password change, and reject tokens whose `iat` precedes it:
```java
// in isTokenValid
Date issuedAt = extractClaim(token, Claims::getIssuedAt);
return username.equals(userDetails.getUsername())
        && !isTokenExpired(token)
        && !issuedAt.before(user.getTokensValidFrom());
```
This needs one column and one comparison and gives real revocation without session state.

**Verification Steps:** Call logout, then replay the old token and assert 401.

**References:** OWASP A07:2021; CWE-613; OWASP ASVS V3.3.

---

### Finding SEC-007: Login throttling is per-IP only; no account lockout

**Severity:** Medium
**Confidence:** High
**Status:** Confirmed by code inspection
**Affected Files:** `src/main/java/com/kashmirmenu/app/security/filter/AuthRateLimitFilter.java`,
`src/main/resources/application.yaml:42-43`

**Description:**
`/api/v1/auth/**` is rate-limited to 20 requests per 60 seconds per client IP, in an in-memory
`ConcurrentHashMap`. There is no per-account failure counter and no lockout.

The implementation is thoughtful in two respects worth crediting: `X-Forwarded-For` is only trusted
when `TRUST_FORWARDED_HEADER` is explicitly enabled, and nginx overwrites rather than appends that
header (`frontend/nginx.conf:19`) — so the common spoofing bypass is closed. The map is also capped
to avoid unbounded growth.

**Attack Scenario:** 20 attempts/minute/IP is 28,800/day from a single address and effectively
unlimited from a botnet, since the counter is keyed only by IP. Credential stuffing against a known
owner email is not meaningfully impeded. The counter is also per-instance, so horizontal scaling
multiplies the allowance.

**Business Impact:** Account takeover via password guessing, especially given no MFA exists.

**Recommended Fix:** Add per-account failure tracking (counter + lock-until on the user row), with
exponential backoff, in addition to the IP limit. Move the limiter to a shared store (Redis) or an
edge/WAF limiter when running more than one instance. Lower `auth-limit` to ~10/minute.

**Verification Steps:** Assert the 11th failed login for one account is refused even from a fresh IP.

**References:** OWASP A07:2021; CWE-307; OWASP ASVS V2.2.

---

### Finding SEC-008: Personal data written to application logs

**Severity:** Low
**Confidence:** High
**Status:** Confirmed by code inspection
**Affected Files:** `src/main/java/com/kashmirmenu/app/service/impl/AuthServiceImpl.java:51,91,116,128,137`

**Description:** Email addresses are logged at INFO on registration and on every login, including a
failed lookup logged at ERROR.

**Evidence:**
```java
// AuthServiceImpl.java:116
        log.info("Login request received for email: {}", request.getEmail());
```

**Business Impact:** Log aggregation becomes a personal-data store, expanding GDPR/DPDP scope and
turning log read access into a user-enumeration oracle. No credentials are logged — passwords never
appear — so impact is limited to identifiers.

**Recommended Fix:** Log the user id after resolution, or a hash/partial mask of the email. Drop the
ERROR-level log for a non-existent user at line 128; it is attacker-controlled and provides no
operational value.

**References:** OWASP A09:2021; CWE-532.

---

### Finding SEC-009: Dependency vulnerability status unverified

**Severity:** Informational (unknown risk)
**Confidence:** N/A
**Status:** Could not be verified
**Affected Files:** `build.gradle`, `frontend/package.json`, lockfiles

**Description:**
No dependency audit was possible — the environment has no network access, so neither
`./gradlew dependencyCheckAnalyze` nor `npm audit` nor an OSV/NVD query could run. **This report makes
no claim that these dependencies are free of known vulnerabilities**, and no CVE is asserted here.

Declared versions observed, for whoever runs the scan: Spring Boot 3.5.4, jjwt 0.12.7, MapStruct
1.6.3, springdoc-openapi 2.8.9, ZXing 3.5.3, Flyway (BOM-managed); React 19.3.0, react-router-dom
7.18.4, @tanstack/react-query 5.104.2, Vite 7.3.7, sockjs-client 1.6.1.

One structural note that does not require a scanner: `sockjs-client` is a long-standing transitive
XSS-surface concern in some configurations and is only needed for browsers without native WebSocket
support. Modern targets do not need the SockJS fallback; dropping `.withSockJS()` and the client
dependency would remove that surface entirely.

**Recommended Fix:** Run in CI, failing the build on High/Critical:
```
./gradlew dependencyCheckAnalyze
cd frontend && npm audit --omit=dev
```
Add Dependabot/Renovate for both manifests.

**References:** OWASP A06:2021; CWE-1035.

---

### Finding SEC-010: Swagger UI and `/actuator/health/**` exposed pre-authentication

**Severity:** Low
**Confidence:** High
**Status:** Confirmed by code inspection
**Affected Files:** `src/main/java/com/kashmirmenu/app/config/SecurityConfig.java:52-59`,
`src/main/resources/application-prod.yml:51-56`, `src/main/resources/application.yaml:13-20`

**Description:** `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health/**`, `/actuator/info` and
`/websocket-test.html` are `permitAll`.

This is largely handled already and deserves credit: the `prod` profile disables springdoc entirely
(`application-prod.yml:52-56`), actuator exposure is limited to `health,info`, and health details are
`when-authorized`. The residual issues are that the permits remain in the filter chain for any
non-prod profile (compounding SEC-005), and `/websocket-test.html` is a debug page that should not
ship at all.

**Recommended Fix:** Delete `websocket-test.html` and its matcher from the production build. Keep only
`/actuator/health/liveness` and `/actuator/health/readiness` public — probes need those; the aggregate
`/actuator/health/**` is broader than necessary.

**References:** OWASP A05:2021; CWE-200.

---

### Finding SEC-011: JWT in `localStorage`

**Severity:** Low (in this threat model)
**Confidence:** High
**Status:** Confirmed by code inspection
**Affected Files:** `frontend/src/api/client.ts:17-26`

**Description:** The access token is held in `localStorage`, readable by any JavaScript on the origin.

Assessed in context rather than by reflex: a token in `localStorage` is only materially worse than an
`HttpOnly` cookie if an attacker can execute script on the origin. The relevant mitigations are
present — a strict CSP with `script-src 'self'` and no inline scripts
(`frontend/security-headers.conf:14`), React's default escaping, and no `dangerouslySetInnerHTML`
anywhere in the frontend (verified by grep). Conversely, the cookie alternative would introduce CSRF
exposure, and CSRF is currently disabled (`SecurityConfig.java:34`) — which is *correct* for a
pure Bearer-token API, but would become a vulnerability the moment credentials moved to cookies.

The honest summary: this is an accepted trade-off, not a defect, **provided** the CSP and the absence
of XSS sinks are maintained. It is listed so the trade-off is explicit and so any future change to
cookie auth is accompanied by re-enabling CSRF protection.

**Recommended Fix:** Keep as is; retain short token lifetimes (see SEC-006) to bound the damage from
any future XSS. If moving to `HttpOnly` cookies, re-enable CSRF with
`CookieCsrfTokenRepository.withHttpOnlyFalse()` in the same change.

**References:** OWASP A05:2021; CWE-922; OWASP ASVS V3.4.

---

### Finding SEC-012: nginx serves plain HTTP; HSTS asserted without TLS

**Severity:** Medium (deployment-dependent)
**Confidence:** Medium
**Status:** Needs runtime verification
**Affected Files:** `frontend/nginx.conf:2`, `src/main/java/com/kashmirmenu/app/config/SecurityConfig.java:45-47`

**Description:** The container listens on port 80 only, with no TLS configuration and no
HTTP→HTTPS redirect, while the backend emits `Strict-Transport-Security` with a one-year max-age and
`includeSubDomains`.

If TLS is terminated by an upstream load balancer this is fine and normal. If not, bearer tokens and
diner order data cross the network in clear text, and the HSTS header is being asserted for a site not
actually served over HTTPS. The repository does not reveal which is the case, hence Medium/Needs
verification rather than a confirmed finding.

**Recommended Fix:** Confirm TLS terminates upstream. If it does not, terminate at nginx and add a
301 redirect from port 80. Only emit HSTS on responses actually served over HTTPS.

**References:** OWASP A02:2021; CWE-319.

---

## Controls assessed as sound

Stated explicitly so these are not re-litigated, and because several represent good decisions rather
than mere absence of evidence:

- **No SQL injection surface found.** Every query reviewed uses JPQL with named parameters or
  Criteria API. `PaymentAnalyticsQueries` assembles JPQL from *fixed fragments* with all values bound
  as named parameters, and `PaymentSpecifications` escapes LIKE wildcards explicitly. Sorting is
  restricted to a whitelist map (`PaymentAnalyticsServiceImpl.SORT_FIELDS`) and rejects anything else
  rather than interpolating a column name.
- **Password storage.** Delegated to Spring Security's `PasswordEncoder`; no custom hashing.
- **Role forgery.** Not possible: roles are not carried in the JWT, and are re-read per request.
- **Mass assignment at registration.** Not possible; roles assigned server-side.
- **SSRF in the Google Places integration.** Not present: fixed `baseUrl`, the place id is bound as a
  URI path variable (so it is encoded, not concatenated), and the DTO constrains it with `@Pattern`.
  The API key is sent as a header and the failure log explicitly excludes it.
- **CORS.** Origins come from configuration with no default in prod, `allowCredentials(true)` is
  paired with an explicit origin list (never `*`, which Spring would reject anyway), and headers are
  enumerated rather than wildcarded.
- **Security headers / CSP.** Strict CSP with no `unsafe-inline`, `object-src 'none'`,
  `frame-ancestors 'self'`, plus `X-Content-Type-Options`, `Referrer-Policy` and frame denial. The
  config file comments correctly about nginx's `add_header` inheritance trap.
- **Error handling.** No stack traces or internal details returned; the catch-all returns a generic
  message and logs server-side.
- **Container hardening.** Non-root user, JRE-only runtime stage, no secrets baked into the image.
- **Financial integrity controls** (relevant to fraud, if not to classic AppSec): pessimistic row
  locks on order/payment/stock/counter rows, idempotency keys on payment collection, conditional
  `UPDATE` for coupon claims, and exact `BigDecimal` arithmetic throughout.

## Areas that could not be verified

- Dependency CVE status (no network).
- Whether `UserDetailsService` honours a disabled/locked account flag.
- Database user privileges (the app may be connecting as a high-privilege user).
- Whether TLS terminates upstream of nginx.
- Real environment variable values; whether `SPRING_PROFILES_ACTIVE=prod` is actually set.
- Runtime behaviour of the STOMP endpoint (SEC-003).
- Git history for previously committed secrets.

---

## Phase 8: Prioritized Remediation Plan

| ID | Vulnerability | Severity | File | Exploitability | Recommended Action |
|---|---|---|---|---|---|
| SEC-001 | Admin APIs accept any authenticated user | Critical | `SecurityConfig.java:65` + 9 controllers | High | Add role rules + `denyAll()` default |
| SEC-002 | Cross-tenant IDOR (payment settings, notifications, inventory) | Critical | `RestaurantPaymentSettingServiceImpl.java:53`, `NotificationController.java:22`, `InventoryServiceImpl.java:68` | High | Derive tenant from principal; never from request |
| SEC-003 | Unauthenticated WebSocket subscription | High | `WebSocketConfig.java:18` | Medium-High | Authenticate CONNECT, authorize SUBSCRIBE |
| SEC-005 | Insecure default JWT secret via profile default | High | `application.yaml:6`, `application-dev.yml:36` | High (if misdeployed) | Default profile to `prod`; remove literal fallback; rotate |
| SEC-004 | Enumerable public order tracking | Medium | `OrderServiceImpl.nextOrderNumber`, `PublicController.java:109` | High | Random public token for lookups; keep `ORD-n` for display |
| SEC-006 | No token revocation / logout | Medium | `AuthController.java`, `JwtService.java:71` | Medium | Short tokens + `tokens_valid_from` check |
| SEC-007 | Login throttling per-IP only | Medium | `AuthRateLimitFilter.java` | Medium | Per-account lockout + shared store |
| SEC-012 | Plain HTTP / HSTS without TLS | Medium | `nginx.conf:2` | Deployment-dependent | Confirm/terminate TLS; redirect 80→443 |
| SEC-010 | Swagger/actuator/debug page exposed pre-auth | Low | `SecurityConfig.java:52` | Low | Delete debug page; narrow health matchers |
| SEC-008 | PII in logs | Low | `AuthServiceImpl.java:116` | Low | Log ids, not emails |
| SEC-011 | JWT in `localStorage` | Low | `client.ts:17` | Low (CSP mitigates) | Accept; keep CSP and short tokens |
| SEC-009 | Dependency status unknown | Info | `build.gradle`, `package.json` | Unknown | Run scanners in CI |

**Immediate (before any further production exposure):** SEC-005, SEC-001, SEC-002. Together these are
the difference between "multi-tenant SaaS" and "shared database". SEC-005 is the single cheapest fix
with the largest consequence.

**Next (within the sprint):** SEC-003, SEC-004, SEC-006, SEC-007, SEC-012.

**Longer term:** Replace per-service tenant scoping with a structural mechanism (Hibernate filter or a
tenant-aware repository base) so SEC-002 cannot recur; add MFA for owner/manager accounts; add
dependency and SAST scanning to CI; consider an audit log for financial and configuration changes.

---

## Phase 9: Recommended Security Tests

Negative tests only, using the stack already present (JUnit 5, Spring Boot Test, MockMvc) — no new
dependencies. These mirror the style of the existing `PaymentAnalyticsIT` and `CouponFlowIT`.

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthorizationBoundaryIT {

    // SEC-001: a self-registered CUSTOMER must not reach staff or admin functionality.
    @Test
    void customerCannotReachStaffEndpoints() throws Exception {
        String customer = registerCustomer();
        for (String path : List.of(
                "/api/v1/dashboard/summary", "/api/v1/inventory", "/api/categories",
                "/api/menu-items", "/api/v1/tables", "/api/v1/orders")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + customer))
                    .andExpect(status().isForbidden());
        }
    }

    // SEC-001: a waiter may work the floor but must not administer the restaurant.
    @Test
    void floorStaffCannotAdministerTheRestaurant() throws Exception {
        String waiter = staffTokenWithRole(RoleName.WAITER);
        mockMvc.perform(delete("/api/menu-items/" + menuItemId)
                        .header("Authorization", "Bearer " + waiter))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + waiter))
                .andExpect(status().isForbidden());
    }

    // SEC-002: one restaurant must never see or change another's data.
    @Test
    void crossTenantAccessIsRefused() throws Exception {
        Shop a = setupShop();
        Shop b = setupShop();

        mockMvc.perform(get("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings")
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(put("/api/v1/restaurants/" + a.restaurantId() + "/payment-settings")
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cashEnabled\":false,\"upiEnabled\":false,"
                               + "\"cardEnabled\":false,\"walletEnabled\":false}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/v1/notifications?restaurantId=" + a.restaurantId())
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().is4xxClientError());

        // Inventory must be scoped, not global.
        String body = mockMvc.perform(get("/api/v1/inventory")
                        .header("Authorization", "Bearer " + b.token()))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(a.menuItemName()));
    }

    // SEC-005/SEC-006: malformed, foreign-signed and expired tokens are all rejected.
    @Test
    void onlyProperlySignedUnexpiredTokensAreAccepted() throws Exception {
        for (String bad : List.of("not-a-jwt", forgedWithOtherSecret(), expiredToken())) {
            mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized());
        }
    }

    // SEC-004: a tracking record must not be reachable by guessing a neighbouring number.
    @Test
    void anotherTablesOrderCannotBeRead() throws Exception {
        String victim = placeOrderAt(setupShop());          // e.g. ORD-1
        String guess = "ORD-" + (Integer.parseInt(victim.substring(4)) + 1);
        mockMvc.perform(get("/api/public/orders/" + guess))
                .andExpect(status().isNotFound());
    }
}
```

Also worth adding: a reflection-based test asserting every `@RestController` outside
`/api/public/**` and `/api/v1/auth/**` carries a class- or method-level authorization annotation.
That is the test that would have caught SEC-001 as the controllers were written, and it keeps the
guarantee as new controllers are added.

Frontend (Vitest, already present): assert that `logout()` clears the stored token and that an
unauthenticated 401 response redirects to sign-in rather than silently retrying.

---

## Phase 10: Final Assessment

**Findings by severity:** 2 Critical, 2 High, 4 Medium, 3 Low, 1 Informational (12 total).

**Overall posture.** The *infrastructure* security of this application is better than average for its
size: strict CSP, sound headers, non-root containers, parameterised queries throughout, no XSS sinks,
fail-fast secret validation in prod, thoughtful rate-limiter design, and genuinely careful handling of
money. The *authorization model*, by contrast, is the weak axis — and it is weak in a structural way
rather than through isolated oversights. Authentication is enforced globally; authorization is
enforced per-controller, by convention, and the convention was followed in four controllers out of
thirteen.

**Most dangerous confirmed attack path:**
`POST /api/v1/auth/register` (anyone) → valid JWT → `GET /api/v1/inventory` returns **every
restaurant's** stock → `PUT /api/v1/restaurants/{anyId}/payment-settings` disables a competitor's
payment methods. Two requests, no privilege required, cross-tenant read and write. This chains
SEC-001 and SEC-002 and needs no unusual preconditions.

**Most dangerous conditional path:** if `SPRING_PROFILES_ACTIVE` is unset in production (SEC-005), any
reader of the repository can forge a token for any account and obtain owner privileges — total
compromise, with the repository itself as the only prerequisite.

**Missing security controls:** deny-by-default authorization; structural tenant isolation; token
revocation; per-account brute-force protection; MFA; audit logging of financial and configuration
changes; dependency and SAST scanning in CI; WebSocket authorization.

**No security score is given.** Any single number here would be invented rather than measured: the
audit is static, no dependency data was available, and several findings are deployment-conditional.
A score would convey a precision this review does not have.

**Immediate actions, in order:**
1. Set `SPRING_PROFILES_ACTIVE=prod` everywhere and rotate `JWT_SECRET` (SEC-005) — minutes of work,
   removes an authentication-bypass path.
2. Add `.denyAll()` as the fallback rule plus explicit role matchers (SEC-001).
3. Derive the restaurant from the principal in the three services that take it from the request
   (SEC-002).
4. Add the `AuthorizationBoundaryIT` tests above and run them in CI, so these cannot regress.
