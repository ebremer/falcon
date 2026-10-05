# Falcon — Implementation Plan (umbrella)

**Falcon** is a multi-module Maven umbrella for **pure-JDK 25, zero-runtime-dependency** readers and
writers of scientific-data formats. Each format is a JPMS module under `com.ebremer.falcon.*`, exporting
only its public API package, and depends on nothing beyond `java.base` (JUnit 5 is test-scope only).

This document is the **program-level roadmap**. Each module carries its own detailed plan and remaining-
work list:

| Phase | Module | Package | Scope | State | Plan | TODO |
|---|---|---|---|---|---|---|
| **1** | `hdf5` | `com.ebremer.falcon.hdf5` | Read + write HDF5 File Format Spec **v4.0** (HDF5 2.0) | **1.0-ready** — read-complete (H0–H6), write-broad (H7–H8), hardened (H9) | [`hdf5/PLAN.md`](hdf5/PLAN.md) | [`hdf5/TODO.md`](hdf5/TODO.md) |
| **2** | `zarr` | `com.ebremer.falcon.zarr` | Read Zarr **v2 + v3**, write **v3** | **Built** — v3 core, every common codec, verified vs zarr-python / libzstd / c-blosc | [`zarr/PLAN.md`](zarr/PLAN.md) | [`zarr/TODO.md`](zarr/TODO.md) |
| — | `core` | `com.ebremer.falcon.core` | Compression codecs both formats share: zstd, Blosc (BloscLZ, Snappy, shuffles), LZ4, LZF, bitshuffle | **Built** (2026-10-05) — exported only to `hdf5` and `zarr` | [`zarr/PLAN.md`](zarr/PLAN.md) §10 | the module TODOs |

## Repository & module structure

```
falcon/                              parent aggregator POM (packaging: pom) — shared config only
├── pom.xml                          shared versions, dependencyManagement (JUnit BOM), pluginManagement
├── LICENSE  CLAUDE.md  PLAN.md  README.md
├── core/                            shared codecs — com.ebremer.falcon.core (both modules depend on it)
│   ├── pom.xml                      parent = com.ebremer:falcon
│   └── src/{main,test}/java/…
├── hdf5/                            Falcon Phase 1 — com.ebremer.falcon.hdf5
│   ├── pom.xml                      parent = com.ebremer:falcon
│   ├── PLAN.md  TODO.md  USER_GUIDE.md  BENCHMARKS.md
│   └── src/{main,test}/java/…
└── zarr/                            Falcon Phase 2 — com.ebremer.falcon.zarr
    ├── pom.xml                      parent = com.ebremer:falcon
    ├── PLAN.md  TODO.md  USER_GUIDE.md  BENCHMARKS.md
    └── src/{main,test}/java/…
```

The root `pom.xml` is a `pom`-packaging aggregator holding only shared configuration; each module is a
`jar` with its own `module-info.java`.

## Shared principles (both modules)

Locked at review and applied uniformly across the reactor:

1. **Pure JDK, zero runtime dependencies.** Every shipped artifact depends on nothing beyond `java.base`
   (and Falcon's own `core`, which depends on nothing else). Compression the JDK lacks is implemented
   from scratch (HDF5 szip as CCSDS 121.0 extended-Rice; zstd per RFC 8878, the blosc container, LZ4,
   LZF, and bitshuffle in `core`), never by wrapping native code. JUnit 5 is test-scope only.
2. **Approval gate for dependencies.** Adding any library outside `java.base` — any module, any scope —
   requires explicit approval first (see `CLAUDE.md`).
3. **JDK 25**, `--release 25`; **JPMS module per format**, exporting only the public API package.
4. **Foreign Function & Memory API** (`MemorySegment`/`Arena`) as the primary I/O backend, so files
   larger than 2 GB are handled without per-mapping size limits.
5. **Reader before writer**, and within each a **thin vertical slice first**, then broaden coverage —
   every stage gated by conformance tests against a reference oracle (h5py for HDF5, zarr-python for Zarr).
6. **Typed exceptions carrying byte offsets** for precise diagnostics against a binary format.
7. **Git identity:** `Erich Bremer <erich@ebremer.com>`, no co-author trailers. **License:** Apache-2.0.

## Program non-goals

- **A shared data model in `core`** — a survey of the two modules found no shared *model* worth one
  (data types, byte I/O, checksums, and chunk indexing are format-specific and correctly separate).
  `core` holds the compression codecs only: HDF5's third-party filters needed Zarr's zstd and Blosc, so
  on 2026-10-05 they moved there rather than being copied. Details in [`zarr/PLAN.md`](zarr/PLAN.md) §10.
- Module-specific non-goals (SWMR / MPI / HL APIs for HDF5; Zarr v2 writing; cloud object stores; …) are
  listed in each module's PLAN and TODO.
