#!/usr/bin/env bash
# Reads the HDF Group's CVE test files -- https://github.com/HDFGroup/cve_hdf5, the malformed files behind each
# CVE filed against the HDF5 library and the fuzzer finds beside them -- with Falcon's hdf5 module, as
# .github/workflows/hdf5-conformance.yml does in CI:
#
#   bash tools/conformance/run_hdf5_cve.sh
#
# Each file must read, or fail with a typed exception (HdfException, IOException), within a minute, under a
# small stack and heap: never crash, hang, or run out of memory. The files are fetched, at the commit pinned
# below, into hdf5/target/cve_hdf5: dev-time data, not Falcon's. The test is CveCorpusTest, in the hdf5
# module's fuzz execution; its reports go to hdf5/target/surefire-reports. Needs bash, git, Maven, and java 25;
# runs on Linux, macOS, and Git Bash on Windows.
set -euo pipefail

CVE_COMMIT=3fd1f5ae3869e01b8ae02b41d7108de7ffb1a374 # HDFGroup/cve_hdf5's main, 2026-09-17

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
corpus=$root/hdf5/target/cve_hdf5-${CVE_COMMIT:0:7}
if [ ! -d "$corpus/cvefiles" ]; then
  rm -rf "$corpus"
  git init --quiet "$corpus"
  git -C "$corpus" fetch --quiet --depth 1 https://github.com/HDFGroup/cve_hdf5.git "$CVE_COMMIT"
  git -C "$corpus" -c advice.detachedHead=false checkout --quiet FETCH_HEAD
fi

cd "$root"
# Git Bash: java wants D:/..., not /d/...
dir=$(cygpath -m "$corpus" 2>/dev/null || echo "$corpus")
mvn -B --no-transfer-progress -pl hdf5 -am test -Dtest=CveCorpusTest -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dfalcon.cve.dir=$dir" "$@"
