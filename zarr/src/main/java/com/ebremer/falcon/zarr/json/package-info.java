/**
 * A small, dependency-free JSON reader and writer for Zarr metadata.
 *
 * <p>{@code java.base} has no JSON support, so Falcon parses and serializes {@code zarr.json}
 * documents with this hand-written implementation. {@link com.ebremer.falcon.zarr.json.Json} is the
 * entry point; JSON values are modeled by the sealed
 * {@link com.ebremer.falcon.zarr.json.JsonValue} hierarchy. The parser is strict (RFC&nbsp;8259, with
 * UTF-8 checked and repeated keys refused), except that it reads the bare {@code NaN},
 * {@code Infinity}, and {@code -Infinity} that Python writes; the writer is canonical: object member
 * order is preserved and numbers keep their exact literal, so parse-then-write is byte-stable.
 *
 * <p>The package is exported because attributes and fill values are JSON.
 */
package com.ebremer.falcon.zarr.json;
