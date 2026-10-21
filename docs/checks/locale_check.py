#!/usr/bin/env python3
"""
The app is English only. This finds formatting that would instead follow the DEVICE's language: a guest
whose phone is set to Hindi, Urdu or Arabic would then see other numerals, month names or date orders.

Flagged in frontend/src (tests excluded):
  * Intl.NumberFormat / Intl.DateTimeFormat / Intl.* constructed with `undefined` or no locale
  * toLocaleString / toLocaleDateString / toLocaleTimeString with no locale, `undefined` or `[]`
An explicit English locale ('en-IN', 'en-GB', ...) is fine.
"""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / 'frontend/src'

INTL = re.compile(r'\bnew\s+Intl\.(\w+)\s*\(\s*(undefined|\)|\[\s*\])')
LOCALE_FN = re.compile(r'\.(toLocale(?:Date|Time)?String)\s*\(\s*(\)|undefined|\[\s*\])')
INTL_ANY = re.compile(r'\bnew\s+Intl\.(\w+)\s*\(\s*([\'"`]?)([\w-]*)')


def strip(s):
    s = re.sub(r'/\*[\s\S]*?\*/', '', s)
    return re.sub(r'(?<![:\'"`])//[^\n]*', '', s)


def main():
    bad, files, uses = [], 0, 0
    for f in sorted(list(SRC.rglob('*.ts')) + list(SRC.rglob('*.tsx'))):
        if '.test.' in f.name:
            continue
        files += 1
        s = strip(f.read_text(encoding='utf-8'))
        for m in INTL.finditer(s):
            bad.append(f'{f.relative_to(SRC)}:{s.count(chr(10), 0, m.start()) + 1}: new Intl.{m.group(1)}({m.group(2)}...) follows the device language')
        for m in LOCALE_FN.finditer(s):
            bad.append(f'{f.relative_to(SRC)}:{s.count(chr(10), 0, m.start()) + 1}: .{m.group(1)}() follows the device language')
        for m in INTL_ANY.finditer(s):
            if m.group(2):
                uses += 1
                if not m.group(3).lower().startswith('en'):
                    bad.append(f'{f.relative_to(SRC)}:{s.count(chr(10), 0, m.start()) + 1}: Intl.{m.group(1)} uses locale "{m.group(3)}", which is not English')
    print('== Display language (English only) ==')
    print(f'   {files} frontend files scanned; {uses} explicit-locale Intl formatters, all English')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   nothing formats by the device language')
    return 0


if __name__ == '__main__':
    sys.exit(main())
