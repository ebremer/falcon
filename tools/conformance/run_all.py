"""Runs Falcon's conformance checks and writes one short report of their results.

    mvn -pl cli -am package -DskipTests
    python tools/conformance/run_all.py                    # every check this machine can run, then the report
    python tools/conformance/run_all.py --only hdf5,cve    # some of them
    python tools/conformance/run_all.py --report-only      # the report of the last runs, running nothing
    python tools/conformance/run_all.py --update-docs      # and put the report into docs/conformance-results.md

The checks, each its own runner (docs/testing.md says what each does and what its numbers mean):

    zarr    the Zarr community's conformance suite          bash tools/conformance/run_conformance.sh
    values  the suite's arrays, against zarr-python          python -I tools/conformance/check_values.py
    ome     the OME-Zarr specification's conformance tests   python tools/conformance/run_ome_conformance.py
    hdf5    the HDF5 library's test files, against h5py      python tools/conformance/run_hdf5_conformance.py
    cve     the HDF Group's CVE files                         bash tools/conformance/run_hdf5_cve.sh

A check whose tools are missing (bash, Maven, or a Python package: numpy and zarr for values, h5py for hdf5) is
skipped and reported "not run". The report, a Markdown table, goes to the console, to
cli/target/conformance-report.md, and, in GitHub Actions, to the job's summary ($GITHUB_STEP_SUMMARY). Each
workflow runs its own checks and then `run_all.py --report-only --only <them>`. Exits with 1 if a check that
ran failed. Needs Python 3.11+; nothing beyond its standard library.
"""
import argparse
import dataclasses
import datetime
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "conformance"
TARGET = ROOT / "cli" / "target"
JAR = TARGET / "falcon.jar"
REPORT = TARGET / "conformance-report.md"
DOCS = ROOT / "docs" / "conformance-results.md"
START, END = "<!-- results:start -->", "<!-- results:end -->"


@dataclasses.dataclass
class Result:
    status: str          # "pass", "FAIL", or "not run"
    summary: str
    against: str = ""
    when: float | None = None  # when its result file was written


def pinned(script, variable):
    """{return a version a runner pins, such as SUITE_TAG=v0.0.2 in run_conformance.sh}"""
    match = re.search(rf"^{variable}=(\S+)", (TOOLS / script).read_text(encoding="utf-8"), re.M)
    return match.group(1) if match else "?"


def junit(path):
    """{return the tests, and those that failed, of a JUnit XML report}"""
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
    tests = failed = 0
    for suite in suites:
        tests += int(suite.get("tests", 0)) - int(suite.get("skipped", 0))
        failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    return tests, failed


def link(repository, version):
    return f"[{repository}](https://github.com/{repository}) {version}"


# ---- each check's result, from the files its last run wrote -------------------------------------------------

def zarr_result():
    # run_conformance.sh's bats report, or, in CI, the community action's (in the workspace's root)
    for path in (TARGET / "conformance" / "report.xml", ROOT / "report.xml"):
        if path.is_file():
            tests, failed = junit(path)
            return Result("pass" if tests and not failed else "FAIL", f"{tests - failed} / {tests} pass",
                          when=path.stat().st_mtime)
    return None


def values_result():
    path = TARGET / "conformance" / "values-summary.json"
    if not path.is_file():
        return None
    s = json.loads(path.read_text(encoding="utf-8"))
    return Result("pass" if not s["failures"] else "FAIL",
                  f"{s['arrays'] - s['failures']} / {s['arrays']} arrays' values match zarr-python's",
                  when=path.stat().st_mtime)


def ome_result():
    path = TARGET / "conformance" / "ome-summary.json"
    if not path.is_file():
        return None
    s = json.loads(path.read_text(encoding="utf-8"))
    return Result("pass" if not s["unexpected"] else "FAIL",
                  f"{s['agree']} / {s['tests']} agree; the other {s['errata']} are errata in the suites' own data "
                  f"(listed in the runner); {s['unexpected']} unexpected", when=path.stat().st_mtime)


