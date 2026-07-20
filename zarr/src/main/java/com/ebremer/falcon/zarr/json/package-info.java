/**
 * A small, dependency-free JSON reader and writer for Zarr metadata.
 *
 * <p>{@code java.base} has no JSON support, so Falcon parses and serializes {@code zarr.json}
 * documents with this hand-written implementation. {@link com.ebremer.falcon.zarr.json.Json} is the
 * entry point; JSON values are modeled by the sealed
 * {@link com.ebremer.falcon.zarr.json.JsonValue} hierarchy. The parser is strict (RFC&nbsp;8259) and
 * the writer is canonical: object member order is preserved and numbers keep their exact literal, so
 * parse-then-write is byte-stable.
 *
 * <p>This package is Zarr-internal and not exported.
 */
package com.ebremer.falcon.zarr.json;
