"""Runs the OME-Zarr specification's own conformance tests against the falcon command's validator.

    mvn -pl cli -am package -DskipTests
    python tools/conformance/run_ome_conformance.py

Two sets of tests, both fetched (with git) into cli/target/conformance -- dev-time data, not Falcon's:

- 0.6: https://github.com/ome/ngff-spec at tag 0.6, run through its own tool, conformance/ome_zarr_conformance.py,
  in both its modes: 'attributes' (one group's attributes, as JSON) and 'zarr' (a hierarchy's root zarr.json).
  Its "dingus" is `java -jar falcon.jar ome validate --json`, with --strict for the strict tests.
- 0.4 and 0.5: https://github.com/ome/ngff at tag 0.5.2, whose tests are JSON schema test suites
  (<version>/tests/*_suite.json): each test's data goes to the same command, strict suites with --strict.

A test whose data breaks a rule of the specification's text that its JSON schemas do not check is listed in
ERRATA with the rule: Falcon's validator reports it invalid where the suite says valid, as ome-zarr-models (the
community's Python validator) does too. Such a test is reported, not failed. The script exits with 1 if any
other test disagrees, 0 otherwise. Needs Python 3.11+, git, and java 25; nothing beyond Python's standard library.
"""
import concurrent.futures
import json
import os
import pathlib
import subprocess
import sys
import tempfile

SPEC_06 = ("ome/ngff-spec", "0.6")
SPEC_05 = ("ome/ngff", "0.5.2")

ROOT = pathlib.Path(__file__).resolve().parents[2]
TARGET = ROOT / "cli" / "target"
WORK = TARGET / "conformance"
JAR = TARGET / "falcon.jar"

SWAPPED = "the well's path is its row's name, '/', and its column's name (the rows and columns are swapped)"
SCALE = "the scale must have one value for each axis"
ERRATA = {
    # 0.6, in both modes
    "spec/valid/image/mismatch_axes_units": SCALE,
    "spec/valid/image/multiscales_transform_additional_transforms":
        "every output axis of a byDimension must appear in one of its parts (axis 2 appears in none)",
    "spec/valid/plate/minimal_acquisitions": SWAPPED,
    "spec/valid/plate/minimal_no_acquisitions": SWAPPED,
    "spec/valid/plate/non_alphanumeric_row": SWAPPED,
    "strict/valid/image/multiscales_example": "a level's transformation's input must be its own path ('1', not 's1')",
    "strict/valid/plate/strict_acquisitions": SWAPPED,
    "strict/valid/plate/strict_no_acquisitions": SWAPPED,
    # 0.4 and 0.5 suites
    "image_suite valid/mismatch_axes_units.json": SCALE,
    "plate_suite plate/minimal_acquisitions": SWAPPED,
    "plate_suite plate/minimal_no_acquisitions": SWAPPED,
    "plate_suite plate/non_alphanumeric_row": SWAPPED,
    "strict_plate_suite plate/strict_acquisitions": SWAPPED,
    "strict_plate_suite plate/strict_no_acquisitions": SWAPPED,
}
# 0.6's zarr-mode image tests still give the draft's string "input" and "output", which 0.6's own schemas
# reject as its attributes-mode twins (with objects) show; ome_zarr_conformance.py's schema dingus fails them too.
STALE_06_ZARR = {
    "spec/valid/image/custom_type_axes", "spec/valid/image/invalid_axis_units", "spec/valid/image/mismatch_axes_units",
    "spec/valid/image/missing_name", "spec/valid/image/untyped_axes", "strict/valid/image/image",
    "strict/valid/image/image_metadata", "strict/valid/image/image_omero", "strict/valid/image/multiscales_example",
    "strict/valid/image/multiscales_transformations",
}
STALE = "0.6's zarr-mode image tests give the draft's string input and output; 0.6's schemas require objects"


def fetch(repository, tag):
    directory = WORK / f"{repository.split('/')[1]}-{tag}"
    if not directory.exists():
        WORK.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1", "--branch", tag,
                        f"https://github.com/{repository}.git", str(directory)], check=True)
    return directory


def falcon(*args):
    return ["java", "-jar", str(JAR), "ome", "validate", "--json", *args]


def suite_06(spec, mode):
    """Runs ngff-spec's tool in one mode; returns {test name: 'pass' | 'fail' | 'error'}."""
    tool = spec / "conformance" / "ome_zarr_conformance.py"
    flag = "--attributes" if mode == "attributes" else "--metadata-only"
    results = {}
    for strict in (False, True):
        selection = ["-p", "^strict/"] if strict else ["-S"]
        dingus = falcon(*(["--strict"] if strict else []), flag)
        run = subprocess.run([sys.executable, "-I", str(tool), mode, "-X", *selection, "--", *dingus],
                             capture_output=True, text=True)
        for line in run.stdout.splitlines():
            name, status = line.split("\t")
            results[name] = status
    return results


def suites_045(ngff):
    """Runs the 0.4 and 0.5 JSON schema test suites; returns {test name: (expected valid, falcon's answer)}."""
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        for version in ("0.4", "0.5"):
            for path in sorted((ngff / version / "tests").glob("*_suite.json")):
                suite = json.loads(path.read_text(encoding="utf-8"))
                for i, test in enumerate(suite["tests"]):
                    data = pathlib.Path(tmp) / f"{version}-{path.stem}-{i}.json"
                    data.write_text(json.dumps(test["data"]), encoding="utf-8")
                    cases.append((f"{version} {path.stem} {test['formerly']}", path.stem.startswith("strict_"),
                                  test["valid"], data))

        def check(case):
            name, strict, valid, data = case
            out = subprocess.run(falcon(*(["--strict"] if strict else []), "--attributes", str(data)),
                                 capture_output=True, text=True)
            if out.returncode != 0:
                return name, valid, None
            return name, valid, json.loads(out.stdout)["valid"]

        with concurrent.futures.ThreadPoolExecutor(max(4, os.cpu_count() or 4)) as pool:
            return {name: (valid, got) for name, valid, got in pool.map(check, cases)}


def main():
    if not JAR.is_file():
        sys.exit(f"no {JAR}: build it first, with mvn -pl cli -am package -DskipTests")
    spec = fetch(*SPEC_06)
    ngff = fetch(*SPEC_05)
    unexpected = 0
    errata = 0
    for mode in ("attributes", "zarr"):
        results = suite_06(spec, mode)
        passed = sum(1 for s in results.values() if s == "pass")
        print(f"0.6 {mode}: {len(results)} tests, {passed} pass")
        for name, status in sorted(results.items()):
            if status == "pass":
                continue
            reason = ERRATA.get(name) or (STALE if mode == "zarr" and name in STALE_06_ZARR else None)
            if reason and status == "fail":
                errata += 1
                print(f"  erratum  {name}: {reason}")
            else:
                unexpected += 1
                print(f"  {status.upper():7}  {name}")
    results = suites_045(ngff)
    passed = sum(1 for valid, got in results.values() if valid == got)
    print(f"0.4 and 0.5 suites: {len(results)} tests, {passed} pass")
    for name, (valid, got) in sorted(results.items()):
        if valid == got:
            continue
        reason = ERRATA.get(name.split(" ", 1)[1])
        if reason and got is not None:
            errata += 1
            print(f"  erratum  {name}: {reason}")
        else:
            unexpected += 1
            print(f"  {'ERROR' if got is None else 'FAIL':7}  {name} (expected {'valid' if valid else 'invalid'})")
    print(f"{errata} errata in the suites' data, {unexpected} unexpected results")
    sys.exit(1 if unexpected else 0)


if __name__ == "__main__":
    main()
