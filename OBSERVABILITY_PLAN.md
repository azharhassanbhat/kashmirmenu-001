# Observability, Monitoring and Performance Testing — Findings and Plan

**Status: proposal. No application code has been changed.** This is Phase 1 plus the proposed design
for Phases 2–10, for your approval before anything is implemented.

---

## Phase 1: What exists today

### Stack

| Layer | Detail |
|---|---|
| Backend | Spring Boot **3.5.4**, Java **21**, Gradle wrapper |
| Database | MySQL **8.4**, HikariCP (Boot default), Flyway V1–V25 |
| Frontend | React **19.3**, TypeScript 5.9, Vite 7.3, Vitest 4.1, served by nginx |
| Auth | Stateless HS256 JWT; roles read from the database per request |
| Realtime | STOMP over WebSocket (SockJS), authenticated per connection |
| CI | GitHub Actions: `./gradlew build`, frontend typecheck/test/build/`npm audit`, Docker builds |
| Orchestration | Docker Compose (mysql, app, frontend). **No Kubernetes, no cloud deployment config** |

### What is already in place

- **`spring-boot-starter-actuator` is a dependency.** Exposure is restricted to `health,info`,
  health details are `when-authorized`, and liveness/readiness probes are enabled.
- **Request correlation already works.** `RequestIdFilter` accepts or generates `X-Request-Id`, puts
  it in the SLF4J MDC, and echoes it on the response; the console pattern already prints
  `[%X{requestId}]`. Phase 6's correlation-ID requirement is largely satisfied — what is missing is
  structured output and a collector.
- **`http.server.requests` is already recorded** by Boot, with a templated `uri` tag. Count, duration,
  status and outcome come for free the moment a registry exists.
- **An audit log exists** (`audit_log`, added last phase) recording `LOGIN_SUCCEEDED`, `LOGIN_FAILED`,
  `LOGGED_OUT` and platform bootstrap actions. This is the natural hook for an authentication-failure
  metric, and it already avoids storing credentials.
- **The Docker healthcheck** already hits `/actuator/health/readiness`.

### What is missing

Nothing observability-related is configured: `grep` for `micrometer`, `prometheus`, `opentelemetry`,
`zipkin`, `loki` and `logstash` across `build.gradle` returns nothing. There is no `prometheus/`,
`grafana/` or `k6/` directory, no alert rules, and no frontend error reporting.

**So there is no duplication risk**, and only one dependency is strictly required (below).

### Two findings that affect the design

**1. `/actuator/prometheus` is currently unreachable, and that is the right starting point.**
The filter chain permits only `/actuator/health/liveness` and `/actuator/health/readiness`; everything
else falls to `.anyRequest().denyAll()`. A scrape endpoint added today would return 403. Exposing it
is therefore a deliberate act, and the plan below keeps it off the public path entirely rather than
adding a role check to a scrape endpoint.

**2. Unrelated, but found while reading the compose file: MySQL is published to every interface.**
`ports: "3306:3306"` binds `0.0.0.0`, so the database is reachable from the host's network — unlike
the app, which correctly binds `127.0.0.1:8080`. This is outside the observability brief and I have
not changed it, but it is a real exposure and the one-character fix is `"127.0.0.1:3306:3306"`.
Flagging rather than silently fixing, since you asked for no unrelated changes.

---

## Phase 2: Spring Boot metrics

### Dependency

One addition, version managed by the Boot BOM so it cannot drift:

