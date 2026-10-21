# Platform Owner Dashboard — Findings and Implementation Plan

**Status: proposal. No application code has been changed.** This document is Steps 1–5 of your
process; Step 7 waits on your approval.

---

## Step 1: What the application actually is today

**Stack.** Spring Boot 3.5.4 / Java 21 / MySQL with Flyway (V1–V23), stateless HS256 JWT, Spring
Security with `@EnableMethodSecurity`, React 19 + TypeScript + Vite behind nginx.

**Roles** (`enums/RoleName`): `ADMIN`, `RESTAURANT_OWNER`, `MANAGER`, `CHEF`, `WAITER`, `CASHIER`,
`CUSTOMER`. Roles are assigned server-side at registration — a client cannot choose its own — and are
re-read from the database on every request rather than carried in the JWT. That last point matters a
great deal here: **role claims cannot be forged in a token**, so requirement 7 of your section 6 is
already structurally satisfied rather than needing new defences.

**Tenancy.** Every `User` belongs to a `Restaurant`; data is scoped per service by calling
`CurrentUserService.getCurrentRestaurantId()`. There is no global tenant filter. The recent security
audit found three services that had simply never opted in, which is the context for the warnings
below.

**Authorization.** The filter chain now ends in `.anyRequest().denyAll()` with explicit role matchers,
and each controller carries a class-level `@PreAuthorize`. Deny-by-default already holds.

### Five findings that change the design

**1. `ADMIN` is not a platform role — it is a restaurant-scoped role, and it is everywhere.**
`ADMIN` appears in 29 authorization expressions across 15 controllers, always grouped with
`RESTAURANT_OWNER`/`MANAGER`, and every one of those handlers resolves data through
`getCurrentRestaurantId()` — which dereferences `user.getRestaurant()`. An `ADMIN` with no restaurant
gets a `NullPointerException`, not platform-wide data.

So `ADMIN` today means "a highly privileged member of one restaurant". **It must not be reused as the
platform owner**, and `ROLE_PLATFORM_OWNER` must *not* be added to the existing `MANAGEMENT`/`STAFF`
groups — doing so would hand the platform owner restaurant-scoped endpoints that would fail or, worse,
silently operate on whichever restaurant happens to be attached to the account.

**2. `ADMIN` is seeded but unreachable.** `V3` inserts the `ADMIN` and `CUSTOMER` rows; `V16` adds the
rest. But no code path assigns `ADMIN` — `register` always issues `CUSTOMER`, `register-owner` always
issues `RESTAURANT_OWNER`. There is currently **no way to create an `ADMIN` user through the API at
all**. The role is vestigial. That is convenient: nothing existing depends on it behaving like a
platform role.

**3. There is no approval workflow, and no commission model.** `Restaurant` has a single
`Boolean active` flag plus activate/deactivate endpoints. There is no `PENDING` state, no rejection
reason, no suspension reason, and no commission or settlement concept anywhere in the codebase
(`grep` for `commission`, `audit`, `mfa`, `totp` returns nothing).

Your section 3A asks for "pending restaurant approvals" and "platform revenue and commission", and
section 7 says not to fabricate statistics. **Those two cannot both be satisfied without new domain
concepts.** See the decisions you need to make, below.

**4. There is no audit log.** Nothing records logins, failed authentications, approvals, suspensions
or role changes. Section 3F needs a new table and a write path; it cannot be assembled from what
exists.

**5. No cross-restaurant queries exist.** `OrderRepository` has only `countByRestaurantId…` variants;
`UserRepository` has `findByRestaurantIdOrderByNameAsc`. Every aggregate the platform overview needs
is a new query. This is good news for isolation — there is no existing platform-wide query that could
leak — but it means the overview is genuinely new work, not a re-skin of the restaurant dashboard.

**Existing dashboards to reuse rather than duplicate.** `/admin` (AdminLayout + sidebar, `RequireRole`
guard, `MANAGER_ROLES`), the `components/ui.tsx` primitives (`QueryState`, `EmptyState`, `StatusBadge`,
`Field`, `Modal`, `table-wrap` responsive tables), the payments design system in `styles.css`
(`.pay-*`), and the hand-rolled SVG charts in `components/payments/charts.tsx`. The platform area
should import all of these.

---

## Step 2: Proposed security design

### The role

