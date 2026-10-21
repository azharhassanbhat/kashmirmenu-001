#!/usr/bin/env python3
"""
Resolves every repository method call against its declaration.

This exists because of a real mistake: removing dead queries from OrderRepository also removed
findByRestaurantAndStatusOrderByCreatedAtAsc, which KitchenServiceImpl calls. A missing repository
method is a `cannot find symbol` error - and the Java syntax pass used throughout this work filters
exactly that class of error out, because without dependencies on the classpath every file produces
hundreds of them. So the one check that would have caught it was the one being suppressed.

Spring Data makes this worse than an ordinary missing method: the interface still compiles, and a
derived query that no longer exists fails when the context starts, not where it is called.
"""
import re
import sys
from pathlib import Path

ROOT = Path('/home/claude/kashmirmenu/src/main/java/com/kashmirmenu/app')

INHERITED = {
    'save', 'saveAll', 'findById', 'findAll', 'deleteById', 'delete', 'existsById', 'count',
    'flush', 'saveAndFlush', 'deleteAll', 'getReferenceById', 'findOne', 'deleteAllById',
}


def declarations():
    out = {}
    for p in list(ROOT.rglob('*Repository.java')) + list(ROOT.rglob('*Queries.java')):
        src = p.read_text(encoding='utf-8', errors='replace')
        out[p.stem] = set(re.findall(r'\b(\w+)\s*\(', src))
    return out


def main():
    decl = declarations()
    problems = set()

    for p in ROOT.rglob('*.java'):
        src = re.sub(r'//[^\n]*', '', p.read_text(encoding='utf-8', errors='replace'))
        for m in re.finditer(r'(\w*[Rr]epository|\w*Queries)\s*\.\s*(\w+)\s*\(', src):
            field, method = m.group(1), m.group(2)
            if method in INHERITED:
                continue
            stem = field.lower().replace('repository', '').replace('queries', '')
            candidates = [k for k in decl
                          if stem and (k.lower().startswith(stem[:6]) or field.lower() in k.lower())]
            if candidates and not any(method in decl[c] for c in candidates):
                problems.add(f'{p.name}: {field}.{method}() is not declared in {candidates}')

    print('== Repository call resolution ==')
    for x in sorted(problems):
        print(f'   [!] {x}')
    print('   every repository call resolves' if not problems
          else f'   {len(problems)} unresolved call(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
