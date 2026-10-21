#!/usr/bin/env python3
"""
Finds a method declared in a class that has the same NAME as a static import in the same file.

In Java a method declared in a class hides every static import of that name, whatever its parameters, so
`status().isOk()` stops compiling the moment the class also declares `int status(Shop s, long id)`. It is
a compile error that no name-based or arity-based checker sees, and a very easy one to make in tests that
statically import get/post/put/status/assertEquals and then add helpers with natural names.

Handles explicit imports (`import static x.Y.name;`) and the wildcard imports tests use for JUnit's
Assertions and MockMvc's request builders and result matchers, using the names those classes define.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

WILDCARDS = {
    'org.junit.jupiter.api.Assertions': {
        'assertEquals', 'assertNotEquals', 'assertTrue', 'assertFalse', 'assertNull', 'assertNotNull', 'assertSame',
        'assertNotSame', 'assertThrows', 'assertDoesNotThrow', 'assertArrayEquals', 'assertAll', 'fail',
        'assertInstanceOf', 'assertIterableEquals', 'assertLinesMatch', 'assertTimeout'},
    'org.springframework.test.web.servlet.request.MockMvcRequestBuilders': {
        'get', 'post', 'put', 'delete', 'patch', 'options', 'head', 'request', 'multipart', 'asyncDispatch'},
    'org.springframework.test.web.servlet.result.MockMvcResultMatchers': {
        'status', 'content', 'jsonPath', 'header', 'view', 'model', 'redirectedUrl', 'forwardedUrl', 'cookie',
        'flash', 'request', 'handler', 'xpath'},
}

METHOD = re.compile(
    r'(?:^|\n)[ \t]+(?:(?:public|private|protected|static|final|synchronized|abstract)\s+)*'
    r'(?:<[^>]+>\s+)?[\w<>\[\]?,.]+(?:\s*<[^>]*>)?\s+(\w+)\s*\([^;{)]*\)\s*(?:throws [\w., ]+)?\s*\{')
KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'else', 'try', 'synchronized', 'do'}


def strip(s):
    s = re.sub(r'/\*[\s\S]*?\*/', '', s)
    s = re.sub(r'"""[\s\S]*?"""', '""', s)
    s = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', s)
    return re.sub(r'(?<!:)//[^\n]*', '', s)


def main():
    bad, files, imports = [], 0, 0
    for f in sorted(list((ROOT / 'src').rglob('*.java'))):
        raw = f.read_text(encoding='utf-8')
        names = {}
        for m in re.finditer(r'^import\s+static\s+([\w.]+)\.(\w+|\*)\s*;', raw, flags=re.M):
            owner, name = m.group(1), m.group(2)
            if name == '*':
                for n in WILDCARDS.get(owner, ()):
                    names[n] = owner
            else:
                names[name] = owner
        if not names:
            continue
        files += 1
        imports += len(names)
        src = strip(raw)
        for m in METHOD.finditer(src):
            n = m.group(1)
            if n in KEYWORDS or n not in names:
                continue
            line = src.count('\n', 0, m.start(1)) + 1
            bad.append(f'{f.relative_to(ROOT)}:{line}: method "{n}" hides the static import of {names[n]}.{n}')
    print('== Methods that hide a static import ==')
    print(f'   {files} files with static imports, {imports} imported names checked')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   no declared method hides a static import')
    return 0


if __name__ == '__main__':
    sys.exit(main())
