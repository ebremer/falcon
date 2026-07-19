# CLAUDE.md — Falcon project conventions

Falcon is a **pure-JDK 25, zero-runtime-dependency HDF5 reader/writer** implementing the
[HDF5 File Format Specification, Version 4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html)
(HDF5 2.0). The implementation roadmap lives in [`PLAN.md`](PLAN.md).

## Commit policy (IMPORTANT)

All commits in this repository **must be authored and committed solely as**:

```
Erich Bremer <erich@ebremer.com>
```

- **Do NOT** add `Co-Authored-By:` trailers (including any Claude/Anthropic co-author trailer).
- **Do NOT** attribute commits to any other name, email, or bot account.
- Author **and** committer identity must both be `erich@ebremer.com`.
- This identity is pinned in the repository-local git config (`git config --local user.email`),
  so commits made from this repo use it automatically. Do not override it per-commit.

Verify before pushing anywhere: `git log --format='%an <%ae> | %cn <%ce>'` — every line must read
`Erich Bremer <erich@ebremer.com> | Erich Bremer <erich@ebremer.com>`.

## Build & test

```bash
mvn compile        # compile the modular main sources (JDK 25, --release 25)
mvn test           # run the JUnit 5 suite
mvn verify         # full build
```

- **JDK 25 required.** The build sets `<maven.compiler.release>25</maven.compiler.release>`.
- The project is a **JPMS module** (`module com.ebremer.falcon`). Only `com.ebremer.falcon`
  is exported; format-level packages stay encapsulated.

## Hard constraints

- **Pure JDK, zero runtime dependencies.** The shipped artifact must depend on nothing beyond
  `java.base`. JUnit 5 is allowed but **test scope only**. Do not add runtime dependencies —
  including compression libraries. `deflate` uses `java.util.zip`; every other filter and the
  Jenkins lookup3 checksum are implemented from scratch.
- **Foreign Function & Memory API** (`java.lang.foreign`, `MemorySegment`/`Arena`) is the primary
  I/O backend, so files larger than 2 GB are handled without per-mapping size limits.
- **HDF5 metadata is little-endian.** Per-datatype *data* byte order is read from the datatype
  message, not assumed.

## Conventions

- Base package: `com.ebremer.falcon`. Sub-packages by format concern (see `PLAN.md` §5).
- Reference the spec section in a comment when implementing a non-obvious on-disk structure.
- Every phase in `PLAN.md` lands with tests (unit + conformance against reference files).
- Match the style of surrounding code; keep the public API small and documented with Javadoc.
