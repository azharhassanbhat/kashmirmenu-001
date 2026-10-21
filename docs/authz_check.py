#!/usr/bin/env python3
"""
Sandbox-only verifier (not part of the repo).

Gradle cannot run here, so the two changes most likely to break the application are checked
mechanically instead:

  1. Authorization matrix - every controller endpoint is resolved against the SecurityConfig
     matcher list in declaration order (the way Spring evaluates them) and against the
     class-level @PreAuthorize, then reported per role. Catches an endpoint that fell through
     to denyAll, a contradiction between the two layers, and any path a CUSTOMER can still reach.

  2. Entity/migration consistency - ddl-auto is 'validate', so a column an entity declares but
     the migrations never create is a startup failure, not a runtime one.

This is not a substitute for ./gradlew test. It cannot see Spring's actual matcher semantics,
SpEL evaluation, or anything that only fails at runtime.
"""
import re
import sys
from pathlib import Path

ROOT = Path('/home/claude/kashmirmenu')
JAVA = ROOT / 'src/main/java/com/kashmirmenu/app'
MIGRATIONS = ROOT / 'src/main/resources/db/migration'

ALL_ROLES = ['PLATFORM_OWNER', 'ADMIN', 'RESTAURANT_OWNER', 'MANAGER', 'CHEF', 'WAITER',
             'CASHIER', 'CUSTOMER']

def _parse_groups():
    src = (Path('/home/claude/kashmirmenu') /
           'src/main/java/com/kashmirmenu/app/config/SecurityConfig.java').read_text(
               encoding='utf-8', errors='replace')
    groups = {}
    for m in re.finditer(r'static final String\[\]\s+(\w+)\s*=\s*\{([^}]*)\}', src):
        groups[m.group(1)] = re.findall(r'"([A-Z_]+)"', m.group(2))
    return groups


GROUPS = _parse_groups()

