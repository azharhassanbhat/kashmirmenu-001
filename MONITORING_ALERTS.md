# Monitoring Alerts

Nine rules in `prometheus/rules/kashmirmenu.yml`, grouped by what they tell you.

## Principles

**Every rule has a sustained `for:` window.** A single failed scrape or one slow request is not an
incident, and an alert that fires on noise gets muted — which is worse than having no alert.

**Ratios, not counts, for errors.** Five errors a second matters at 50 requests/sec and is noise at
5000. Every error rule divides, with `clamp_min` on the denominator so an idle period does not divide
by zero and page someone at 3am.

**Signals are not verdicts.** The authentication rule is named *ElevatedAuthenticationFailures*, not
*CredentialStuffing*, and its description says so. A released mobile client with a broken token
refresh produces an identical graph to an attack.

## The rules

| Alert | Fires when | For | Severity |
|---|---|---|---|
| `ApplicationDown` | `up == 0` | 2m | critical |
| `ScrapeFailing` | scrape takes > 10s | 5m | warning |
| `High5xxRate` | 5xx ratio > 5% | 5m | critical |
| `LatencyDegraded` | P95 > 1.5s | 10m | warning |
| `JvmHeapPressure` | heap > 90% of max | 10m | warning |
| `HighCpu` | process CPU > 85% | 10m | warning |
| `DbPoolExhausted` | threads waiting for a connection | 5m | critical |
| `DbPoolSaturated` | pool > 90% utilised | 10m | warning |
| `ElevatedAuthenticationFailures` | > 3× the 24h baseline | 15m | warning |
| `OrderingLockConflictsGivingUp` | > 3 lock conflicts in 15m outlasted every retry (guests saw "table is busy") | 5m | warning |
| `OrderingLockRetriesElevated` | > 0.2 lock retries/s (retries that all succeeded) | 15m | warning |

### Why these windows

- **2 minutes for down.** Long enough to ride out a deploy or a container restart, short enough that
  a real outage is not discovered by a customer first.
- **5 minutes for errors and pool exhaustion.** Both are actively hurting users; waiting longer buys
  nothing.
- **10 minutes for resources.** A JVM legitimately sits near its heap ceiling just before a
  collection, and CPU spikes during start-up. Neither is an incident on its own.
- **15 minutes for authentication.** The noisiest signal of the set, and the least likely to need a
  response within the hour.

### Why `DbPoolExhausted` has no threshold above zero

`hikaricp_connections_pending > 0` means a request thread is blocked waiting for a connection. There
is no healthy steady-state value other than zero, so the sustained window does the filtering rather
than a magic number. `DbPoolSaturated` at 90% is the earlier, gentler warning.

## Routing

The rules work under either setup; pick one.

**Grafana-managed alerting (recommended for this stack).** Grafana already has the data source, and
one fewer service to run and upgrade. In Grafana: *Alerting → Alert rules → Import from Prometheus*,
pointing at the same rule file, then add a contact point (email, Slack, PagerDuty) and a notification
policy routing `severity=critical` to a paging channel and `severity=warning` to a non-paging one.

**Prometheus + Alertmanager.** Add an `alertmanager` service to the observability overlay on the same
private network, point Prometheus at it with `alerting.alertmanagers`, and route on the `severity`
label. Choose this if you already run Alertmanager elsewhere or need its silencing and grouping.

### Suggested routing

| Severity | Route | Why |
|---|---|---|
| `critical` | Page whoever is on call | Users are affected now |
| `warning` | Chat channel, business hours | Degradation, not an outage |

Group by `alertname` and `service`, with a 4-hour repeat interval. Silence `warning` during planned
load tests — a stress test *should* trip latency and pool alerts, and that is the test working.

## What to check first, per alert

- **ApplicationDown** → is the container running, then is the management port reachable from the
  observability network (`docker compose exec prometheus wget -qO- http://app:9091/actuator/health`).
- **High5xxRate** → API dashboard, *Errors by endpoint*. Find the endpoint before opening the logs.
- **LatencyDegraded** → API dashboard, *Slowest endpoints*, then the Database dashboard. A slow
  endpoint with rising connection usage time is a query, not the JVM.
- **DbPoolExhausted** → *Connection usage time*. Rising usage with flat traffic means a query got
  slower or a transaction is being held open.
- **ElevatedAuthenticationFailures** → the audit log in the platform admin area, which records the
  actor and time for each attempt. Metrics deliberately carry no identifiers; the audit log is where
  the detail lives. Check for a recent client release before concluding anything.

- **OrderingLockConflictsGivingUp / OrderingLockRetriesElevated** → the `operation` label on
  `app_lock_failures_total` / `app_lock_retries_total` says where requests are colliding: `cart`, `order`,
  `join`, `settlement`, `settlement-confirm`, `table-close`, `table-reopen`. A few retries are normal when a
  table's guests act together; the retry exists to absorb exactly that. A *rising* rate means the conflicts
  are lasting longer, which is a slow query or a transaction held open: check the Database dashboard's
  connection usage time, as for `DbPoolExhausted`. Giving up is returned to the client as HTTP 503 with
  `Retry-After: 1`, and is safe to repeat: the request changed nothing.

  The two thresholds are starting hypotheses, like the others, until a baseline under realistic load
  (`k6/tables.js`, ideally against MySQL) shows what normal looks like.

## What was and was not verified

**Executed here:** the rule file parses as YAML; every rule has a `for:` window and a valid severity;
every PromQL expression was checked against the metric names Micrometer exports, including the
`histogram_quantile`/`by (le)` and divide-by-zero traps, with that checker mutation-tested.

**Not executed:** no Prometheus has ever evaluated these expressions, and no alert has ever fired.
Run `promtool check rules prometheus/rules/kashmirmenu.yml` and confirm
`/api/v1/rules` lists them without error after starting the stack.
