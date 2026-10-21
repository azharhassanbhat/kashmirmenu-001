#!/usr/bin/env python3
"""
Resolves every Spring Data query against the entity model. A query that names a property that does
not exist (a typo in JPQL, a derived method like findByTableSessionIdAndTypeAndStatusIn that cannot
be split into real properties) fails when the Spring context STARTS, so the whole application is
down, not one endpoint.

  * @Query (JPQL): every `alias.property.path` must walk real fields; `from`/`join` aliases are tracked.
  * Derived methods (findBy..., countBy..., existsBy...): the predicate is split the way Spring Data
    does (And/Or, then the longest property path, then operator keywords) and each path must resolve.

Calibrated against the original repositories: they must pass before a finding about new ones counts.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from entity_schema_check import parse_entity, ENT, ROOT  # noqa: E402

REPO = ROOT / 'src/main/java/com/kashmirmenu/app'

OPERATORS = ['IsNotNull', 'NotNull', 'IsNull', 'Null', 'IsNot', 'Not', 'NotIn', 'In', 'IsTrue', 'True', 'IsFalse', 'False',
             'Containing', 'Contains', 'StartingWith', 'EndingWith', 'Like', 'NotLike', 'GreaterThanEqual',
             'GreaterThan', 'LessThanEqual', 'LessThan', 'After', 'Before', 'Between', 'IsEmpty', 'IsNotEmpty',
             'IgnoreCase', 'AllIgnoreCase', 'Equals', 'Is']


def model():
    ents, base = {}, {'createdAt': 'LocalDateTime', 'updatedAt': 'LocalDateTime'}
    for p in sorted(REPO.glob('**/*.java')):
        if not re.search(r'^\s*@Entity\b', p.read_text(encoding='utf-8'), flags=re.M):
            continue
        e = parse_entity(p)
        if not e:
            continue
        fields = {}
        for ann, jtype, fname in e['fields']:
            if '@Transient' in ann:
                continue
            target = re.sub(r'^.*<\s*([\w.]+)\s*>.*$', r'\1', jtype).split('.')[-1]
            fields[fname] = target
        if e['parent'] == 'BaseEntity':
            fields.update(base)
        ents[e['class']] = fields
    return ents


def resolve(ents, entity, text):
    """Spring Data PropertyPath resolution: whole name first, else split at camel-case humps from the right."""
    if not text:
        return True
    if '_' in text:   # explicit traversal: Role_Name = role -> name
        head, tail = text.split('_', 1)
        cur = ents.get(entity, {})
        h = head[0].lower() + head[1:] if head else head
        return h in cur and resolve(ents, cur[h], tail)
    cur = ents.get(entity)
    if cur is None:
        return False
    uncap = text[0].lower() + text[1:]
    if uncap in cur and ('.' not in uncap):
        return True
    humps = [m.start() for m in re.finditer(r'(?<=[a-z0-9])(?=[A-Z])', text)]
    for i in reversed(humps):
        head, tail = text[:i], text[i:]
        h = head[0].lower() + head[1:]
        if h in cur and resolve(ents, cur[h], tail):
            return True
    return False


def split_predicate(pred: str):
    return [p for p in re.split(r'(?<=[a-z0-9])(?:And|Or)(?=[A-Z])', pred) if p]


def strip_operator(part: str):
    for op in sorted(OPERATORS, key=len, reverse=True):
        if part.endswith(op) and len(part) > len(op):
            return part[:-len(op)]
    return part


def check_derived(ents, entity, method):
    m = re.match(r'^(?:find|read|get|query|stream|count|exists|delete|remove)(?:Distinct)?(?:First\d*|Top\d*)?(?:Distinct)?(?:\w*?)By(\w+)$', method)
    if not m:
        return None   # findAll etc.: no predicate
    pred = m.group(1)
    order = ''
    if 'OrderBy' in pred:
        pred, order = pred.split('OrderBy', 1)
    bad = []
    for part in split_predicate(pred):
        prop = strip_operator(part)
        if not resolve(ents, entity, prop):
            bad.append(prop)
    if order:
        for part in re.split(r'(?<=[a-z0-9])(?:Asc|Desc)(?=[A-Z]|$)', order):
            part = re.sub(r'(Asc|Desc)$', '', part)
            if part and not resolve(ents, entity, part):
                bad.append(part)
    return bad


def jpql_strings(body: str):
    """The text of every @Query annotation: concatenated literals; native queries are skipped."""
    out = []
    for m in re.finditer(r'@Query\s*\(', body):
        i, depth = m.end(), 1
        j = i
        while j < len(body) and depth:
            if body[j] == '"':
                if body.startswith('"""', j):
                    k = body.index('"""', j + 3) + 3
                    j = k
                    continue
                k = j + 1
                while body[k] != '"':
                    k += 2 if body[k] == '\\' else 1
                j = k + 1
                continue
            depth += {'(': 1, ')': -1}.get(body[j], 0)
            j += 1
        args = body[i:j - 1]
        if re.search(r'nativeQuery\s*=\s*true', args):
            continue
        lits = re.findall(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', args, flags=re.S)
        text = ''.join(a or b for a, b in lits)
        line = body.count('\n', 0, m.start()) + 1
        out.append((line, text))
    return out


def check_jpql(ents, text):
    problems = []
    aliases = {}
    # from Entity alias / update Entity alias / delete from Entity alias
    for m in re.finditer(r'(?i)\b(?:from|update|delete\s+from)\s+(\w+)\s+(?:as\s+)?(\w+)', text):
        if m.group(1) in ents:
            aliases[m.group(2)] = m.group(1)
    # join [fetch] alias.path [as] alias2   (repeat: later joins can hang off earlier ones)
    for _ in range(4):
        for m in re.finditer(r'(?i)\bjoin\s+(?:fetch\s+)?(\w+)\.(\w+)\s+(?:as\s+)?(\w+)', text):
            base, prop, alias = m.groups()
            if alias.lower() in ('on', 'where', 'order', 'group', 'left', 'inner', 'join'):
                continue
            if base in aliases and prop in ents.get(aliases[base], {}):
                aliases[alias] = ents[aliases[base]][prop]
    for m in re.finditer(r'(?<![\w.:])(\w+)((?:\.\w+)+)', text):
        alias, rest = m.group(1), m.group(2)
        if alias not in aliases:
            continue
        cur = aliases[alias]
        for prop in rest.strip('.').split('.'):
            fields = ents.get(cur, {})
            if prop not in fields:
                problems.append(f'{alias}{rest}: no property "{prop}" on {cur}')
                break
            cur = fields[prop]
    return problems


def main():
    ents = model()
    repos = sorted(REPO.glob('**/*Repository.java')) + sorted(REPO.glob('**/*Queries.java'))
    bad, derived_n, jpql_n = [], 0, 0
    for path in repos:
        src = re.sub(r'/\*.*?\*/', '', path.read_text(encoding='utf-8'), flags=re.S)
        src = re.sub(r'(?<!:)//[^\n]*', '', src)
        em = re.search(r'extends\s+(?:\w+<\s*)?JpaRepository\s*<\s*(\w+)\s*,', src)
        entity = em.group(1) if em else None
        # JPQL
        for line, text in jpql_strings(src):
            jpql_n += 1
            for p in check_jpql(ents, text):
                bad.append(f'{path.name}:{line}: {p}')
        # derived
        if entity:
            body = src
            # method declarations: [annotations] ReturnType name(
            for mm in re.finditer(r'(?:^|\n)\s*((?:@[\w.]+(?:\([^)]*\))?\s*)*)(?:[\w<>?,. \[\]]+?)\s+(\w+)\s*\(', body):
                ann, name = mm.group(1), mm.group(2)
                if '@Query' in ann or name in ('equals', 'hashCode', 'toString'):
                    continue
                r = check_derived(ents, entity, name)
                if r is None:
                    continue
                derived_n += 1
                for p in r:
                    bad.append(f'{path.name}: {name}: cannot resolve property "{p}" on {entity}')
    print('== Spring Data queries vs the entity model ==')
    print(f'   {len(ents)} entities, {jpql_n} @Query (JPQL) and {derived_n} derived methods resolved')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   every query resolves')
    return 0


if __name__ == '__main__':
    sys.exit(main())