A new `RoleName.PLATFORM_OWNER` (authority `ROLE_PLATFORM_OWNER`), seeded by migration, and
**deliberately excluded from every existing role group**. It appears only in
`/api/platform-admin/**` matchers.

This gives a clean property worth stating explicitly: *the platform owner has no access to
restaurant-scoped APIs at all.* They cannot take a payment, edit a menu, or read `/api/v1/dashboard`.
Everything they can see arrives through read-mostly platform endpoints that never consult
`getCurrentRestaurantId()`. That is stronger than "platform owner can do everything", easier to
reason about, and it keeps your requirement that restaurant owners stay confined to their own data
symmetrical.

A platform owner account has `restaurant_id = NULL`. A guard is needed: `CurrentUserServiceImpl`
currently NPEs on a null restaurant, so it should throw a clear `AccessDeniedException` instead —
which also hardens the existing three-service tenancy gap.

### Provisioning the first owner

Four options, with the trade-off stated plainly:

| Option | Secrets at rest | Auditable | Works in containers |
|---|---|---|---|
| Hardcoded credentials | ✗ forbidden by your rules | ✗ | — |
| Env var email+password, auto-created at boot | password in env | partial | ✓ |
| **Env var email only, one-time claim token logged at boot** | none persisted | ✓ | ✓ |
| Manual SQL insert + documented bcrypt step | none | ✗ | awkward |

**Recommendation: the third.** On startup, if `PLATFORM_OWNER_EMAIL` is set and no platform owner
exists, create the account **disabled** with no usable password and print a single-use claim token to
the application log (never to an API response). The operator reads the token from the log, calls
`POST /api/platform-admin/claim` once with it plus a chosen password, and the token is consumed. No
credential is ever stored in configuration or the image, the bootstrap is recorded in the audit log,
and it fails closed if the variable is unset.

### Preventing escalation

- `PLATFORM_OWNER` is never assignable through `register`, `register-owner`, or any profile update —
  the registration methods hardcode their role and take no role parameter, which already holds.
- A platform owner cannot grant `PLATFORM_OWNER` to anyone in phase 1. Delegation is deferred (see
  "Explicitly out of scope").
- Role changes in general get no API. Today none exists; none is added.
- JWT carries no roles, so claim manipulation is not a vector.
- `.anyRequest().denyAll()` already covers any endpoint added without a matcher.

### MFA

Your section 2 says "protect platform-owner accounts with MFA where supported". **MFA is not
currently supported anywhere in this application**, and TOTP would mean a new dependency, a secret
store, enrolment UI and recovery codes. I would not bolt that onto phase 1. Phase 1 instead: a
distinct longer-lived audit trail, a shorter token lifetime for platform sessions, and the claim-token
bootstrap. If you want real TOTP, it is a phase of its own — say so and I will scope it.

---

## Step 3: Database and API changes

### Migrations

`V24__platform_owner_role.sql`
- `INSERT IGNORE INTO roles (name) VALUES ('PLATFORM_OWNER')`
- `users.platform_claim_token VARCHAR(64) NULL`, `users.platform_claim_expires_at TIMESTAMP NULL`,
  both unique/indexed, used once and nulled.

`V25__audit_log.sql`
```sql
CREATE TABLE audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at     TIMESTAMP    NOT NULL,
    actor_user_id   BIGINT       NULL,      -- NULL for failed logins with no known account
    actor_email     VARCHAR(255) NULL,      -- captured at the time; survives account deletion
    actor_role      VARCHAR(40)  NULL,
    action          VARCHAR(60)  NOT NULL,  -- LOGIN_SUCCEEDED, RESTAURANT_SUSPENDED, ...
    resource_type   VARCHAR(40)  NULL,
    resource_id     VARCHAR(64)  NULL,
    detail          VARCHAR(500) NULL,      -- never credentials or card data
    ip_address      VARCHAR(45)  NULL,
    INDEX idx_audit_time (occurred_at),
    INDEX idx_audit_actor (actor_user_id, occurred_at),
    INDEX idx_audit_action (action, occurred_at)
);
```
Append-only by convention and by API: no update or delete endpoint is written, and the platform audit
endpoint is read-only. True immutability needs a DB-level grant (revoke UPDATE/DELETE from the app's
MySQL user on this table) — worth doing, and a deployment step rather than code.

