#
# Logisim-evolution - digital logic design tool and simulator
# Copyright by the Logisim-evolution developers
#
# https://github.com/logisim-evolution/
#
# This is free software released under GNU GPLv3 license
#

"""Verdicts and reports shared by the HDL verification runners.

A test case may carry a known issue (see HdlTestCase.knownVhdlIssue/knownVerilogIssue): its
failure is then expected and does not fail the run, while passing fails the run, so the stale
marker gets removed.
"""

import html
import os
import xml.etree.ElementTree as ElementTree
from pathlib import Path

PASS = "pass"
FAIL = "fail"
EXPECTED_FAILURE = "xfail"
UNEXPECTED_PASS = "xpass"

TEST_BENCH = "tb_logisim_component"
TEST_NAME = "generated_hdl_matches_logisim_simulation"


def verdict(passed, known_issue):
    if known_issue is None:
        return PASS if passed else FAIL
    return UNEXPECTED_PASS if passed else EXPECTED_FAILURE


def mark_known_issues(testsuite, verdicts, known_issues):
    """Adjusts the testcases of a JUnit testsuite element to the verdicts.

    Expected failures become skipped tests, unexpected passes become failures.
    """
    for testcase in testsuite.iter("testcase"):
        name = testcase.get("classname", "").split(".")[0]
        if name not in verdicts or known_issues.get(name) is None:
            continue
        for child in list(testcase):
            if child.tag in ("failure", "error", "skipped"):
                testcase.remove(child)
        if verdicts[name] == EXPECTED_FAILURE:
            ElementTree.SubElement(testcase, "skipped", message="Known issue: " + known_issues[name])
        else:
            ElementTree.SubElement(
                testcase,
                "failure",
                message="Passes despite its known issue marker, remove the marker: " + known_issues[name],
            )
    counts = {kind: 0 for kind in ("failure", "skipped")}
    for testcase in testsuite.iter("testcase"):
        for child in testcase:
            if child.tag in counts:
                counts[child.tag] += 1
    testsuite.set("failures", str(counts["failure"]))
    testsuite.set("errors", "0")
    testsuite.set("skipped", str(counts["skipped"]))


def junit_testsuite(suite_name, results, known_issues):
    """Creates a JUnit testsuite element from {test case name: (passed, log, seconds)}."""
    testsuite = ElementTree.Element("testsuite", name=suite_name, tests=str(len(results)))
    verdicts = {}
    for name, (passed, log, seconds) in results.items():
        testcase = ElementTree.SubElement(
            testsuite, "testcase", classname=f"{name}.{TEST_BENCH}", name=TEST_NAME, time=f"{seconds:.3f}"
        )
        if not passed:
            ElementTree.SubElement(testcase, "failure", message="Deviates from the Logisim simulation").text = log
        ElementTree.SubElement(testcase, "system-out").text = log
        verdicts[name] = verdict(passed, known_issues.get(name))
    mark_known_issues(testsuite, verdicts, known_issues)
    return testsuite


def write_junit(path, testsuite):
    path.parent.mkdir(parents=True, exist_ok=True)
    ElementTree.ElementTree(testsuite).write(path, encoding="utf-8", xml_declaration=True)


def print_summary(language, verdicts, known_issues):
    """Prints the verdicts and returns whether the run succeeded."""
    counts = {kind: [] for kind in (PASS, FAIL, EXPECTED_FAILURE, UNEXPECTED_PASS)}
    for name, case_verdict in sorted(verdicts.items()):
        counts[case_verdict].append(name)
    print(
        f"\n{language} HDL verification: {len(counts[PASS])} passed, {len(counts[FAIL])} failed, "
        f"{len(counts[EXPECTED_FAILURE])} known issues, {len(counts[UNEXPECTED_PASS])} known issues passing"
    )
    if counts[EXPECTED_FAILURE]:
        print("Known issues (expected to fail):")
        for name in counts[EXPECTED_FAILURE]:
            print(f"  {name}: {known_issues[name]}")
    if counts[UNEXPECTED_PASS]:
        print("Passing despite a known issue marker; remove the marker from the test case:")
        for name in counts[UNEXPECTED_PASS]:
            print(f"  {name}")
    if counts[FAIL]:
        print("Failed:")
        for name in counts[FAIL]:
            print(f"  {name}")
    succeeded = bool(verdicts) and not counts[FAIL] and not counts[UNEXPECTED_PASS]
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        write_github_summary(Path(os.environ["GITHUB_STEP_SUMMARY"]), language, succeeded, counts, known_issues)
    return succeeded


def write_github_summary(path, language, succeeded, counts, known_issues):
    """Appends the verdicts as Markdown to the job summary of a GitHub Actions run."""
    lines = [
        f"### {'✅' if succeeded else '❌'} {language} HDL verification",
        "",
        "| Passed | Failed | Known issues | Known issues passing |",
        "| ---: | ---: | ---: | ---: |",
        f"| {len(counts[PASS])} | {len(counts[FAIL])} | {len(counts[EXPECTED_FAILURE])} "
        f"| {len(counts[UNEXPECTED_PASS])} |",
        "",
    ]
    if counts[FAIL]:
        lines += ["**Failed** (see the job log or the uploaded results):", ""]
        lines += [f"* `{name}`" for name in counts[FAIL]] + [""]
    if counts[UNEXPECTED_PASS]:
        lines += ["**Passing despite a known issue marker** (remove the marker):", ""]
        lines += [f"* `{name}`" for name in counts[UNEXPECTED_PASS]] + [""]
    if counts[EXPECTED_FAILURE]:
        lines += ["<details><summary>Known issues (expected to fail)</summary>", ""]
        lines += [f"* `{name}`: {html.escape(known_issues[name])}" for name in counts[EXPECTED_FAILURE]]
        lines += ["", "</details>", ""]
    with path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines) + "\n")
