#!/usr/bin/env python3
"""
Compares each TypeScript interface in frontend/src/api/types.ts with the Java response (or request)
object it mirrors: field NAMES and basic TYPE KIND (number / string / boolean / array / object).

A field the screen reads but the server never sends is silently `undefined` in the browser: a blank
price, a button that never appears. A field the screen sends that the server's request object lacks is
silently ignored. Neither is a compile error on either side.

Pairing: TS `Foo` <-> Java `FooResponse` / `Foo`, plus the explicit table below. Interfaces with no Java
counterpart (client-only view models) are listed, not guessed at.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java'
TYPES = ROOT / 'frontend/src/api/types.ts'

# TS interface -> Java type (simple name, or Outer.Inner for a nested record)
EXPLICIT = {
    'StaffServiceRequest': 'ServiceRequestResponse',
    'ServiceRequestEvent': 'ServiceRequestResponse.Event',
    'PublicServiceRequest': 'PublicServiceRequestResponse',
    'OptionChoice': 'OptionResponse',
    'OptionGroup': 'OptionGroupResponse',
    'SelectedOption': 'SelectedOptionResponse',
    'Tag': 'TagResponse',
    'StaffMember': 'StaffResponse',
    'BillingSettings': 'BillingSettingsResponse',
    'MenuItemInput': 'MenuItemRequest',
    'KitchenOrder': 'KitchenOrderResponse',
    'OrderTracking': 'OrderTrackingResponse',
    'Order': 'OrderResponse',
    'OrderItem': 'OrderItemResponse',
    'Cart': 'CartResponse',
    'CartItem': 'CartItemResponse',
    'MenuItem': 'MenuItemResponse',
    'OnboardingProgress': 'OnboardingRules.Progress',
    'OnboardingStep': 'OnboardingRules.Step',
    'MenuImportPreview': 'MenuImportPreviewResponse',
    'MenuImportRow': 'MenuImportPreviewResponse.Row',
    'MenuImportProblem': 'MenuCsv.Problem',
    'MenuImportResult': 'MenuImportResultResponse',
    'UploadedImage': 'ImageUploadResponse',
    'FeedbackSaved': 'FeedbackResponse',
    'FeedbackEntry': 'FeedbackEntryResponse',
    'FeedbackSummary': 'FeedbackSummaryResponse',
    'QrCards': 'QrCardsResponse',
    'Receipt': 'ReceiptResponse',
    'ReceiptRestaurant': 'ReceiptResponse.Restaurant',
    'ReceiptItem': 'ReceiptResponse.Item',
    'ReceiptPayment': 'ReceiptResponse.Payment',
}

NUMERIC = {'BigDecimal', 'Long', 'Integer', 'long', 'int', 'Double', 'double', 'Short', 'Float'}
STRINGY = {'String', 'LocalDateTime', 'LocalDate', 'LocalTime', 'Instant', 'UUID'}


def java_kind(jtype: str, enums: set):
    base = re.sub(r'<.*', '', jtype.strip()).split('.')[-1]
    if base in NUMERIC:
        return 'number'
    if base in STRINGY or base in enums:
        return 'string'
    if base in ('Boolean', 'boolean'):
        return 'boolean'
    if base in ('List', 'Set', 'Collection') or jtype.strip().endswith('[]'):
        return 'array'
    return 'object'


def ts_kind(ttype: str):
    t = ttype.strip().rstrip(';').strip()
    t = re.sub(r'\s*\|\s*(null|undefined)\b', '', t)
    t = re.sub(r'\b(null|undefined)\s*\|\s*', '', t)
    if t.endswith('[]') or t.startswith('Array<') or re.search(r'\}\s*\[\]$', t):
        return 'array'
    if t == 'number':
        return 'number'
    if t == 'string' or re.fullmatch(r"('[^']*'\s*\|?\s*)+", t):
        return 'string'
    if t == 'boolean':
        return 'boolean'
    if re.fullmatch(r'[A-Z]\w*', t):
        return 'enum-or-object'      # a named type: a string union or another interface
    if t.startswith('{'):
        return 'object'
    if '|' in t and all(re.fullmatch(r"'[^']*'", x.strip()) for x in t.split('|')):
        return 'string'
    return 'unknown'


def java_types():
    """simple name -> {field: java type}; nested records as Outer.Inner. Also the set of enum names."""
    out, enums = {}, set()
    for p in sorted(JAVA.glob('**/*.java')):
        src = re.sub(r'/\*.*?\*/', '', p.read_text(encoding='utf-8'), flags=re.S)
        src = re.sub(r'(?<!:)//[^\n]*', '', src)
        for em in re.finditer(r'\benum\s+(\w+)', src):
            enums.add(em.group(1))

        def records(text, prefix=''):
            for m in re.finditer(r'\brecord\s+(\w+)\s*\(', text):
                i, depth = m.end(), 1
                j = i
                while depth:
                    depth += {'(': 1, ')': -1}.get(text[j], 0)
                    j += 1
                comps = text[i:j - 1]
                fields = {}
                # split on top-level commas (generics and annotations contain commas/parens)
                parts, cur, d = [], [], 0
                for ch in comps:
                    if ch in '(<':
                        d += 1
                    elif ch in ')>':
                        d -= 1
                    if ch == ',' and d == 0:
                        parts.append(''.join(cur)); cur = []
                    else:
                        cur.append(ch)
                parts.append(''.join(cur))
                for part in parts:
                    part = re.sub(r'@\w+(\((?:[^()]|\([^()]*\))*\))?\s*', '', part).strip()
                    mm = re.match(r'(.+?)\s+(\w+)$', part, flags=re.S)
                    if mm:
                        fields[mm.group(2)] = mm.group(1).strip()
                out.setdefault(m.group(1), fields)
                if prefix:
                    out[prefix + '.' + m.group(1)] = fields
        records(src)
        cm = re.search(r'\b(?:class|record)\s+(\w+)', src)
        if cm and not re.search(r'\brecord\s+' + cm.group(1), src):
            fields = {}
            for fm in re.finditer(r'private\s+(?!static)([\w.<>?,\[\] ]+?)\s+(\w+)\s*(?:=[^;]*)?;', src):
                fields[fm.group(2)] = fm.group(1).strip()
            if fields:
                out.setdefault(cm.group(1), fields)
        # nested records under an outer record/class, addressable as Outer.Inner
        om = re.search(r'\b(?:record|class)\s+(\w+)[^{]*\{(.*)\}\s*$', src, flags=re.S)
        if om:
            records(om.group(2), om.group(1))
    return out, enums


def ts_interfaces():
    src = TYPES.read_text(encoding='utf-8')
    out = {}
    for m in re.finditer(r'export interface (\w+)\s*(?:extends [^{]+)?\{', src):
        i, depth = m.end(), 1
        j = i
        while depth:
            depth += {'{': 1, '}': -1}.get(src[j], 0)
            j += 1
        body = src[i:j - 1]
        fields = {}
        lines = [re.sub(r'/\*.*?\*/|//.*$', '', l) for l in body.splitlines()]
        k = 0
        while k < len(lines):
            line = lines[k]
            fm = re.match(r'\s*(\w+)(\?)?\s*:\s*(.*)$', line)
            if fm:
                text = fm.group(3)
                # a multi-line inline type ( { ... }[] ): join lines until the braces balance
                while text.count('{') > text.count('}') and k + 1 < len(lines):
                    k += 1
                    text += ' ' + lines[k].strip()
                fields[fm.group(1)] = (bool(fm.group(2)), text.strip().rstrip(';').strip())
            k += 1
        out[m.group(1)] = fields
    return out


def main():
    jt, enums = java_types()
    ti = ts_interfaces()
    bad, notes, pairs = [], [], 0
    unpaired = []
    for name, fields in ti.items():
        target = EXPLICIT.get(name)
        if target is None:
            for cand in (name + 'Response', name):
                if cand in jt:
                    target = cand
                    break
        if target is None or target not in jt:
            unpaired.append(name)
            continue
        pairs += 1
        jf = jt[target]
        for fname, (optional, ttype) in fields.items():
            if fname not in jf:
                if not optional:
                    bad.append(f'{name}.{fname}: required by the screen, but {target} has no such field')
                else:
                    notes.append(f'{name}.{fname}: optional on the screen, absent from {target} (never populated)')
                continue
            jk, tk = java_kind(jf[fname], enums), ts_kind(ttype)
            if tk in ('unknown', 'enum-or-object'):
                if tk == 'enum-or-object' and jk in ('number', 'boolean'):
                    bad.append(f'{name}.{fname}: TS {ttype.strip()} but Java sends {jf[fname]} ({jk})')
                continue
            if tk != jk and not (tk == 'string' and jk == 'object'):
                bad.append(f'{name}.{fname}: TS is {ttype.strip()} ({tk}) but {target}.{fname} is {jf[fname]} ({jk})')
    print('== TypeScript interfaces vs Java DTOs ==')
    print(f'   {pairs} interface pairs compared; {len(unpaired)} client-only interfaces not compared')
    for n in notes:
        print(f'   (note) {n}')
    if bad:
        for b in bad:
            print(f'   [!] {b}')
        return 1
    print('   every field the screens require exists on the server with a compatible type')
    return 0


if __name__ == '__main__':
    sys.exit(main())
