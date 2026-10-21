#!/usr/bin/env python3
"""
Checks JSX call sites against the component definitions in frontend/src:

  * a REQUIRED prop that a call site leaves out        (TS error: property is missing)
  * a prop the component does not declare              (TS error: property does not exist)

Components are found by `function Name({ a, b }: { a: T; b?: U })` or `: SomeProps` / `interface SomeProps`.
Spread attributes ({...x}) switch the missing-prop check off for that call site, since they may supply
anything. `children` is judged only when the component declares it. Calibrated against the original
frontend first: it must report nothing there.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'frontend/src'


def strip(s: str) -> str:
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'(?<![:\'"`])//[^\n]*', '', s)


def balanced(text: str, i: int, open_ch: str, close_ch: str):
    """text[i] == open_ch; -> (inner text, index after the closer)"""
    d, j = 0, i
    while j < len(text):
        if text[j] == open_ch:
            d += 1
        elif text[j] == close_ch:
            d -= 1
            if d == 0:
                return text[i + 1:j], j + 1
        j += 1
    return text[i + 1:], len(text)


def split_members(body: str):
    """Members of an object type literal: split on ; or , or newline at depth 0."""
    out, cur, d, prev = [], [], 0, ''
    for ch in body:
        if ch in '({[<':
            d += 1
        elif ch in ')}]' or (ch == '>' and prev != '='):   # '=>' is an arrow, not a closing bracket
            d -= 1
        prev = ch
        if ch in ';,\n' and d <= 0:
            if ''.join(cur).strip():
                out.append(''.join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        out.append(''.join(cur).strip())
    return out


def parse_props(type_body: str):
    props = {}
    for mem in split_members(type_body):
        mem = re.sub(r'^/\*.*?\*/\s*', '', mem)
        m = re.match(r'(?:readonly\s+)?(\w+)(\?)?\s*:', mem)
        if m:
            props[m.group(1)] = bool(m.group(2))     # name -> optional
        elif mem.startswith('...') or mem.startswith('['):
            props['*'] = True                        # index signature / spread: anything goes
    return props


def components(files):
    comps, interfaces = {}, {}
    for f in files:
        src = strip(f.read_text(encoding='utf-8'))
        for m in re.finditer(r'\b(?:interface|type)\s+(\w+)\s*(?:=\s*)?(?:extends [^{]+)?\{', src):
            body, _ = balanced(src, m.end() - 1, '{', '}')
            interfaces[m.group(1)] = parse_props(body)
    for f in files:
        src = strip(f.read_text(encoding='utf-8'))
        for m in re.finditer(r'\bfunction\s+([A-Z]\w*)\s*(?:<[^>]*>)?\s*\(', src):
            name = m.group(1)
            inner, end = balanced(src, m.end() - 1, '(', ')')
            inner = inner.strip()
            if not inner:
                comps.setdefault(name, {})
                continue
            tm = re.search(r'^(\{.*?\})\s*:\s*(.+)$', inner, flags=re.S)
            if tm and tm.group(1).startswith('{'):
                # destructured props with a type annotation after the pattern; find the pattern's end
                pat, after = balanced(inner, 0, '{', '}')
                rest = inner[after:].lstrip()
                rest = rest[1:].strip() if rest.startswith(':') else ''
                if rest.startswith('{'):
                    body, _ = balanced(rest, 0, '{', '}')
                    comps[name] = parse_props(body)
                elif re.match(r'\w+$', rest):
                    comps[name] = dict(interfaces.get(rest, {'*': True}))
                else:
                    comps[name] = {'*': True}
            else:
                pm = re.match(r'\w+\s*:\s*(\w+)\s*$', inner)
                comps[name] = dict(interfaces.get(pm.group(1), {'*': True})) if pm else {'*': True}
    return comps


def call_sites(src: str):
    for m in re.finditer(r'<([A-Z]\w*)\b', src):
        name, i = m.group(1), m.end()
        attrs, j, spread = {}, i, False
        # read attributes until '>' or '/>' at depth 0
        while j < len(src):
            while j < len(src) and src[j].isspace():
                j += 1
            if src.startswith('/>', j) or src[j] == '>':
                break
            if src[j] == '{':
                inner, j = balanced(src, j, '{', '}')
                if inner.strip().startswith('...'):
                    spread = True
                continue
            am = re.match(r'([\w-]+)', src[j:])
            if not am:
                break
            attr = am.group(1)
            j += len(attr)
            attrs[attr] = True
            while j < len(src) and src[j].isspace():
                j += 1
            if j < len(src) and src[j] == '=':
                j += 1
                while j < len(src) and src[j].isspace():
                    j += 1
                if src[j] == '{':
                    _, j = balanced(src, j, '{', '}')
                elif src[j] in '"\'':
                    q = src[j]
                    j = src.index(q, j + 1) + 1
        selfclosing = src.startswith('/>', j)
        yield name, attrs, spread, selfclosing, src.count('\n', 0, m.start()) + 1


def main():
    files = sorted(p for p in SRC.rglob('*.tsx')) + sorted(p for p in SRC.rglob('*.ts') if not p.name.endswith('.d.ts'))
    comps = components(files)
    bad, sites = [], 0
    for f in sorted(SRC.rglob('*.tsx')):
        src = strip(f.read_text(encoding='utf-8'))
        for name, attrs, spread, selfclosing, line in call_sites(src):
            spec = comps.get(name)
            if spec is None:
                continue      # a library component (Link, Routes, ...) or defined another way
            sites += 1
            if '*' in spec:
                continue
            where = f'{f.relative_to(SRC)}:{line}'
            for a in attrs:
                if a in ('key', 'ref') or a.startswith('aria-') or a.startswith('data-'):
                    continue
                if a not in spec:
                    bad.append(f'{where}: <{name}> has no prop "{a}"')
            if not spread:
                for p, optional in spec.items():
                    if not optional and p != 'children' and p not in attrs:
                        bad.append(f'{where}: <{name}> is missing required prop "{p}"')
    print('== JSX call sites vs component props ==')
    print(f'   {len(comps)} components defined, {sites} call sites checked')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   every call site passes the props its component declares')
    return 0


if __name__ == '__main__':
    sys.exit(main())
