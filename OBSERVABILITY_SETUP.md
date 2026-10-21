# Observability Setup

Prometheus and Grafana for the KashmirMenu backend. Read the verification checklist at the end
before trusting any of it: **none of this has been executed** — see "What was and was not verified".

## What was added

| Path | Purpose |
|---|---|
| `build.gradle` | `micrometer-registry-prometheus` (runtime), `logstash-logback-encoder` |
| `src/main/resources/application.yaml` | management port, endpoint exposure, histogram config |
| `src/main/resources/logback-spring.xml` | human-readable logs on dev, JSON on prod |
| `config/SecurityConfig.java` | a dedicated Actuator filter chain |
| `metrics/AuthenticationMetrics.java` | the one custom metric |
| `prometheus/` | scrape config, production variant, alert rules |
| `grafana/provisioning/`, `grafana/dashboards/` | data source and six dashboards, as code |
| `docker-compose.observability.yml` | Prometheus + Grafana overlay |
| `docs/build_dashboards.py` | regenerates the dashboards |

## How metrics are protected

Not by a password on the scrape endpoint, but by **topology**:

- Actuator listens on `MANAGEMENT_PORT` (default **9091**), not on 8080.
- That port is **never** in a `ports:` block, so nothing on the host can reach it.
- nginx proxies only `/api` and `/ws`, so nothing under `/actuator` is routable from the internet.
- Prometheus reaches `app:9091` over the private `kashmirmenu-observability` network.
- Prometheus publishes **no** host port at all; Grafana publishes to `127.0.0.1` only.

Why not a bearer token on `/actuator/prometheus` instead? Because it puts a credential in Prometheus
and makes scraping depend on the authentication stack being healthy — exactly when metrics matter
most. The `actuatorFilterChain` bean exists as a second line: if the management and server ports are
ever collapsed into one, Actuator requests arriving on the application port are refused rather than
served.

## Running it

```bash
cp .env.example .env            # then set GRAFANA_ADMIN_PASSWORD and the rest
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

Grafana: <http://127.0.0.1:3001> (user `admin`, password from `GRAFANA_ADMIN_PASSWORD`).
There is no default password — compose refuses to start without one.

Prometheus has no published port by design. To reach its UI:

```bash
docker compose -f docker-compose.observability.yml exec prometheus \
  wget -qO- http://localhost:9090/api/v1/targets | head
