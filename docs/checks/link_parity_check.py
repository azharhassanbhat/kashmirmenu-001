#!/usr/bin/env python3
"""
Some rules exist twice: in Java (the authority) and in TypeScript (a courtesy so the screen can answer
sooner). Written separately, they drift; then the screen accepts something the server refuses (a confusing
failure on save) or refuses something the server would take.

For each rule below this runs ONE shared list of inputs through BOTH, using the real Java class and the real
TypeScript module, and reports every input on which they disagree:
  * photo links           ImageUrlRules.problem         vs photoLinkProblem
  * registration numbers  TaxRegistration.clean         vs registrationProblem
"""
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
KEY = '0123456789abcdef0123456789abcdef'

CASES = [
    '', '   ', 'https://example.com/dish.jpg', 'HTTPS://Example.com/dish', '  https://example.com/dish.jpg  ',
    'https://res.cloudinary.com/demo/image/upload/v1/sample.png?w=400', 'https://example.com:8443/a.png',
    'https://sub.example.co.in/path/to/a%20b.jpg', 'https://192.168.1.10/a.jpg', 'https://[::1]/a.jpg',
    f'/api/public/images/{KEY}', '/api/public/images/notakey', f'/api/public/images/{KEY}/extra',
    f'/api/public/images/{KEY.upper()}', '/api/public/images/', '/api/public/images/../../x',
    'http://example.com/a.jpg', 'HTTP://example.com/a.jpg', 'javascript:alert(1)', 'JaVaScRiPt:alert(1)',
    'data:image/png;base64,AAAA', 'ftp://example.com/a.jpg', '//example.com/a.jpg', 'example.com/a.jpg',
    'file:///etc/passwd', 'vbscript:x', '/etc/passwd', '../a.jpg', 'https://', 'https:///path',
    'https://exa mple.com/a.jpg', 'https://user:pass@example.com/a.jpg', 'https://user@example.com/a.jpg',
    'https://example.com/a\tb.jpg', 'https://example.com/' + 'a' * 500, 'https://example.com/' + 'a' * 470,
    'https:example.com', 'https:/example.com/a.jpg', 'https://example.com/a.jpg#frag', 'https://example.com/?q=1&r=2',
    'https://例え.jp/a.jpg', 'https://example.com/ü.jpg', 'x' * 501, ' ', 'https://exam\u00a0ple.com/a.jpg',
]

REG_CASES = [
    '', '   ', '29ABCDE1234F1Z5', '29abcde1234f1z5', '  29 ABCDE 1234 F1Z5 ', 'GB 123 456 789', 'DE-123/45', 'ABC12',
    'ABCD', 'ABCDE', 'A' * 30, 'A' * 31, 'A' * 29, '<script>1</script>', 'ABC12;DROP', 'ABC 12 & CO',
    'GSTIN: 29ABCDE1234F1Z5', '29ABCDE1234F1Z5!', "ABC12'--", '=1+1+1', 'ABC\t12', 'ABC\u000012',
    'ABC\u00a012',            # a non-breaking space, as pasted from a web page or Word
    'ABC\u200312',            # an em space
    '\ufeffABC12',            # a byte-order mark pasted at the front
    'ABC\u200b12',            # a zero-width space: not whitespace to either, so refused by both
    'AB\u3000CDE', 'ABC\u202f12',
    'ABC\u00dfD',             # the German sharp s upper-cases to SS in both
    'ABC\u0131D',             # dotless i upper-cases to I
    'ABC\u017fD',             # long s upper-cases to S
    'ABC\ufb01D',             # the fi ligature upper-cases to FI
    'ABC\u0130D',             # Turkish capital I with a dot stays non-ASCII
    '\uff11\uff12\uff13\uff14\uff15',   # full-width digits
    # punctuation: only the hyphen and the slash are allowed, every other mark must be refused by both
    'ABC_12', '_____', 'ABC.12', 'ABC+12', 'ABC@12', 'ABC#12', 'ABC%12', 'ABC(12)', 'ABC,12', 'ABC*12', 'ABC\\12',
    'ABC:12', 'ABC?12', 'ABC&12', 'ABC~12', 'ABC^12', 'ABC|12', 'ABC`12', 'ABC$12', 'ABC!12', 'ABC[12]', 'ABC{12}',
    '\u0917\u0941\u0936\u094d\u0924\u093e', '12345', '-----', '/////', 'ab-cd',
]

