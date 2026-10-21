#!/usr/bin/env python3
"""
Checks every JPA entity against the schema the Flyway migrations really produce (built on SQLite by
migrate_sqlite.py). Production runs `ddl-auto: validate`: the application REFUSES TO START if an entity
names a table or column the database lacks, or maps a column to a type Hibernate cannot read it as.
Neither H2 (which builds its schema from the entities) nor a syntax pass can show that.

For each @Entity it verifies:
  1. the table exists;
  2. every mapped column exists;
  3. its type is one Hibernate accepts for the Java type (Long<->BIGINT, Integer<->INT, ...);
  4. no NOT NULL column without a default is left unmapped (every INSERT would fail);
  5. (warning) an entity that allows null for a NOT NULL column, or the reverse.
Calibrated against the original entities: they must pass before any finding about new ones is believed.

Usage: python3 docs/checks/entity_schema_check.py
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from migrate_sqlite import build, ROOT  # noqa: E402

ENT = ROOT / 'src/main/java/com/kashmirmenu/app/entity'

FAMILY = {  # Java type -> acceptable SQL type families (Hibernate validate is strict about these)
    'String': {'VARCHAR', 'CHAR', 'TEXT', 'LONGTEXT', 'MEDIUMTEXT', 'TINYTEXT'},
    'Long': {'BIGINT'}, 'long': {'BIGINT'},
    'Integer': {'INT', 'INTEGER', 'SMALLINT', 'TINYINT'}, 'int': {'INT', 'INTEGER', 'SMALLINT', 'TINYINT'},
    'Boolean': {'BOOLEAN', 'BIT', 'TINYINT'}, 'boolean': {'BOOLEAN', 'BIT', 'TINYINT'},
    'BigDecimal': {'DECIMAL', 'NUMERIC'},
    'LocalDateTime': {'DATETIME', 'TIMESTAMP'},
    'LocalDate': {'DATE'}, 'LocalTime': {'TIME'}, 'Instant': {'TIMESTAMP', 'DATETIME'},
    'Double': {'DOUBLE', 'FLOAT', 'DECIMAL'}, 'double': {'DOUBLE', 'FLOAT', 'DECIMAL'},
    'UUID': {'BINARY', 'CHAR', 'VARCHAR'},
    'byte[]': {'BLOB', 'MEDIUMBLOB', 'LONGBLOB', 'TINYBLOB', 'VARBINARY', 'BINARY', 'LONGVARBINARY'},
}


def snake(name: str) -> str:
    s = re.sub(r'(?<=[a-z0-9])([A-Z])', r'_\1', name)
    return s.lower()


def parse_entity(path: Path):
    src = path.read_text(encoding='utf-8')
    if not re.search(r'^\s*@Entity\b', src, flags=re.M):
        return None
    clean = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    clean = re.sub(r'//[^\n]*', '', clean)
    tm = re.search(r'@Table\s*\((.*?)\)\s*(?=@|public)', clean, flags=re.S)
    name = None
    if tm:
        nm = re.search(r'\bname\s*=\s*"([^"]+)"', tm.group(1))
        name = nm.group(1) if nm else None
    cls = re.search(r'class\s+(\w+)(?:\s+extends\s+(\w+))?', clean)
    if not cls:
        return None
    cname, parent = cls.group(1), cls.group(2)
    if not name:
        name = snake(cname)
    body = clean[clean.index('{', cls.end()):]
    fields = []
    # split into statements; keep the annotation text that precedes each field
    for m in re.finditer(r'((?:@[\w.]+(?:\([^;{}]*?\))?\s*)*)\s*private\s+(?!static)(?!final)([\w.<>?,\[\] ]+?)\s+(\w+)\s*(?:=[^;]*)?;', body, flags=re.S):
        ann, jtype, fname = m.group(1), m.group(2).strip(), m.group(3)
        fields.append((ann, jtype, fname))
    return {'class': cname, 'table': name, 'parent': parent, 'fields': fields, 'file': path.name}


def column_for(ann: str, jtype: str, fname: str):
    """-> (column name, java type, nullable, is_enum_string) or None when not a column."""
    if '@Transient' in ann or re.search(r'@(OneToMany|ManyToMany|ElementCollection)', ann):
        return None
    if re.search(r'@(OneToOne|ManyToOne)', ann):
        if re.search(r'mappedBy\s*=', ann):
            return None
        jc = re.search(r'@JoinColumn\s*\((.*?)\)', ann, flags=re.S)
        nm = re.search(r'\bname\s*=\s*"([^"]+)"', jc.group(1)).group(1) if jc and re.search(r'\bname\s*=', jc.group(1)) else snake(fname) + '_id'
        nullable = not (re.search(r'optional\s*=\s*false', ann) or (jc and re.search(r'nullable\s*=\s*false', jc.group(1))))
        return nm, 'Long', nullable, False
    col = re.search(r'@Column\s*\((.*?)\)', ann, flags=re.S)
    nm = snake(fname)
    nullable = True
    if col:
        n = re.search(r'\bname\s*=\s*"([^"]+)"', col.group(1))
        if n:
            nm = n.group(1)
        if re.search(r'nullable\s*=\s*false', col.group(1)):
            nullable = False
    enum_string = bool(re.search(r'@Enumerated\s*\(\s*(EnumType\.)?STRING', ann))
    if '@Id' in ann:
        nullable = False
    return nm, jtype, nullable, enum_string


def sql_family(decl: str) -> str:
    return re.match(r'[A-Za-z]+', decl or '').group(0).upper() if decl else ''


def main():
    con, rep = build()
    if rep['errors']:
        print('migrations did not apply cleanly; run migrate_sqlite.py first')
        return 1

    schema = {}
    for (t,) in con.execute("select name from sqlite_master where type='table'"):
        cols = {}
        for cid, name, typ, notnull, dflt, pk, hidden in con.execute(f'pragma table_xinfo({t})'):
            # hidden 2/3 = a generated column (virtual/stored): the database fills it, never the entity
            cols[name] = {'type': typ, 'notnull': bool(notnull), 'default': dflt, 'pk': bool(pk),
                          'generated': hidden in (2, 3)}
        schema[t] = cols

    entities = [e for e in (parse_entity(p) for p in sorted(ENT.parent.glob('**/*.java'))) if e]
    problems, warnings, checked = [], [], 0

    for e in entities:
        if e['table'] not in schema:
            problems.append(f"{e['class']}: table '{e['table']}' does not exist in the migrated schema")
            continue
        cols = schema[e['table']]
        mapped = set()
        wanted = []
        if e['parent'] == 'BaseEntity':
            wanted += [('created_at', 'LocalDateTime', False, False), ('updated_at', 'LocalDateTime', False, False)]
        for ann, jtype, fname in e['fields']:
            c = column_for(ann, jtype, fname)
            if c:
                wanted.append(c)
        for nm, jtype, nullable, enum_string in wanted:
            checked += 1
            mapped.add(nm)
            if nm not in cols:
                problems.append(f"{e['class']}.{nm}: column does not exist in table '{e['table']}'")
                continue
            fam = sql_family(cols[nm]['type'])
            base = re.sub(r'<.*', '', jtype).split('.')[-1]
            if enum_string:
                ok = fam in FAMILY['String']
            elif base in FAMILY:
                ok = fam in FAMILY[base] or (base in ('Long', 'long') and fam == 'INTEGER' and cols[nm]['pk'])
            else:
                ok = True   # enums stored as ordinal, embeddables, etc.: not judged
                if base[:1].isupper() and not enum_string and base not in FAMILY:
                    # an enum without @Enumerated(STRING) would be stored as a number
                    pass
            if not ok:
                problems.append(f"{e['class']}.{nm}: Java {jtype} but column is {cols[nm]['type']}")
            if nullable is False and not cols[nm]['notnull'] and not cols[nm]['pk']:
                warnings.append(f"{e['class']}.{nm}: entity says NOT NULL, column allows NULL")
            if nullable and cols[nm]['notnull'] and cols[nm]['default'] is None and not cols[nm]['pk']:
                warnings.append(f"{e['class']}.{nm}: column is NOT NULL with no default, but the entity allows null")
        # 4. NOT NULL, no default, unmapped, not generated, not the key -> every INSERT fails
        for cname, info in cols.items():
            if cname in mapped:
                continue
            if info['notnull'] and info['default'] is None and not info['pk'] and not info.get('generated'):
                problems.append(
                    f"{e['class']}: column '{cname}' is NOT NULL with no default but is not mapped; every INSERT would fail")

    print('== Entities vs the migrated schema ==')
    print(f'   {len(entities)} entities, {checked} column mappings checked')
    for w in warnings:
        print(f'   (warn) {w}')
    if problems:
        for p in problems:
            print(f'   [!] {p}')
        return 1
    print('   every entity maps onto the schema')
    return 0


if __name__ == '__main__':
    sys.exit(main())
