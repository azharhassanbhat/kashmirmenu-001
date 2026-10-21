#!/usr/bin/env python3
"""
Resolves every getter, setter and is-accessor called on an entity, DTO or record against the fields it
really has (including inherited ones such as BaseEntity's createdAt).

Lombok generates these, so without a compiler a misspelt one (order.getDinerNam()) is invisible. This
reads the declared fields instead. Variable types are tracked per method, signature included, so a name
reused with a different type elsewhere in a file is not confused, and a method PARAMETER is checked as
well as a local. Mutation-tested on a parameter, a local and a setter.
"""
import re, pathlib
SRC = pathlib.Path('/home/claude/kashmirmenu/src')

def strip(code):
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
    code = re.sub(r'//[^\n]*', '', code)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', code)

def strip_annotations(code):
    out, i, n = [], 0, len(code)
    while i < n:
        if code[i] == '@' and i + 1 < n and (code[i+1].isalpha()) :
            j = i + 1
            while j < n and (code[j].isalnum() or code[j] in '._'): j += 1
            k = j
            while k < n and code[k] in ' \t\r\n': k += 1
            if k < n and code[k] == '(':
                depth = 0
                while k < n:
                    depth += (code[k] == '(') - (code[k] == ')')
                    k += 1
                    if depth == 0: break
                j = k
            out.append(' ')
            i = j
            continue
        out.append(code[i]); i += 1
    return ''.join(out)

def split_top(s):
    parts, depth, cur = [], 0, ''
    for ch in s:
        if ch in '(<[{': depth += 1
        elif ch in ')>]}': depth -= 1
        if ch == ',' and depth == 0: parts.append(cur); cur = ''
        else: cur += ch
    if cur.strip(): parts.append(cur)
    return parts

# accessor names per data type (entities, DTO classes, records)
data = {}          # name -> (set of accessor names, parent)
for p in (SRC / 'main/java').rglob('*.java'):
    pkg = str(p)
    if not re.search(r'/(entity|dto|common)/', pkg): continue
    code = strip_annotations(strip(p.read_text(encoding='utf-8')))
    m = re.search(r'\b(class|record)\s+(\w+)(?:\s*\(([^)]*)\))?(?:\s+extends\s+(\w+))?', code)
    if not m: continue
    kind, name, comps, parent = m.groups()
    acc = set()
    if kind == 'record':
        for c in split_top(comps or ''):
            acc.add(c.strip().split()[-1])
    else:
        body_start = code.find('{', m.end()) + 1
        depth_at = {}
        d = 1
        top = []
        for idx in range(body_start, len(code)):
            ch = code[idx]
            if ch == '{': d += 1
            elif ch == '}': d -= 1
            if d == 1: top.append(ch)
            elif d == 0: break
            else: top.append(' ')
        top_level = ''.join(top)
        for f in re.finditer(r'(?m)^\s*(?:(?:private|protected|public)\s+)?(?!static)(?:final\s+)?([\w<>\[\],.? ]+?)\s+(\w+)\s*(?:=[^;]*)?;', top_level):
            t, fname = f.group(1).strip(), f.group(2)
            if t in ('return','throw','new','package','import'): continue
            cap = fname[0].upper() + fname[1:]
            acc |= {'get' + cap, 'set' + cap, 'is' + cap}
    # explicit methods too
    for d in re.finditer(r'(?m)^\s*(?:public\s+)?[\w<>\[\],.? ]+\s+(\w+)\s*\([^)]*\)\s*\{', code):
        acc.add(d.group(1))
    data[name] = (acc, parent)

def accessors(name, seen=None):
    seen = seen or set()
    if name in seen or name not in data: return set()
    seen.add(name)
    acc, parent = data[name]
    return acc | (accessors(parent, seen) if parent else set())

problems = []
for p in list((SRC / 'main/java').rglob('*.java')) + list((SRC / 'test/java').rglob('*.java')):
    code = strip(p.read_text(encoding='utf-8'))
    # Method-sized chunks, each KEEPING its own signature (that is where parameters are declared).
    starts = [m.start() for m in re.finditer(r'(?m)^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|protected|private)\s+[\w<>\[\],.? ]+\s+\w+\s*\([^)]*\)[^{;]*\{', code)]
    bounds = [0] + starts + [len(code)]
    chunks = [code[bounds[i]:bounds[i + 1]] for i in range(len(bounds) - 1)]
    for chunk in chunks:
        var_type = {}
        for d in re.finditer(r'\b([A-Z]\w+)(?:<[^>]*>)?\s+(\w+)\s*[=;,):]', chunk):
            t, v = d.group(1), d.group(2)
            if t in data: var_type[v] = t
        for v, t in var_type.items():
            acc = accessors(t)
            for call in re.finditer(r'(?<![\w.])' + re.escape(v) + r'\s*\.\s*((?:get|set|is)[A-Z]\w*)\s*\(', chunk):
                if call.group(1) not in acc:
                    problems.append(f'{p.relative_to(SRC)}: {v}.{call.group(1)}() - no such accessor on {t}')
print('== Entity/DTO accessor resolution ==')
for x in sorted(set(problems)): print('   [!]', x)
print(f'   {len(data)} data types; ' + ('every accessor call resolves' if not problems else f'{len(set(problems))} problem(s)'))