def hdf5_result():
    path = TARGET / "hdf5-conformance" / "summary.json"
    if not path.is_file():
        return None
    s = json.loads(path.read_text(encoding="utf-8"))
    text = (f"{s['files']} files{' (a subset)' if s['subset'] else ''}: {s['agree']} agree, "
            f"{s['both refuse']} refused by both, Falcon reads more of {s['Falcon reads more']}, "
            f"{s['known']} known differences (listed in the runner); {s['unexpected']} unexpected")
    if s["untyped"]:
        text += f"; {s['untyped']} with untyped Falcon failures"
    return Result("pass" if not s["unexpected"] else "FAIL", text,
                  against=f"{link('HDFGroup/hdf5', s['tag'])}, h5py {s['h5py']} (HDF5 {s['hdf5']})",
                  when=path.stat().st_mtime)


def cve_result():
    path = ROOT / "hdf5" / "target" / "surefire-reports" / "TEST-com.ebremer.falcon.hdf5.CveCorpusTest.xml"
    if not path.is_file():
        return None
    tests, failed = junit(path)
    if not tests:
        return None  # skipped: no corpus given
    return Result("pass" if not failed else "FAIL",
                  f"{tests - failed} / {tests} files read or fail typed, within a minute, in a 128 MB heap",
                  when=path.stat().st_mtime)


# ---- running ------------------------------------------------------------------------------------------------

def bash():
    """Git's bash on Windows (not WSL's, which may come first on the PATH), else bash."""
    git = shutil.which("git")
    if os.name == "nt" and git:
        candidate = pathlib.Path(git).resolve().parents[1] / "bin" / "bash.exe"
        if candidate.is_file():
            return str(candidate)
    return shutil.which("bash")


def has_modules(*modules, isolated=False):
    probe = [sys.executable] + (["-I"] if isolated else []) + ["-c", "import " + ", ".join(modules)]
    return subprocess.run(probe, cwd=TARGET if TARGET.is_dir() else ROOT, capture_output=True).returncode == 0


@dataclasses.dataclass
class Check:
    key: str
    module: str
    title: str
    against: str
    result: object
    command: object       # () -> the command line, or a reason it cannot run
    stale: list           # its result files, removed before it runs


def checks():
    def need_bash(*tools):
        def command(script):
            missing = [t for t in ("git", "java", *tools) if not shutil.which(t)]
            if not bash():
                missing.insert(0, "bash")
            return f"needs {', '.join(missing)}" if missing else [bash(), str(TOOLS / script)]
        return command

    def python(script, *modules, isolated=False):
        def command():
            missing = [t for t in ("git", "java") if not shutil.which(t)]
            if modules and not has_modules(*modules, isolated=isolated):
                missing.append(" and ".join(modules) + " (Python)")
            return f"needs {', '.join(missing)}" if missing else (
                [sys.executable] + (["-I"] if isolated else []) + [str(TOOLS / script)])
        return command

    zarr_suite = pinned("run_conformance.sh", "SUITE_TAG")
    return [
        Check("zarr", "zarr", "Zarr community conformance suite",
              link("Bisaloo/zarr-conformance-tests", zarr_suite), zarr_result,
              lambda: need_bash()("run_conformance.sh"), [TARGET / "conformance" / "report.xml"]),
        Check("values", "zarr", "The suite's arrays' values",
              f"zarr-python, on {link('Bisaloo/zarr-conformance-tests', zarr_suite)}", values_result,
              python("check_values.py", "numpy", "zarr", isolated=True),
              [TARGET / "conformance" / "values-summary.json"]),
        Check("ome", "ome", "OME-Zarr specification conformance tests (0.4, 0.5, 0.6)",
              f"{link('ome/ngff-spec', '0.6')}, {link('ome/ngff', '0.5.2')}", ome_result,
              python("run_ome_conformance.py"), [TARGET / "conformance" / "ome-summary.json"]),
        Check("hdf5", "hdf5", "The HDF5 library's own test files, against h5py",
              link("HDFGroup/hdf5", "hdf5_2.0.0"), hdf5_result,
              python("run_hdf5_conformance.py", "h5py"), [TARGET / "hdf5-conformance" / "summary.json"]),
        Check("cve", "hdf5", "The HDF Group's CVE files",
              link("HDFGroup/cve_hdf5", pinned("run_hdf5_cve.sh", "CVE_COMMIT")[:7]), cve_result,
              lambda: need_bash("mvn")("run_hdf5_cve.sh"),
              [ROOT / "hdf5" / "target" / "surefire-reports" / "TEST-com.ebremer.falcon.hdf5.CveCorpusTest.xml"]),
    ]


