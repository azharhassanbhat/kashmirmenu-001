#!/usr/bin/env python3
"""
Mutation testing that cannot damage the real project: every sabotage is applied to a TEMPORARY COPY of
the repository and the check is run from that copy, so an interrupted or crashed run leaves the real
files untouched. (Doing this in place once left a sabotaged line in the real source when a run timed
out; the tests still passed, because nothing could see it. Never mutate in place.)

    from mutation_lab import caught
    caught('src/main/java/.../X.java', 'old text', 'new text', ['run_junit.py', 'XTest'], expect='XTest')

A sabotage is CAUGHT when the check fails and its output mentions `expect`.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IGNORE = shutil.ignore_patterns('node_modules', '.git', 'build', '.gradle', 'dist', '__pycache__')


def caught(rel_path, old, new, command, expect, timeout=240):
    """-> (was_caught, short_note). `command` is [script_name_in_docs_checks, *args]."""
    work = Path(tempfile.mkdtemp(prefix='mutation-'))
    try:
        copy = work / 'repo'
        shutil.copytree(ROOT, copy, ignore=IGNORE)
        target = copy / rel_path
        text = target.read_text(encoding='utf-8')
        if text.count(old) != 1:
            return False, f'the text to sabotage appears {text.count(old)} times in {rel_path}, not once'
        script = copy / 'docs' / 'checks' / command[0]

        def run():
            return subprocess.run([sys.executable, str(script), *command[1:]], capture_output=True, text=True,
                                  cwd=str(copy / 'docs' / 'checks'), timeout=timeout)

        # BASELINE: the check must PASS on the untouched copy. Otherwise "the check failed" proves nothing:
        # a test file that does not even compile fails every sabotage, and looks as if each was caught.
        try:
            base = run()
        except subprocess.TimeoutExpired:
            return False, 'baseline timed out'
        if base.returncode != 0:
            return False, 'BASELINE ALREADY FAILING, so this sabotage proves nothing: ' + (base.stdout + base.stderr).strip()[-200:]

        target.write_text(text.replace(old, new, 1), encoding='utf-8')
        try:
            r = run()
        except subprocess.TimeoutExpired:
            return False, 'timed out'
        out = r.stdout + r.stderr
        return (r.returncode != 0 and expect in out), ('' if r.returncode != 0 else 'the check still passed')
    finally:
        shutil.rmtree(work, ignore_errors=True)
