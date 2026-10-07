package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.hdf5.Filter;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The command's parts on their own: compression, chunk guesses, blocks, values, and attributes. */
class PartsTest {

    @Test
    void compressionParsesAndMapsBothWays() {
        assertEquals(new Compression(Compression.Kind.GZIP, 4, null, null), Compression.parse("gzip"));
        assertEquals(new Compression(Compression.Kind.GZIP, 9, null, null), Compression.parse("Deflate:9"));
        assertEquals(new Compression(Compression.Kind.ZSTD, 0, null, null), Compression.parse("zstd"));
        assertEquals(new Compression(Compression.Kind.BLOSC, 5, "lz4", "shuffle"), Compression.parse("blosc"));
        assertEquals(new Compression(Compression.Kind.BLOSC, 2, "zstd", "noshuffle"), Compression.parse("blosc:zstd:2:noshuffle"));
        assertEquals("bz2:9", Compression.parse("bzip2").describe());
        for (String bad : List.of("gzip:10", "zstd:x", "blosc:snappy", "blosc:lz4:5:shuffle:1", "lz4:1", "brotli", "none:1")) {
            assertThrows(UsageException.class, () -> Compression.parse(bad), bad);
        }
        assertInstanceOf(Compression.Auto.class, Compression.Policy.parse("AUTO"));
        assertInstanceOf(Compression.Keep.class, Compression.Policy.parse("keep"));

        assertEquals(new Compression(Compression.Kind.GZIP, 6, null, null), Compression.fromHdf5(List.of(
                new Filter(Filter.SHUFFLE, "shuffle", false, new int[] {4}), new Filter(Filter.DEFLATE, "deflate", false, new int[] {6}))));
        assertEquals(new Compression(Compression.Kind.BLOSC, 7, "zstd", "bitshuffle"), Compression.fromHdf5(List.of(
                new Filter(Filter.BLOSC, "blosc", false, new int[] {2, 2, 4, 1024, 7, 2, 5}))));
        assertEquals(Compression.NONE, Compression.fromHdf5(List.of(new Filter(Filter.FLETCHER32, "fletcher32", false, new int[0]))));
        assertNull(Compression.fromHdf5(List.of(new Filter(Filter.SZIP, "szip", false, new int[] {4, 32}))));

        Compression lz4 = new Compression(Compression.Kind.LZ4, 0, null, null);
        Compression gzip = new Compression(Compression.Kind.GZIP, 2, null, null);
        assertEquals(Compression.ZARR_DEFAULT, Compression.choose(new Compression.Auto(), Compression.NONE, true));
        assertEquals(lz4, Compression.choose(new Compression.Auto(), lz4, true));
        assertEquals(Compression.HDF5_DEFAULT, Compression.choose(new Compression.Auto(), lz4, false));
        assertEquals(gzip, Compression.choose(new Compression.Auto(), gzip, false));
        assertEquals(lz4, Compression.choose(new Compression.Keep(), lz4, false));
        assertEquals(Compression.NONE, Compression.choose(new Compression.Keep(), Compression.NONE, false));
        assertEquals(Compression.HDF5_DEFAULT, Compression.choose(new Compression.Keep(), null, false));
    }

    @Test
    void chunkGuessesAreH5pysAndZarrPythons() {
        // h5py 3.16's guess_chunk and zarr-python 3.4's _guess_regular_chunks for these shapes and element sizes
        long[][] shapes = {{100, 100}, {8192, 8192}, {1000000}, {10, 20, 30}, {5}, {3000, 4000, 3}, {1}};
        int[] sizes = {8, 2, 4, 4, 1, 1, 16};
        long[][] h5py = {{25, 50}, {128, 256}, {7813}, {5, 20, 30}, {5}, {188, 250, 1}, {1}};
        long[][] zarr = {{100, 100}, {512, 1024}, {125000}, {10, 20, 30}, {5}, {750, 1000, 1}, {1}};
        for (int i = 0; i < shapes.length; i++) {
            assertArrayEquals(h5py[i], Chunking.HDF5.guess(shapes[i], sizes[i]), "h5py " + i);
            assertArrayEquals(zarr[i], Chunking.ZARR.guess(shapes[i], sizes[i]), "zarr " + i);
        }
        assertArrayEquals(new long[] {1, 3}, Chunking.ZARR.guess(new long[] {0, 3}, 8));
        assertArrayEquals(new long[] {1, 5, 7}, Chunking.clamp(new long[] {0, 9, 7}, new long[] {0, 5, 10}));
    }

