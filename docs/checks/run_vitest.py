#!/usr/bin/env python3
"""
Runs the frontend's PURE test files (*.test.ts) with a small stand-in for vitest (docs/checks/vitest_shim)
on Node's built-in TypeScript stripping. The same files `npm test` will run, unmodified.

Files that need vitest's mocking (vi.*), the DOM, or browser storage are skipped and named. This runs
logic only: it does not type-check (tsc is not available), render a component, or call the server.
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE.parents[1] / 'frontend/src'
SHIM = HERE / 'vitest_shim'


def main():
    if not shutil.which('node'):
        print('node not found'); return 1
    ran, skipped, failed, total = [], [], [], 0
    for f in sorted(SRC.rglob('*.test.ts')):
        text = f.read_text(encoding='utf-8')
        if re.search(r'\bvi\.|localStorage|sessionStorage|document\.|window\.|beforeEach\(|fetch\(', text):
            skipped.append(f.name)
            continue
        r = subprocess.run(['node', '--experimental-strip-types', '--no-warnings', '--import', str(SHIM / 'register.mjs'),
                            str(SHIM / 'run.mjs'), str(f)], capture_output=True, text=True, cwd=str(SHIM))
        last = (r.stdout.strip().splitlines() or [r.stderr.strip().splitlines()[-1] if r.stderr.strip() else '(no output)'])[-1]
        m = re.match(r'RESULT (\d+) passed, (\d+) failed', last)
        if r.returncode == 0 and m:
            n = int(m.group(1)); total += n; ran.append((f.name, n))
        else:
            fails = [l for l in r.stdout.splitlines() if l.startswith('FAILED')]
            failed.append((f.name, '; '.join(fails)[:300] or r.stderr.strip()[-300:]))
    print('== Real frontend test files (pure), executed with a vitest stand-in ==')
    for n, c in ran:
        print(f'   ok   {n}: {c} tests')
    for n, why in failed:
        print(f'   [!]  {n}: {why}')
    for n in skipped:
        print(f'   skip {n}: needs vi mocks, the DOM or browser storage')
    print(f'   {total} tests passed in {len(ran)} files; {len(skipped)} skipped; {len(failed)} failed')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
