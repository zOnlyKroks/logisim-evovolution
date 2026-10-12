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

The report only goes into detail for test cases that fail: if everything works, it is a single
line per language.
"""

import os
import re
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path

PASS = "pass"
FAIL = "fail"
EXPECTED_FAILURE = "xfail"
UNEXPECTED_PASS = "xpass"

TEST_BENCH = "tb_logisim_component"
TEST_NAME = "generated_hdl_matches_logisim_simulation"

# number of deviating vectors shown per failing test case
SHOWN_DEVIATIONS = 3


@dataclass
class CaseResult:
    """Outcome of one test case; output is the log the failure excerpt is taken from."""

    passed: bool
    output: str = ""
    seconds: float = 0.0


def verdict(passed, known_issue):
    if known_issue is None:
        return PASS if passed else FAIL
    return UNEXPECTED_PASS if passed else EXPECTED_FAILURE


# VUnit log lines start with the simulation time, the logger and the level
VUNIT_LOG_PREFIX = re.compile(r"^\s*\d+\s+\w+\s+-\s+\S+\s+-\s+\w+\s+-\s+")
DEVIATION = re.compile(r"(?i)response deviates from the Logisim simulation")
VECTOR_FIELD = re.compile(r"^(inputs|expected|actual)\s*:")
PROBLEM = re.compile(r"(?i)error|fail|fatal|not found|exit code|deviate|does not compile")
# longest excerpt shown per failing test case
SHOWN_LINES = 20
NOISE = re.compile(
    r"(?i)Logger \w+ has \d+ error|Final log check failed|core_pkg\.vhd|simulation failed|report failed|"
    r"\$finish called|Compile failed"
)


def excerpt(output):
    """Returns the lines of a log that explain a failure: deviating vectors, errors and the total."""
    lines = []
    deviations = 0
    in_shown_deviation = False
    for raw_line in output.splitlines():
        line = VUNIT_LOG_PREFIX.sub("", raw_line).strip()
        if not line or line.startswith("$ "):
            in_shown_deviation = False
            continue
        if VECTOR_FIELD.match(line):
            if in_shown_deviation:
                lines.append("  " + line)
            continue
        in_shown_deviation = False
        if DEVIATION.search(line):
            deviations += 1
            in_shown_deviation = deviations <= SHOWN_DEVIATIONS
            if in_shown_deviation:
                lines.append(line)
        elif PROBLEM.search(line) and not NOISE.search(line):
            lines.append(line)
    if len(lines) > SHOWN_LINES:
        lines = lines[:SHOWN_LINES] + ["..."]
    if deviations > SHOWN_DEVIATIONS:
        lines.append(f"(only the first {SHOWN_DEVIATIONS} deviating vectors are shown)")
    return lines


def report(language, results, known_issues, log_location):
    """Prints the result of a run and returns whether it succeeded.

    results maps the test case names to CaseResult; log_location tells where the full logs are.
    """
    verdicts = {name: verdict(result.passed, known_issues.get(name)) for name, result in results.items()}
    failed = sorted(name for name, case_verdict in verdicts.items() if case_verdict == FAIL)
    stale = sorted(name for name, case_verdict in verdicts.items() if case_verdict == UNEXPECTED_PASS)
    known = sum(1 for case_verdict in verdicts.values() if case_verdict == EXPECTED_FAILURE)
    succeeded = bool(results) and not failed and not stale
    known_note = f" ({known} known issues)" if known else ""

    if not results:
        print(f"{language}: no test cases were run")
    elif succeeded:
        print(f"{language}: all {len(results)} test cases match the Logisim simulation{known_note}")
    else:
        if failed:
            print(f"{language}: {len(failed)} of {len(results)} test cases FAILED{known_note}")
        for name in failed:
            print(f"\n  {name}")
            for line in excerpt(results[name].output) or ["(no details, see the log)"]:
                print(f"    {line}")
        if stale:
            print(
                f"\n{language}: these test cases pass now, remove their known issue marker"
                f" (in the *HdlVerificationCases classes):"
            )
            for name in stale:
                print(f"  {name}")
        print(f"\nFull logs: {log_location}")

    if os.environ.get("GITHUB_STEP_SUMMARY"):
        write_github_summary(Path(os.environ["GITHUB_STEP_SUMMARY"]), language, results, failed, stale, known)
    return succeeded


def write_github_summary(path, language, results, failed, stale, known):
    """Appends the result as Markdown to the job summary of a GitHub Actions run."""
    passed = len(results) - len(failed) - len(stale) - known
    lines = [
        f"### {'✅' if not failed and not stale else '❌'} {language} HDL verification",
        "",
        "| Passed | Failed | Known issues | Known issues passing |",
        "| ---: | ---: | ---: | ---: |",
        f"| {passed} | {len(failed)} | {known} | {len(stale)} |",
        "",
    ]
    for name in failed:
        lines += [f"<details><summary><code>{name}</code> failed</summary>", "", "```text"]
        lines += excerpt(results[name].output) or ["(no details, see the log)"]
        lines += ["```", "", "</details>", ""]
    if stale:
        lines += ["**Passing despite a known issue marker** (remove the marker):", ""]
        lines += [f"* `{name}`" for name in stale] + [""]
    with path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines) + "\n")


def junit_testsuite(suite_name, results, known_issues):
    """Creates a JUnit testsuite element; expected failures become skipped tests."""
    testsuite = ElementTree.Element("testsuite", name=suite_name, tests=str(len(results)))
    failures = skipped = 0
    for name, result in sorted(results.items()):
        testcase = ElementTree.SubElement(
            testsuite, "testcase", classname=f"{name}.{TEST_BENCH}", name=TEST_NAME, time=f"{result.seconds:.3f}"
        )
        case_verdict = verdict(result.passed, known_issues.get(name))
        if case_verdict == FAIL:
            failures += 1
            failure = ElementTree.SubElement(testcase, "failure", message="Deviates from the Logisim simulation")
            failure.text = "\n".join(excerpt(result.output))
        elif case_verdict == UNEXPECTED_PASS:
            failures += 1
            ElementTree.SubElement(
                testcase, "failure", message="Passes despite its known issue marker, remove the marker: "
                + known_issues[name]
            )
        elif case_verdict == EXPECTED_FAILURE:
            skipped += 1
            ElementTree.SubElement(testcase, "skipped", message="Known issue: " + known_issues[name])
        ElementTree.SubElement(testcase, "system-out").text = result.output
    testsuite.set("failures", str(failures))
    testsuite.set("errors", "0")
    testsuite.set("skipped", str(skipped))
    return testsuite


def write_junit(path, testsuite):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    ElementTree.ElementTree(testsuite).write(path, encoding="utf-8", xml_declaration=True)
