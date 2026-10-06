/**
 * Public API for Falcon's Zarr reader/writer: Zarr v3 read and write, Zarr v2 read.
 *
 * <p>{@link com.ebremer.falcon.zarr.Zarr#open} reads a hierarchy from a
 * {@link com.ebremer.falcon.zarr.store.Store} and returns its root
 * {@link com.ebremer.falcon.zarr.ZarrNode}: a {@link com.ebremer.falcon.zarr.ZarrGroup} or a
 * {@link com.ebremer.falcon.zarr.ZarrArray}. Groups navigate to their children and create new ones; arrays
 * describe themselves and read and write their elements through
 * {@link com.ebremer.falcon.zarr.Selection}s.
 *
 * <h2>Errors</h2>
 *
 * <p>What is wrong with the store and what is wrong with the call are told apart:
 * <ul>
 *   <li><b>The store:</b> a {@link com.ebremer.falcon.zarr.ZarrException}, whatever the caller does.
 *       {@link com.ebremer.falcon.zarr.ZarrFormatException}: malformed metadata, chunks, or compressed
 *       data, including a failed checksum and a chunk that decodes to more than it should hold.
 *       {@link com.ebremer.falcon.zarr.ZarrUnsupportedException}: a valid store using a feature Falcon
 *       does not implement. A plain {@code ZarrException}: an I/O failure, a selection too large for one
 *       Java array, or a typed read or write the array's data type does not support (for example
 *       {@code readInts} of a {@code uint64} array, {@code writeDoubles} of a string array).</li>
 *   <li><b>The call:</b> the JDK's own exceptions. {@link IllegalArgumentException}: a wrong rank or
 *       number of values, an invalid name, a spec {@link com.ebremer.falcon.zarr.ArraySpec.Builder#build()}
 *       refuses, a value the data type cannot hold, or a node that already exists when {@code overwrite}
 *       is false. {@link IndexOutOfBoundsException}: a selection or chunk coordinates outside the array.
 *       {@link java.util.NoSuchElementException}: {@code group.array(name)}, {@code group(name)}, or
 *       {@code delete(name)} of a missing child. {@link IllegalStateException}: {@code asArray()} of a group or {@code asGroup()}
 *       of an array. {@link UnsupportedOperationException}: writing through a read-only store.
 *       {@link NullPointerException}: a null argument.</li>
 * </ul>
 * Anything else escaping for some stored bytes is a defect; corrupt metadata, chunks, and shard indexes
 * are fuzzed under a small heap to find such defects.
 *
 * <h2>Threads</h2>
 *
 * <p>Handles ({@code ZarrGroup}, {@code ZarrArray}, {@code Selection}) hold no mutable state except a
 * cached handle's cache and a consolidated group's snapshot (which {@code delete} through it updates, as one
 * swap), and every shipped store is safe for concurrent use, so a handle may be used from several threads
 * at once. Reads run in parallel with each other and with writes. Writes to different
 * chunks run in parallel; writes that touch the same chunk, or the same shard, through the same
 * {@code Store} object take turns, so neither is lost. Writes to one chunk through different {@code Store}
 * objects or processes are not coordinated: each stores its own update of the chunk, and the last one
 * wins. A handle keeps the metadata (shape, codecs, fill value) it read when it was opened. Changes to
 * metadata (attributes, {@code resize}, {@code delete}, {@code consolidate}) are several store calls, not
 * one atomic step: make them while nothing else writes to that part of the hierarchy.
 */
package com.ebremer.falcon.zarr;