MAPPING = re.compile(
    r'@(Get|Post|Put|Patch|Delete|Request)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
BARE_MAPPING = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\b(?!\s*\()')


def read(p):
    return p.read_text(encoding='utf-8', errors='replace')


# ---------------------------------------------------------------- endpoints

def controller_endpoints():
    """[(path, controller, class_roles_or_None)] for every mapped handler."""
    out = []
    for f in sorted((JAVA / 'controller').glob('*.java')):
        src = read(f)
        base_m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', src)
        base = base_m.group(1) if base_m else ''

        # Class-level @PreAuthorize, if any (method-level ones are reported separately).
        header = src[:src.index('public class')] if 'public class' in src else src
        class_roles = roles_from_preauthorize(header)

        body = src[src.index('public class'):] if 'public class' in src else src
        for m in MAPPING.finditer(body):
            sub = m.group(2)
            out.append((join(base, sub), f.name, tuple(class_roles) if class_roles else None))
        for _ in BARE_MAPPING.finditer(body):
            out.append((join(base, ''), f.name, tuple(class_roles) if class_roles else None))
    return out


def roles_from_preauthorize(text):
    m = re.search(r"@PreAuthorize\(\"([^\"]+)\"\)", text)
    if not m:
        return None
    expr = m.group(1)
    if 'isAuthenticated' in expr:
        return list(ALL_ROLES)
    found = re.findall(r"'([A-Z_]+)'", expr)
    return found or None


def join(base, sub):
    if not sub:
        return base or '/'
    if not base:
        return sub
    return base.rstrip('/') + '/' + sub.lstrip('/')


# ---------------------------------------------------------------- matchers

def security_rules():
    """[(patterns, decision)] in declaration order, as Spring evaluates them."""
    src = read(JAVA / 'config/SecurityConfig.java')
    chain = src[src.index('authorizeHttpRequests'):src.index('.addFilterBefore')]

    rules = []
    # Each .requestMatchers(...)<decision>, decision being permitAll/authenticated/hasAnyRole/denyAll.
    for m in re.finditer(
            r'\.requestMatchers\((.*?)\)\s*(?:\r?\n\s*)?\.(permitAll|authenticated|denyAll|hasAnyRole)\(([^)]*)\)',
            chain, re.S):
        patterns = re.findall(r'"([^"]+)"', m.group(1))
        kind = m.group(2)
        if kind == 'hasAnyRole':
            roles = []
            for token in re.findall(r'"([A-Z_]+)"|(\b[A-Z]+\b)', m.group(3)):
                literal, group = token
                if literal:
                    roles.append(literal)
                elif group in GROUPS:
                    roles.extend(GROUPS[group])
            decision = roles
        elif kind == 'permitAll':
            decision = 'ANON'
        elif kind == 'authenticated':
            decision = list(ALL_ROLES)
        else:
            decision = []
        rules.append((patterns, decision))

    if re.search(r'\.anyRequest\(\)\s*\.denyAll\(\)', chain):
        rules.append((['/**'], []))
    elif re.search(r'\.anyRequest\(\)\s*\.authenticated\(\)', chain):
        rules.append((['/**'], list(ALL_ROLES)))
    return rules


def ant_match(pattern, path):
    if pattern.endswith('/**'):
        prefix = pattern[:-3]
        return path == prefix or path.startswith(prefix + '/') or path.startswith(prefix)
    regex = '^' + re.escape(pattern).replace(r'\*', '[^/]*').replace(r'\{', '{') + '$'
    regex = re.sub(r'\\\{[^}]*\\\}', '[^/]+', '^' + re.escape(pattern) + '$')
    return re.match(regex, path) is not None


def decide(path, rules):
    for patterns, decision in rules:
        for p in patterns:
            if ant_match(p, path):
                return decision, p
    return [], '(no rule)'


# ---------------------------------------------------------------- checks

def check_authorization():
    print('== Authorization matrix ==')
    rules = security_rules()
    print(f'   {len(rules)} rules parsed from SecurityConfig\n')

    problems = []
    for path, controller, class_roles in sorted(set(controller_endpoints())):
        decision, pattern = decide(path, rules)

        if decision == 'ANON':
            effective = 'anonymous'
            reachable = set(ALL_ROLES)
        else:
            reachable = set(decision)
            effective = ','.join(sorted(reachable)) or 'NOBODY'

        # Method security narrows further; the intersection is what actually gets through.
        if class_roles is not None and decision != 'ANON':
            combined = reachable & set(class_roles)
        else:
            combined = reachable

        flag = ''
        if decision != 'ANON' and not reachable:
            flag = 'UNREACHABLE (falls through to denyAll)'
        elif class_roles is not None and decision != 'ANON' and not combined:
            flag = (f'CONTRADICTION filter={sorted(reachable)} '
                    f'@PreAuthorize={sorted(class_roles)}')
        elif 'CUSTOMER' in combined and not path.startswith(('/api/public', '/api/v1/auth')) \
                and path not in ('/api/v1/me',) and not path.startswith('/api/restaurants'):
            flag = 'REACHABLE BY CUSTOMER'

        if flag:
            problems.append((path, controller, flag))
            print(f'   [!] {path:<58} {controller:<38} {flag}')

    if not problems:
        print('   every endpoint resolves to an intended, non-empty role set')
    print()
    return problems


def check_duplicate_annotations():
    """
    Two @PreAuthorize on one element is a compile error - the annotation is not repeatable. A bulk
    edit that adds a class-level rule to a controller which already had one produces exactly this,
    and no amount of path analysis will notice, so it is checked directly.
    """
    print('== Duplicate non-repeatable annotations ==')
    problems = []
    single = ['PreAuthorize', 'RequestMapping', 'RestController', 'Service', 'Component',
              'Transactional', 'Entity', 'Table']
    for f in sorted(JAVA.rglob('*.java')):
        src = read(f)
        if 'public class' not in src and 'public interface' not in src:
            continue
        marker = 'public class' if 'public class' in src else 'public interface'
        header = src[:src.index(marker)]
        # Only the annotation block, i.e. after the imports.
        header = header[header.rfind('import'):] if 'import' in header else header
        for ann in single:
            n = len(re.findall(r'@' + ann + r'\b', header))
            if n > 1:
                problems.append(f'{f.name}: @{ann} appears {n} times on the type')
                print(f'   [!] {f.name}: @{ann} declared {n} times on the type (not repeatable)')
    if not problems:
        print('   no type carries a duplicated non-repeatable annotation')
    print()
    return problems


def check_platform_role_isolation():
    """
    PLATFORM_OWNER must appear in exactly one place: the /api/platform-admin/** matcher. Granting it
    anywhere else would hand a platform owner restaurant-scoped endpoints, which resolve data through
    the caller's own restaurant - something a platform owner does not have. Conversely, no restaurant
    role may appear on a platform-admin matcher.
    """
    print('== Platform role isolation ==')
    problems = []

    cfg = read(JAVA / 'config/SecurityConfig.java')
    chain = cfg[cfg.index('authorizeHttpRequests'):cfg.index('.addFilterBefore')]
    for m in re.finditer(r'\.requestMatchers\((.*?)\)\s*(?:\r?\n\s*)?\.(?:hasAnyRole|hasRole)\(([^)]*)\)',
                         chain, re.S):
        patterns = re.findall(r'"([^"]+)"', m.group(1))
        roles = re.findall(r'"([A-Z_]+)"', m.group(2))
        for name in re.findall(r'\b([A-Z][A-Z_]+)\b', m.group(2)):
            if name in GROUPS:
                roles.extend(GROUPS[name])
        platform_path = any(p.startswith('/api/platform-admin') for p in patterns)
        if 'PLATFORM_OWNER' in roles and not platform_path:
            problems.append(f'PLATFORM_OWNER granted on non-platform paths {patterns}')
            print(f'   [!] PLATFORM_OWNER granted on {patterns}')
        if platform_path and [r for r in roles if r != 'PLATFORM_OWNER']:
            other = [r for r in roles if r != 'PLATFORM_OWNER']
            problems.append(f'restaurant role(s) {other} granted on {patterns}')
            print(f'   [!] {other} granted on platform path {patterns}')

    # And not via method security on any non-platform controller either.
    for f in sorted((JAVA / 'controller').glob('*.java')):
        src = read(f)
        if 'PLATFORM_OWNER' in src:
            problems.append(f'{f.name}: mentions PLATFORM_OWNER outside the platform package')
            print(f'   [!] {f.name} mentions PLATFORM_OWNER')

    # Every platform endpoint must be covered: either its own annotation or the chain matcher.
    plat = JAVA / 'platform'
    if plat.exists():
        for f in sorted(plat.glob('*Controller.java')):
            src = read(f)
            if '/api/platform-admin' not in src:
                problems.append(f'{f.name}: not under /api/platform-admin')
                print(f'   [!] {f.name} is not under /api/platform-admin')

    if not problems:
        print('   PLATFORM_OWNER appears only on /api/platform-admin/**, and nothing else does')
    print()
    return problems


def check_entities_against_migrations():
    print('== Entity columns vs Flyway migrations (ddl-auto=validate) ==')
    ddl = '\n'.join(read(p) for p in sorted(MIGRATIONS.glob('*.sql')))
    ddl_lower = ddl.lower()

    problems = []
    for f in sorted((JAVA / 'entity').glob('*.java')):
        src = read(f)
        tm = re.search(r'@Table\(\s*(?:name\s*=\s*)?"([^"]+)"', src)
        if not tm:
            continue
        table = tm.group(1)
        if f'`{table}`' not in ddl_lower and f' {table} ' not in ddl_lower \
                and f'table {table}' not in ddl_lower:
            problems.append(f'{f.name}: table "{table}" not found in any migration')
            print(f'   [!] {f.name}: table "{table}" never created')
            continue

        for cm in re.finditer(r'@Column\(\s*name\s*=\s*"([^"]+)"', src):
            col = cm.group(1)
            if col.lower() not in ddl_lower:
                problems.append(f'{f.name}: column "{col}" ({table}) not in migrations')
                print(f'   [!] {f.name}: column "{col}" on {table} is never created')

    if not problems:
        print('   every @Table and named @Column appears in the migrations')
    print()
    return problems


def check_frontend_routes():
    """Any frontend call to a path the backend no longer serves."""
    print('== Frontend API calls vs backend mappings ==')
    api = read(ROOT / 'frontend/src/api/index.ts')
    backend = {p for p, _, _ in controller_endpoints()}

    problems = []
    for m in re.finditer(r"`(/api/[^`$]*)", api):
        path = m.group(1).rstrip('/')
        if not path or '${' in path:
            continue
        if not any(ant_match(b.replace('{', '{'), path) or path.startswith(b.split('{')[0].rstrip('/'))
                   for b in backend):
            problems.append(path)
            print(f'   [!] frontend calls {path} - no backend mapping starts with it')
    if not problems:
        print('   every literal frontend API path has a matching backend prefix')
    print()
    return problems


if __name__ == '__main__':
    found = []
    found += check_authorization()
    found += check_duplicate_annotations()
    found += check_platform_role_isolation()
    found += check_entities_against_migrations()
    found += check_frontend_routes()
    print('=' * 70)
    print(f'{len(found)} problem(s) found' if found else 'No problems found by these checks.')
    sys.exit(1 if found else 0)
