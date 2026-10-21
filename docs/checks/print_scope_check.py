#!/usr/bin/env python3
"""
Two unscoped print rules fight. The app's original print rule hides everything with `body * { visibility:
hidden }` and shows `.print-area`. A second unscoped rule of the same kind (for receipts, QR cards...) with
`!important`, or just coming later, can silently make the FIRST one's Print button produce a blank page, and
it only shows on paper, never in a test.

This fails if styles.css has more than one UNSCOPED `body *` rule inside @media print. A rule scoped by a body
class (`body.print-receipt *`) is fine: it applies only while that page is open.
"""
import re
import sys
from pathlib import Path

CSS = Path(__file__).resolve().parents[2] / 'frontend/src/styles.css'
ALLOWED_UNSCOPED = 1     # the app's original print rule, used by the QR dialog


def print_blocks(css):
    for m in re.finditer(r'@media\s+print\s*\{', css):
        depth, i = 1, m.end()
        while depth and i < len(css):
            depth += {'{': 1, '}': -1}.get(css[i], 0)
            i += 1
        yield css[m.end():i - 1], css.count('\n', 0, m.start()) + 1


def main():
    css = re.sub(r'/\*[\s\S]*?\*/', lambda m: '\n' * m.group(0).count('\n'), CSS.read_text(encoding='utf-8'))
    unscoped = []
    for body, start in print_blocks(css):
        for r in re.finditer(r'(^|[}\s,])((?:body|html)\s*\*)\s*(?=[,{])', body):
            line = start + body.count('\n', 0, r.start(2))
            unscoped.append(line)
    print('== Print rules that hide the page ==')
    print(f'   {len(unscoped)} unscoped "body *" rule(s) inside @media print (allowed: {ALLOWED_UNSCOPED})')
    if len(unscoped) > ALLOWED_UNSCOPED:
        print(f'   [!] a second unscoped rule at line(s) {unscoped}: it can blank the QR dialog\'s Print button. '
              f'Scope it with a body class, for example body.print-receipt *')
        return 1
    print('   every other print rule is scoped to a page-specific body class')
    return 0


if __name__ == '__main__':
    sys.exit(main())
