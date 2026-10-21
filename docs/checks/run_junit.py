#!/usr/bin/env python3
"""
Runs the project's REAL JUnit test classes that need no framework, using a small stand-in for the JUnit
API (docs/checks/junit_shim). Nothing is rewritten: the very same *Test.java files Gradle will run are
compiled with the JDK's own compiler against the real main sources and executed.

For each src/test/**/*Test.java it works out which main classes the test (transitively) uses. If any of
them needs Spring, JPA, Lombok, Jackson, Micrometer, jjwt, ... the test cannot run here and is reported
as skipped, with the dependency that blocked it. The *IT.java integration tests are never attempted.

This is real compilation and execution, but of pure code only: it does not start Spring, touch a
database, or exercise the security chain.
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MAIN = ROOT / 'src/main/java'
TEST = ROOT / 'src/test/java'
SHIM = HERE / 'junit_shim'

FRAMEWORK = re.compile(r'^\s*import\s+(?:static\s+)?(org\.springframework|jakarta\.|lombok|io\.jsonwebtoken|org\.hibernate|'
                       r'com\.fasterxml|org\.mapstruct|io\.micrometer|org\.slf4j|com\.google|org\.thymeleaf|org\.springdoc|'
                       r'io\.swagger|net\.logstash|javax\.(persistence|servlet|validation|annotation|inject|transaction|sql))', re.M)


def strip(s):
    """Comments AND string literals removed, so a word inside a message is not mistaken for a class."""
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'"""(.*?)"""', '""', s, flags=re.S)
    s = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', s)
    return re.sub(r'(?<!:)//[^\n]*', '', s)


def index(root):
    out = {}
    for p in sorted(root.glob('**/*.java')):
        out[p.stem] = p
    return out


def closure(start, main_idx, test_idx):
    seen, order, blocked = {}, [], None
    stack = [start]
    while stack:
        f = stack.pop()
        if f in seen:
            continue
        seen[f] = True
        order.append(f)
        raw = f.read_text(encoding='utf-8')
        m = FRAMEWORK.search(raw)
        if m and f != start and blocked is None:
            blocked = f'{f.name} imports {m.group(1)}'
        elif m and f == start and blocked is None and 'org.springframework' in raw:
            blocked = f'{f.name} imports {m.group(1)}'
        for ident in set(re.findall(r'\b[A-Z]\w*\b', strip(raw))):
            for idx in (main_idx, test_idx):
                if ident in idx and idx[ident] not in seen and idx[ident] != f:
                    stack.append(idx[ident])
    return order, blocked


def fqcn(path):
    """The class's real name: its DECLARED package, not the folder it happens to sit in."""
    m = re.search(r'^\s*package\s+([\w.]+)\s*;', path.read_text(encoding='utf-8'), flags=re.M)
    return (m.group(1) + '.' if m else '') + path.stem


def main():
    if not shutil.which('java'):
        print('java not found'); return 1
    jc = ['java', '--module', 'jdk.compiler/com.sun.tools.javac.Main']
    shim_dir = Path(tempfile.mkdtemp())
    shim_src = [str(p) for p in SHIM.glob('**/*.java')]
    r = subprocess.run(jc + ['-d', str(shim_dir)] + shim_src, capture_output=True, text=True)
    if r.returncode:
        print('shim did not compile\n' + r.stderr); return 1

    main_idx, test_idx = index(MAIN), index(TEST)
    only = sys.argv[1:]   # optional name filters, e.g. `run_junit.py ImageProcessorTest`
    tests = sorted(p for p in TEST.glob('**/*Test.java') if not only or any(o in p.name for o in only))
    ran, skipped, failed, total_pass = [], [], [], 0
    for t in tests:
        order, blocked = closure(t, main_idx, test_idx)
        if blocked:
            skipped.append((t.name, blocked))
            continue
        out = Path(tempfile.mkdtemp())
        c = subprocess.run(jc + ['-cp', str(shim_dir), '-d', str(out)] + [str(f) for f in order],
                           capture_output=True, text=True)
        if c.returncode:
            failed.append((t.name, 'DID NOT COMPILE: ' + c.stderr.strip().splitlines()[0][:150]))
            continue
        run = subprocess.run(['java', '-cp', f'{out}:{shim_dir}', 'shimrunner.Run', fqcn(t)],
                             capture_output=True, text=True)
        last = (run.stdout.strip().splitlines() or ['(no output)'])[-1]
        if run.returncode == 0:
            n = int(re.match(r'RESULT (\d+)', last).group(1))
            total_pass += n
            ran.append((t.name, n))
        else:
            fails = [l for l in run.stdout.splitlines() if l.startswith('FAILED')]
            failed.append((t.name, '; '.join(fails)[:300] or run.stderr[:300]))

    print('== Real JUnit test classes (no Spring), compiled and executed ==')
    for name, n in ran:
        print(f'   ok   {name}: {n} tests')
    for name, why in failed:
        print(f'   [!]  {name}: {why}')
    for name, why in skipped:
        print(f'   skip {name}: needs a framework ({why})')
    print(f'   {total_pass} tests passed in {len(ran)} classes; {len(skipped)} classes need Spring/other libraries (not run); '
          f'{len(failed)} failed')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