JAVA_TEMPLATE = r'''
import java.nio.file.*;
public class Parity {
    public static void main(String[] a) throws Exception {
        // each line is the input as hex, so tabs, spaces and non-ASCII survive intact
        for (String hex : Files.readAllLines(Path.of(a[0]))) {
            byte[] b = new byte[hex.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            String input = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println(verdict(input) ? "OK" : "BAD");
        }
    }
    static boolean verdict(String input) {
        %s
    }
}
'''

TS_TEMPLATE = '''
import { %s } from '%s';
import { readFileSync } from 'node:fs';
for (const hex of readFileSync(process.argv[2], 'utf8').split('\\n').filter((l, i, all) => i < all.length - 1 || l !== '')) {
  const input = Buffer.from(hex, 'hex').toString('utf8');
  console.log((%s) ? 'OK' : 'BAD');
}
'''

RULES = [
    dict(name='Photo links: ImageUrlRules (server) vs photoLinkProblem (screen)', cases=CASES,
         java_sources=['exception/BadRequestException.java', 'service/ImageKeys.java', 'service/ImageUrlRules.java'],
         java_verdict='return com.kashmirmenu.app.service.ImageUrlRules.problem(input) == null;',
         ts_module='lib/imageUpload.ts', ts_import='photoLinkProblem', ts_ok='photoLinkProblem(input) === null'),
    dict(name='Registration numbers: TaxRegistration (server) vs registrationProblem (screen)', cases=REG_CASES,
         java_sources=['exception/BadRequestException.java', 'service/TaxRegistration.java'],
         java_verdict='try { com.kashmirmenu.app.service.TaxRegistration.clean(input); return true; } '
                      'catch (com.kashmirmenu.app.exception.BadRequestException e) { return false; }',
         ts_module='lib/receipt.ts', ts_import='registrationProblem', ts_ok='registrationProblem(input) === null'),
]


def run_rule(rule):
    work = Path(tempfile.mkdtemp())
    cases = work / 'cases.txt'
    cases.write_text('\n'.join(c.encode('utf-8').hex() for c in rule['cases']) + '\n')

    classes = work / 'classes'
    java_src = work / 'Parity.java'
    java_src.write_text(JAVA_TEMPLATE % rule['java_verdict'])
    app = ROOT / 'src/main/java/com/kashmirmenu/app'
    c = subprocess.run(['java', '--module', 'jdk.compiler/com.sun.tools.javac.Main', '-d', str(classes)]
                       + [str(app / f) for f in rule['java_sources']] + [str(java_src)], capture_output=True, text=True)
    if c.returncode:
        print('Java did not compile\n' + c.stderr[:500])
        return False
    j = subprocess.run(['java', '-cp', str(classes), 'Parity', str(cases)], capture_output=True, text=True)

    ts_file = work / 'parity.mts'
    ts_file.write_text(TS_TEMPLATE % (rule['ts_import'], ROOT / 'frontend/src' / rule['ts_module'], rule['ts_ok']))
    # the same resolver the frontend tests use, so a module may import a sibling without a file extension
    t = subprocess.run(['node', '--experimental-strip-types', '--no-warnings', '--import',
                        str(HERE / 'vitest_shim' / 'register.mjs'), str(ts_file), str(cases)],
                       capture_output=True, text=True, cwd=str(HERE / 'vitest_shim'))
    if j.returncode or t.returncode:
        print('a runner failed\n' + j.stderr[:300] + t.stderr[:300])
        return False

    java_out, ts_out = j.stdout.split(), t.stdout.split()
    print('== ' + rule['name'] + ' ==')
    print(f'   {len(rule["cases"])} inputs run through both')
    if len(java_out) != len(rule['cases']) or len(ts_out) != len(rule['cases']):
        print(f'   [!] unexpected output lengths: java {len(java_out)}, ts {len(ts_out)}')
        return False
    bad = [(c, jv, tv) for c, jv, tv in zip(rule['cases'], java_out, ts_out) if jv != tv]
    for case, jv, tv in bad:
        shown = ascii(case if len(case) < 60 else case[:57] + '...')
        print(f'   [!] {shown}: server says {jv}, screen says {tv}')
    if bad:
        return False
    print('   the two implementations agree on every input')
    return True


def main():
    ok = True
    for rule in RULES:
        ok = run_rule(rule) and ok
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
