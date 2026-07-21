# Falcon HDF5 — remaining work

Status snapshot: the **`hdf5` module is 1.0-ready** — read-complete (H0–H6), write-broad (H7–H8), and
H9-essentially-complete (robustness, >2 GB, performance, streaming API, CI, docs). 144 tests green.

Nothing below is required for a solid 1.0. Items are grouped by whether they are actionable now, blocked
by this environment, or explicit non-goals. See [`PLAN.md`](PLAN.md) for the full roadmap, or the umbrella
[`../PLAN.md`](../PLAN.md) for the program-level view.

## Priorities — top 5 high-value

Ranked read-completeness-for-modern-files first (the reader is otherwise complete), then write breadth.
(Full backlog in the summary below.)

1. **Revised `H5R_ref_t` reference encoding (read).** HDF5 1.12+/2.0 files created with the new reference
   API use this encoding; Falcon reads only the legacy form today. As modern h5py/libhdf5 output
   proliferates this becomes a *read* gap on mainstream files — the highest-impact miss, since reading is
   the completed strength.
2. **SOHM shared-message deduplication (read).** A file that enables shared messages (many objects reusing
   one compound datatype/dataspace) throws `HdfUnsupportedException`. A genuine unsupported *input*, not
   just an untested path, and produced by real data pipelines.
3. **Unlimited-pattern (printf) VDS mappings (read).** Virtual datasets mapping to an open-ended set of
   source files (acquisition/streaming layouts) are the one VDS shape still refused. Completes VDS read.
4. **Indirect-block fractal heaps (write).** Unblocks writing groups/attribute sets past ~one direct block
   (thousands of entries); very large dense groups throw on write without it.
5. **Opaque / bitfield / time datatypes (write).** Closes the last write datatype-coverage gap. Opaque
   (arbitrary binary blobs) is the most useful; bitfield/time are rare. Each is a small, well-scoped
   addition like the atomics already implemented.

## Unimplemented features (summary)

Everything not yet implemented, in one place. None of it blocks reading or writing real HDF5 files; the
read path is complete (H0–H6) and the write path is broad. The sections below give the detail.

- [ ] **Exotic atomic datatypes on write** — bitfield, opaque, time.
- [ ] **Indirect-block fractal heaps** — for dense link/attribute sets that overflow a single direct block.
- [ ] **szip better-ratio encode modes** — nearest-neighbour preprocessing, zero-block / second-extension (current output is already libaec-correct).
- [ ] **Benchmark / perf-regression harness** — deliberately skipped (low value, flaky in CI).
- [ ] **SOHM shared-message deduplication** (message 15) — read side; not producible by the local h5py.
- [ ] **Unlimited-pattern (printf) VDS mappings** — read side; h5py rejects an unlimited virtual dimension.
- [ ] **Revised `H5R_ref_t` reference encoding** (HDF5 1.12+).
- [ ] **Signed szip at the *filter* level** — the AEC decoder handles signed data; threading datatype signedness into the filter is untested here.
- [ ] **Public accessors for B-tree K-values (msg 19) / Driver Info (msg 20)** — both already parse; no local emitter to test end-to-end.
- Non-goals (by design): SWMR concurrency, MPI / parallel I/O, the HDF5 high-level APIs, multi-file drivers, and byte-for-byte on-disk parity with libhdf5.

## Write path — concrete but niche

- [ ] **Exotic atomic datatypes on write:** bitfield, opaque, time. Rare in real data; each is a small
      addition like the int8/16/64 / float32 / fixed-string atomics already implemented.
- [ ] **Indirect-block fractal heaps** for dense link/attribute sets that overflow a single direct block.
      The writer already handles a full direct block (~65 KB of objects — thousands of small links/attrs),
      so this only matters for extreme cases.
- [ ] **szip better-ratio encode modes:** nearest-neighbour preprocessing + zero-block / second-extension.
      Current szip output is already libaec-correct; these only improve the compression *ratio*, and can't
      be h5py-verified here (this h5py ships szip disabled).

## H9 — optional polish

- [ ] **Benchmark / perf-regression harness.** Deliberately skipped so far (low value; perf tests are
      flaky in CI). All other H9 work is done: robustness fuzzing, >2 GB read, touch-only-needed-chunks,
      decoded-chunk cache, streaming/scalar API, JDK 25 CI, and the user guide.

## Read edge cases — blocked in this environment

None block real files; each was probed and found un-producible by the local h5py 3.16 / HDF5 2.0 build,
so they can't be validated against the oracle here.

- [ ] **SOHM** shared-message deduplication (message 15) — `fcpl` cannot enable it in this h5py.
- [ ] **Unlimited-pattern (printf) VDS** mappings — h5py rejects an unlimited virtual dimension.
- [ ] **Revised `H5R_ref_t`** reference encoding (HDF5 1.12+).
- [ ] **Signed szip at the *filter* level** — the AEC decoder already handles signed data; reaching it on
      a real file needs the datatype's signedness threaded into the szip filter, untestable without szip
      fixtures.
- [ ] **Surface B-tree K-values (19) / Driver Info (20)** via a public `Hdf5File` accessor — trivial, but
      untestable end-to-end (no local emitter), so low value. Both messages already parse.

## Explicit non-goals (Phase 1)

Out of scope by design — not planned for the `hdf5` module:

- SWMR concurrent-writer semantics; MPI / parallel I/O.
- The HDF5 high-level APIs (images, tables, dimension scales).
- Multi-file drivers (family / multi / split) — read-only, low priority.
- Byte-for-byte on-disk layout parity with libhdf5.

## Beyond the HDF5 module

- **Falcon Phase 2 — Zarr** (`com.ebremer.falcon.zarr`): now underway; its remaining work is tracked in
  [`../zarr/TODO.md`](../zarr/TODO.md).
