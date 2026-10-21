#!/usr/bin/env python3
"""
Finds imports in frontend/src that are never used. tsconfig has noUnusedLocals, so ONE unused import
fails `npm run build` for the whole app. Without tsc available this is the closest cheap guard.

Handles default, named (with `type` and `as`) and namespace imports. A name counts as used when it
appears anywhere in the file outside the import statements, comments and string literals.
"""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / 'frontend/src'


def body_without_imports(s: str) -> str:
    s = re.sub(r'^import[\s\S]*?from\s+[\'"][^\'"]+[\'"];?\n', '', s, flags=re.M)
    s = re.sub(r'^import\s+[\'"][^\'"]+[\'"];?\n', '', s, flags=re.M)
    s = re.sub(r'/\*[\s\S]*?\*/', '', s)
    s = re.sub(r'(?<![:\'"`])//[^\n]*', '', s)
    return s


def imported_names(s: str):
    for m in re.finditer(r'^import\s+(type\s+)?([\s\S]*?)\s+from\s+[\'"][^\'"]+[\'"];?', s, flags=re.M):
        spec = m.group(2)
        d = re.match(r'^([A-Za-z_$][\w$]*)\s*(,|$)', spec)
        if d:
            yield d.group(1)
        b = re.search(r'\{([\s\S]*?)\}', spec)
        if b:
            for part in b.group(1).split(','):
                part = re.sub(r'^\s*type\s+', '', part.strip())
                if part:
                    yield part.split(' as ')[-1].strip()
        ns = re.search(r'\*\s+as\s+([\w$]+)', spec)
        if ns:
            yield ns.group(1)


def main():
    bad, files = [], 0
    for f in sorted(list(SRC.rglob('*.ts')) + list(SRC.rglob('*.tsx'))):
        if f.name.endswith('.d.ts'):
            continue
        files += 1
        s = f.read_text(encoding='utf-8')
        body = body_without_imports(s)
        for name in imported_names(s):
            if name == 'React':
                continue   # the JSX runtime is automatic, but an explicit import is not an error
            if not re.search(r'(?<![\w$.])' + re.escape(name) + r'(?![\w$])', body):
                bad.append(f'{f.relative_to(SRC)}: "{name}" is imported but never used')
    print('== Unused imports (noUnusedLocals) ==')
    print(f'   {files} frontend files scanned')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   no unused imports')
    return 0


if __name__ == '__main__':
    sys.exit(main())
