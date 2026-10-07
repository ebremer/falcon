---
title: Conformance results
description: The latest results of Falcon's conformance checks - the Zarr and OME-Zarr specifications' suites, and the HDF Group's own HDF5 files.
---

Falcon is checked against five sets of tests written by others: the Zarr community's conformance suite, with
zarr-python reading the same arrays; the OME-Zarr specification's conformance tests; and, for HDF5, which has no
such suite, the HDF5 library's own test files, which Falcon and h5py read and compare, and the HDF Group's CVE
files. [Testing and conformance](testing.md) says what each check does and how to run it.

## In CI

Every push to `main` runs them on GitHub Actions, all but the zarr-python comparison. Each run's page shows its
results as a table like the one below.

| Workflow | Checks | Status |
|---|---|---|
| [Zarr conformance tests](https://github.com/ebremer/falcon/actions/workflows/conformance.yml) | `zarr` | ![Zarr conformance tests](https://github.com/ebremer/falcon/actions/workflows/conformance.yml/badge.svg) |
| [OME-Zarr conformance tests](https://github.com/ebremer/falcon/actions/workflows/ome-conformance.yml) | `ome` | ![OME-Zarr conformance tests](https://github.com/ebremer/falcon/actions/workflows/ome-conformance.yml/badge.svg) |
| [HDF5 conformance tests](https://github.com/ebremer/falcon/actions/workflows/hdf5-conformance.yml) | `hdf5`, `cve` | ![HDF5 conformance tests](https://github.com/ebremer/falcon/actions/workflows/hdf5-conformance.yml/badge.svg) |

## The latest full run

All five checks, run together on one machine by `python tools/conformance/run_all.py --update-docs`, which
writes this table:

<!-- results:start -->

| Module | Check | Against | Result | Status | Run |
|---|---|---|---|---|---|
| `zarr` | Zarr community conformance suite | [Bisaloo/zarr-conformance-tests](https://github.com/Bisaloo/zarr-conformance-tests) v0.0.2 | 6 / 6 pass | pass | 2026-10-07 21:32 UTC |
| `zarr` | The suite's arrays' values | zarr-python, on [Bisaloo/zarr-conformance-tests](https://github.com/Bisaloo/zarr-conformance-tests) v0.0.2 | 6 / 6 arrays' values match zarr-python's | pass | 2026-10-07 21:32 UTC |
| `ome` | OME-Zarr specification conformance tests (0.4, 0.5, 0.6) | [ome/ngff-spec](https://github.com/ome/ngff-spec) 0.6, [ome/ngff](https://github.com/ome/ngff) 0.5.2 | 368 / 403 agree; the other 35 are errata in the suites' own data (listed in the runner); 0 unexpected | pass | 2026-10-07 21:32 UTC |
| `hdf5` | The HDF5 library's own test files, against h5py | [HDFGroup/hdf5](https://github.com/HDFGroup/hdf5) hdf5_2.0.0, h5py 3.16.0 (HDF5 2.0.0) | 434 files: 310 agree, 60 refused by both, Falcon reads more of 62, 2 known differences (listed in the runner); 0 unexpected | pass | 2026-10-07 22:01 UTC |
| `hdf5` | The HDF Group's CVE files | [HDFGroup/cve_hdf5](https://github.com/HDFGroup/cve_hdf5) 3fd1f5a | 147 / 147 files read or fail typed, within a minute, in a 128 MB heap | pass | 2026-10-07 22:02 UTC |

Falcon at commit `7fb984d` with local changes. What each check does, and how to run it: [Testing and conformance](https://ebremer.github.io/falcon/testing.html).

<!-- results:end -->

The HDF5 counts depend on h5py's build: on Linux, as in CI, h5py also reads szip data and 16-byte long doubles,
which h5py on Windows cannot, so more files agree there and fewer are ones where Falcon reads more.

## What the numbers mean

- **Zarr suite**: the suite reads each of its arrays through `falcon conformance --array_path` and checks that
  it can. **Values** then reads the same arrays with zarr-python and with `falcon dump`, and compares every value.
- **OME-Zarr**: each test case is a document the specification marks valid or invalid, which
  `falcon ome validate` must judge the same way. The errata are cases marked valid that break a rule of the
  specification's text which its schemas cannot check; ome-zarr-models, the community's validator, rejects
  them too.
- **HDF5 library's test files**: each file *agrees* (Falcon and h5py read the same links, objects, attributes,
  and values), is *refused by both* (files of the multi-file drivers, and files broken on purpose), is one where
  *Falcon reads more* (what h5py or libhdf5 cannot read: region and new-style references, szip data, VAX floats,
  damaged files), or is a *known difference*: a file whose metadata is in a metadata cache image, which Falcon
  does not read yet, and an h5py bug that the HDF5 library's expected output confirms. Anything else fails the
  check, as does any Falcon failure that is not a typed exception.
- **CVE files**: the malformed files behind the HDF5 library's CVEs, and fuzzer finds. Each must read, or fail
  with a typed exception, within a minute, in a 256 KB stack and a 128 MB heap: never crash, hang, or run out of
  memory.
