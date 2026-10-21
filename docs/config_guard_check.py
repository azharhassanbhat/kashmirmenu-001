#!/usr/bin/env python3
"""
Runs the JwtProperties start-up guard against every application*.yml in the repository.

Written after that guard rejected this project's own test fixture: jwt.secret in application-test.yml
contained "secret-key", which an over-broad banned list refused, so every @SpringBootTest failed at
context startup - 81 tests, one cause.

The lesson is narrower than "test your validators": a validator that runs at start-up is part of the
build, so its rules have to be checked against every value the build will actually feed it. The rules
are parsed out of the Java rather than restated here, so the two cannot drift.
"""
import re
import sys
from pathlib import Path

ROOT = Path('/home/claude/kashmirmenu')
PROPS = ROOT / 'src/main/java/com/kashmirmenu/app/security/jwt/JwtProperties.java'


def guard_rules():
    """The banned substrings and minimum length, read from the source of truth."""
    src = PROPS.read_text(encoding='utf-8')
    code = re.sub(r'//[^\n]*', '', src)
    banned = re.findall(r'"([a-z-]+)"', code[code.index('for (String banned'):][:400])
    length = re.search(r'length\s*<\s*(\d+)', code)
    return banned, int(length.group(1)) if length else 32


def configured_secrets():
    """[(file, profile, value)] for every literal jwt.secret, with ${VAR:default} resolved."""
    out = []
    for p in sorted((ROOT / 'src/main/resources').glob('application*.y*ml')):
        text = p.read_text(encoding='utf-8')
        m = re.search(r'^jwt:\s*$(.*?)(?=^\S|\Z)', text, re.M | re.S)
        if not m:
            continue
        s = re.search(r'secret:\s*(\S.*)', m.group(1))
        if not s:
            continue
        value = s.group(1).strip()
        # ${JWT_SECRET:default} -> default; ${JWT_SECRET} -> supplied at runtime, nothing to check.
        env = re.match(r'\$\{(\w+)(?::(.*))?\}$', value)
        if env:
            if env.group(2) is None:
                out.append((p.name, 'runtime', None))
                continue
            value = env.group(2)
        out.append((p.name, 'literal', value))
    return out


def main():
    banned, min_len = guard_rules()
    print('== JwtProperties guard vs. configured secrets ==')
    print(f'   banned substrings: {banned}')
    print(f'   minimum length: {min_len} bytes')

    problems = []
    for name, kind, value in configured_secrets():
        if value is None:
            print(f'   ok  {name}: supplied at runtime, nothing committed')
            continue
        lowered = value.lower()
        hit = next((b for b in banned if b in lowered), None)
        if hit:
            problems.append(f'{name}: value contains banned substring "{hit}" -> context will not start')
            print(f'   [!] {name}: contains "{hit}" - the guard will reject it at start-up')
        elif len(value.encode()) < min_len:
            problems.append(f'{name}: {len(value.encode())} bytes, under the {min_len}-byte minimum')
            print(f'   [!] {name}: only {len(value.encode())} bytes')
        else:
            print(f'   ok  {name}: {len(value.encode())} bytes, no banned substring')

    print('   every configured secret passes its own guard' if not problems
          else f'   {len(problems)} configuration(s) the guard would reject')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
