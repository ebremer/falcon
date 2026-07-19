# CLAUDE.md — Falcon project conventions

**Falcon** is a multi-module Maven umbrella for **pure-JDK 25, zero-runtime-dependency** readers and
writers of scientific-data formats:

- **`hdf5`** module (`com.ebremer.falcon.hdf5`) — an HDF5 reader/writer implementing the
  [HDF5 File Format Specification, Version 4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html)
  (HDF5 2.0). **Built now** (Falcon Phase 1).
- **`zarr`** module (`com.ebremer.falcon.zarr`) — a Zarr reader/writer. **Planned / pinned**
  (Falcon Phase 2). Do not start it until asked.

The full roadmap is in [`PLAN.md`](PLAN.md).

## Commit policy (IMPORTANT)

All commits in this repository **must be authored and committed solely as**:

```
Erich Bremer <erich@ebremer.com>
```

- **Do NOT** add `Co-Authored-By:` trailers (including any Claude/Anthropic co-author trailer).
- **Do NOT** attribute commits to any other name, email, or bot account.
- Author **and** committer identity must both be `erich@ebremer.com`.
- Pinned in repository-local git config (`git config --local user.email` / `user.name`), so commits
  from this repo use it automatically. Do not override it per-commit.

Verify before pushing anywhere: `git log --format='%an <%ae> | %cn <%ce>'` — every line must read
`Erich Bremer <erich@ebremer.com> | Erich Bremer <erich@ebremer.com>`.

## Build & test

```bash
mvn verify                 # build/test the whole reactor (parent + all modules)
mvn -pl hdf5 test          # test just the hdf5 module
mvn -pl hdf5 compile       # compile just the hdf5 module
```

- **JDK 25 required.** The parent POM sets `<maven.compiler.release>25</maven.compiler.release>`.
- Each format module is a **JPMS module** (e.g. `module com.ebremer.falcon.hdf5`), exporting only its
  public API package; format-level packages stay encapsulated.
- The root `pom.xml` is a `pom`-packaging aggregator: shared versions live in its `<properties>`,
  `<dependencyManagement>` (JUnit BOM), and `<pluginManagement>`.

## Hard constraints

- **Approval gate for dependencies (standing instruction from Erich).** Before adding ANY library
  outside `java.base` — any module, **any scope** (runtime, test, or build) — **stop and ask Erich for
  explicit approval first.** The only pre-approved non-JDK library is **JUnit 5** (test scope).
- **Pure JDK, zero runtime dependencies.** Every shipped artifact must depend on nothing beyond
  `java.base`. JUnit 5 is allowed but **test scope only**. Do not add runtime dependencies —
  including compression libraries.
  - `deflate` uses `java.util.zip`.
  - **`szip` is IN scope** and must be implemented from scratch in pure Java as CCSDS 121.0
    extended-Rice / adaptive entropy coding (libaec-compatible), **not** by wrapping native code.
  - `shuffle`, `fletcher32`, `nbit`, `scaleoffset`, and the Jenkins lookup3 checksum are hand-written.
- **Foreign Function & Memory API** (`java.lang.foreign`, `MemorySegment`/`Arena`) is the primary I/O
  backend, so files larger than 2 GB are handled without per-mapping size limits.
- **HDF5 metadata is little-endian.** Per-datatype *data* byte order is read from the datatype
  message, not assumed.

## Conventions

- HDF5 code lives under `com.ebremer.falcon.hdf5.*`; sub-packages by format concern (see `PLAN.md` §6).
- Shared abstractions (byte I/O, checksums, the array/datatype/chunk model) may be promoted to a
  future `com.ebremer.falcon.core` module when the Zarr module lands — keep them cohesive.
- Reference the spec section in a comment when implementing a non-obvious on-disk structure.
- Every roadmap stage lands with tests: unit tests plus conformance tests against reference `.h5`
  files generated with **h5py** (3.16.0 / HDF5 2.0.0 is installed locally — the reference oracle).
- Test fixtures live in `hdf5/src/test/resources/fixtures/`; regenerate `.h5` files with
  `python tools/fixtures/gen_fixtures.py`. Fixture bytes are stable enough that tests assert structure,
  not incidental addresses. The AEC/szip reference vectors (`aec_vectors.txt`, `szip_chunks.txt`) come
  from **libaec** via `tools/fixtures/gen_aec_vectors.py` (needs `pip install imagecodecs`, a dev-time
  tool like h5py — not a Falcon dependency).
- Match the style of surrounding code; keep the public API small and documented with Javadoc.
- License: **Apache-2.0** (`LICENSE` at the repo root). New source files may carry the standard
  Apache header; keep `Copyright <year> Erich Bremer`.
