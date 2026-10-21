#!/usr/bin/env python3
"""
Checks every PromQL expression in the dashboards and alert rules against the metric names this
application will actually export.

This is the check that matters most for a dashboard, and the one that is easiest to skip: a panel
querying `jvm_memory_used` instead of `jvm_memory_used_bytes` looks perfectly fine in review and
renders as "No data" forever. Nothing here can run Prometheus, so the metric names are compared
against an explicit inventory of what spring-boot-starter-actuator plus
micrometer-registry-prometheus register for this dependency set, plus the one custom counter.

Also checks the two PromQL mistakes that silently produce wrong numbers rather than no numbers:
  - histogram_quantile over a sum that does not group by `le`
  - a bare division that can divide by zero when traffic is idle (no clamp_min)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path('/home/claude/kashmirmenu')

# ---------------------------------------------------------------- expected metrics

# Exported by Boot's JVM/system binders, which activate as soon as a MeterRegistry bean exists.
JVM = {
    'jvm_memory_used_bytes', 'jvm_memory_max_bytes', 'jvm_memory_committed_bytes',
    'jvm_gc_pause_seconds_count', 'jvm_gc_pause_seconds_sum', 'jvm_gc_pause_seconds_max',
    'jvm_threads_live_threads', 'jvm_threads_daemon_threads', 'jvm_threads_peak_threads',
    'jvm_classes_loaded_classes',
    'process_cpu_usage', 'system_cpu_usage', 'process_uptime_seconds', 'process_start_time_seconds',
}

# From the Spring MVC observation instrumentation. The _bucket series exist only because
# percentiles-histogram is enabled for http.server.requests in application.yaml — asserted below.
HTTP = {
    'http_server_requests_seconds_count', 'http_server_requests_seconds_sum',
    'http_server_requests_seconds_max', 'http_server_requests_seconds_bucket',
}

# Registered by Boot's HikariCP metrics, again contingent only on a registry being present.
HIKARI = {
    'hikaricp_connections', 'hikaricp_connections_active', 'hikaricp_connections_idle',
    'hikaricp_connections_pending', 'hikaricp_connections_max', 'hikaricp_connections_min',
    'hikaricp_connections_timeout_total',
    'hikaricp_connections_acquire_seconds_count', 'hikaricp_connections_acquire_seconds_sum',
    'hikaricp_connections_usage_seconds_count', 'hikaricp_connections_usage_seconds_sum',
    'hikaricp_connections_creation_seconds_count', 'hikaricp_connections_creation_seconds_sum',
}

# Prometheus' own series.
PROM = {'up', 'scrape_duration_seconds', 'scrape_samples_scraped'}

# Written by k6, not by the application. Present only during a run with remote write enabled, so
# panels using these are expected to be empty otherwise.
K6 = {
    'k6_vus', 'k6_vus_max', 'k6_http_reqs_total', 'k6_http_req_failed_total',
    'k6_http_req_duration_avg', 'k6_http_req_duration_p95', 'k6_http_req_duration_p99',
    'k6_iterations_total', 'k6_data_sent_total', 'k6_data_received_total',
}

# The application's own metric. Named here so a typo in either place is caught.
# Every custom counter, with where it is registered. A name queried but not registered here renders as
# "No data" for ever, so the check below compares each one against the Java that creates it.
CUSTOM_REGISTRATION = {
    'app_authentication_failures_total': ('app.authentication.failures', 'AuthenticationMetrics.java'),
    'app_lock_retries_total': ('app.lock.retries', 'FloorMetrics.java'),
    'app_lock_failures_total': ('app.lock.failures', 'FloorMetrics.java'),
    'app_settlements_started_total': ('app.settlements.started', 'FloorMetrics.java'),
    'app_table_sessions_closed_total': ('app.table_sessions.closed', 'FloorMetrics.java'),
}
CUSTOM = set(CUSTOM_REGISTRATION)

EXPECTED = JVM | HTTP | HIKARI | PROM | K6 | CUSTOM

PROMQL_FUNCTIONS = {
    'rate', 'irate', 'increase', 'sum', 'avg', 'min', 'max', 'count', 'topk', 'bottomk',
    'histogram_quantile', 'clamp_min', 'clamp_max', 'by', 'without', 'le', 'on', 'ignoring',
    'group_left', 'group_right', 'label_replace', 'absent', 'delta', 'idelta', 'quantile',
    'stddev', 'stdvar', 'round', 'ceil', 'floor', 'abs', 'sum_over_time', 'avg_over_time',
    'max_over_time', 'min_over_time', 'count_over_time', 'last_over_time', 'and', 'or', 'unless',
}


def metric_names(expr):
    """
    Identifiers used as metric selectors.

    Everything that is not a metric has to be removed first, or the check drowns in false positives:
    Grafana macros ($__rate_interval) lose their sigil once tokenized, label matchers inside {} are
    not metrics, and the label lists in `by (uri, status)` are label names. Removing them in this
    order is what makes the remaining identifiers actually metric names.
    """
    stripped = expr
    stripped = re.sub(r'\$__[a-zA-Z_]+', ' ', stripped)          # Grafana macros
    stripped = re.sub(r'\{[^}]*\}', ' ', stripped)               # label matchers
    stripped = re.sub(r'\[[^\]]*\]', ' ', stripped)              # range selectors
    stripped = re.sub(r'\{\{[^}]*\}\}', ' ', stripped)           # legend placeholders
    # Label lists attached to an aggregation: `by (le, uri)` / `without (instance)`.
    stripped = re.sub(r'\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)',
                      ' ', stripped)

    found = set()
    for token in re.findall(r'\b([a-zA-Z_][a-zA-Z0-9_]*)\b', stripped):
        if token in PROMQL_FUNCTIONS or token.isdigit():
            continue
        found.add(token)
    return found


def expressions():
    """[(source, expr)] from every dashboard panel and every alert rule."""
    out = []
    for f in sorted((ROOT / 'grafana/dashboards').glob('*.json')):
        d = json.loads(f.read_text(encoding='utf-8'))
        for p in d['panels']:
            for t in p.get('targets', []):
                out.append((f'{f.name}:{p["title"]}', t['expr']))

    import yaml
    for f in sorted((ROOT / 'prometheus/rules').glob('*.yml')):
        d = yaml.safe_load(f.read_text(encoding='utf-8'))
        for g in d['groups']:
            for r in g['rules']:
                out.append((f'{f.name}:{r["alert"]}', r['expr']))
    return out


def main():
    problems = []
    exprs = expressions()
    print(f'== PromQL: {len(exprs)} expressions from dashboards and alert rules ==')

    used = set()
    for source, expr in exprs:
        names = metric_names(expr)
        used |= names
        for name in sorted(names):
            if name not in EXPECTED:
                problems.append(f'{source}: unknown metric "{name}"')
                print(f'   [!] {source}: queries "{name}", which nothing exports')

        # histogram_quantile needs `le` preserved by the inner aggregation, or it returns a number
        # that looks plausible and is meaningless.
        for m in re.finditer(r'histogram_quantile\s*\(', expr):
            tail = expr[m.start():]
            if 'by (le' not in tail and 'by(le' not in tail:
                problems.append(f'{source}: histogram_quantile without a "by (le" grouping')
                print(f'   [!] {source}: histogram_quantile does not group by le')

        # A ratio with an unguarded denominator spikes to +Inf when traffic is idle.
        if '/' in expr and 'clamp_min' not in expr and 'histogram_quantile' not in expr:
            if re.search(r'/\s*(sum|rate|increase|clamp)', expr) or re.search(r'/\s*[a-z_]+\{', expr):
                problems.append(f'{source}: division without clamp_min on the denominator')
                print(f'   [!] {source}: divides without clamp_min - will spike when idle')

    print(f'   {len(used)} distinct metric names referenced')

    # The _bucket series exist only because the histogram is switched on in configuration.
    app_yaml = (ROOT / 'src/main/resources/application.yaml').read_text(encoding='utf-8')
    if 'http_server_requests_seconds_bucket' in used:
        if 'percentiles-histogram' not in app_yaml:
            problems.append('dashboards use _bucket series but percentiles-histogram is not enabled')
            print('   [!] _bucket series queried but percentiles-histogram is not configured')
        else:
            print('   _bucket series are backed by percentiles-histogram in application.yaml')

    # Each custom counter that is queried must actually be registered under the name it is queried by.
    metrics_dir = ROOT / 'src/main/java/com/kashmirmenu/app/metrics'
    for prom_name, (dotted, java_file) in sorted(CUSTOM_REGISTRATION.items()):
        if prom_name not in used:
            continue
        if dotted not in (metrics_dir / java_file).read_text(encoding='utf-8'):
            problems.append(f'{prom_name} is queried but {dotted!r} is never registered in {java_file}')
            print(f'   [!] custom counter {prom_name} does not match {java_file}')
        else:
            print(f'   custom counter {dotted} matches {java_file}')

    # Metrics exported but never looked at: not an error, but worth knowing.
    unused = (JVM | HTTP | HIKARI | CUSTOM) - used
    if unused:
        print(f'   note: {len(unused)} available metric(s) are not on any dashboard')

    print()
    print('=' * 70)
    print(f'{len(problems)} problem(s) found' if problems else 'No PromQL problems found.')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
