#!/usr/bin/env python3
"""
Guards against fetch-joining a COLLECTION alongside a Pageable.

Hibernate cannot apply SQL LIMIT to a query that fetch-joins a to-many association, so it reads
every matching row and discards the rest in memory (HHH90003004). That is strictly worse than the
N+1 it is usually added to fix, and it is silent - the results are correct, only the cost is wrong.

To-ONE fetch joins (@ManyToOne) page perfectly well and are not flagged: the check resolves each
named attribute against its entity and only complains about List/Set fields.
"""
import re
import sys
from pathlib import Path

ROOT = Path('/home/claude/kashmirmenu')
ENTITIES = ROOT / 'src/main/java/com/kashmirmenu/app/entity'
REPOS = ROOT / 'src/main/java/com/kashmirmenu/app/repository'


def strip_comments(code):
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
    return re.sub(r'//[^\n]*', '', code)


def collection_attributes():
    """{entity -> {field names that are to-many}}"""
    out = {}
    for f in ENTITIES.glob('*.java'):
        src = strip_comments(f.read_text(encoding='utf-8', errors='replace'))
        fields = set(re.findall(r'private\s+(?:List|Set)<[^>]+>\s+(\w+)\s*[;=]', src))
        out[f.stem] = fields
    return out


def main():
    collections = collection_attributes()
    every_collection = set().union(*collections.values()) if collections else set()
    problems = []

    print('== Paged queries fetch-joining a collection ==')
    for f in sorted(REPOS.glob('*.java')):
        src = strip_comments(f.read_text(encoding='utf-8', errors='replace'))

        for m in re.finditer(
                r'(@EntityGraph\s*\([^)]*\)|@Query\s*\(\s*"[^"]*join\s+fetch[^"]*"\s*\))'
                r'\s*((?:@\w+(?:\([^)]*\))?\s*)*)([^;{]*;)', src):
            annotation, _, signature = m.group(1), m.group(2), m.group(3)
            if 'Pageable' not in signature:
                continue

            named = set(re.findall(r'"([\w.]+)"', annotation))
            named |= set(re.findall(r'join\s+fetch\s+\w+\.(\w+)', annotation))
            offenders = {n.split('.')[0] for n in named} & every_collection

            name = re.search(r'(\w+)\s*\(', signature)
            label = f'{f.name}:{name.group(1) if name else "?"}'
            if offenders:
                problems.append(label)
                print(f'   [!] {label}: fetch-joins collection(s) {sorted(offenders)} with a Pageable')
            else:
                print(f'   ok  {label}: to-one fetch join only {sorted(named) or ""}')

    print('   no paged query fetch-joins a collection' if not problems
          else f'   {len(problems)} problem(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
