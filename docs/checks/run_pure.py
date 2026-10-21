#!/usr/bin/env python3
"""
Runs the pure-logic checks: real code, really executed, with no framework needed.

Java: the dependency-free rule classes (option pricing, order charges, staff permissions, popularity,
service-request lifecycle) are compiled with the JDK's own compiler module and exercised.
TypeScript: the client-side helpers are executed with Node's built-in type stripping.
The JUnit classes with the same names under src/test are what Gradle will run later; these run today.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
APP = ROOT / 'src/main/java/com/kashmirmenu/app'

JAVA = {
    'CheckServiceRequestRules': ['enums/ServiceRequestType.java', 'enums/ServiceRequestStatus.java'],
    'CheckOptionPricing': ['exception/BadRequestException.java', 'enums/OptionKind.java', 'service/OptionPricing.java'],
    'CheckLowestPrice': ['exception/BadRequestException.java', 'enums/OptionKind.java', 'service/OptionPricing.java'],
    'CheckPopularityAndTags': ['service/PopularityRules.java', 'service/MenuTags.java', 'dto/response/TagResponse.java',
                               'enums/Allergen.java', 'enums/DietaryLabel.java'],
    'CheckOrderCharges': ['service/OrderCharges.java'],
    'CheckStaffRules': ['exception/AccessDeniedException.java', 'enums/RoleName.java', 'service/StaffRules.java'],
    'CheckMenuCsvTemplate': ['service/MenuCsv.java'],
}


def run(cmd, cwd=None):
    return subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)


def main():
    failed = 0
    print('== Pure logic, executed ==')
    java = ['java']
    if not shutil.which('java'):
        print('   java not found; skipping the Java checks')
    else:
        for name, srcs in JAVA.items():
            out = Path(tempfile.mkdtemp())
            files = [str(APP / s) for s in srcs] + [str(HERE / 'pure' / f'{name}.java')]
            c = run(java + ['--module', 'jdk.compiler/com.sun.tools.javac.Main', '-d', str(out)] + files)
            if c.returncode:
                print(f'   [!] {name}: did not compile\n{c.stderr[:400]}')
                failed += 1
                continue
            r = run(java + ['-cp', str(out), name, str(ROOT / 'frontend/src/lib/menuCsvTemplate.ts')])
            line = (r.stdout.strip().splitlines() or ['(no output)'])[-1]
            ok = r.returncode == 0
            print(f"   {'ok ' if ok else '[!]'} {name}: {line}" + ('' if ok else '\n' + (r.stderr or '')[:400]))
            failed += 0 if ok else 1
    if shutil.which('node'):
        for f in sorted((HERE / 'pure').glob('check_*.mts')):
            r = run(['node', '--experimental-strip-types', '--no-warnings', str(f)])
            lines = r.stdout.strip().splitlines()
            ok = r.returncode == 0 and bool(lines)
            print(f"   {'ok ' if ok else '[!]'} {f.name}: {lines[-1] if lines else r.stderr[:300]}")
            failed += 0 if ok else 1
    else:
        print('   node not found; skipping the TypeScript checks')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
