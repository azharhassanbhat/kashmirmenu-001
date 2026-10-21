#!/usr/bin/env python3
"""
Every JSON key an integration test WRITES (in a request body) or READS (`.get("key")`, jsonPath) must be
a real property of some request/response object on the server. A misspelt key in a request body is
silently ignored by Jackson; a misspelt key in a read returns null and an NPE or a vacuous assertion.

Scope is deliberately global (a key must exist on SOME DTO, record or entity), so this finds typos, not
wrong-object mistakes. Pre-existing unknown keys are listed as notes; keys in the four Stage A-D test
classes must all resolve.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java'
TESTS = ROOT / 'src/test/java'
STRICT = {'ServiceRequestIT.java', 'MenuOptionsIT.java', 'BillingChargesIT.java', 'StaffIT.java',
          'MenuImportIT.java', 'OnboardingIT.java', 'OrderHistoryIT.java', 'ImageUploadIT.java', 'EnglishOnlyIT.java', 'FeedbackIT.java', 'ReceiptIT.java', 'QrCardsIT.java'}

# Not DTO fields: framework or wrapper keys the tests legitimately read.
FRAMEWORK = {'data', 'success', 'message', 'items', 'page', 'size', 'content', 'totalElements', 'totalPages',
             'errors', 'field', 'timestamp', 'status', 'error', 'path', 'fieldErrors'}


def field_names():
    names = set(FRAMEWORK)
    for p in JAVA.glob('**/*.java'):
        src = re.sub(r'/\*.*?\*/', '', p.read_text(encoding='utf-8'), flags=re.S)
        src = re.sub(r'(?<!:)//[^\n]*', '', src)
        for m in re.finditer(r'private\s+(?!static)[\w.<>?,\[\] ]+?\s+(\w+)\s*(?:=[^;]*)?;', src):
            names.add(m.group(1))
        for m in re.finditer(r'\brecord\s+\w+\s*\(', src):
            i, depth, j = m.end(), 1, m.end()
            while depth:
                depth += {'(': 1, ')': -1}.get(src[j], 0)
                j += 1
            for part in re.split(r',(?![^<(]*[>)])', src[i:j - 1]):
                part = re.sub(r'@\w+(\((?:[^()]|\([^()]*\))*\))?\s*', '', part).strip()
                mm = re.match(r'.+?\s+(\w+)$', part, flags=re.S)
                if mm:
                    names.add(mm.group(1))
        # getters on classes without fields (e.g. interface projections) and enum-style accessors
        for m in re.finditer(r'\bget([A-Z]\w*)\s*\(\s*\)', src):
            n = m.group(1)
            names.add(n[0].lower() + n[1:])
    return names


def test_keys(src: str):
    keys = set()
    # keys in JSON request bodies: "key": inside Java string literals (escaped quotes or text blocks)
    for m in re.finditer(r'\\"(\w+)\\"\s*:', src):
        keys.add((m.group(1), 'written'))
    for m in re.finditer(r'(?<!\\)"(\w+)"\s*:', re.sub(r'"""(.*?)"""', lambda t: t.group(1), src, flags=re.S)):
        pass
    for tb in re.finditer(r'"""(.*?)"""', src, flags=re.S):
        for m in re.finditer(r'"(\w+)"\s*:', tb.group(1)):
            keys.add((m.group(1), 'written'))
    for m in re.finditer(r'\.get\(\s*"(\w+)"\s*\)', src):
        keys.add((m.group(1), 'read'))
    for m in re.finditer(r'\.has\(\s*"(\w+)"\s*\)', src):
        keys.add((m.group(1), 'read'))
    for m in re.finditer(r'jsonPath\(\s*"\$\.([\w.]+)"', src):
        for k in m.group(1).split('.'):
            keys.add((k, 'read'))
    for m in re.finditer(r'\.put\(\s*"(\w+)"\s*,', src):
        keys.add((m.group(1), 'written'))
    return keys


def main():
    known = field_names()
    bad, notes, total = [], [], 0
    for p in sorted(TESTS.glob('**/*IT.java')):
        src = p.read_text(encoding='utf-8')
        for key, how in sorted(test_keys(src)):
            total += 1
            if key not in known:
                (bad if p.name in STRICT else notes).append(f'{p.name}: {how} key "{key}" is not a field on any DTO/entity')
    print('== JSON keys in integration tests vs server fields ==')
    print(f'   {len(known)} known field names; {total} key uses checked')
    for n in notes:
        print(f'   (note, pre-existing) {n}')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   every key in the new integration tests is a real field')
    return 0


if __name__ == '__main__':
    sys.exit(main())
