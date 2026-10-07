"""Checks Falcon's values for the Zarr conformance tests' arrays against zarr-python's.

The community suite (run_conformance.sh) passes an implementation whose command exits 0 on each array; this
script also reads every array with zarr-python, the reference, and with `falcon dump --format json`, and
compares each value:

    bash tools/conformance/run_conformance.sh    # fetches the suite into cli/target/conformance
    python -I tools/conformance/check_values.py  # needs numpy and zarr-python 3 (dev-time tools, like h5py)

An argument names another directory of arrays to check instead of the suite's data.
"""
import json
import pathlib
import subprocess
import sys

import numpy as np
import zarr

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAR = ROOT / 'cli' / 'target' / 'falcon.jar'
SUITE_TAG = 'v0.0.2'  # as run_conformance.sh fetches it


def main():
    data = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else (
        ROOT / 'cli' / 'target' / 'conformance' / f'zarr-conformance-tests-{SUITE_TAG}' / 'data')
    if not JAR.is_file() or not data.is_dir():
        sys.exit(f'needs {JAR} and {data}: run tools/conformance/run_conformance.sh first')
    zarr.config.set({'async.concurrency': 1})
    failures = 0
    arrays = sorted(p for p in data.iterdir() if p.is_dir())
    for array in arrays:
        expected = zarr.open_array(str(array), mode='r')[...]
        run = subprocess.run(['java', '-jar', str(JAR), 'dump', str(array), '/', '--format', 'json'],
                             capture_output=True, text=True, encoding='utf-8')
        if run.returncode != 0:
            failures += 1
            print(f'FAIL {array.name}: falcon exited {run.returncode}: {run.stderr.strip()}')
            continue
        got = np.array(json.loads(run.stdout), dtype=expected.dtype).reshape(expected.shape)
        if np.array_equal(got, expected, equal_nan=expected.dtype.kind in 'fc'):
            print(f'ok   {array.name}: {expected.dtype} {list(expected.shape)}, {expected.size} values')
        else:
            failures += 1
            print(f'FAIL {array.name}: values differ\n  zarr-python: {expected.tolist()}\n  falcon: {got.tolist()}')
    print(f'{len(arrays)} arrays, {failures} failures')
    if len(sys.argv) == 1:  # the suite's data: for run_all.py's report
        summary = {'arrays': len(arrays), 'failures': failures, 'suite': SUITE_TAG}
        (data.parents[1] / 'values-summary.json').write_text(json.dumps(summary), encoding='utf-8')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
