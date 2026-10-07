---
title: Testing and conformance
description: How Falcon is tested - unit tests, reference fixtures, community conformance suites, and reference-tool checks - and how to run each.
---

Falcon is tested at four levels, and each can be run on your own machine:

1. **Unit and fixture tests** (`mvn verify`): JUnit tests of every module, against committed files that the
   reference tools wrote. They need nothing but the JDK and Maven.
2. **Community conformance suites**: the Zarr community's tests and the OME-Zarr specification's own tests,
   run against the `falcon` command. They need Git, and Bash or Python.
3. **Reference-tool checks**: scripts that have Falcon write files and the reference tools (h5py, zarr-python,
   ome-zarr-py, ome-zarr-models) read them back, value by value. They need a Python environment with those tools.
4. **CI** on GitHub Actions runs levels 1 and 2 on every push.

## Unit and fixture tests

```bash
mvn verify                     # every module, with every test (a few minutes)
mvn -pl hdf5 -am test          # one module (and the modules it needs)
mvn -pl zarr -am test
mvn -pl ome -am test
mvn -pl cli -am package        # the falcon command, with its tests
mvn verify -Pcoverage          # with a JaCoCo coverage report: <module>/target/site/jacoco/index.html
```

The build is **hermetic**: the HDF5 and Zarr files the tests read are committed under each module's
`src/test/resources/fixtures/`, so no HDF5 library, h5py, or zarr-python is needed to build. Those tools are
the *reference oracles* that generated the fixtures, never Falcon dependencies.

What the tests cover, roughly:

| Module | Tests |
|---|---|
| `core` | every codec against vectors from its reference implementation (libzstd, c-blosc and c-blosc2, libbzip2, libzfp, libSZ, libaec, and the rest) |
| `hdf5` | reading files h5py and libhdf5 wrote, of every format version, layout, datatype, and filter; writing and changing files; corrupt input |
| `zarr` | reading stores zarr-python wrote (v2 and v3, every codec and data type); writing; stores; corrupt input |
| `ome` | ome-zarr-py's images, labels, plates, and collections; a 0.6 scene; the validator's rules; writing and pyramids; hostile metadata |
| `s3` | the stores and range reader against an in-process S3 |
| `cli` | every command, locally, over HTTP, and against the in-process S3 |

## Community conformance suites

### Zarr: zarr-conformance-tests

The Zarr community's [conformance tests](https://github.com/Bisaloo/zarr-conformance-tests) (the suite
zarr-java runs too) call `falcon conformance --array_path=<array>` on each of their arrays.

```bash
mvn -pl cli -am package -DskipTests
bash tools/conformance/run_conformance.sh     # fetches the suite and bats-core into cli/target/conformance
```

The suite checks only that each array can be read. To compare every value with zarr-python's too:

```bash
python -I tools/conformance/check_values.py   # needs numpy and zarr-python
```

### OME-Zarr: the specification's own tests

The OME-Zarr specification ships conformance tests: for 0.6 in
[ngff-spec](https://github.com/ome/ngff-spec), run through its own `ome_zarr_conformance.py`, and for 0.4 and
0.5 as JSON schema test suites in [ngff](https://github.com/ome/ngff). They call `falcon ome validate --json`
on each case:

```bash
mvn -pl cli -am package -DskipTests
python tools/conformance/run_ome_conformance.py   # Python 3.11+ and Git; nothing else to install
```

The script fetches both suites at pinned tags into `cli/target/conformance`, runs all 403 tests, and prints
each disagreement. Falcon agrees with 368. The other 35 are errors in the suites' own test data: cases
marked valid that break a rule of the specification's text which its schemas cannot check (plates whose rows
and columns are swapped, a scale with too few values, and 0.6 tests in a pre-release form). The script lists
each with the rule it breaks; ome-zarr-models, the community's Python validator, rejects them too. It fails
only on any *other* disagreement.

## Reference-tool checks

These scripts check Falcon against the reference implementations on files written now, not just the
committed fixtures. Set up a Python environment once:

```bash
python -m venv .venv-fixtures
.venv-fixtures/bin/pip install -r tools/fixtures/requirements.txt     # Scripts\pip on Windows
```

| Script | Checks |
|---|---|
| `tools/fixtures/check_hdf5_writer.py` | files Falcon's `Hdf5Writer` writes, read and changed by h5py (and optionally HDF5 1.14) |
| `tools/fixtures/check_zarr_writer.py`, `check_zarr_v2_writes.py`, `check_zarr_hierarchies.py`, `check_zip_store.py` | stores Falcon writes, read by zarr-python |
| `tools/fixtures/check_zstd_encoder.py`, `check_blosc_encoder.py` | Falcon's compressors' output, read by libzstd and c-blosc |
| `tools/fixtures/check_cli.py` | `falcon convert` and `copy`, both directions, read back by h5py and zarr-python |
| `tools/fixtures/check_ome.py` | `falcon ome pyramid` in each OME-Zarr version, validated by ome-zarr-models, read by ome-zarr-py, and compared with numpy's pyramid |

Build what a script needs first (`mvn install -DskipTests`, or `mvn -pl cli -am package -DskipTests` for the
command), then run it with the environment's Python, from the repository root. Run Python with `-I` (or from
another directory) so that the repository's `zarr/` folder does not hide the `zarr` package.

### Regenerate the fixtures

The committed fixtures come from the `tools/fixtures/gen_*.py` scripts, each writing with a reference tool:

```bash
python tools/fixtures/gen_fixtures.py          # HDF5 fixtures, with h5py
python tools/fixtures/gen_zarr_fixtures.py     # Zarr fixtures, with zarr-python (and the other gen_zarr_* scripts)
python tools/fixtures/gen_ome_fixtures.py      # OME-Zarr fixtures, with ome-zarr-py
```

Each script's first lines say what it writes and what it needs. Use the versions in `requirements.txt`, so
that regenerated fixtures hold the same data and a change shows up as a real difference.

## Continuous integration

| Workflow | Runs |
|---|---|
| `.github/workflows/ci.yml` | `mvn verify` on Linux and Windows, JDK 25 |
| `.github/workflows/conformance.yml` | the Zarr community's conformance tests against `falcon.jar` |
| `.github/workflows/ome-conformance.yml` | the OME-Zarr specification's conformance tests against `falcon.jar` |

All three run on every push to `main` and on every pull request.
