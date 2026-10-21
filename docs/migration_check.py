#!/usr/bin/env python3
"""Flyway sanity: unique versions, and every ALTER/INSERT targets a table created earlier."""
import re, sys
from pathlib import Path

MIG = Path('/home/claude/kashmirmenu/src/main/resources/db/migration')
files = sorted(MIG.glob('V*.sql'), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))

problems, created, versions = [], set(), {}

for f in files:
    version = int(re.match(r'V(\d+)__', f.name).group(1))
    if version in versions:
        problems.append(f'duplicate version V{version}: {f.name} and {versions[version]}')
    versions[version] = f.name

    sql = f.read_text(encoding='utf-8')
    for t in re.findall(r'CREATE TABLE(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?', sql, re.I):
        created.add(t.lower())
    # A table altered before it exists fails at migrate time, not at review time.
    for t in re.findall(r'ALTER TABLE\s+`?(\w+)`?', sql, re.I):
        if t.lower() not in created:
            problems.append(f'{f.name}: ALTER TABLE {t} before any CREATE TABLE {t}')
    # 'ON UPDATE CURRENT_TIMESTAMP' is a column default, not a write, so require a real statement.
    writes = re.findall(r'(?<!ON )\bUPDATE\s+`?(\w+)`?\s+(?:\w+\s+)?SET\b', sql, re.I)
    writes += re.findall(r'INSERT INTO\s+`?(\w+)`?', sql, re.I)
    for t in writes:
        if t.lower() not in created:
            problems.append(f'{f.name}: writes to {t} before it is created')

gaps = [v for v in range(1, max(versions) + 1) if v not in versions]
print(f'{len(files)} migrations, V1..V{max(versions)}')
if gaps:
    print(f'   note: no file for version(s) {gaps} (Flyway allows gaps)')
print(f'   tables created: {len(created)}')
print('\n'.join('   [!] ' + p for p in problems) if problems else '   no ordering problems')
sys.exit(1 if problems else 0)