def run(check):
    command = check.command()
    if isinstance(command, str):
        print(f"\n== {check.key}: not run, {command}", flush=True)
        return Result("not run", command)
    for path in check.stale:
        path.unlink(missing_ok=True)
    print(f"\n== {check.key}: {' '.join(command)}", flush=True)
    status = subprocess.run(command, cwd=ROOT).returncode
    result = check.result()
    if result is None:
        return Result("FAIL", f"no result (the runner exited with {status})")
    if status and result.status == "pass":
        result.status, result.summary = "FAIL", f"{result.summary} (but the runner exited with {status})"
    return result


# ---- the report ---------------------------------------------------------------------------------------------

def report(rows):
    commit = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"], capture_output=True,
                            text=True).stdout.strip() or "?"
    dirty = subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"],
                           capture_output=True, text=True).stdout.strip()
    lines = ["| Module | Check | Against | Result | Status | Run |", "|---|---|---|---|---|---|"]
    for check, result in rows:
        when = datetime.datetime.fromtimestamp(result.when, datetime.UTC).strftime("%Y-%m-%d %H:%M UTC") \
            if result.when else ""
        status = {"pass": "pass", "FAIL": "**FAIL**"}.get(result.status, f"*{result.status}*")
        lines.append(f"| `{check.module}` | {check.title} | {result.against or check.against} | {result.summary} "
                     f"| {status} | {when} |")
    lines += ["", f"Falcon at commit `{commit}`{' with local changes' if dirty else ''}. What each check does, "
                  "and how to run it: [Testing and conformance](https://ebremer.github.io/falcon/testing.html)."]
    return "\n".join(lines) + "\n"


def main():
    names = [c.key for c in checks()]
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--only", help=f"the checks to run or report, by name: {', '.join(names)}")
    parser.add_argument("--report-only", action="store_true", help="report the last runs' results; run nothing")
    parser.add_argument("--update-docs", action="store_true", help=f"also put the report into {DOCS.relative_to(ROOT)}")
    args = parser.parse_args()
    wanted = args.only.split(",") if args.only else names
    unknown = [n for n in wanted if n not in names]
    if unknown:
        parser.error(f"unknown check {', '.join(unknown)}: the checks are {', '.join(names)}")
    selected = [c for c in checks() if c.key in wanted]
    if not args.report_only and not JAR.is_file():
        sys.exit(f"no {JAR}: build it first, with mvn -pl cli -am package -DskipTests")

    rows = []
    for check in selected:
        if args.report_only:
            rows.append((check, check.result() or Result("not run", "no result yet")))
        else:
            rows.append((check, run(check)))
    text = report(rows)
    TARGET.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("## Falcon conformance\n\n" + text, encoding="utf-8")
    print("\n" + text + f"\nreport: {REPORT}")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write("## Falcon conformance\n\n" + text)
    if args.update_docs:
        page = DOCS.read_text(encoding="utf-8")
        before, rest = page.split(START, 1)
        after = rest.split(END, 1)[1]
        DOCS.write_text(f"{before}{START}\n\n{text}\n{END}{after}", encoding="utf-8", newline="\n")
        print(f"updated {DOCS}")
    return 1 if any(r.status == "FAIL" for _, r in rows) and not args.report_only else 0


if __name__ == "__main__":
    sys.exit(main())