```gradle
// Exposes Micrometer's metrics in Prometheus' text format at /actuator/prometheus.
// Version comes from the Spring Boot BOM (3.5.4), so it is guaranteed compatible.
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

`runtimeOnly`, not `implementation`: no application code imports Micrometer directly for this, so the
compile classpath stays unchanged.

### Exposure — a separate management port

The cleanest answer to "protect monitoring endpoints" here is **not** to authenticate the scrape
endpoint but to move it off the application's port:

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:9091}   # never published to the host, never proxied by nginx
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

Consequences, stated plainly:

- nginx proxies only `/api` and `/ws`, so nothing under `/actuator` is reachable from the internet
  regardless of Spring Security.
- Prometheus reaches `app:9091` over the Compose network; the port is never in a `ports:` block.
- The security filter chain needs the management port permitted — Boot applies the same chain to both
  ports unless told otherwise. I would add an `EndpointRequest.toAnyEndpoint()` matcher restricted to
  that port, which is the documented, non-deprecated approach and **does not weaken** any existing
  rule: the `denyAll` fallback stays.
- The Dockerfile healthcheck must move to the new port.

If you would rather keep one port, the alternative is `hasRole('PLATFORM_OWNER')` on `/actuator/**`
plus a bearer token in Prometheus' scrape config — workable, but it puts a credential in Prometheus
and makes scraping depend on the auth stack being healthy, which is exactly when you most need
metrics. **I recommend the separate port.**

### Metrics, and which are free versus custom

| Requirement | Source | Custom code? |
|---|---|---|
| Request count, duration, 4xx/5xx rate | `http.server.requests` | none |
| P50/P95/P99 | same, with histogram enabled (below) | none |
| JVM heap/non-heap, GC, threads, CPU, uptime | Boot's JVM binders | none |
| DB pool active/idle/pending/acquire time | `hikaricp.connections.*` — auto-registered once a registry exists | none |
| Health/readiness/liveness | `/actuator/health/*` + Prometheus `up` | none |
| **Authentication failures** | **one counter** | yes — see below |

Only one custom metric is proposed. Authorization failures (403s) are already distinguishable from
`http.server.requests{status="403"}`, so adding a second counter for them would duplicate an existing
signal — I would not add it.

```java
// Incremented where the audit log already records LOGIN_FAILED. No identifier of any kind:
// no email, no IP, no user id. Just a count, tagged only by a bounded reason.
Counter.builder("app.authentication.failures")
       .description("Failed sign-in attempts")
       .tag("reason", "bad_credentials")   // bounded set, never user-supplied
       .register(registry);
```

### Percentiles — a decision worth making explicitly

Micrometer offers two things that look alike:

- `percentiles: 0.5, 0.95, 0.99` computes them **inside each instance** and exports them as gauges.
  Cheap, but **mathematically wrong to aggregate** across instances or to average over time.
- `percentile-histogram: true` exports Prometheus histogram buckets, so Grafana can compute
  `histogram_quantile(0.95, ...)` correctly across instances and windows. Costs more series.

Dashboards that compare endpoints and survive a second replica need the histogram. I propose the
histogram with explicit SLO boundaries to keep the bucket count sane:

```yaml
management:
  metrics:
    tags:
      application: kashmirmenu
      environment: ${ENVIRONMENT:local}
  observations:
    key-values:
      application: kashmirmenu
  endpoint:
    health:
      show-details: when-authorized
  prometheus:
    metrics:
      export:
        enabled: true
distribution:
  percentiles-histogram:
    http.server.requests: true
  slo:
    http.server.requests: 50ms, 100ms, 200ms, 500ms, 1s, 2s
  maximum-expected-value:
    http.server.requests: 5s
```

### Cardinality safeguards

The brief is right to call this out; it is the usual way a metrics stack falls over.

- Spring's `uri` tag is already **templated** (`/api/v1/orders/{orderId}`), not raw, so order ids and
  tokens never become labels.
- Unmatched paths become `/**`, but a scanner hitting thousands of 404 URLs can still inflate series.
  Mitigation: `management.metrics.web.server.max-uri-tags: 150`, which makes Boot stop adding new URI
  tags past that point.
- No metric will carry a user id, email, request id, token or restaurant id. Restaurant-level revenue
  belongs in the platform dashboard (backed by SQL), **not** in Prometheus — one series per restaurant
  is unbounded growth by design.

---

## Phase 3: Prometheus

`prometheus/prometheus.yml`, with environment-specific overrides in
`prometheus/prometheus.<env>.yml`:

- Scrape `app:9091/actuator/prometheus` every **15s**, timeout **10s** (comfortably under the
  interval; a timeout at or above the interval causes overlapping scrapes).
- `external_labels: { environment, cluster }` so a later federated or remote-write setup can tell
  deployments apart.
- Retention: `--storage.tsdb.retention.time=15d` with a named volume. 15 days is a deliberate default —
  enough to see a weekly pattern, small enough to stay on a single node. Longer retention means
  Thanos/Mimir, which is a different project.
- `--web.enable-remote-write-receiver` **only if** you want the k6 integration in Phase 5; it opens a
  write path, so it should be on the internal network only, and I would rather you decide than have it
  default on.
- Access control: Prometheus and Grafana attach to a dedicated `observability` Compose network and
  publish **only** Grafana, and only to `127.0.0.1`. Prometheus' own UI gets no host port — it is
  reachable via `docker compose exec` or an SSH tunnel. Documented in `OBSERVABILITY_SETUP.md`.

A target-health check (`up == 1` plus a scrape of the endpoint) is part of Phase 10's verification
checklist, but see the limitation at the end: I cannot execute it here.

---

## Phase 4: Grafana dashboards

Provisioned as code under `grafana/provisioning/` (datasource + dashboard providers) and
`grafana/dashboards/*.json`, so they are version-controlled rather than clicked together.

Six dashboards as specified. Every panel maps to a metric that will actually exist after Phase 2 —
the mapping table will be included in `OBSERVABILITY_SETUP.md` so you can check it rather than trust
it. Representative queries:

| Dashboard | Panel | Query |
|---|---|---|
| 1 Overview | RPS | `sum(rate(http_server_requests_seconds_count[5m]))` |
| 1 | 5xx rate | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` |
| 1 | P95 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))` |
| 2 JVM | Heap used | `sum by (id) (jvm_memory_used_bytes{area="heap"})` |
| 2 | GC pause | `rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])` |
| 3 API | Slowest endpoints | `topk(10, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))))` |
| 4 DB | Pool saturation | `hikaricp_connections_active / hikaricp_connections_max` |
| 4 | Pending | `hikaricp_connections_pending` |
| 5 Security | Auth failures | `rate(app_authentication_failures_total[5m])` |
| 6 k6 | VUs / latency | `k6_vus`, `k6_http_req_duration` (only if remote write is enabled) |

**Two honesty notes.** Dashboard 5 will be titled *Security signals*, and panels will read "elevated
failed sign-ins", not "attack detected" — a spike in failed logins is as likely to be a broken client
or a forgotten password as an attack. Dashboard 6 will ship with a panel explaining that it stays
empty until a k6 run with remote write enabled, rather than being padded with placeholder series.

---

## Phase 5: k6

`k6/` with a shared `lib/` (config, auth helper, seed helper) and one script per test type: smoke,
baseline, stress, spike, soak, plus two workflow scripts — the **diner QR journey** (scan → menu →
cart → coupon → order → track) and the **staff journey** (sign in → kitchen board → take payment),
since those are the paths that actually matter commercially.

Everything parameterised by environment variable: `BASE_URL`, `K6_VUS`, `K6_DURATION`, `ENVIRONMENT`,
`TEST_EMAIL`/`TEST_PASSWORD`, and threshold overrides. No credential in the repository.

### On thresholds — I will propose, not assert

You asked for SLO-based targets rather than arbitrary numbers, and I cannot know this application's
real latency profile without measuring it. So the plan is: **ship the smoke and baseline tests with
provisional thresholds, run the baseline, then set real SLOs from the observed P95.** The provisional
values and their reasoning:

| Journey | Provisional P95 | Why this number |
|---|---|---|
| Public menu read | 400ms | Cached-ish reads a diner waits on; above this the QR experience feels slow |
| Cart mutation | 600ms | A write plus a recalculation, on a phone network |
| Order placement | 1200ms | Locks the order, the counter, stock and the coupon — the heaviest write in the app |
| Staff dashboard / analytics | 1500ms | Aggregates across a date range; a manager tolerates more than a diner |
| Error rate, all | < 1% | — |

These are **hypotheses to calibrate**, stated as such in the guide.

### Safety

The guide will lead with it, and the scripts will enforce it: a non-smoke test **refuses to start**
unless `ENVIRONMENT` is explicitly a non-production value, so the default outcome of a careless
`k6 run` against a production URL is a refusal rather than a stress test. Tests seed their own
restaurants and diners through the public and registration APIs and never use a real account. CI runs
the **smoke test only**, against an ephemeral environment; load, stress, spike and soak stay manual.

Soak deserves a caution: a multi-hour soak writes a lot of rows (orders, payments, audit entries).
Against a shared test database that is a disk-space and data-hygiene issue, so the guide will cover
cleanup.

---

## Phase 6: Logging and tracing — recommended in two steps

**Step A (recommended now): structured JSON logging.** One dependency
(`net.logstash.logback:logstash-logback-encoder`), a `logback-spring.xml` with a JSON encoder on the
`prod` profile and the current human-readable pattern kept for `dev`. The MDC already carries
`requestId`, so every line becomes queryable and correlatable immediately. Low cost, no new
infrastructure.

A redaction review comes with it: the audit work already removed emails from auth logs, and the
Google Places client already excludes its API key. I would add a documented rule and a test asserting
that no appender pattern includes `Authorization`, and keep the bootstrap claim-token log line
exactly as it is — deliberately logged once, deliberately nowhere else.

**Step B (propose, do not implement yet): Loki + Tempo + OpenTelemetry.** Honest cost: two more
services to run, store and upgrade; the OTel Java agent adds roughly 1–2s to start-up and a few
percent CPU; and tracing is most valuable across service boundaries — this is a **single backend with
one database**, where the MDC request id plus endpoint-level metrics already answer most "why was this
slow" questions. My recommendation is to defer Step B until there is a second service or a real
latency mystery that metrics cannot explain. If you want it anyway, `micrometer-tracing` with
`tracing-bridge-otel` plus Tempo is the path, and I will scope it separately.

---

## Phase 7: Alerts

`prometheus/rules/*.yml`, with `MONITORING_ALERTS.md` covering routing. Every rule uses a sustained
`for:` window, because a single bad scrape is not an incident:

| Alert | Expression (abbreviated) | For | Severity |
|---|---|---|---|
| ApplicationDown | `up{job="kashmirmenu"} == 0` | 2m | critical |
| ScrapeFailing | `up == 0` or `scrape_duration_seconds > 10` | 5m | warning |
| High5xxRate | 5xx ratio `> 0.05` | 5m | critical |
| LatencyDegraded | P95 `> 1.5` | 10m | warning |
| JvmHeapPressure | heap used/max `> 0.9` | 10m | warning |
| DbPoolExhausted | `hikaricp_connections_pending > 0` | 5m | critical |
| HighCpu | process CPU `> 0.85` | 10m | warning |
| AuthFailureSpike | `rate(app_authentication_failures_total[5m])` above a multiple of its 24h baseline | 15m | warning |

The last one is phrased as *unusual rate of failed sign-ins*, not *credential stuffing*, and routes to
a human rather than paging.

Routing: Alertmanager is **not** in the proposed compose stack by default — it is a fourth service and
most teams at this stage route through Grafana's built-in alerting instead. `MONITORING_ALERTS.md`
will document both, with the rules written to work under either.

---

## Phase 8: Frontend monitoring

The frontend is a Vite SPA with no error reporting today. Proposed, deliberately minimal:

- **An error boundary** around the admin and diner shells that reports a render failure instead of a
  white screen. Pure React, no dependency.
- **Failed API counts.** `api/client.ts` is a single choke point for every request, so a counter by
  status and route *template* can be collected there — never the full URL, which can carry a QR
  identifier or an order token.
- **Core Web Vitals** via the `web-vitals` package (~2KB gzipped) — the only new frontend dependency,
  and genuinely the standard way to get LCP/INP/CLS.

**The open question is where this data goes.** A browser cannot write to Prometheus, so it needs an
ingest endpoint — which is a new public, unauthenticated write surface, and therefore a security
decision rather than a monitoring one. Options: (a) log to console only in dev and skip collection
entirely, (b) a rate-limited `POST /api/public/telemetry` that accepts a strict, validated schema and
feeds a Micrometer counter, (c) a third-party RUM service. I lean to **(b) with a tight schema and the
existing rate limiter**, but I would not build any public write endpoint without you choosing it.

No user tracking, no identifiers, no session recording, no third-party script — which also keeps the
existing strict CSP (`script-src 'self'`) intact.

---

## Phase 9: Docker

`docker-compose.observability.yml` as an **overlay**, run alongside the existing file, so the main
compose file stays usable on its own and nothing existing is restructured:

```
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

- Pinned images: `prom/prometheus:v3.1.0`, `grafana/grafana:11.5.0` (pinned minor, not `latest`).
- Named volumes for both, so metrics and Grafana state survive a restart.
- A dedicated `observability` network; the app joins it so Prometheus can reach `app:9091`.
- Only Grafana gets a host port, bound to `127.0.0.1:3001`.
- Grafana admin password from `${GRAFANA_ADMIN_PASSWORD:?...}` — the `:?` form, matching the existing
  file's handling of `DB_PASSWORD`, so compose **fails to start** rather than falling back to
  `admin/admin`. No credential in the repository; `.env.example` gets a blank entry.
- Healthchecks for both services.
- `GF_USERS_ALLOW_SIGN_UP=false`, anonymous access off.

---

## Phase 10: Verification — what I can and cannot do

I have to be straight about this, because it determines what "done" will mean.

**In this environment I cannot run any of it.** There is no network (so no Gradle dependency
resolution, no Docker image pulls, no `npm install`), no Docker daemon, no database, and no k6 binary.
`./gradlew` cannot even download Gradle. So of your Phase 10 list, items 1–6 and 8 **cannot be
executed by me at all**.

What I *can* do, and will:

- Validate every YAML and dashboard JSON file parses, and that each dashboard's datasource UID matches
  the provisioned datasource.
- **Check every PromQL expression** in dashboards and alert rules against the list of metric names
  Boot/Micrometer actually exports for this dependency set — catching a panel that queries a metric
  that will never exist, which is the most common way these dashboards ship broken.
- Syntax-check the k6 scripts.
- Re-run the existing static verifiers (authorization matrix, platform isolation, entity/migration
  consistency) to confirm the Spring Security change does not alter any existing rule, plus the 61
  pure-Java and 31 frontend logic tests.
- Mutation-test the new PromQL checker, as with the previous ones.

What **you** must run before trusting it: `./gradlew build`, `docker compose … up`, confirm the target
is `up` in Prometheus, confirm panels render, and run the k6 smoke test. `OBSERVABILITY_SETUP.md` will
be a checklist in that order.

---

## Summary of changes requiring approval

| Change | Risk |
|---|---|
| +1 runtime dependency (`micrometer-registry-prometheus`) | low, BOM-managed |
| +1 logging dependency (`logstash-logback-encoder`) if Step A approved | low |
| +1 frontend dependency (`web-vitals`) if Phase 8 approved | low, ~2KB |
| Management endpoints move to port 9091 | **touches SecurityConfig and the Dockerfile healthcheck** |
| `application.yaml` metrics/histogram configuration | low |
| One custom counter at the existing audit hook | low |
| New top-level `prometheus/`, `grafana/`, `k6/`, overlay compose, three docs | none — additive |
| A public telemetry ingest endpoint | **not proposed without your explicit choice** |

Nothing above modifies business logic, and the only security-relevant edit is the management-port
matcher, which adds a rule and removes none.

### Decisions I need

1. **Management port 9091, or token-authenticated `/actuator/**` on 8080?** (I recommend the port.)
2. **Enable Prometheus remote write** for the k6 → Grafana integration? (Opens a write path.)
3. **Structured JSON logging now** (Step A), and **defer Loki/Tempo** (Step B)? (I recommend both.)
4. **Frontend telemetry**: console-only, a new public ingest endpoint, or nothing for now?
5. **Alertmanager**, or Grafana-managed alerting? (I lean Grafana for this stack size.)
6. Shall I also fix the **MySQL `0.0.0.0:3306`** binding, or leave it as flagged-but-untouched?

Say which way you want 1–6 and I will implement in the phase order above, smallest first.
