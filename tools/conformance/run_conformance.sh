#!/usr/bin/env bash
# Runs the Zarr community's conformance tests -- https://github.com/Bisaloo/zarr-conformance-tests, the suite
# zarr-java runs -- against falcon.jar, as .github/workflows/conformance.yml does in CI:
#
#   mvn -pl cli -am package -DskipTests
#   bash tools/conformance/run_conformance.sh
#
# The suite calls "$ZARR_CLI --array_path=<array>" on each of its arrays, which here is
# "java -jar falcon.jar conformance". This script fetches the suite, at the tag the CI pins, and bats-core, the
# test runner (unless bats is on the PATH), into cli/target/conformance: dev-time tools, like h5py, not Falcon
# dependencies. The JUnit report goes there too, as report.xml. Needs bash, git, and java 25; runs on Linux,
# macOS, and Git Bash on Windows.
set -euo pipefail

SUITE_TAG=v0.0.2 # keep in step with .github/workflows/conformance.yml
BATS_TAG=v1.14.0

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
target=$root/cli/target
if [ ! -f "$target/falcon.jar" ]; then
  echo "no $target/falcon.jar: build it first, with mvn -pl cli -am package -DskipTests" >&2
  exit 2
fi

fetch() { # <repository> <tag> <directory>
  if [ ! -d "$3" ]; then
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$2" "https://github.com/$1.git" "$3"
  fi
}

work=$target/conformance
suite=zarr-conformance-tests-$SUITE_TAG
mkdir -p "$work"
fetch Bisaloo/zarr-conformance-tests "$SUITE_TAG" "$work/$suite"
bats=$(command -v bats || true)
if [ -z "$bats" ]; then
  fetch bats-core/bats-core "$BATS_TAG" "$work/bats-core-$BATS_TAG"
  bats=$work/bats-core-$BATS_TAG/bin/bats
fi

# The suite splits $ZARR_CLI and the array paths on spaces, so both are given relative to $work, whatever the
# checkout's path.
cd "$work"
export ZARR_CLI="java -jar ../falcon.jar conformance"
export ZARR_CONFORMANCE_DATA=$suite/data
"$bats" --timing --report-formatter junit --output . "$suite/tests.bats"