    @Test
    void blocksAreWholeChunksAndCoverTheArray() throws Exception {
        assertArrayEquals(new long[] {20, 100}, Blocks.blockShape(new long[] {100, 100}, new long[] {10, 10}, 2000));
        assertArrayEquals(new long[] {10, 30}, Blocks.blockShape(new long[] {100, 100}, new long[] {10, 10}, 300));
        assertArrayEquals(new long[] {10, 10}, Blocks.blockShape(new long[] {100, 100}, new long[] {10, 10}, 5));
        for (int threads : new int[] {1, 3}) {
            for (boolean ordered : new boolean[] {false, true}) {
                long[] shape = {37, 23};
                int[] seen = new int[37 * 23];
                List<long[]> order = java.util.Collections.synchronizedList(new ArrayList<>());
                Blocks.copy(shape, new long[] {5, 4}, 1 << 20, threads, ordered,
                        (offset, count) -> new long[][] {offset, count},
                        (offset, count, data) -> {
                            order.add(offset);
                            for (long r = offset[0]; r < offset[0] + count[0]; r++) {
                                for (long c = offset[1]; c < offset[1] + count[1]; c++) {
                                    seen[(int) (r * 23 + c)]++;
                                }
                            }
                        });
                for (int s : seen) {
                    assertEquals(1, s);
                }
                if (ordered || threads == 1) {
                    for (int i = 1; i < order.size(); i++) {
                        long[] a = order.get(i - 1);
                        long[] b = order.get(i);
                        assertEquals(-1, Long.signum(a[0] == b[0] ? a[1] - b[1] : a[0] - b[0]));
                    }
                }
            }
        }
        List<String> calls = new ArrayList<>();
        Blocks.copy(new long[0], new long[0], 8, 1, false, (o, c) -> "x", (o, c, d) -> calls.add((String) d));
        Blocks.copy(new long[] {0, 5}, new long[] {1, 5}, 8, 1, false, (o, c) -> "y", (o, c, d) -> calls.add((String) d));
        assertEquals(List.of("x"), calls);
    }

    @Test
    void valuesPrintAsNumpyAndJsonDo() {
        assertEquals("0.1", Values.floats(new float[] {0.1f}).text(0));
        assertEquals("NaN", Values.doubles(new double[] {Double.NaN}).text(0));
        assertEquals("-Infinity", Values.floats(new float[] {Float.NEGATIVE_INFINITY}).json(0).toJson());
        assertEquals("18446744073709551615", Values.unsignedLongs(new long[] {-1}).text(0));
        assertEquals("1.5-2.0j", Values.complex(new double[] {1.5, -2}, false).text(0));
        assertEquals("[1.5,-2.0]", Values.complex(new double[] {1.5, -2}, false).json(0).toJson());
        assertEquals("b\"a\\x00\\\"\"", Values.bytes(new byte[][] {{'a', 0, '"'}}, true).text(0));
        assertEquals("\"ab\"", Values.bytes(new byte[][] {{'a', 'b'}}, true).json(0).toJson());
        assertEquals("\"/w==\"", Values.bytes(new byte[][] {{-1}}, true).json(0).toJson());
        assertEquals("0x00ff", Values.bytes(new byte[][] {{0, -1}}, false).text(0));
        assertEquals("2020-01-01T00:00:00.500", Values.time(1577836800500L, "ms", 1, false));
        assertEquals("1970-01", Values.time(0, "M", 1, false));
        assertEquals("NaT", Values.time(Long.MIN_VALUE, "s", 1, false));
        assertEquals("30 s", Values.time(3, "s", 10, true));
        assertEquals("[[1,2],[3,4]]", Values.nested(Values.longs(new long[] {1, 2, 3, 4}), new long[] {2, 2}).toJson());
        assertEquals("7", Values.nested(Values.longs(new long[] {7}), new long[0]).toJson());
    }

    @Test
    void zarrAttributesBecomeHdf5AttributesOfTheirShape() {
        record Written(String name, Datatype type, long[] shape, Object values) {
        }
        List<Written> written = new ArrayList<>();
        Context context = new Context(System.out, System.err, new CommonOptions());
        AttributeJson.toHdf5(context, "/", (JsonObject) Json.parse("""
                {"s": "x", "i": -3, "f": 2.5, "nan": NaN, "b": true, "grid": [[1, 2], [3, 4]], "mixed": [1, 2.5],
                 "big": 18446744073709551615, "huge": 1e400, "strs": ["a", "b"], "obj": {"k": 1}, "null": null,
                 "ragged": [[1], [2, 3]], "empty": [], "kinds": [1, "a"], "": 1}"""),
                (name, type, shape, values) -> written.add(new Written(name, type, shape, values)));
        java.util.Map<String, Written> by = new java.util.HashMap<>();
        written.forEach(w -> by.put(w.name(), w));
        assertEquals(Datatype.variableString(), by.get("s").type());
        assertArrayEquals(new long[0], by.get("s").shape());
        assertEquals(Datatype.int64(), by.get("i").type());
        assertArrayEquals(new long[] {-3}, (long[]) by.get("i").values());
        assertEquals(Datatype.float64(), by.get("f").type());
        assertEquals(Double.NaN, ((double[]) by.get("nan").values())[0]);
        assertEquals(Datatype.bool(), by.get("b").type());
        assertArrayEquals(new long[] {2, 2}, by.get("grid").shape());
        assertArrayEquals(new long[] {1, 2, 3, 4}, (long[]) by.get("grid").values());
        assertArrayEquals(new double[] {1, 2.5}, (double[]) by.get("mixed").values());
        assertEquals(Datatype.uint64(), by.get("big").type());
        assertArrayEquals(new BigInteger[] {new BigInteger("18446744073709551615")}, (BigInteger[]) by.get("big").values());
        assertEquals(Double.POSITIVE_INFINITY, ((double[]) by.get("huge").values())[0]);
        assertArrayEquals(new String[] {"a", "b"}, (String[]) by.get("strs").values());
        for (String json : List.of("obj", "null", "ragged", "empty", "kinds")) {
            assertEquals(Datatype.variableString(), by.get(json).type(), json);
        }
        assertArrayEquals(new String[] {"{\"k\":1}"}, (String[]) by.get("obj").values());
        assertArrayEquals(new String[] {"[[1],[2,3]]"}, (String[]) by.get("ragged").values());
        assertNull(by.get(""));
        assertEquals(15, written.size());
    }
}
