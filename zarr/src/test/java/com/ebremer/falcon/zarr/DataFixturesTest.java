package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stores written by zarr-python 3.4, numcodecs 0.17 (c-blosc), and zstandard 0.25 for the data-path cases
 * P1 added (T3); see {@code tools/fixtures/gen_zarr_data_fixtures.py}. Each has a {@code .expected.json}
 * sidecar in the format of {@link ConformanceTest}'s fixtures.
 */
class DataFixturesTest {

    private static Path fixture(String name) {
        try {
            return Path.of(DataFixturesTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name + " (regenerate with tools/fixtures/gen_zarr_data_fixtures.py)", e);
        }
    }

    private static JsonObject expected(String name) {
        try {
            return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ZarrArray open(String name) {
        return Zarr.open(fixture(name)).asArray();
    }

    private static String[] strings(JsonObject meta) {
        List<JsonValue> values = meta.get("values").asArray().values();
        return values.stream().map(JsonValue::asString).toArray(String[]::new);
    }

    private static long[] longs(JsonObject meta) {
        return meta.get("values").asArray().values().stream().mapToLong(v -> v.asNumber().longValue()).toArray();
    }

    /** zarr-python's sharded string array: vlen-utf8 inside the shard, no outer string codec (I2). */
    @Test
    void shardedStrings() {
        ZarrArray a = open("sharded_string");
        String[] want = strings(expected("sharded_string"));
        assertArrayEquals(want, a.readStrings());
        // A region inside one sub-chunk, and one across four.
        assertArrayEquals(new String[] {want[8 + 5], want[8 + 6]}, a.select(new long[] {1, 5}, new long[] {1, 2}).readStrings());
        assertArrayEquals(new String[] {want[8 + 3], want[8 + 4], want[16 + 3], want[16 + 4]},
                a.select(new long[] {1, 3}, new long[] {2, 2}).readStrings());
        assertArrayEquals(strings(expected("sharded_string_partial")), open("sharded_string_partial").readStrings());
    }

    /** transpose before vlen-utf8, zarr-python's order (I4). */
    @Test
    void transposedStrings() {
        assertArrayEquals(strings(expected("transposed_string")), open("transposed_string").readStrings());
    }

    @Test
    void shardIndexAtTheStart() {
        assertArrayEquals(longs(expected("sharded_index_start")), open("sharded_index_start").readLongs());
    }

    @Test
    void zstdFramesWithChecksumsAndSeveralFramesInAChunk() {
        assertArrayEquals(longs(expected("zstd_checksum")), open("zstd_checksum").readLongs());
        assertArrayEquals(longs(expected("zstd_multiframe")), open("zstd_multiframe").readLongs());
    }

    /** c-blosc writes a 300-byte type size as 1 (what Falcon's encoder now does too, Z5). */
    @Test
    void bloscWithATypeSizeAbove255() {
        byte[] want = HexFormat.of().parseHex(expected("blosc_r2400").get("hex").asString());
        assertArrayEquals(want, open("blosc_r2400").readRawBytes());
    }

    private static byte[][] hexes(JsonObject meta) {
        return meta.get("values").asArray().values().stream()
                .map(v -> HexFormat.of().parseHex(v.asString())).toArray(byte[][]::new);
    }

    /**
     * zarr-python's {@code variable_length_bytes} arrays (F5, {@code gen_zarr_bytes_fixtures.py}): plain with
     * zstd, sharded with a three-byte fill value and only part written, and transposed before
     * {@code vlen-bytes}.
     */
    @Test
    void variableLengthBytes() {
        byte[][] plain = hexes(expected("bytes_plain"));
        ZarrArray a = open("bytes_plain");
        assertEquals(com.ebremer.falcon.zarr.datatype.DataType.BYTES, a.dataType());
        assertArrayEquals(plain, a.readByteArrays());
        assertArrayEquals(new byte[][] {plain[6], plain[7], plain[11], plain[12]},
                a.select(new long[] {1, 1}, new long[] {2, 2}).readByteArrays());

        JsonObject sharded = expected("bytes_sharded");
        byte[][] want = hexes(sharded);
        ZarrArray s = open("bytes_sharded");
        assertArrayEquals(want, s.readByteArrays());
        assertArrayEquals(new byte[][] {want[6 + 1], want[6 + 2], want[12 + 1], want[12 + 2]},
                s.select(new long[] {1, 1}, new long[] {2, 2}).readByteArrays());
        assertEquals(sharded.get("fill").asString(),
                HexFormat.of().formatHex(java.util.Base64.getDecoder().decode(s.fillValue().asString())));

        assertArrayEquals(hexes(expected("bytes_transposed")), open("bytes_transposed").readByteArrays());
    }

    /**
     * zarr-python's nested shards (F11, {@code gen_zarr_nested_fixtures.py}): two levels of int32, a partial
     * write with absent inner shards and inner sub-chunks (read as the fill value), zstd inside and a crc32c
     * after the inner shard, three levels, and strings. Each is read whole and in regions that cut through
     * every level.
     */
    @Test
    void nestedSharding() {
        for (String name : new String[] {"nested_2d", "nested_partial", "nested_three"}) {
            long[] want = longs(expected(name));
            ZarrArray a = open(name);
            assertArrayEquals(want, a.readLongs(), name);
            long[] shape = a.shape();
            int rank = shape.length;
            for (long start = 0; start < shape[0]; start += 3) {
                long[] offset = new long[rank];
                long[] extent = new long[rank];
                offset[0] = start;
                extent[0] = Math.min(2, shape[0] - start);
                if (rank == 2) {
                    offset[1] = start % shape[1];
                    extent[1] = Math.min(5, shape[1] - offset[1]);
                }
                long[] got = a.select(offset, extent).readLongs();
                int k = 0;
                for (long r = offset[0]; r < offset[0] + extent[0]; r++) {
                    if (rank == 1) {
                        assertEquals(want[(int) r], got[k++], name + " at " + r);
                        continue;
                    }
                    for (long c = offset[1]; c < offset[1] + extent[1]; c++) {
                        assertEquals(want[(int) (r * shape[1] + c)], got[k++], name + " at " + r + "," + c);
                    }
                }
            }
        }
        double[] doubles = expected("nested_compressed").get("values").asArray().values().stream()
                .mapToDouble(v -> v.asNumber().doubleValue()).toArray();
        assertArrayEquals(doubles, open("nested_compressed").readDoubles());
        assertArrayEquals(new double[] {doubles[5 * 8 + 3], doubles[5 * 8 + 4], doubles[6 * 8 + 3], doubles[6 * 8 + 4]},
                open("nested_compressed").select(new long[] {5, 3}, new long[] {2, 2}).readDoubles());

        String[] strings = strings(expected("nested_string"));
        ZarrArray s = open("nested_string");
        assertArrayEquals(strings, s.readStrings());
        assertArrayEquals(new String[] {strings[4 + 1], strings[4 + 2], strings[8 + 1], strings[8 + 2]},
                s.select(new long[] {1, 1}, new long[] {2, 2}).readStrings());
    }

    /** A 140 MB chunk: more than one libzstd block, and well past any small fixed buffer. */
    @Test
    void aChunkOver128Megabytes() {
        ZarrArray a = open("zstd_large_chunk");
        byte[] all = a.readRawBytes();
        assertEquals(140_000_000, all.length);
        for (int i = 0; i < all.length; i++) {
            if ((all[i] & 0xff) != i % 251) {
                throw new AssertionError("element " + i + " is " + (all[i] & 0xff) + ", expected " + i % 251);
            }
        }
    }
}
