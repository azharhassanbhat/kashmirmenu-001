#!/usr/bin/env python3
"""
Builds the six Grafana dashboards.

Hand-writing ~2000 lines of dashboard JSON invites copy-paste drift: a stale datasource uid in one
panel, a missing `le` grouping in another. Generating them keeps every panel structurally identical
and puts all the PromQL in one reviewable place, which is also what the PromQL checker reads.

Run from the repository root:  python3 docs/build_dashboards.py
"""
import json
from pathlib import Path

OUT = Path('grafana/dashboards')
DS = {'type': 'prometheus', 'uid': 'kashmirmenu-prometheus'}
JOB = 'job="kashmirmenu"'

# Grafana's $__rate_interval adapts the window to the panel's resolution, which avoids the classic
# "rate() over a window shorter than the scrape interval returns nothing" problem.
RATE = '$__rate_interval'


def target(expr, legend, ref='A'):
    return {
        'datasource': DS,
        'expr': expr,
        'legendFormat': legend,
        'refId': ref,
        'editorMode': 'code',
        'range': True,
    }


def panel(title, targets, x, y, w=12, h=8, kind='timeseries', unit='short',
          description='', extra_options=None, thresholds=None):
    p = {
        'type': kind,
        'title': title,
        'description': description,
        'datasource': DS,
        'gridPos': {'h': h, 'w': w, 'x': x, 'y': y},
        'targets': [target(e, l, chr(ord('A') + i)) for i, (e, l) in enumerate(targets)],
        'fieldConfig': {
            'defaults': {
                'unit': unit,
                'custom': {'fillOpacity': 8, 'lineWidth': 2, 'showPoints': 'never'},
                'thresholds': thresholds or {'mode': 'absolute', 'steps': [
                    {'color': 'green', 'value': None}]},
            },
            'overrides': [],
        },
        'options': {'legend': {'displayMode': 'list', 'placement': 'bottom', 'showLegend': True},
                    'tooltip': {'mode': 'multi', 'sort': 'desc'}},
    }
    if kind == 'stat':
        p['options'] = {'reduceOptions': {'calcs': ['lastNotNull'], 'fields': '', 'values': False},
                        'textMode': 'auto', 'colorMode': 'value'}
    if kind == 'table':
        p['options'] = {'showHeader': True}
    if extra_options:
        p['options'].update(extra_options)
    return p


def text_panel(title, content, x, y, w=24, h=4):
    return {
        'type': 'text',
        'title': title,
        'gridPos': {'h': h, 'w': w, 'x': x, 'y': y},
        'options': {'mode': 'markdown', 'content': content},
    }


def dashboard(uid, title, description, panels, tags):
    return {
        'uid': uid,
        'title': title,
        'description': description,
        'tags': ['kashmirmenu'] + tags,
        'timezone': 'browser',
        'schemaVersion': 39,
        'version': 1,
        'editable': True,
        'refresh': '30s',
        'time': {'from': 'now-6h', 'to': 'now'},
        'panels': panels,
        'templating': {'list': []},
    }


# ---------------------------------------------------------------- 1. overview

