#!/usr/bin/env python3
"""Print a readable per-test report from Android instrumentation JUnit XML.

The Android Gradle plugin writes one XML file per connected test run. Those files
are the only place a test's exception and stack appear, and the job that produces
them has no readable log for anyone without repo admin, so this renders the same
information onto the run summary page.

Usage: junit_summary.py TEST-*.xml ...
"""

import sys
import xml.etree.ElementTree as ET

STACK_LINES = 25


def render(path: str) -> list[str]:
    try:
        root = ET.parse(path).getroot()
    except Exception as exc:  # noqa: BLE001 - report, never crash the workflow
        return [f"-- {path}", f"   unreadable: {exc}"]

    lines = [f"-- {path}"]
    counts = {"PASS": 0, "FAIL": 0, "SKIP": 0}
    for case in root.iter("testcase"):
        name = case.get("name", "?")
        bad = [c for c in case if c.tag in ("failure", "error")]
        if bad:
            counts["FAIL"] += 1
            lines.append(f"   FAIL {name}")
            for child in bad:
                lines.append(f"        {child.tag}: {child.get('message', '')}")
                body = (child.text or "").strip().splitlines()
                lines.extend(f"          {row}" for row in body[:STACK_LINES])
                if child.get("classname"):
                    lines.append(f"          in {child.get('classname')}")
        elif any(c.tag == "skipped" for c in case):
            counts["SKIP"] += 1
            lines.append(f"   SKIP {name}")
        else:
            counts["PASS"] += 1
            lines.append(f"   PASS {name}")
    lines.append(
        f"   totals: {counts['PASS']} passed, {counts['FAIL']} failed, {counts['SKIP']} skipped"
    )
    return lines


def main(argv: list[str]) -> int:
    if not argv:
        print("no JUnit XML was produced, so no instrumentation test ever reported")
        return 1
    for path in argv:
        for line in render(path):
            print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