`V26__restaurant_lifecycle.sql` — **only if you choose the approval workflow** (see decisions):
`restaurants.status ENUM('PENDING','ACTIVE','SUSPENDED','REJECTED')` backfilled from `active`,
plus `status_reason VARCHAR(255)` and `status_changed_at`. The existing `active` flag stays in place
and stays authoritative for the diner-facing path, so nothing breaks; `status` is derived alongside it.

### API surface (`/api/platform-admin/**`, all `hasRole('PLATFORM_OWNER')`)

| Method | Path | Notes |
|---|---|---|
| POST | `/claim` | the only unauthenticated one; single-use token |
| GET | `/overview` | aggregate counts and totals |
| GET | `/restaurants` | paged, `q`, `status`, sort |
| GET | `/restaurants/{id}` | detail incl. owner, counts; **menu/orders summaries, not full graphs** |
| POST | `/restaurants/{id}/suspend` · `/reactivate` (· `/approve` · `/reject`) | reason required; audited |
| GET | `/users` | paged, `q`, `role`, `status`; never password hash or token |
| POST | `/users/{id}/suspend` · `/reactivate` | audited |
| GET | `/orders` | paged; filter by restaurant, status, date, order number |
| GET | `/orders/{id}` | timeline incl. payment components |
| GET | `/analytics` | revenue by restaurant, trends, payment-method split |
| GET | `/audit-logs` | paged; filter by actor, action, date |
| GET | `/health` | curated snapshot assembled server-side |

All responses are purpose-built DTOs (records), all list endpoints paged server-side with a whitelist
of sortable columns — the pattern already used in `PaymentAnalyticsServiceImpl.SORT_FIELDS`.

Monetary aggregation reuses the existing exact-decimal path: `PaymentAnalyticsQueries` already
aggregates database-side by method/provider/hour with `BigDecimal`. The platform queries will be the
same shape with the restaurant predicate lifted and a `GROUP BY restaurant_id` added. **No float
arithmetic, and the existing definitions of gross / collected / net / refunds carry over unchanged**,
so platform figures will reconcile with each restaurant's own dashboard.

### Monitoring without exposing infrastructure

`GET /api/platform-admin/health` returns a curated DTO the server assembles from Actuator's
`HealthEndpoint` and Micrometer's `MeterRegistry` **in-process** — not by proxying. Actuator stays
restricted to `/actuator/health/{liveness,readiness}`, which is where the recent audit left it. Request
volume, error rate and latency come from the existing `http.server.requests` timer, which Spring Boot
already records; JVM memory and CPU from the standard JVM meters; DB pool health from HikariCP's
metrics. Prometheus/Grafana/k6 are left as a documented integration point, not wired in — adding a
Prometheus scrape endpoint is a new exposed surface and should be a deliberate, separate decision.

---

## Step 4: Frontend structure

```
/platform-admin                     PlatformLayout (own sidebar, own accent, own shell)
  /                                 Overview
  /restaurants  /restaurants/:id    list + detail with lifecycle actions
  /users                            paged user administration
  /orders       /orders/:id         cross-restaurant order investigation
  /analytics                        revenue and payment analytics
  /audit                            audit log viewer
  /system                           health and metrics
```

- Guarded by the existing `RequireRole` with a new `PLATFORM_ROLES = ['PLATFORM_OWNER']`, and
  `homeFor()` extended so a platform owner lands on `/platform-admin` rather than `/admin`.
- Reuses `components/ui.tsx`, the `.pay-*` card/table/KPI styles, and the SVG chart components. New
  CSS is limited to a distinct accent and sidebar treatment — the visual cue that you are operating
  across all tenants rather than inside one. No component is duplicated.
- Route protection is **cosmetic only**; every figure comes from an endpoint that enforces the role
  server-side. The tests in Step 5 assert the two layers agree.

---

## Step 5: Security risks and required tests

### Risks this introduces

| Risk | Mitigation |
|---|---|
| The bootstrap becomes a backdoor | Single-use token, disabled account until claimed, token only in logs, audited, fails closed without the env var |
| `PLATFORM_OWNER` creeps into restaurant role groups | Excluded by construction; the authorization-matrix verifier (`docs/authz_check.py`) is extended to assert it never appears outside `/api/platform-admin/**` |
| Platform endpoints leak PII | DTOs only; user listing excludes password hash and tokens; audit `detail` is documented as never holding credentials |
| A platform query is accidentally reachable by a restaurant role | Separate controller, separate matcher, deny-by-default fallback, plus the tests below |
| Audit log tampering | No write API; revoke UPDATE/DELETE at the database grant level |
| New cross-tenant aggregates become slow | Database-side aggregation with indexes mirroring the existing payment indexes |