overview = dashboard(
    'km-overview', 'KashmirMenu — Application Overview',
    'Service health, throughput, errors and latency. The first dashboard to open during an incident.',
    [
        panel('Service up', [(f'up{{{JOB}}}', 'up')], 0, 0, w=4, h=4, kind='stat',
              description='1 when Prometheus can scrape the management port, 0 when it cannot.',
              thresholds={'mode': 'absolute', 'steps': [
                  {'color': 'red', 'value': None}, {'color': 'green', 'value': 1}]}),
        panel('Uptime', [(f'process_uptime_seconds{{{JOB}}}', 'uptime')], 4, 0, w=4, h=4,
              kind='stat', unit='s', description='Time since the JVM started. A reset means a restart.'),
        panel('Requests / sec', [
            (f'sum(rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}]))', 'rps')],
            8, 0, w=4, h=4, kind='stat', unit='reqps'),
        panel('Error ratio (5xx)', [
            (f'sum(rate(http_server_requests_seconds_count{{{JOB},status=~"5.."}}[{RATE}])) '
             f'/ clamp_min(sum(rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}])), 0.001)',
             '5xx ratio')],
            12, 0, w=4, h=4, kind='stat', unit='percentunit',
            description='Server errors as a share of all requests. clamp_min avoids a divide-by-zero '
                        'spike when traffic is idle.',
            thresholds={'mode': 'absolute', 'steps': [
                {'color': 'green', 'value': None}, {'color': 'orange', 'value': 0.01},
                {'color': 'red', 'value': 0.05}]}),
        panel('P95 latency', [
            (f'histogram_quantile(0.95, sum by (le) '
             f'(rate(http_server_requests_seconds_bucket{{{JOB}}}[{RATE}])))', 'p95')],
            16, 0, w=4, h=4, kind='stat', unit='s'),
        panel('Failed sign-ins / sec', [
            (f'sum(rate(app_authentication_failures_total{{{JOB}}}[{RATE}]))', 'failures')],
            20, 0, w=4, h=4, kind='stat', unit='short',
            description='Counts only. No identifiers are recorded in metrics; see the audit log.'),

        panel('Throughput by status', [
            (f'sum by (status) (rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}]))',
             '{{status}}')],
            0, 4, unit='reqps',
            description='Traffic split by HTTP status, so a rise in 4xx is distinguishable from a '
                        'rise in load.'),
        panel('Latency percentiles', [
            (f'histogram_quantile(0.50, sum by (le) '
             f'(rate(http_server_requests_seconds_bucket{{{JOB}}}[{RATE}])))', 'p50'),
            (f'histogram_quantile(0.95, sum by (le) '
             f'(rate(http_server_requests_seconds_bucket{{{JOB}}}[{RATE}])))', 'p95'),
            (f'histogram_quantile(0.99, sum by (le) '
             f'(rate(http_server_requests_seconds_bucket{{{JOB}}}[{RATE}])))', 'p99')],
            12, 4, unit='s',
            description='Computed from histogram buckets, so these aggregate correctly across '
                        'instances - unlike per-instance percentile gauges.'),

        panel('4xx vs 5xx rate', [
            (f'sum(rate(http_server_requests_seconds_count{{{JOB},status=~"4.."}}[{RATE}]))', '4xx'),
            (f'sum(rate(http_server_requests_seconds_count{{{JOB},status=~"5.."}}[{RATE}]))', '5xx')],
            0, 12, unit='reqps',
            description='4xx is usually a client or an authorization refusal; 5xx is ours.'),
        panel('Requests by outcome', [
            (f'sum by (outcome) (rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}]))',
             '{{outcome}}')],
            12, 12, unit='reqps'),
    ],
    ['overview'])

# ---------------------------------------------------------------- 2. JVM

jvm = dashboard(
    'km-jvm', 'KashmirMenu — JVM Performance',
    'Memory, garbage collection, CPU and threads for the backend JVM.',
    [
        panel('Heap used / max', [
            (f'sum(jvm_memory_used_bytes{{{JOB},area="heap"}})', 'used'),
            (f'sum(jvm_memory_max_bytes{{{JOB},area="heap"}})', 'max')],
            0, 0, unit='bytes',
            description='A sawtooth is healthy: it is the collector doing its job. Sustained high '
                        'used against max is the thing to worry about.'),
        panel('Heap utilisation', [
            (f'sum(jvm_memory_used_bytes{{{JOB},area="heap"}}) '
             f'/ clamp_min(sum(jvm_memory_max_bytes{{{JOB},area="heap"}}), 1)', 'utilisation')],
            12, 0, unit='percentunit',
            thresholds={'mode': 'absolute', 'steps': [
                {'color': 'green', 'value': None}, {'color': 'orange', 'value': 0.8},
                {'color': 'red', 'value': 0.9}]}),

        panel('Non-heap used', [
            (f'sum by (id) (jvm_memory_used_bytes{{{JOB},area="nonheap"}})', '{{id}}')],
            0, 8, unit='bytes',
            description='Metaspace and code cache. Steady growth here suggests a class or '
                        'classloader leak rather than an object leak.'),
        panel('Memory pool detail', [
            (f'sum by (id) (jvm_memory_used_bytes{{{JOB},area="heap"}})', '{{id}}')],
            12, 8, unit='bytes'),

        panel('GC pause rate', [
            (f'sum by (action) (rate(jvm_gc_pause_seconds_count{{{JOB}}}[{RATE}]))',
             '{{action}} collections/s')],
            0, 16, unit='short'),
        panel('Mean GC pause', [
            (f'sum by (action) (rate(jvm_gc_pause_seconds_sum{{{JOB}}}[{RATE}])) '
             f'/ clamp_min(sum by (action) (rate(jvm_gc_pause_seconds_count{{{JOB}}}[{RATE}])), 0.001)',
             '{{action}}')],
            12, 16, unit='s',
            description='Sum over count gives the mean pause for the window. Rising mean pause with '
                        'flat allocation usually means heap pressure.'),

        panel('CPU usage', [
            (f'process_cpu_usage{{{JOB}}}', 'process'),
            (f'system_cpu_usage{{{JOB}}}', 'system')],
            0, 24, unit='percentunit',
            description='Process is this JVM; system is the whole host. A gap between them means '
                        'something else on the box is busy.'),
        panel('Threads', [
            (f'jvm_threads_live_threads{{{JOB}}}', 'live'),
            (f'jvm_threads_daemon_threads{{{JOB}}}', 'daemon'),
            (f'jvm_threads_peak_threads{{{JOB}}}', 'peak')],
            12, 24, unit='short'),
    ],
    ['jvm'])

