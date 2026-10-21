#!/usr/bin/env python3
"""
Resolves every HTTP call against the controllers that serve it.

  * Frontend: each `http.get/post/put/patch/delete(`/api/...`)` in frontend/src/api/*.ts
  * Integration tests: each get/post/put/delete("/api/...") in src/test/java/**/*IT.java

A call whose method and path match no controller mapping is a guaranteed 404 (or 405): a screen that
never loads, a test that fails for the wrong reason. Path variables and string concatenation are
normalised, so `/api/menu-items/${id}/options` matches `/api/menu-items/{menuItemId}/options`.
Calibrated against the original controllers and calls: they must all resolve.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java'
TESTS = ROOT / 'src/test/java'
FRONT = ROOT / 'frontend/src/api'

METHODS = {'GetMapping': 'GET', 'PostMapping': 'POST', 'PutMapping': 'PUT', 'DeleteMapping': 'DELETE',
           'PatchMapping': 'PATCH'}


def paths_of(args: str):
    """The path strings in a mapping annotation's argument text (value=, path=, or positional)."""
    return re.findall(r'"([^"]*)"', args) or ['']


def controllers():
    routes = []   # (METHOD, regex, description)
    for p in sorted(JAVA.glob('**/*.java')):
        src = re.sub(r'/\*.*?\*/', '', p.read_text(encoding='utf-8'), flags=re.S)
        src = re.sub(r'(?<!:)//[^\n]*', '', src)
        if not re.search(r'@(Rest)?Controller\b', src):
            continue
        cm = re.search(r'@RequestMapping\s*\((.*?)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+class', src, flags=re.S)
        bases = paths_of(cm.group(1)) if cm else ['']
        # also a class-level @RequestMapping that sits above other annotations
        if not cm:
            cm2 = re.search(r'@RequestMapping\s*\(([^)]*)\)', src[:src.find('class ')])
            bases = paths_of(cm2.group(1)) if cm2 else ['']
        for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(([^)]*)\))?', src):
            method = METHODS[m.group(1) + 'Mapping']
            subs = paths_of(m.group(2) or '')
            for b in bases:
                for s in subs:
                    full = ('/' + b.strip('/') + '/' + s.strip('/')).replace('//', '/').rstrip('/') or '/'
                    rx = re.sub(r'\{[^/}]+\}', '[^/]+', full)
                    routes.append((method, re.compile('^' + rx + '$'), f'{p.name} {full}'))
    return routes


def match(routes, method, path):
    path = path.split('?')[0].rstrip('/') or '/'
    if 'X' not in path:
        return [r for r in routes if r[0] == method and r[1].match(path)]
    # A segment that is (or contains) a dynamic value may stand for anything, even a literal segment
    # such as /confirm or /preparing in a route that is chosen at runtime.
    segs = path.split('/')
    found = []
    for r in routes:
        if r[0] != method:
            continue
        rsegs = r[2].split(' ', 1)[1].split('/')
        if len(rsegs) != len(segs):
            continue
        if all('X' in a or (b.startswith('{') and b.endswith('}')) or a == b for a, b in zip(segs, rsegs)):
            found.append(r)
    return found


def norm_template(s: str) -> str:
    return re.sub(r'\$\{[^}]*\}', 'X', s)


def frontend_calls():
    calls = []
    for p in sorted(FRONT.glob('*.ts')):
        if p.name.endswith('.test.ts'):
            continue   # tests of the client use made-up paths on purpose
        src = p.read_text(encoding='utf-8')
        for m in re.finditer(r'http\.(get|post|put|patch|delete)\s*(?:<[^()]*?>)?\s*\(\s*([`\'"])(/api/[^`\'"]*)\2', src):
            line = src.count('\n', 0, m.start()) + 1
            calls.append((m.group(1).upper(), norm_template(m.group(3)), f'{p.name}:{line}'))
    return calls


def first_argument(src: str, i: int):
    """Text of the first argument of a call whose '(' is just before index i; stops at a depth-0 ',' or ')'."""
    depth, j, quote = 0, i, None
    while j < len(src):
        ch = src[j]
        if quote:
            if ch == '\\':
                j += 2
                continue
            if ch == quote:
                quote = None
        elif ch == '"':
            quote = '"'
        elif ch in '([{':
            depth += 1
        elif ch in ')]}':
            if depth == 0:
                return src[i:j]
            depth -= 1
        elif ch == ',' and depth == 0:
            return src[i:j]
        j += 1
    return src[i:]


def to_path(expr: str):
    """"/api/a/" + id + "/b" -> /api/a/X/b ; any non-literal operand becomes X. None if not a literal path."""
    parts, depth, quote, cur = [], 0, None, []
    for ch in expr:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch == '"':
            quote = '"'
        elif ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        elif ch == '+' and depth == 0:
            parts.append(''.join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    parts.append(''.join(cur).strip())
    out = []
    for part in parts:
        m = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', part)
        out.append(m.group(1) if m else 'X')
    path = ''.join(out)
    return path if path.startswith('/api/') else None


def test_calls():
    calls = []
    for p in sorted(TESTS.glob('**/*.java')):
        src = p.read_text(encoding='utf-8')
        # MockMvc's multipart(...) builder is a POST; the others are named for their verb.
        for m in re.finditer(r'(?<![\w.])(get|post|put|delete|patch|multipart)\s*\(', src):
            arg = first_argument(src, m.end())
            path = to_path(arg)
            if path:
                line = src.count('\n', 0, m.start()) + 1
                verb = 'POST' if m.group(1) == 'multipart' else m.group(1).upper()
                calls.append((verb, path, f'{p.name}:{line}'))
    return calls


# Pre-existing calls that deliberately (or harmlessly) have no matching route. Listed, not hidden: new
# code must resolve, and these are printed on every run so nobody forgets them.
KNOWN = {
    ('AuthorizationBoundaryIT.java', 'DELETE', '/api/menu-items/X'):
        'a customer must be refused (403); security answers before routing, so no DELETE mapping is needed',
    ('AuthorizationBoundaryIT.java', 'GET', '/api/v1/does-not-exist-yet'):
        'a deliberately unmapped path, to prove deny-by-default',
}


def main():
    routes = controllers()
    bad = []
    fc, tc = frontend_calls(), test_calls()
    known_seen = []
    for kind, calls in (('frontend', fc), ('tests', tc)):
        for method, path, where in calls:
            if not match(routes, method, path):
                key = (where.split(':')[0], method, path)
                if key in KNOWN:
                    known_seen.append((where, method, path, KNOWN[key]))
                else:
                    bad.append(f'{kind}: {where}: {method} {path} matches no controller mapping')
    print('== HTTP calls vs controller mappings ==')
    print(f'   {len(routes)} mapped routes; {len(fc)} frontend calls and {len(tc)} test calls resolved')
    for where, method, path, why in known_seen:
        print(f'   (known) {where} {method} {path}: {why}')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   every other call resolves to a controller route')
    return 0


if __name__ == '__main__':
    sys.exit(main())