### Tests (`PlatformAdminAuthorizationIT`, extending `AuthorizationBoundaryIT`'s style)

Mapping directly to your section 6:

1–4. Customer, restaurant owner, manager and anonymous callers → every `/api/platform-admin/**`
endpoint returns 403 (401 anonymous). Driven by a list of paths so a new endpoint added without a
test fails the suite.
5. A platform owner reads overview, restaurants, users, orders, analytics, audit logs → 200.
6. A restaurant owner still sees only their own restaurant; a second shop's data stays invisible
(already covered by `AuthorizationBoundaryIT`, re-asserted here).
7. A token minted for a restaurant owner cannot reach platform endpoints even with a forged body or
query parameter; roles come from the database, so this asserts the property rather than patching it.
8. A reflection test asserting every `@RestController` under `/api/platform-admin` carries
`hasRole('PLATFORM_OWNER')`, and that `PLATFORM_OWNER` appears in no other matcher.
9. Platform owner calls a restaurant-scoped endpoint (`/api/v1/dashboard/summary`) → 403, not a 500
and not another tenant's data.
10. Suspend a restaurant, then assert an `audit_log` row exists with the actor, action and resource —
and that a failed login writes one too.

---

## Decisions I need from you

**1. Approval workflow — build it, or omit those tiles?**
There is no `PENDING` state today, and `register-owner` creates a live restaurant immediately. Your
3B asks for approve/reject "if approval workflows exist" (they do not), while 3A lists "pending
restaurant approvals" as a statistic. I can either (a) add the `status` lifecycle in `V26` and make new
registrations land as `PENDING` — **note this changes signup behaviour: a new restaurant would not be
able to trade until you approve it** — or (b) ship suspend/reactivate only and omit the pending tile
rather than show a hardcoded zero. **My recommendation: (b) for phase 1**, then (a) deliberately once
you have decided whether you want to gate signups.

**2. Commission — what is the actual business rule?**
There is no commission model, and I will not invent a percentage. Phase 1 can honestly show gross
collected, net of refunds, and per-restaurant revenue — all real. "Platform revenue" needs you to
state the rule (flat percentage? per-order fee? per-restaurant plan?). Until then that tile is
labelled as not configured rather than filled with a plausible number.

**3. MFA now or later?** Not supported anywhere today; a real TOTP implementation is its own phase.

**4. What should happen to the vestigial `ADMIN` role?** It is seeded, unreachable, and currently
means "restaurant super-user". Options: leave it exactly as is (safest, recommended), or rename it to
`RESTAURANT_ADMIN` for clarity (touches 29 expressions and the frontend `MANAGER_ROLES`; cosmetic,
some risk, no security benefit).

---

## Suggested phasing (after approval)

| Phase | Content | Verification |
|---|---|---|
| 1 | Role + migration + bootstrap/claim + `CurrentUserService` null guard | `PlatformAdminAuthorizationIT` cases 1–5, 7–9 |
| 2 | Audit log table, writer, login/suspension hooks, read API | case 10 |
| 3 | Overview + restaurants + users endpoints and DTOs | repository aggregate tests |
| 4 | Orders + analytics (lifting the existing payment aggregation) | reconciliation against a single restaurant's own dashboard figures |
| 5 | Frontend `/platform-admin` area | responsive render checks |
| 6 | Curated health/metrics endpoint | — |

Each phase is independently shippable and leaves existing behaviour untouched.

---

## Explicitly out of scope for phase 1

Multiple platform administrators with delegated least-privilege permissions (needs a permission model,
not just a role — worth doing properly later, and designed for by keeping the role check in one place);
TOTP/MFA; Prometheus scrape endpoint and Grafana dashboards; k6 integration; refunds or order edits
initiated by the platform owner (deliberately read-only in phase 1 — a cross-tenant write path
deserves its own design and confirmation flow); email delivery for approval notifications.

---

## One standing limitation

I cannot compile or run anything in this environment — no Gradle, no dependency cache, no database.
Everything above would be delivered the way the recent security work was: static verification plus
mutation-tested checkers, with `./gradlew test` required on your side before trusting any of it.