# ---------------------------------------------------------------- 3. API

api = dashboard(
    'km-api', 'KashmirMenu — API Performance',
    'Per-endpoint latency and throughput. The uri label is Spring\'s route template, never a raw '
    'path, so order ids and tokens never appear here.',
    [
        panel('Slowest endpoints (P95)', [
            (f'topk(10, histogram_quantile(0.95, sum by (le, uri) '
             f'(rate(http_server_requests_seconds_bucket{{{JOB}}}[{RATE}]))))', '{{uri}}')],
            0, 0, w=24, unit='s',
            description='Grouping by le and uri together is required: dropping le would make '
                        'histogram_quantile meaningless.'),

        panel('Throughput by endpoint', [
            (f'topk(10, sum by (uri) (rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}])))',
             '{{uri}}')],
            0, 8, unit='reqps'),
        panel('Errors by endpoint', [
            (f'topk(10, sum by (uri) '
             f'(rate(http_server_requests_seconds_count{{{JOB},status=~"5.."}}[{RATE}])))', '{{uri}}')],
            12, 8, unit='reqps'),

        panel('Status distribution', [
            (f'sum by (status) (rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}]))',
             '{{status}}')],
            0, 16, unit='reqps', kind='timeseries'),
        panel('Mean latency by endpoint', [
            (f'topk(10, sum by (uri) (rate(http_server_requests_seconds_sum{{{JOB}}}[{RATE}])) '
             f'/ clamp_min(sum by (uri) (rate(http_server_requests_seconds_count{{{JOB}}}[{RATE}])), 0.001))',
             '{{uri}}')],
            12, 16, unit='s',
            description='Mean, not P95: useful for spotting an endpoint that is uniformly slow '
                        'rather than occasionally slow.'),

        panel('Request count by endpoint and method', [
            (f'sum by (uri, method) (increase(http_server_requests_seconds_count{{{JOB}}}[$__range]))',
             '{{method}} {{uri}}')],
            0, 24, w=24, kind='table', unit='short',
            description='Totals over the selected range. Useful for deciding which endpoints a load '
                        'test should actually cover.'),
    ],
    ['api'])

# ---------------------------------------------------------------- 4. database

