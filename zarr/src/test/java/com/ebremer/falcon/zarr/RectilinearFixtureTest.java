package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * zarr-python 3.4's rectilinear arrays (P2 F14; {@code tools/fixtures/gen_zarr_rectilinear_fixtures.py}, written
 * with {@code array.rectilinear_chunks}): every element reads as zarr-python reads it, and
 * {@link ZarrArray#chunkSizes()} is zarr-python's {@code write_chunk_sizes}. Each fixture has a
 * {@code .expected.json} sidecar in the format of {@link DataFixturesTest}'s.
 */
class RectilinearFixtureTest {

    private static Path fixture(String name) {
        try {
            return Path.of(RectilinearFixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name
                    + " (regenerate with tools/fixtures/gen_zarr_rectilinear_fixtures.py)", e);
        }
    }

    private static JsonObject expected(String name) {
        try {
            return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long[][] chunkSizes(JsonObject meta) {
        return meta.get("chunk_sizes").asArray().values().stream()
                .map(d -> d.asArray().values().stream().mapToLong(v -> v.asNumber().longValue()).toArray())
                .toArray(long[][]::new);
    }

    /** Checks every element, the chunk sizes, and each chunk read on its own as a block. */
    private static void check(ZarrArray a, JsonObject meta) {
        List<JsonValue> values = meta.get("values").asArray().values();
        String dtype = meta.get("dtype").asString();
        String name = meta.get("name").asString();
        assertTrue(a.isRectilinear(), name);
        assertArrayEquals(chunkSizes(meta), a.chunkSizes(), name);
        switch (dtype) {
            case "string" -> assertArrayEquals(values.stream().map(JsonValue::asString).toArray(String[]::new),
                    a.readStrings(), name);
            case "bytes" -> assertArrayEquals(values.stream().map(v -> HexFormat.of().parseHex(v.asString()))
                    .toArray(byte[][]::new), a.readByteArrays(), name);
            case "float32", "float64" -> assertArrayEquals(
                    values.stream().mapToDouble(v -> v.asNumber().doubleValue()).toArray(), a.readDoubles(), name);
            default -> {
                long[] want = values.stream().mapToLong(v -> v.asNumber().longValue()).toArray();
                assertArrayEquals(want, a.readLongs(), name);
                // Each chunk on its own, as blocks() cuts the array.
                long[] shape = a.shape();
                a.blocks().forEach(block -> {
                    long[] got = block.readLongs();
                    long[] part = new long[got.length];
                    RectilinearGridTest.forEachInBox(shape, block.offset(), block.shape(),
                            (k, flat) -> part[k] = want[flat]);
                    assertArrayEquals(part, got, name + " block at " + Arrays.toString(block.offset()));
                });
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rect_2d", "rect_3d", "rect_rle", "rect_past", "rect_resized", "rect_sharded",
        "rect_sharded_partial", "rect_string", "rect_bytes", "rect_transposed", "rect_v2_keys", "rect_example"})
    void readsAsZarrPythonReads(String name) {
        check(Zarr.open(fixture(name)).asArray(), expected(name));
    }

    /** zarr-python's own resize: rows 4 and 5 hold the values a shrink cut off, as zarr-python reads them. */
    @Test
    void zarrPythonsResizeIsReadAsStored() {
        ZarrArray a = Zarr.open(fixture("rect_resized")).asArray();
        assertArrayEquals(new long[] {0, 7, 14, 21, 28, 35, -1, -1, -1},
                a.select(new long[] {0, 0}, new long[] {9, 1}).readLongs());
        assertArrayEquals(new long[] {2, 5}, a.chunkSizes()[1]);
    }

    /** Sharded on a rectilinear grid: the sub-chunks are regular, the shards are not. */
    @Test
    void shardsOfTwoShapes() {
        ZarrArray a = Zarr.open(fixture("rect_sharded")).asArray();
        assertArrayEquals(new long[] {2, 4}, a.innerChunkShape());
        assertEquals(12, a.blocks(a.innerChunkShape()).count());
        assertArrayEquals(new double[] {(9 * 8 + 5) * 0.5}, a.select(new long[] {9, 5}, new long[] {1, 1}).readDoubles());
    }

    @Test
    void aConsolidatedGroupOfRectilinearArrays() {
        ZarrGroup g = Zarr.openGroup(FileSystemStore.open(fixture("rect_group")));
        assertTrue(g.isConsolidated());
        for (String name : new String[] {"rect_group_a", "rect_group_sub_b"}) {
            JsonObject meta = expected(name);
            check(g.array(meta.get("path").asString()), meta);
        }
        assertFalse(Zarr.openGroup(FileSystemStore.open(fixture("rect_group")), false).isConsolidated());
    }
}
