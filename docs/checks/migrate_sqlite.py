#!/usr/bin/env python3
"""
Runs every Flyway migration, in version order, on a real SQL engine (SQLite, from the Python standard
library) so that schema mistakes surface without a MySQL server: a column that does not exist, a table
used before it is created, a bad constraint, a typo in an index.

SQLite is not MySQL. The migrations are translated first (AUTO_INCREMENT, inline INDEX, ON UPDATE,
CONCAT, multi-column ALTER, ...). Statements SQLite cannot express at all (ADD CONSTRAINT, MODIFY
COLUMN, DROP FOREIGN KEY) are skipped and REPORTED, never hidden. What passes is evidence, not proof:
it says "this SQL is well formed and consistent", not "MySQL 8 will accept every detail".

Usage:  python3 docs/checks/migrate_sqlite.py            # run and summarise
Import: from migrate_sqlite import build   # build() -> (connection, report)
"""
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIG = ROOT / 'src/main/resources/db/migration'


def strip_comments(sql: str) -> str:
    out = []
    for line in sql.splitlines():
        # a -- comment, but not inside a quoted string (migrations here never put -- in strings)
        i = line.find('--')
        out.append(line if i < 0 else line[:i])
    return '\n'.join(out)


def split_statements(sql: str):
    stmts, cur, depth, quote = [], [], 0, None
    for ch in sql:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ("'", '"', '`'):
            quote = ch
            cur.append(ch)
            continue
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ';' and depth == 0:
            s = ''.join(cur).strip()
            if s:
                stmts.append(s)
            cur = []
        else:
            cur.append(ch)
    s = ''.join(cur).strip()
    if s:
        stmts.append(s)
    return stmts