database = dashboard(
    'km-database', 'KashmirMenu — Database Performance',
    'HikariCP connection pool health. Exported automatically by Spring Boot once a metrics registry '
    'is present; no custom instrumentation.',
    [
        panel('Connections', [
            (f'hikaricp_connections_active{{{JOB}}}', 'active'),
            (f'hikaricp_connections_idle{{{JOB}}}', 'idle'),
            (f'hikaricp_connections{{{JOB}}}', 'total'),
            (f'hikaricp_connections_max{{{JOB}}}', 'max')],
            0, 0, unit='short',
            description='Active approaching max, with idle at zero, is a pool about to queue.'),
        panel('Pool utilisation', [
            (f'hikaricp_connections_active{{{JOB}}} '
             f'/ clamp_min(hikaricp_connections_max{{{JOB}}}, 1)', 'utilisation')],
            12, 0, unit='percentunit',
            thresholds={'mode': 'absolute', 'steps': [
                {'color': 'green', 'value': None}, {'color': 'orange', 'value': 0.8},
                {'color': 'red', 'value': 0.9}]}),

        panel('Threads awaiting a connection', [
            (f'hikaricp_connections_pending{{{JOB}}}', 'pending')],
            0, 8, unit='short',
            description='Anything above zero means request threads are blocked waiting for the pool. '
                        'There is no healthy steady-state value here other than zero.',
            thresholds={'mode': 'absolute', 'steps': [
                {'color': 'green', 'value': None}, {'color': 'red', 'value': 1}]}),
        panel('Connection acquisition time', [
            (f'rate(hikaricp_connections_acquire_seconds_sum{{{JOB}}}[{RATE}]) '
             f'/ clamp_min(rate(hikaricp_connections_acquire_seconds_count{{{JOB}}}[{RATE}]), 0.001)',
             'mean acquire')],
            12, 8, unit='s',
            description='How long borrowing a connection takes. Rises before pending does, so it is '
                        'the earlier warning of the two.'),

        panel('Connection usage time', [
            (f'rate(hikaricp_connections_usage_seconds_sum{{{JOB}}}[{RATE}]) '
             f'/ clamp_min(rate(hikaricp_connections_usage_seconds_count{{{JOB}}}[{RATE}]), 0.001)',
             'mean usage')],
            0, 16, unit='s',
            description='How long a connection is held. A rise here with flat traffic points at a '
                        'slow query or a transaction held open too long.'),
        panel('Connection timeouts', [
            (f'rate(hikaricp_connections_timeout_total{{{JOB}}}[{RATE}])', 'timeouts/s')],
            12, 16, unit='short',
            description='Requests that gave up waiting. These surface to the user as errors.'),

        text_panel('On query-level metrics', (
            'Per-query timings are **not** on this dashboard, because nothing in the application '
            'exports them today. Micrometer does not instrument individual JPA queries, and adding '
            'that means either Hibernate statistics (noticeable overhead, high cardinality) or '
            'tracing (see the deferred Loki/Tempo step in `OBSERVABILITY_PLAN.md`).\n\n'
            'For now, use *connection usage time* above as the proxy: a query that got slower shows '
            'up as a connection held longer. For MySQL internals, the slow query log remains the '
            'right tool.'), 0, 24)
    ],
    ['database'])

# ---------------------------------------------------------------- 5. security

security = dashboard(
    'km-security', 'KashmirMenu — Security Signals',
    'Authentication and authorization signals. These are observations, not verdicts: a spike here '
    'is an anomaly to investigate, not a confirmed attack.',
    [
        text_panel('How to read this dashboard', (
            'Every panel shows a **signal**, not an incident. A rise in failed sign-ins is as often a '
            'released client with a broken token refresh, or a staff member who forgot a password, as '
            'it is an attack.\n\n'
            'Metrics here carry **no identifiers** - no email, IP, user id or token - by design. To '
            'investigate an individual event, use the audit log in the platform admin area, which '
            'records the actor and resource for each action.'), 0, 0, h=4),

        panel('Failed sign-ins / sec', [
            (f'sum by (reason) (rate(app_authentication_failures_total{{{JOB}}}[{RATE}]))',
             '{{reason}}')],
            0, 4, unit='short'),
        panel('Failed sign-ins vs 24h baseline', [
            (f'sum(rate(app_authentication_failures_total{{{JOB}}}[{RATE}]))', 'current'),
            (f'sum(rate(app_authentication_failures_total{{{JOB}}}[24h]))', '24h baseline')],
            12, 4, unit='short',
            description='The ElevatedAuthenticationFailures alert compares these two. Seeing both '
                        'makes it obvious whether a spike is unusual for this deployment.'),

        panel('Authorization refusals (403) / sec', [
            (f'sum by (uri) (rate(http_server_requests_seconds_count{{{JOB},status="403"}}[{RATE}]))',
             '{{uri}}')],
            0, 12, unit='reqps',
            description='Already available from the standard request metric, so no custom counter '
                        'was added. A steady trickle is normal - the API denies by default. A spike '
                        'on one endpoint is worth a look.'),
        panel('Unauthenticated rejections (401) / sec', [
            (f'sum(rate(http_server_requests_seconds_count{{{JOB},status="401"}}[{RATE}]))', '401s')],
            12, 12, unit='reqps',
            description='Expired tokens produce these routinely; a step change usually means a '
                        'deployment or a secret rotation.'),

        panel('Rate-limited requests (429) / sec', [
            (f'sum(rate(http_server_requests_seconds_count{{{JOB},status="429"}}[{RATE}]))', '429s')],
            0, 20, unit='reqps',
            description='The application throttles sign-in, the platform claim and public writes. '
                        'Sustained 429s mean the limiter is doing something.'),
        panel('Server error spikes by endpoint', [
            (f'topk(5, sum by (uri) '
             f'(rate(http_server_requests_seconds_count{{{JOB},status=~"5.."}}[{RATE}])))', '{{uri}}')],
            12, 20, unit='reqps',
            description='An abnormal error spike can be the symptom of probing as easily as of a bug. '
                        'Correlate with the panels above before concluding either.'),
    ],
    ['security'])

