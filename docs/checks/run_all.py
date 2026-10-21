#!/usr/bin/env python3
"""
One command, every check that can run without Gradle, Docker, MySQL or npm:

    python3 docs/checks/run_all.py

Each check is a separate script and prints its own findings. This runs them in order and gives one
verdict. Exit code 0 only if every check passed.

WHAT THIS IS NOT. None of this compiles the Java, runs Spring, talks to MySQL, or type-checks the
TypeScript. It removes whole classes of mistakes (wrong names, wrong arguments, schema drift, broken
URLs, mismatched JSON, broken props, wrong arithmetic) so that the first real build is far less likely
to fail on something trivial. It does not replace `./gradlew test`, `./gradlew mysqlTest`, or
`npm run typecheck && npm test`: run those as soon as a machine is available.
"""
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DOCS = HERE.parent

CHECKS = [
    # (label, script, what it proves)
    ('Pure logic, executed', HERE / 'run_pure.py', 'pricing, charges, permissions, lifecycle, popularity, helpers'),
    ('Real JUnit classes', HERE / 'run_junit.py', 'the project\'s own pure *Test.java files, compiled and executed'),
    ('Real frontend tests', HERE / 'run_vitest.py', 'the project\'s pure *.test.ts files, executed'),
    ('Photo-link parity', HERE / 'link_parity_check.py', 'server and screen agree on which photo links are valid'),
    ('Migrations on SQLite', HERE / 'migrate_sqlite.py', 'all Flyway files are well formed and ordered'),
    ('Entities vs schema', HERE / 'entity_schema_check.py', 'ddl-auto=validate would find every table and column'),
    ('New-table behaviour', HERE / 'schema_behaviour_check.py', 'V34 to V40 constraints, cascades, defaults'),
    ('Spring Data queries', HERE / 'query_check.py', 'every JPQL path and derived method resolves'),
    ('HTTP contract', HERE / 'api_contract_check.py', 'every frontend and test URL has a controller'),
    ('JSON field contract', HERE / 'dto_contract_check.py', 'TypeScript types match the Java responses'),
    ('JSON keys in tests', HERE / 'json_key_check.py', 'no typo in a test body or read'),
    ('Cross-class calls', HERE / 'call_check.py', 'method names/arity, static calls, builders, lambdas'),
    ('JSX props', HERE / 'jsx_props_check.py', 'components get the props they declare'),
    ('English-only display', HERE / 'locale_check.py', 'no date, time or number follows the device language'),
    ('Print rules', HERE / 'print_scope_check.py', 'no second unscoped print rule that could blank another page'),
    ('Unused imports', HERE / 'unused_imports_check.py', 'no import would fail noUnusedLocals'),
    ('Static-import shadowing', HERE / 'static_shadow_check.py', 'no helper hides a static import (a compile error)'),
    # the project's own checkers (they expect the repo at /home/claude/kashmirmenu)
    ('Imports (project)', DOCS / 'imports_check.py', 'every type name resolves to an import'),
    ('Accessors (project)', DOCS / 'accessor_check.py', 'Lombok getters/setters exist'),
    ('Arity (project)', DOCS / 'arity_check.py', 'service calls: name and argument count'),
    ('Repository calls (project)', DOCS / 'repo_calls_check.py', 'repository methods exist'),
    ('Paging (project)', DOCS / 'paging_check.py', 'no collection fetch-join with Pageable'),
    ('Authorization (project)', DOCS / 'authz_check.py', 'every endpoint has an intended role set'),
    ('Config guard (project)', DOCS / 'config_guard_check.py', 'configured secrets pass their own guard'),
]


def main():
    rows, failed = [], 0
    for label, script, proves in CHECKS:
        if not script.exists():
            rows.append((label, 'SKIP', 'script not found', 0))
            continue
        if script.parent == DOCS and not Path('/home/claude/kashmirmenu').exists():
            rows.append((label, 'SKIP', 'project checker expects the repo at /home/claude/kashmirmenu', 0))
            continue
        t0 = time.time()
        r = subprocess.run([sys.executable, str(script)], capture_output=True, text=True, cwd=str(HERE))
        secs = time.time() - t0
        text = r.stdout + r.stderr
        bad = r.returncode != 0 or '[!]' in text
        rows.append((label, 'FAIL' if bad else 'ok', proves, secs))
        if bad:
            failed += 1
            print(f'--- {label}: output ---')
            print('\n'.join(text.strip().splitlines()[-25:]))
            print()
    w = max(len(r[0]) for r in rows)
    print('=' * (w + 60))
    for label, status, proves, secs in rows:
        print(f'  {status:<4}  {label:<{w}}  {proves}' + (f'  ({secs:.1f}s)' if secs else ''))
    print('=' * (w + 60))
    ran = sum(1 for r in rows if r[1] != 'SKIP')
    print(f'  {ran - failed}/{ran} checks passed' + (f', {sum(1 for r in rows if r[1] == "SKIP")} skipped' if any(r[1] == 'SKIP' for r in rows) else ''))
    print('  Reminder: this is static evidence. It is not a build. Run gradlew test / mysqlTest / npm test next.')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