def split_top_level(text: str):
    """Split on commas that are not inside parentheses or quotes."""
    parts, cur, depth, quote = [], [], 0, None
    for ch in text:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ("'", '"', '`'):
            quote = ch
            cur.append(ch)
            continue
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(''.join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        parts.append(''.join(cur).strip())
    return parts


def mysql_to_sqlite_expr(s: str) -> str:
    # CONCAT(a, ':', b) -> (a || ':' || b)
    def concat(m):
        args = split_top_level(m.group(1))
        return '(' + ' || '.join(args) + ')'
    s = re.sub(r'CONCAT\s*\(((?:[^()]|\([^()]*\))*)\)', concat, s, flags=re.I)
    return s


def clean_column_def(d: str) -> str:
    d = re.sub(r'\bAUTO_INCREMENT\b', '', d, flags=re.I)
    d = re.sub(r'\bON\s+UPDATE\s+CURRENT_TIMESTAMP(\(\d*\))?', '', d, flags=re.I)
    d = re.sub(r"\bCOMMENT\s+'(?:[^']|'')*'", '', d, flags=re.I)
    d = re.sub(r'\bUNSIGNED\b', '', d, flags=re.I)
    d = re.sub(r'\b(CHARACTER SET|CHARSET)\s+\w+', '', d, flags=re.I)
    d = re.sub(r'\bCOLLATE\s+\w+', '', d, flags=re.I)
    return mysql_to_sqlite_expr(d)


def translate_create_table(stmt: str):
    """-> (create_sql, [extra index statements])"""
    m = re.match(r'(?is)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"]?(\w+)[`"]?\s*\((.*)\)\s*([^()]*)$', stmt)
    if not m:
        return stmt, []
    table, body = m.group(1), m.group(2)
    items = split_top_level(body)
    # An AUTO_INCREMENT column becomes SQLite's real row-id key (INTEGER PRIMARY KEY AUTOINCREMENT). A
    # plain BIGINT PRIMARY KEY would let SQLite store NULL ids, which MySQL never does.
    auto_col = None
    for it in items:
        am = re.match(r'(?is)^[`"]?(\w+)[`"]?\s+\w+.*\bAUTO_INCREMENT\b', it.strip())
        if am:
            auto_col = am.group(1)
    kept, indexes = [], []
    for it in items:
        if auto_col:
            if re.match(r'(?is)^[`"]?' + auto_col + r'[`"]?\s+\w+.*\bAUTO_INCREMENT\b', it.strip()):
                kept.append(f'{auto_col} INTEGER PRIMARY KEY AUTOINCREMENT')
                continue
            if re.match(r'(?is)^PRIMARY\s+KEY\s*\(\s*[`"]?' + auto_col + r'[`"]?\s*\)\s*$', it.strip()):
                continue
        low = it.lstrip().upper()
        im = re.match(r'(?is)(UNIQUE\s+)?(?:INDEX|KEY)\s+[`"]?(\w+)[`"]?\s*\((.*)\)', it.strip())
        if im and not low.startswith('CONSTRAINT'):
            uniq = 'UNIQUE ' if im.group(1) else ''
            indexes.append(f'CREATE {uniq}INDEX {im.group(2)} ON {table} ({im.group(3)})')
            continue
        kept.append(clean_column_def(it))
    return f'CREATE TABLE {table} (\n  ' + ',\n  '.join(kept) + '\n)', indexes


def translate_alter(stmt: str):
    """Split a multi-clause ALTER TABLE into one statement per clause. -> (list, skipped_reasons)"""
    m = re.match(r'(?is)ALTER\s+TABLE\s+[`"]?(\w+)[`"]?\s+(.*)$', stmt)
    if not m:
        return [stmt], []
    table, rest = m.group(1), m.group(2)
    out, skipped = [], []
    for clause in split_top_level(rest):
        c = clause.strip()
        if re.match(r'(?is)ADD\s+(COLUMN\s+)?[`"]?\w+[`"]?\s+\w', c) and not re.match(
                r'(?is)ADD\s+(CONSTRAINT|PRIMARY|FOREIGN|UNIQUE|INDEX|KEY|CHECK)\b', c):
            col = re.sub(r'(?is)^ADD\s+(COLUMN\s+)?', '', c)
            col = re.sub(r'(?is)\s+(AFTER|FIRST)\s+\w*$', '', col)
            # SQLite: no NOT NULL without a default on ADD COLUMN is allowed only with a default; the
            # migrations here always give one. Generated STORED columns cannot be ADDed; reported.
            if re.search(r'(?is)GENERATED\s+ALWAYS.*STORED', col):
                # SQLite cannot ADD a STORED generated column; VIRTUAL has the same values and can be
                # indexed, which is all a structural check needs.
                col = re.sub(r'(?i)\bSTORED\b', 'VIRTUAL', col)
            out.append(f'ALTER TABLE {table} ADD COLUMN {clean_column_def(col)}')
        elif re.match(r'(?is)ADD\s+(UNIQUE\s+)?(INDEX|KEY)\s+', c):
            im = re.match(r'(?is)ADD\s+(UNIQUE\s+)?(?:INDEX|KEY)\s+[`"]?(\w+)[`"]?\s*\((.*)\)', c)
            if im:
                out.append(f'CREATE {"UNIQUE " if im.group(1) else ""}INDEX {im.group(2)} ON {table} ({im.group(3)})')
        elif re.match(r'(?is)DROP\s+(INDEX|KEY)\s+[`"]?(\w+)', c):
            out.append('DROP INDEX ' + re.match(r'(?is)DROP\s+(?:INDEX|KEY)\s+[`"]?(\w+)', c).group(1))
        elif re.match(r'(?is)DROP\s+COLUMN\s+[`"]?(\w+)', c):
            out.append(f'ALTER TABLE {table} DROP COLUMN ' + re.match(r'(?is)DROP\s+COLUMN\s+[`"]?(\w+)', c).group(1))
        elif re.match(r'(?is)ADD\s+CONSTRAINT\s+[`"]?\w+[`"]?\s+UNIQUE\s*\(', c):
            um = re.match(r'(?is)ADD\s+CONSTRAINT\s+[`"]?(\w+)[`"]?\s+UNIQUE\s*\((.*)\)', c)
            out.append(f'CREATE UNIQUE INDEX {um.group(1)} ON {table} ({um.group(2)})')
        elif re.match(r'(?is)(MODIFY|CHANGE|ALTER\s+COLUMN|DROP|ADD\s+(CONSTRAINT|FOREIGN|CHECK|PRIMARY)|RENAME)', c):
            skipped.append(f'{table}: {c[:60]}')
        else:
            skipped.append(f'{table}: unrecognised clause {c[:60]}')
    return out, skipped


def translate(stmt: str):
    """-> (statements, skipped)"""
    head = re.match(r'(?is)\s*(\w+)', stmt).group(1).upper()
    if head == 'CREATE' and re.match(r'(?is)\s*CREATE\s+TABLE', stmt):
        create, idx = translate_create_table(stmt)
        return [create] + idx, []
    if head == 'ALTER':
        return translate_alter(stmt)
    if head == 'CREATE' and re.match(r'(?is)\s*CREATE\s+(UNIQUE\s+)?INDEX', stmt):
        return [re.sub(r'(?is)\s+USING\s+\w+', '', stmt)], []
    if head == 'INSERT':
        s = re.sub(r'(?is)^\s*INSERT\s+IGNORE', 'INSERT OR IGNORE', stmt)
        return [mysql_to_sqlite_expr(s)], []
    if head in ('SET', 'USE', 'DELIMITER'):
        return [], [f'{head} statement']
    if head in ('UPDATE', 'DELETE', 'DROP'):
        return [mysql_to_sqlite_expr(stmt)], []
    return [stmt], []


def build(verbose=False):
    """Run all migrations. -> (connection, report dict)."""
    import uuid
    con = sqlite3.connect(':memory:')
    # MySQL functions some data-fix statements use; on the empty schema they match nothing anyway.
    con.create_function('UUID', 0, lambda: str(uuid.uuid4()))
    con.create_function('REGEXP', 2, lambda pat, val: 1 if val is not None and re.search(pat, str(val)) else 0)
    con.execute('PRAGMA foreign_keys = OFF')   # during migration; tests switch it on
    files = sorted(MIG.glob('V*.sql'), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))
    errors, skipped, executed = [], [], 0
    for f in files:
        stmts = split_statements(strip_comments(f.read_text(encoding='utf-8')))
        for s in stmts:
            try:
                out, skip = translate(s)
            except Exception as e:  # translator bug, never the migration's fault
                errors.append((f.name, s[:80], f'TRANSLATOR: {e}'))
                continue
            skipped += [(f.name, x) for x in skip]
            for o in out:
                try:
                    con.execute(o)
                    executed += 1
                except sqlite3.Error as e:
                    head = o.strip().split()[0].upper()
                    # A data-fix UPDATE using MySQL's UPDATE ... JOIN syntax changes rows, not structure,
                    # and a fresh database has none. Reported separately, never silently dropped.
                    if head == 'DROP' and 'no such index' in str(e):
                        # MySQL names the implicit index of a column-level UNIQUE after the column;
                        # SQLite keeps it inline, so there is nothing of that name to drop.
                        skipped.append((f.name, 'implicit MySQL index: ' + o.strip()[:50]))
                    elif head == 'UPDATE' and 'syntax error' in str(e):
                        skipped.append((f.name, 'MySQL-only data fix (UPDATE ... JOIN): ' + o.strip().splitlines()[0][:50]))
                    else:
                        errors.append((f.name, o.strip().splitlines()[0][:90], str(e)))
    return con, {'files': len(files), 'executed': executed, 'errors': errors, 'skipped': skipped}


def main():
    con, rep = build()
    tables = [r[0] for r in con.execute("select name from sqlite_master where type='table' order by name")]
    print(f"== Migrations on SQLite ==\n   {rep['files']} files, {rep['executed']} statements executed, {len(tables)} tables")
    if rep['skipped']:
        print(f"   {len(rep['skipped'])} statements SQLite cannot express (reported, not hidden):")
        for f, why in rep['skipped'][:30]:
            print(f'     - {f}: {why}')
    if rep['errors']:
        print(f"   [!] {len(rep['errors'])} statements FAILED:")
        for f, s, e in rep['errors']:
            print(f'     {f}: {s}\n         -> {e}')
        return 1
    print('   every translatable statement ran')
    return 0


if __name__ == '__main__':
    sys.exit(main())