# ---------------------------------------------------------------- 6. load testing

load = dashboard(
    'km-load', 'KashmirMenu — Load Testing (k6)',
    'Populated only while a k6 run exports to Prometheus remote write. Empty at all other times, '
    'which is expected.',
    [
        text_panel('When this dashboard has data', (
            'These panels read metrics written by **k6**, not by the application, and only appear '
            'while a test is running with remote write enabled:\n\n'
            '```\n'
            'k6 run --out experimental-prometheus-rw k6/baseline.js\n'
            '```\n\n'
            'Between runs the panels are empty. That is correct, not a broken dashboard - no '
            'placeholder series are generated to fill the space.\n\n'
            'k6\'s Prometheus remote-write output is an **experimental** k6 feature. See '
            '`PERFORMANCE_TESTING_GUIDE.md` for the setup and for why the thresholds are provisional.'),
            0, 0, h=5),

        panel('Virtual users', [('k6_vus', 'VUs'), ('k6_vus_max', 'max VUs')], 0, 5, unit='short'),
        panel('Requests / sec', [
            (f'sum(rate(k6_http_reqs_total[{RATE}]))', 'rps')], 12, 5, unit='reqps'),

        panel('Request duration percentiles', [
            ('k6_http_req_duration_p95', 'p95'),
            ('k6_http_req_duration_p99', 'p99'),
            ('k6_http_req_duration_avg', 'avg')],
            0, 13, unit='ms',
            description='k6 computes these itself and exports them as gauges, which is why they are '
                        'read directly rather than through histogram_quantile.'),
        panel('Failed request rate', [
            (f'sum(rate(k6_http_req_failed_total[{RATE}])) '
             f'/ clamp_min(sum(rate(k6_http_reqs_total[{RATE}])), 0.001)', 'failure ratio')],
            12, 13, unit='percentunit',
            thresholds={'mode': 'absolute', 'steps': [
                {'color': 'green', 'value': None}, {'color': 'orange', 'value': 0.01},
                {'color': 'red', 'value': 0.05}]}),

        panel('Application latency during the test', [
            (f'histogram_quantile(0.95, sum by (le) '
             f'(rate(http_server_requests_seconds_bucket{{{JOB}}}[{RATE}])))', 'server p95')],
            0, 21, unit='s',
            description='The server\'s own view, next to k6\'s client-side view above. A gap between '
                        'them is network or client overhead rather than application slowness.'),
        panel('Application resources during the test', [
            (f'process_cpu_usage{{{JOB}}}', 'cpu'),
            (f'sum(jvm_memory_used_bytes{{{JOB},area="heap"}}) '
             f'/ clamp_min(sum(jvm_memory_max_bytes{{{JOB},area="heap"}}), 1)', 'heap utilisation')],
            12, 21, unit='percentunit',
            description='What the load cost the application. The point of a load test is this panel, '
                        'not the throughput number.'),
    ],
    ['load-testing', 'k6'])


DASHBOARDS = {
    'overview.json': overview,
    'jvm.json': jvm,
    'api.json': api,
    'database.json': database,
    'security.json': security,
    'load-testing.json': load,
}

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    for name, d in DASHBOARDS.items():
        (OUT / name).write_text(json.dumps(d, indent=2) + '\n', encoding='utf-8')
        panels = [p for p in d['panels'] if p['type'] != 'text']
        queries = sum(len(p.get('targets', [])) for p in panels)
        print(f'  {name:<22} {len(d["panels"]):>2} panels, {queries:>2} queries')
    print(f'\nwrote {len(DASHBOARDS)} dashboards to {OUT}')
