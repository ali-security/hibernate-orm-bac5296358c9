#!/usr/bin/env python3
"""Print a test summary (totals + every skipped test with its reason) from Gradle JUnit XML reports.

Skip reasons come from the SKIP-REASON lines CustomRunner logs (dialect / @FailureExpected / @Skip / @Ignore
conditions); tests skipped outside CustomRunner (class-level @Ignore, JUnit assumptions) get their reason from
the test source, walking up the superclass chain.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

results_dir = sys.argv[1]
source_root = sys.argv[2]
reason_re = re.compile(r"SKIP-REASON (\S+)#(\S+) : (.*)")
string_concat = r'"((?:[^"\\]|\\.)*)"((?:\s*\+\s*"(?:[^"\\]|\\.)*")*)'


def read_source(cls):
    src = os.path.join(source_root, cls.split("$")[0].replace(".", "/") + ".java")
    if os.path.exists(src):
        with open(src, encoding="utf-8", errors="replace") as handle:
            return src, handle.read()
    return None, ""


def superclass(cls, text):
    m = re.search(r"\bclass\s+\w+(?:\s*<[^{]*?>)?\s+extends\s+([\w.]+)", text)
    if not m:
        return None
    name = m.group(1)
    if "." in name:
        return name
    imp = re.search(r"^import\s+([\w.]+\." + re.escape(name) + r");", text, re.M)
    if imp:
        return imp.group(1)
    return cls.rsplit(".", 1)[0] + "." + name


def literal(m):
    parts = [m.group(1)] + re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2) or "")
    return "".join(parts)


def source_reason(cls, method):
    seen = 0
    current = cls
    while current and seen < 8:
        seen += 1
        _, text = read_source(current)
        if not text:
            return None
        ign = re.search(r"^@Ignore(?:\(\s*" + string_concat + r"\s*\))?", text, re.M)
        if ign:
            if ign.group(1):
                return "test class is annotated @Ignore in source: " + literal(ign)
            doc = re.findall(r"/\*\*(.*?)\*/", text[: ign.start()], re.S)
            note = re.sub(r"\s*\*\s*|\s+", " ", doc[-1]).strip() if doc else ""
            note = re.sub(r"\s*@author.*$", "", note)
            return "test class is annotated @Ignore in source (disabled upstream)" + (
                "; class javadoc: " + note[:300] if note else ""
            )
        asm = re.search(r"assume\w*\(\s*" + string_concat, text)
        if asm:
            return "JUnit assumption not met (%s.%s): %s" % (current.rsplit(".", 1)[-1], method, literal(asm))
        current = superclass(current, text)
    return None


totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
skipped = []
failed = []
for path in sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml"))):
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, 0))
    out = suite.findtext("system-out") or ""
    reasons = {}
    for m in reason_re.finditer(out):
        reasons[(m.group(1), m.group(2))] = m.group(3).strip()
    for case in suite.iter("testcase"):
        cls = case.get("classname")
        name = case.get("name")
        if case.find("failure") is not None or case.find("error") is not None:
            failed.append("%s#%s" % (cls, name))
        node = case.find("skipped")
        if node is None:
            continue
        method = re.sub(r"\[.*\]$", "", name)
        reason = reasons.get((cls, name)) or reasons.get((cls, method)) or node.get("message")
        if not reason:
            reason = source_reason(cls, method) or "skipped by the JUnit runner (no reason reported)"
        skipped.append("%s#%s : %s" % (cls, name, reason))

print("=== Skipped tests (%d) ===" % len(skipped))
for line in skipped:
    print("SKIPPED " + line)
if failed:
    print("=== Failed tests (%d) ===" % len(failed))
    for line in failed:
        print("FAILED " + line)
passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
print(
    "=== Test summary: %d tests, %d passed, %d failed, %d errors, %d skipped ==="
    % (totals["tests"], passed, totals["failures"], totals["errors"], totals["skipped"])
)
