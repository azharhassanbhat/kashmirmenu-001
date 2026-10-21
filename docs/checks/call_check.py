#!/usr/bin/env python3
"""
Resolves cross-class calls the way the compiler would, for the cases the older checkers do not reach:

  1. field.method(args)       through ANY injected field, resolved by the field's declared TYPE (not by
                              a naming convention), for services, components and Spring Data repositories:
                              the method must exist on that type and accept that many arguments.
  2. Type.method(args)        static calls on project classes (StaffRules.requireCanAssign(...)).
  3. Type.builder()...build() each chained step must be a real field of Type (Lombok names builder
                              methods after fields).
  4. lambda accessors         x -> x.getFoo() : the parameter type is unknown, so the accessor must at
                              least exist on SOME data type or method in the project (catches typos).

Compile errors are the largest remaining risk while nothing can be built here. Calibrated first on the
untouched code: it must produce no findings there before a finding about new code is believed.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java'

# Methods every Spring Data repository inherits (JpaRepository / CrudRepository / Paging / QBE).
JPA = {'save': {1}, 'saveAll': {1}, 'saveAndFlush': {1}, 'saveAllAndFlush': {1}, 'findById': {1}, 'findAll': {0, 1, 2},
       'findAllById': {1}, 'existsById': {1}, 'count': {0, 1}, 'delete': {1}, 'deleteById': {1}, 'deleteAll': {0, 1},
       'deleteAllById': {1}, 'deleteAllByIdInBatch': {1}, 'deleteInBatch': {1}, 'deleteAllInBatch': {0, 1}, 'flush': {0}, 'getReferenceById': {1},
       'getById': {1}, 'getOne': {1}, 'findOne': {1}, 'exists': {1}, 'findBy': {2}}
# Lombok's @Getter/@Setter/@Data/@Builder generate these, so they are judged by accessor_check, not here.
COMMON = {'toString', 'equals', 'hashCode', 'getClass', 'name', 'ordinal', 'values', 'valueOf', 'compareTo'}
# Accessors that legitimately come from the JDK/Spring/Jackson/test libraries, not from project types.
EXTERNAL_ACCESSORS = {
    'getMessage', 'getCause', 'getKey', 'getValue', 'getSeconds', 'getNano', 'getClass', 'getOrDefault', 'getFirst',
    'getLast', 'getAndSet', 'getAndIncrement', 'getBytes', 'getName', 'getId', 'getEmail', 'getStatus', 'getRequest',
    'getResponse', 'getContentAsString', 'getStatusCode', 'getBody', 'getHeaders', 'getHeader', 'getParameter',
    'getSession', 'getAuthentication', 'getPrincipal', 'getAuthorities', 'getUsername', 'getPassword', 'getContext',
    'getTime', 'getDayOfWeek', 'getYear', 'getMonth', 'getHour', 'getMinute', 'getSubject', 'getExpiration',
    'getIssuedAt', 'getRemoteAddr', 'getLocalPort', 'getRequestURI', 'getMethod', 'getAttribute', 'getEntity',
    'getBean', 'getProperty', 'getEnvironment', 'getDeclaredFields', 'getSimpleName', 'getStackTrace',
    'getTransactionManager', 'getObject', 'getContent', 'getTotalElements', 'getTotalPages', 'getNumber',
    'getSize', 'getSort', 'getPageable', 'getAnnotation', 'getType', 'getReference', 'getTimestamp', 'getSource',
    'getLocalizedMessage', 'getSuppressed', 'getOutputStream', 'getInputStream', 'getWriter', 'getReader',
    'getContentType', 'getCharacterEncoding', 'getServletPath', 'getContextPath', 'getQueryString', 'getCookies',
    'getBindingResult', 'getFieldErrors', 'getFieldError', 'getDefaultMessage', 'getField', 'getAllErrors',
    'getRejectedValue', 'getCode', 'getObjectName', 'getRequiredType', 'getParameterName', 'getMostSpecificCause',
    'getRootCause', 'getHttpMethod', 'getRequestURL', 'getForwardedHeader', 'getDetails', 'getCredentials',
    'getLocalAddr', 'getLocale', 'getMetricName', 'getAsLong', 'getAsInt', 'getAsDouble', 'getFileName',
    'getEntityManager', 'getFactory', 'getMapping', 'getTables', 'getSchema', 'getAndAdd', 'getPlain', 'getAndDecrement',
}


def strip(src: str) -> str:
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'"""(.*?)"""', lambda m: '""', src, flags=re.S)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    src = re.sub(r"'(?:[^'\\\n]|\\.)'", "''", src)
    return re.sub(r'//[^\n]*', '', src)


def top_level_split(text: str, angle: bool = False):
    """Split on top-level commas. Angle brackets count as nesting only when `angle` (parameter
    declarations such as Map<String, List<X>> m); in call arguments `->` and `<` are not brackets."""
    openers, closers = ('(<[{', ')>]}') if angle else ('([{', ')]}')
    parts, cur, d = [], [], 0
    for ch in text:
        if ch in openers:
            d += 1
        elif ch in closers:
            d -= 1
        if ch == ',' and d == 0:
            parts.append(''.join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        parts.append(''.join(cur).strip())
    return parts


def arity_of_params(params: str):
    params = re.sub(r'@\w+(\((?:[^()]|\([^()]*\))*\))?\s*', '', params).strip()
    if not params:
        return 0, False
    return len(top_level_split(params, angle=True)), '...' in params


def call_args(src: str, i: int):
    """Number of arguments in the call whose '(' is at index i-1; and the index after the ')'. """
    d, j, cur, n, saw = 1, i, [], 0, False
    while j < len(src) and d:
        ch = src[j]
        if ch in '([{':
            d += 1
        elif ch in ')]}':
            d -= 1
            if d == 0:
                break
        elif ch == '<' and d == 1:
            # generic arguments (new ArrayList<>()) or a comparison; both balanced enough for counting
            pass
        if d == 1 and ch == ',':
            n += 1
        if not ch.isspace():
            saw = True
        j += 1
    body = src[i:j]
    if not saw:
        return 0, j + 1
    # lambdas and generics contain commas at depth 1 only inside '<>' which we did not track; recount
    return len(top_level_split(body)), j + 1


def index_types():
    types = {}
    for p in sorted(JAVA.glob('**/*.java')):
        raw = p.read_text(encoding='utf-8')
        src = strip(raw)
        for m in re.finditer(r'\b(class|interface|enum|record)\s+(\w+)', src):
            kind, name = m.group(1), m.group(2)
            if name in types and types[name]['file'] != p.name:
                pass
            t = types.setdefault(name, {'kind': kind, 'file': p.name, 'methods': {}, 'static': {}, 'fields': set(),
                                        'lombok': False, 'repo': False, 'ctor': set()})
            # slice this type's own header to see annotations and extends
            head = src[max(0, m.start() - 400):m.start()]
            if re.search(r'@(Data|Getter|Setter|Builder|Value)\b', head):
                t['lombok'] = True
            if kind == 'interface' and re.search(r'Repository\s*<', src[m.start():m.start() + 300]):
                t['repo'] = True
        # a type's body: only handle the top-level type of the file for member indexing
        tm = re.search(r'\b(class|interface|enum|record)\s+(\w+)[^{;]*\{', src)
        if not tm:
            continue
        tname = tm.group(2)
        t = types[tname]
        body = src[tm.end():]
        if tm.group(1) == 'record':
            rm0 = re.search(r'\brecord\s+' + tname + r'\s*\(', src)
            i0, d0, j0 = rm0.end(), 1, rm0.end()
            while d0:
                d0 += {'(': 1, ')': -1}.get(src[j0], 0)
                j0 += 1
            t['ctor'].add(len(top_level_split(src[i0:j0 - 1], angle=True)) if src[i0:j0 - 1].strip() else 0)
            for cm in re.finditer(r'(?:public\s+)?' + tname + r'\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*\{', body):
                params = cm.group(1)
                if params.strip():     # a compact canonical constructor has no parameter list
                    t['ctor'].add(len(top_level_split(params, angle=True)))
            rm = re.search(r'\brecord\s+' + tname + r'\s*\(', src)
            i, d, j = rm.end(), 1, rm.end()
            while d:
                d += {'(': 1, ')': -1}.get(src[j], 0)
                j += 1
            for comp in top_level_split(src[i:j - 1], angle=True):
                comp = re.sub(r'@\w+(\((?:[^()]|\([^()]*\))*\))?\s*', '', comp).strip()
                mm = re.match(r'.+?\s+(\w+)$', comp, flags=re.S)
                if mm:
                    t['methods'].setdefault(mm.group(1), set()).add(0)
                    t['fields'].add(mm.group(1))
        for fm in re.finditer(r'(?:^|[;{}\n])\s*(?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*(?:private|protected|public)?\s*(?:static\s+)?(?:final\s+)?[\w<>?,.\[\] ]+?\s+(\w+)\s*(?:=[^;]*)?;', body):
            t['fields'].add(fm.group(1))
        for mm in re.finditer(
                r'(?:^|[;{}\n])\s*((?:@[\w.]+(?:\([^)]*\))?\s*)*)((?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)*)(?:<[^>]+>\s+)?([\w<>?,.\[\] ]+?)\s+(\w+)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*(?:throws [\w., ]+)?\s*[;{]',
                body):
            mods, ret, name, params = mm.group(2), mm.group(3), mm.group(4), mm.group(5)
            if name in ('if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized', 'else'):
                continue
            n, varargs = arity_of_params(params)
            t['methods'].setdefault(name, set()).add(-1 if varargs else n)
            if 'static' in mods:
                t['static'].setdefault(name, set()).add(-1 if varargs else n)
        # constructors are not resolved here
    return types


CLASS_NAMES = set()


def index_records():
    """Every record in the project, nested or not: {(outer or '', name): {arities}} and {name: {arities}}."""
    qualified, by_name = {}, {}
    for p in sorted(JAVA.glob('**/*.java')):
        src = strip(p.read_text(encoding='utf-8'))
        for cm in re.finditer(r'\bclass\s+(\w+)', src):
            CLASS_NAMES.add(cm.group(1))
        primary = re.search(r'\b(?:class|interface|enum|record)\s+(\w+)', src)
        primary = primary.group(1) if primary else ''
        for m in re.finditer(r'\brecord\s+(\w+)\s*(?:<[^>]*>)?\s*\(', src):
            name = m.group(1)
            i, d, j = m.end(), 1, m.end()
            while d:
                d += {'(': 1, ')': -1}.get(src[j], 0)
                j += 1
            comps = src[i:j - 1]
            ar = {len(top_level_split(comps, angle=True)) if comps.strip() else 0}
            for cm in re.finditer(r'(?:public\s+)?\b' + name + r'\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*\{', src[j:]):
                if cm.group(1).strip():
                    ar.add(len(top_level_split(cm.group(1), angle=True)))
            outer = '' if name == primary else primary
            qualified[(outer, name)] = ar
            by_name.setdefault(name, set()).update(ar)
    return qualified, by_name


def build_fields(src, types):
    """name -> project type, for fields declared at the top level of the file's primary type only.
    Method parameters and locals are ignored: they shadow fields and differ per method."""
    out = {}
    tm = re.search(r'\b(?:class|interface|enum|record)\s+\w+[^{;]*\{', src)
    if not tm:
        return out
    depth, start, i = 1, tm.end(), tm.end()
    stmt = []
    while i < len(src) and depth:
        ch = src[i]
        if ch == '{':
            if depth == 1:
                stmt = []          # entering a method body / nested type: the header is not a field
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 1:
                stmt = []
        elif depth == 1:
            if ch == ';':
                decl = ''.join(stmt).strip()
                stmt = []
                dm = re.match(r'(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:private|protected|public|static|final)\s+)*([A-Z]\w*)(?:<[^>]*>)?\s+(\w+)\s*(?:=.*)?$', decl, flags=re.S)
                if dm and dm.group(1) in types:
                    out[dm.group(2)] = dm.group(1)
            else:
                stmt.append(ch)
        i += 1
    return out


def ok_arity(arities, n):
    return -1 in arities or n in arities


def check_file(path, types, universe, report):
    raw = path.read_text(encoding='utf-8')
    src = strip(raw)
    fields = build_fields(src, types)
    own = re.search(r'\b(?:class|interface|enum|record)\s+(\w+)', src)
    own_name = own.group(1) if own else ''

    def where(idx):
        return f'{path.relative_to(JAVA)}:{src.count(chr(10), 0, idx) + 1}'

    # 1. field.method(...)
    for m in re.finditer(r'(?<![\w.])(\w+)\.(\w+)\s*\(', src):
        var, meth = m.group(1), m.group(2)
        if var not in fields or var == own_name:
            continue
        t = types.get(fields[var])
        if not t or t['kind'] == 'enum':
            continue
        if meth in COMMON:
            continue
        n, _ = call_args(src, m.end())
        arities = t['methods'].get(meth)
        if t['repo']:
            arities = (arities or set()) | JPA.get(meth, set())
        if not arities:
            # a Lombok getter/setter on a data class is accessor_check's job
            if t['lombok'] and re.match(r'(get|set|is)[A-Z]', meth):
                continue
            if t['kind'] == 'interface' or t['repo'] or t['kind'] == 'class':
                # inherited from a project supertype? search supers by name
                supers = re.findall(r'(?:extends|implements)\s+([\w<>, ]+?)\s*\{', src[:0])
                report.append(f'{where(m.start())}: {var}.{meth}(...) : {fields[var]} has no method "{meth}"')
            continue
        if not ok_arity(arities, n):
            report.append(f'{where(m.start())}: {var}.{meth}({n} args) : {fields[var]}.{meth} takes {sorted(a for a in arities if a >= 0)}')

    # 2. Type.staticMethod(...)
    for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\.(\w+)\s*\(', src):
        tname, meth = m.group(1), m.group(2)
        t = types.get(tname)
        if (not t or t['kind'] in ('enum',) or meth in ('builder', 'values', 'valueOf') or tname == own_name
                or meth[:1].isupper() or t['file'] != tname + '.java'):
            continue
        if t['kind'] == 'record' and meth not in t['static']:
            continue
        if tname in ('ResponseEntity', 'Optional', 'List', 'Map', 'Set'):
            continue
        n, _ = call_args(src, m.end())
        arities = t['static'].get(meth)
        if arities is None:
            if t['methods'].get(meth) is not None or t['lombok']:
                continue   # an instance method reached through something else, or generated: not judged
            report.append(f'{where(m.start())}: {tname}.{meth}(...) : {tname} has no static method "{meth}"')
        elif not ok_arity(arities, n):
            report.append(f'{where(m.start())}: {tname}.{meth}({n} args) : takes {sorted(a for a in arities if a >= 0)}')

    # 5. new Record(args) / new Outer.Record(args): argument count must match a real constructor
    for m in re.finditer(r'\bnew\s+(?:([A-Z]\w*)\.)?([A-Z]\w*)\s*\(', src):
        outer, name = m.group(1), m.group(2)
        if outer:
            ar = RECORDS[0].get((outer, name)) if name not in CLASS_NAMES or (outer, name) in RECORDS[0] else None
        else:
            ar = RECORDS[1].get(name) if name in RECORDS[1] else None
            # a class and a record sharing a simple name elsewhere: not judged
            if name in CLASS_NAMES:
                ar = None   # a record and a class share this simple name: ambiguous, not judged
        if not ar:
            continue
        n, _ = call_args(src, m.end())
        if n not in ar:
            report.append(f'{where(m.start())}: new {(outer + ".") if outer else ""}{name}({n} args) : record takes {sorted(ar)}')

    # 3. Type.builder() chains: every step must be a field of Type
    for m in re.finditer(r'\b([A-Z]\w*)\.builder\s*\(\s*\)', src):
        t = types.get(m.group(1))
        if not t:
            continue
        j, d = m.end(), 0
        while True:
            sm = re.match(r'\s*\.\s*(\w+)\s*\(', src[j:])
            if not sm:
                break
            step = sm.group(1)
            if step != 'build' and step not in t['fields']:
                report.append(f'{where(j)}: {m.group(1)}.builder().{step}(...) : {m.group(1)} has no field "{step}"')
            _, nxt = call_args(src, j + sm.end())
            j = nxt
            if step == 'build':
                break

    # 4. lambda accessors: x -> x.getFoo() must exist somewhere
    for m in re.finditer(r'\b(\w+)\s*->\s*(?:\{[^}]*?)?\b\1\.(get|is)([A-Z]\w*)\s*\(', src):
        acc = m.group(2) + m.group(3)
        prop = m.group(3)[0].lower() + m.group(3)[1:]
        if acc in EXTERNAL_ACCESSORS or prop in universe['fields'] or acc in universe['methods']:
            continue
        report.append(f'{where(m.start())}: lambda calls .{acc}() but no project type has a field "{prop}" or method {acc}')
    return


RECORDS = ({}, {})


def main():
    types = index_types()
    RECORDS[0].update(index_records()[0])
    RECORDS[1].update(index_records()[1])
    universe = {'fields': set(), 'methods': set()}
    for t in types.values():
        universe['fields'] |= t['fields']
        universe['methods'] |= set(t['methods'])
    report = []
    n = 0
    for p in sorted(JAVA.glob('**/*.java')):
        n += 1
        check_file(p, types, universe, report)
    print('== Cross-class calls (by declared type), static calls, builders, lambdas ==')
    print(f'   {len(types)} project types indexed, {n} files scanned')
    if report:
        for r in report:
            print(f'   [!] {r}')
        return 1
    print('   every call resolves')
    return 0


if __name__ == '__main__':
    sys.exit(main())
