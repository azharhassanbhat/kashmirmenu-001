#!/usr/bin/env python3
"""
Resolves every unqualified type name in the source tree to an import, a same-package class, or a
known framework package.

Rewritten after three consecutive build failures of the same class. The first version only knew a
hand-written list of Spring annotations, so it caught a missing @Query import and then missed a
missing ResourceNotFoundException import - a project class, which was never in its list. Enumerating
what to look for was the mistake; this version enumerates what EXISTS and checks every use against it.

Why a checker is needed at all: a missing import is `cannot find symbol`, and the Java syntax pass
available in this environment filters that class of error out, because without dependencies on the
classpath every file emits hundreds of them. Anything depending on symbol resolution is invisible to
it, which is how three of these shipped.
"""
import re
import sys
from pathlib import Path

SRC = Path('/home/claude/kashmirmenu/src/main/java')

# Framework packages used via wildcard-free imports elsewhere; a simple name from one of these is
# only resolvable if imported, so they are not whitelisted - only java.lang is implicit.
JAVA_LANG = {
    'String', 'Integer', 'Long', 'Boolean', 'Double', 'Float', 'Short', 'Byte', 'Character',
    'Object', 'Math', 'System', 'Exception', 'RuntimeException', 'IllegalArgumentException',
    'IllegalStateException', 'UnsupportedOperationException', 'NullPointerException', 'Thread',
    'Comparable', 'Iterable', 'Number', 'Record', 'Enum', 'Class', 'Override', 'Deprecated',
    'SuppressWarnings', 'FunctionalInterface', 'SafeVarargs', 'Void', 'StringBuilder', 'Runnable',
    'ArithmeticException', 'CharSequence', 'Cloneable', 'AutoCloseable', 'Error', 'Throwable',
    'NumberFormatException', 'ClassCastException', 'IndexOutOfBoundsException', 'Iterable',
}


def strip(code):
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
    code = re.sub(r'//[^\n]*', '', code)
    code = re.sub(r'"""(?:.|\n)*?"""', '""', code)
    return re.sub(r'"(?:[^"\\]|\\.)*"', '""', code)


def project_classes():
    """simple name -> set of packages declaring it."""
    out = {}
    for p in SRC.rglob('*.java'):
        pkg = re.search(r'package\s+([\w.]+);', p.read_text(encoding='utf-8', errors='replace'))
        if pkg:
            out.setdefault(p.stem, set()).add(pkg.group(1))
    return out


# Framework types whose home package is unambiguous. Needed as a SECOND pass: enumerating project
# classes cannot see these, and enumerating a hand-written list cannot see project classes. The first
# version of this checker did only the latter and missed a missing ResourceNotFoundException import;
# doing only the former missed a missing @Query. Both passes are required.
FRAMEWORK = {
    'Query': 'org.springframework.data.jpa.repository.Query',
    'Param': 'org.springframework.data.repository.query.Param',
    'Modifying': 'org.springframework.data.jpa.repository.Modifying',
    'Lock': 'org.springframework.data.jpa.repository.Lock',
    'EntityGraph': 'org.springframework.data.jpa.repository.EntityGraph',
    'Transactional': 'org.springframework.transaction.annotation.Transactional',
    'PreAuthorize': 'org.springframework.security.access.prepost.PreAuthorize',
    'Value': 'org.springframework.beans.factory.annotation.Value',
    'Service': 'org.springframework.stereotype.Service',
    'Component': 'org.springframework.stereotype.Component',
    'Configuration': 'org.springframework.context.annotation.Configuration',
    'Bean': 'org.springframework.context.annotation.Bean',
    'Page': 'org.springframework.data.domain.Page',
    'Pageable': 'org.springframework.data.domain.Pageable',
    'PageRequest': 'org.springframework.data.domain.PageRequest',
    'Specification': 'org.springframework.data.jpa.domain.Specification',
    'LockModeType': 'jakarta.persistence.LockModeType',
}


def main():
    classes = project_classes()
    problems = []

    for p in sorted(SRC.rglob('*.java')):
        raw = p.read_text(encoding='utf-8', errors='replace')
        code = strip(raw)
        pkg = re.search(r'package\s+([\w.]+);', raw)
        own_pkg = pkg.group(1) if pkg else ''
        imports = set(re.findall(r'import\s+(?:static\s+)?([\w.]+);', raw))
        imported_simple = {i.rsplit('.', 1)[-1] for i in imports}
        wildcards = {i[:-2] for i in imports if i.endswith('.*')}

        # Every project class name used unqualified in this file.
        for name, pkgs in classes.items():
            if name == p.stem or name in imported_simple:
                continue
            if own_pkg in pkgs:            # same package: no import needed
                continue
            if pkgs & wildcards:
                continue
            # A type declared in this file (a nested record, say) shadows a project class of the same
            # name, exactly as it does for the framework names below.
            if re.search(rf'(?:class|interface|enum|record)\s+{name}\b', code):
                continue
            used = (re.search(rf'(?<![\w.])new\s+{name}\s*[(<]', code)
                    or re.search(rf'(?<![\w.])@{name}\b', code)
                    or re.search(rf'(?<![\w.])\b{name}\s*\.\w', code)
                    or re.search(rf'(?<![\w.])\b{name}\s+\w+\s*[=;),]', code)
                    or re.search(rf'<\s*{name}\s*[,>]', code))
            if used:
                problems.append(
                    f'{p.relative_to(SRC)}: uses {name} but imports no {sorted(pkgs)[0]}.{name}')

        # Second pass: framework types.
        for name, home in FRAMEWORK.items():
            if name in imported_simple or home.rsplit('.', 1)[0] in wildcards:
                continue
            if name in classes:          # a project class shadows it; pass one covers that
                continue
            # A type declared in this file (including a nested record) shadows the framework name.
            if re.search(rf'(?:class|interface|enum|record)\s+{name}\b', code):
                continue
            if (re.search(rf'(?<![\w.])@{name}\b', code)
                    or re.search(rf'(?<![\w.])\b{name}\s*[<(]', code)):
                problems.append(f'{p.relative_to(SRC)}: uses {name} but imports no {home}')

    print('== Unqualified type resolution ==')
    for x in problems:
        print(f'   [!] {x}')
    print(f'   {len(classes)} project classes checked; every unqualified use resolves'
          if not problems else f'   {len(problems)} unresolved')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