# or, for the full UI, tunnel it:
ssh -L 9090:localhost:9090 your-host    # then forward from the container as needed
```

## Dashboards

| File | Dashboard | Answers |
|---|---|---|
| `overview.json` | Application Overview | Is it up, how busy, how broken, how slow |
| `jvm.json` | JVM Performance | Memory, GC, CPU, threads |
| `api.json` | API Performance | Which endpoint is slow or failing |
| `database.json` | Database Performance | Connection pool health and saturation |
| `security.json` | Security Signals | Auth failures, 401/403/429 patterns |
| `load-testing.json` | Load Testing (k6) | Populated only during a k6 run |

Edit them by changing `docs/build_dashboards.py` and re-running it, not by exporting from the UI —
`allowUiUpdates: false` means UI edits are overwritten on restart, which keeps the files
authoritative.

### Every panel maps to a real metric

The brief asked for no decorative panels. Each query was checked against the metrics this dependency
set actually exports (`docs/promql_check.py`), and that check is mutation-tested. Two panels are
intentionally empty most of the time and say so on the dashboard itself:

- **Load Testing** — reads `k6_*` series, which exist only during a run with remote write enabled.
- **Database → query performance** — a text panel explaining that per-query timings are *not*
  exported by anything today, rather than a panel showing nothing. Use *connection usage time* as the
  proxy, and MySQL's slow query log for the real thing.

## The custom metric

Exactly one: `app_authentication_failures_total`, tagged `reason` from a bounded set.

Authorization failures were **not** given a counter — they are already visible as
`http_server_requests_seconds_count{status="403"}`, and a second signal for the same thing is a
liability, not an asset.

No metric carries a user id, email, IP, request id, token or restaurant id. Spring's `uri` tag is the
route template (`/api/v1/orders/{orderId}`), never the raw path, and `max-uri-tags: 150` caps what a
scanner hitting unmatched paths can do to the series count. Per-restaurant revenue deliberately lives
in the platform admin dashboard, backed by SQL — one Prometheus series per restaurant is unbounded
growth by design.

## Percentiles

`percentiles-histogram` is enabled for `http.server.requests`, not client-side `percentiles`. The
difference matters: percentiles computed inside one instance **cannot be correctly aggregated** across
instances or averaged over time, so they break the moment there is a second replica. Histogram buckets
let Grafana compute `histogram_quantile` properly. The explicit `slo` boundaries keep the bucket count
bounded.

## What was and was not verified

**Executed here:**

- Every YAML file parses (`prometheus/*.yml`, both compose files, Grafana provisioning).
- Every dashboard JSON parses, and every panel target uses the provisioned datasource UID.
- **Every PromQL expression** in all six dashboards and all nine alert rules was checked against the
  metric names Micrometer will export — 66 expressions, 33 distinct metric names, no unknowns. The
  checker also catches `histogram_quantile` without a `by (le)` grouping and ratios without
  `clamp_min`, and was mutation-tested against all four failure modes.
- `logback-spring.xml` is well-formed.
- k6 scripts parse; the production guard's logic was unit-tested (and a real fail-open bug was found
  and fixed — see the performance guide).
- The existing static verifiers still pass: authorization matrix, platform-role isolation, duplicate
  annotations, entity/migration consistency, migration ordering.

**Not executed — no network, no Docker daemon, no database, no k6 binary, and Gradle cannot even
download itself in this environment:**

1. The application compiling or starting.
2. Prometheus actually scraping the target.
3. Grafana connecting to Prometheus.
4. Panels rendering real data.
5. k6 running against anything.
6. Alert expressions evaluated by a real Prometheus (syntax checked, not evaluated).

### Your verification checklist, in order

```bash
# 1. It still builds and the existing tests pass.
./gradlew build

# 2. Bring up the stack.
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build

# 3. The target is healthy. Expect health="up" for job "kashmirmenu".
docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/targets?state=active'

# 4. Metrics are actually being produced (from inside the network - this must fail from the host).
docker compose exec prometheus wget -qO- http://app:9091/actuator/prometheus | head -30

# 5. Confirm the management port is NOT reachable from the host. This must fail.
curl -sS --max-time 3 http://localhost:9091/actuator/prometheus && echo "EXPOSED - investigate"

# 6. Alert rules loaded without error.
docker compose exec prometheus wget -qO- http://localhost:9090/api/v1/rules | head

# 7. Grafana, then open each dashboard and confirm panels have data.
open http://127.0.0.1:3001

# 8. Smoke test the app end to end.
BASE_URL=http://localhost:3000 k6 run k6/smoke.js
```

Step 5 is the important one: it proves the protection is real rather than assumed.

## Deferred, with reasons

**Loki and Tempo** (centralised logs, distributed tracing) are not included. They are two more
services to run and upgrade, and the OpenTelemetry Java agent costs start-up time and some CPU.
Tracing earns that cost across service boundaries; this is one backend with one database, where the
`requestId` MDC field plus endpoint-level metrics already answer most "why was this slow" questions.
Revisit when there is a second service or a latency mystery metrics cannot explain.

**Alertmanager** is not in the stack. The rules are written to work under either Prometheus +
Alertmanager or Grafana's built-in alerting; see `MONITORING_ALERTS.md`.

**Frontend telemetry collection.** An error boundary was added; nothing is transmitted. Collecting
Core Web Vitals or client errors centrally needs a public, unauthenticated write endpoint, which is a
security decision rather than a monitoring one. Not taken unilaterally.
