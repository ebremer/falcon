package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Filter;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.ZipStore;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code convert}: HDF5 to Zarr and back, its options, and what it leaves out. */
class ConvertTest {

    @TempDir
    Path dir;

    private String h5;

    @BeforeEach
    void write() throws IOException {
        h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
    }

    private String path(String name) {
        return dir.resolve(name).toString();
    }

    private static ZarrGroup zarr(String location) {
        return Zarr.openGroup(FileSystemStore.openReadOnly(Path.of(location)));
    }

    @Test
    void hdf5BecomesZarrTypeByType() {
        String zarr = path("out.zarr");
        Cli.Result result = Cli.run("convert", h5, zarr).ok();
        assertTrue(result.out().contains("/run/temperature  float32 (4, 6)  chunks (2, 6)  gzip:4"), result.out());
        assertTrue(result.out().endsWith("Wrote 15 arrays and 2 groups to " + zarr + " (4 warnings above)\n"),
                result.out());
        for (String leftOut : List.of("left out /ext: an external link (to other.h5:/x)",
                "left out /ragged: Zarr has no type for vlen int32", "left out /ref: Zarr has no type for object reference",
                "left out /soft: a soft link (to /run/temperature)")) {
            assertTrue(result.err().contains(leftOut), () -> leftOut + " in\n" + result.err());
        }

        ZarrGroup root = zarr(zarr);
        assertEquals(3, root.zarrFormat());
        assertEquals(Json.parse("{\"title\":\"sample\",\"version\":3,\"ranges\":[[0,1],[2,3]],\"scale\":0.1}"),
                root.attributes());
        assertEquals(DataType.UINT64, root.array("big").dataType());
        assertEquals(DataType.BYTES, root.array("blobs").dataType());
        assertEquals(DataType.INT32, root.array("colors").dataType()); // the enumeration's integers
        assertEquals(DataType.COMPLEX64, root.array("cplx").dataType());
        assertEquals(DataType.BOOL, root.array("flags").dataType());
        assertEquals(DataType.STRING, root.array("names").dataType());
        assertEquals(DataType.datetime64("D", 1), root.array("when").dataType());
        assertArrayEquals(new long[] {3, 2}, root.array("pairs").shape());
        assertArrayEquals(new long[] {}, root.array("scalar").shape());
        assertArrayEquals(new long[] {0, 3}, root.array("empty").shape());
        DataType table = root.array("table").dataType();
        assertEquals(DataType.struct(new DataType.Field("id", DataType.INT32), new DataType.Field("v", DataType.FLOAT64),
                new DataType.Field("tag", DataType.nullTerminatedBytes(2))), table);

        ZarrArray temperature = root.array("run/temperature");
        assertEquals(Json.parse("{\"units\":\"K\"}"), temperature.attributes());
        assertEquals("\"NaN\"", temperature.fillValue().toJson());
        assertArrayEquals(new long[] {2, 6}, temperature.chunkShape());
        assertEquals(List.of("bytes", "gzip"), temperature.codecNames());
        assertEquals(List.of("bytes", "zstd"), root.array("cube").codecNames());
        ZarrArray counts = root.array("run/counts");
        assertEquals(ByteOrder.BIG_ENDIAN, ZarrMeta.of(FileSystemStore.openReadOnly(Path.of(zarr)), counts).byteOrder());
        assertArrayEquals(new long[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, counts.readLongs());
        assertArrayEquals(new long[] {0, -1, Long.MIN_VALUE}, root.array("big").readUnsignedLongs());
        assertArrayEquals(new long[] {1, 2, 3, 4, 5, 6}, root.array("pairs").readLongs());
        assertArrayEquals(new String[] {"alpha", "b", ""}, root.array("names").readStrings());
        assertArrayEquals(new long[] {18262, 10956}, root.array("when").readLongs());
        assertArrayEquals(new double[] {1, 2, -3.5, -0.25}, root.array("cplx").readComplex());
    }

    @Test
    void zarrBecomesHdf5AndTheRoundTripKeepsTheValues() throws IOException {
        String zarr = path("out.zarr");
        String back = path("back.h5");
        Cli.run("convert", "-q", h5, zarr).ok();
        Cli.Result result = Cli.run("convert", zarr, back).ok();
        assertTrue(result.err().isEmpty(), result.err());
        Set<String> changed = Set.of("/colors", "/pairs"); // enumeration names and array element types do not return
        for (String p : Samples.CONVERTED) {
            if (!changed.contains(p)) {
                assertEquals(Cli.ok("dump", "-f", "json", h5, p), Cli.ok("dump", "-f", "json", back, p), p);
            }
        }
        for (String a : List.of("title", "version", "ranges", "scale")) {
            assertEquals(Cli.ok("dump", h5, "-a", a), Cli.ok("dump", back, "-a", a), a);
        }
        try (Hdf5File file = Hdf5File.open(Path.of(back))) {
            Dataset counts = file.root().dataset("run/counts");
            assertEquals(Datatype.uint16().withByteOrder(ByteOrder.BIG_ENDIAN), counts.datatype());
            assertEquals(List.of(Filter.DEFLATE), counts.filters().stream().map(Filter::id).toList());
            Dataset when = file.root().dataset("when");
            assertEquals(Datatype.opaque(8, "NUMPY:<M8[D]"), when.datatype());
            Dataset cplx = file.root().dataset("cplx");
            assertTrue(cplx.datatype() instanceof Datatype.Compound c && Hdf5Values.complexParts(c) != null);
            Dataset temperature = file.root().dataset("run/temperature");
            assertTrue(Double.isNaN(java.nio.ByteBuffer.wrap(temperature.fillValueBytes().orElseThrow())
                    .order(ByteOrder.LITTLE_ENDIAN).getFloat()));
            assertEquals("K", temperature.attribute("units").orElseThrow().readString());
            assertArrayEquals(new long[] {3, 2}, file.root().dataset("pairs").dataspace().dimensions());
            assertTrue(file.root().dataset("scalar").chunkShape().isEmpty());
        }
    }

    @Test
    void zarrV2AndZipArchives() throws IOException {
        String v2 = path("v2.zarr");
        Cli.run("convert", "-q", "--zarr-format", "2", h5, v2).ok();
        ZarrGroup root = zarr(v2);
        assertEquals(2, root.zarrFormat());
        assertEquals(2, root.array("run/temperature").zarrFormat());
        assertTrue(Files.exists(Path.of(v2, "run", "temperature", ".zarray")));
        assertEquals(Cli.ok("dump", "-f", "json", h5, "table"), Cli.ok("dump", "-f", "json", v2, "table"));

        String zip = path("out.zip");
        Cli.run("convert", "-q", "--consolidate", h5, zip).ok();
        try (ZipStore store = ZipStore.openReadOnly(Path.of(zip))) {
            ZarrGroup zipped = Zarr.openGroup(store);
            assertTrue(zipped.isConsolidated());
            assertArrayEquals(new long[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, zipped.array("run/counts").readLongs());
        }
        assertEquals(Cli.ok("dump", h5, "cube"), Cli.ok("dump", zip, "cube"));
    }

    @Test
    void pathConvertsOneObject() throws IOException {
        String one = path("temperature.zarr");
        Cli.run("convert", "-q", "--path", "/run/temperature", h5, one).ok();
        ZarrArray array = Zarr.openArray(FileSystemStore.openReadOnly(Path.of(one)));
        assertArrayEquals(new long[] {4, 6}, array.shape());
        String back = path("t.h5");
        Cli.run("convert", "-q", one, back).ok();
        try (Hdf5File file = Hdf5File.open(Path.of(back))) {
            assertEquals(List.of("temperature"), file.root().childNames());
        }
        assertEquals("temperature", ConvertCommand.rootName("a/temperature.zarr", "/"));
        assertEquals("image", ConvertCommand.rootName("s3://b/image.zarr.zip", ""));
        assertEquals("x", ConvertCommand.rootName("store", "/g/x/"));
        assertEquals("data", ConvertCommand.rootName("/", "/"));
    }

    @Test
    void anOutputIsReplacedOnlyWhenAsked() {
        String zarr = path("out.zarr");
        String back = path("back.h5");
        Cli.run("convert", "-q", h5, zarr).ok();
        Cli.Result again = Cli.run("convert", "-q", h5, zarr);
        assertEquals(1, again.status());
        assertTrue(again.err().contains("--overwrite"), again.err());
        Cli.run("convert", "-q", "--overwrite", h5, zarr).ok();
        Cli.run("convert", "-q", zarr, back).ok();
        Cli.Result exists = Cli.run("convert", "-q", zarr, back);
        assertEquals(1, exists.status());
        assertTrue(exists.err().contains("pass --overwrite to replace it"), exists.err());
        Cli.run("convert", "-q", "--overwrite", zarr, back).ok();
        assertEquals(2, Cli.run("convert", "-q", zarr, "s3://bucket/x.h5").status());
        assertEquals(2, Cli.run("convert", "-q", h5, "https://example.com/x.zarr").status());
    }

    @Test
    void compressionAndChunksFollowTheOptions() throws IOException {
        String none = path("none.zarr");
        Cli.run("convert", "-q", "-c", "none", h5, none).ok();
        assertEquals(List.of("bytes"), zarr(none).array("cube").codecNames());
        String blosc = path("blosc.zarr");
        Cli.run("convert", "-q", "-c", "blosc:lz4:3:bitshuffle", "--chunks", "auto", h5, blosc).ok();
        String meta = Files.readString(Path.of(blosc, "cube", "zarr.json"));
        assertTrue(meta.contains("\"cname\":\"lz4\",\"clevel\":3,\"shuffle\":\"bitshuffle\""), meta);
        assertArrayEquals(Chunking.ZARR.guess(new long[] {2, 3, 4}, 8), zarr(blosc).array("cube").chunkShape());
        assertEquals(Cli.ok("dump", h5, "cube"), Cli.ok("dump", blosc, "cube"));

        String zstd = path("zstd.zarr");
        Cli.run("convert", "-q", "-c", "zstd:7", h5, zstd).ok();
        String keep = path("keep.h5");
        String auto = path("auto.h5");
        String lzf = path("lzf.h5");
        Cli.run("convert", "-q", "-c", "keep", zstd, keep).ok();
        Cli.run("convert", "-q", zstd, auto).ok();
        Cli.run("convert", "-q", "-c", "lzf", zstd, lzf).ok();
        try (Hdf5File k = Hdf5File.open(Path.of(keep)); Hdf5File a = Hdf5File.open(Path.of(auto));
             Hdf5File l = Hdf5File.open(Path.of(lzf))) {
            assertEquals(new Filter(Filter.ZSTD, "Zstandard", false, new int[] {7}).id(),
                    k.root().dataset("cube").filters().get(0).id());
            assertArrayEquals(new int[] {7}, k.root().dataset("cube").filters().get(0).clientData());
            assertEquals(Filter.DEFLATE, a.root().dataset("cube").filters().get(0).id());
            assertEquals(Filter.LZF, l.root().dataset("cube").filters().get(0).id());
            assertEquals(Cli.ok("dump", h5, "cube"), Cli.ok("dump", lzf, "cube"));
        }
        Cli.Result noLzf = Cli.run("convert", "-c", "lzf", h5, path("lzf.zarr"));
        assertEquals(2, noLzf.status());
        assertTrue(noLzf.err().contains("Zarr has no LZF"), noLzf.err());
        assertEquals(2, Cli.run("convert", "-c", "gzip:12", h5, path("x.zarr")).status());
        assertEquals(2, Cli.run("convert", "--chunks", "big", h5, path("x.zarr")).status());
        assertEquals(2, Cli.run("convert", "--zarr-format", "4", h5, path("x.zarr")).status());
    }

    @Test
    void aSparseZarrArrayStaysSparseInHdf5AndShardsBecomeChunks() throws IOException {
        Path store = dir.resolve("sparse.zarr");
        ZarrGroup group = Zarr.createGroup(FileSystemStore.open(store));
        ZarrArray sparse = group.createArray("sparse", ArraySpec.builder(new long[] {1000, 1000}, DataType.FLOAT32)
                .chunkShape(100, 100).fillValue(Double.NaN).build());
        sparse.select(new long[] {450, 450}, new long[] {10, 10}).writeFloats(new float[100]);
        ZarrArray sharded = group.createArray("sharded", ArraySpec.builder(new long[] {64, 64}, DataType.UINT16)
                .chunkShape(32, 32).sharding(8, 8).build());
        int[] values = new int[64 * 64];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        sharded.writeInts(values);
        String h5out = path("sparse.h5");
        Cli.run("convert", "-q", store.toString(), h5out).ok();
        try (Hdf5File file = Hdf5File.open(Path.of(h5out))) {
            Dataset d = file.root().dataset("sparse");
            assertArrayEquals(new long[] {100, 100}, d.chunkShape().orElseThrow());
            assertTrue(d.storageSize() < 10_000, () -> "stored " + d.storageSize());
            float[] read = d.select(new long[] {449, 449}, new long[] {2, 2}).readFloats();
            assertTrue(Float.isNaN(read[0]) && read[3] == 0f, java.util.Arrays.toString(read));
            Dataset s = file.root().dataset("sharded");
            assertArrayEquals(new long[] {8, 8}, s.chunkShape().orElseThrow());
            assertArrayEquals(values, s.readInts());
        }
    }

    @Test
    void zarrOnlyTypesBecomeWhatH5pyWrites() throws IOException {
        Path store = dir.resolve("types.zarr");
        ZarrGroup group = Zarr.createGroup(FileSystemStore.open(store),
                (JsonObject) Json.parse("{\"nested\":{\"a\":[1,\"x\"]},\"flags\":[true,false],\"big\":18446744073709551615,"
                        + "\"none\":null,\"grid\":[[1.5,2],[3,4]]}"), false, 3);
        group.createArray("u", ArraySpec.builder(new long[] {2}, DataType.fixedLengthUtf32(4)).build())
                .writeStrings(new String[] {"hé", "abcd"});
        group.createArray("s", ArraySpec.builder(new long[] {2}, DataType.nullTerminatedBytes(3)).build())
                .writeByteArrays(new byte[][] {"ab".getBytes(), "xyz".getBytes()});
        group.createArray("v", ArraySpec.builder(new long[] {1}, DataType.rawBytes(2)).build())
                .writeByteArrays(new byte[][] {{1, 2}});
        group.createArray("td", ArraySpec.builder(new long[] {2}, DataType.timedelta64("s", 10))
                .endian(ByteOrder.BIG_ENDIAN).build()).writeLongs(new long[] {3, Long.MIN_VALUE});
        DataType point = DataType.struct(new DataType.Field("x", DataType.INT16), new DataType.Field("ok", DataType.BOOL),
                new DataType.Field("c", DataType.COMPLEX128));
        byte[] element = java.nio.ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).putShort((short) -7).put((byte) 1)
                .putDouble(1.5).putDouble(-2).array();
        group.createArray("p", ArraySpec.builder(new long[] {1}, point).build()).writeByteArrays(new byte[][] {element});
        group.createArray("half", ArraySpec.builder(new long[] {2}, DataType.FLOAT16).build())
                .writeFloats(new float[] {0.5f, -2f});
        String out = path("types.h5");
        Cli.run("convert", "-q", store.toString(), out).ok();
        try (Hdf5File file = Hdf5File.open(Path.of(out))) {
            assertEquals(Datatype.variableString(), file.root().dataset("u").datatype());
            assertArrayEquals(new String[] {"hé", "abcd"}, file.root().dataset("u").readStrings());
            assertEquals(new Datatype.StringType(3, Datatype.StringPadding.NULL_PAD, Datatype.CharacterSet.ASCII),
                    file.root().dataset("s").datatype());
            assertArrayEquals(new String[] {"ab", "xyz"}, file.root().dataset("s").readStrings());
            assertEquals(Datatype.opaque(2, ""), file.root().dataset("v").datatype());
            assertEquals(Datatype.opaque(8, "NUMPY:>m8[10s]"), file.root().dataset("td").datatype());
            assertEquals(Datatype.float16(), file.root().dataset("half").datatype());
            assertTrue(file.root().dataset("p").datatype() instanceof Datatype.Compound c && c.size() == 19);
            assertEquals(Json.parse("[{\"x\":-7,\"ok\":true,\"c\":[1.5,-2.0]}]"),
                    Json.parse(Cli.ok("dump", "-f", "json", out, "p")));
            assertEquals("[[1.5, 2.0],\n [3.0, 4.0]]\n", Cli.ok("dump", "-f", "json", out, "-a", "grid"));
            assertEquals("[true, false]\n", Cli.ok("dump", "-f", "json", out, "-a", "flags"));
            assertEquals("18446744073709551615\n", Cli.ok("dump", out, "-a", "big"));
            assertEquals("\"{\\\"a\\\":[1,\\\"x\\\"]}\"\n", Cli.ok("dump", out, "-a", "nested"));
            assertEquals("\"null\"\n", Cli.ok("dump", out, "-a", "none"));
        }
        assertEquals("(0): 30 s, NaT\n", Cli.ok("dump", out, "td"));
        assertEquals(Cli.ok("dump", store.toString(), "td"), Cli.ok("dump", out, "td"));
        assertFalse(Cli.ok("ls", out).isEmpty());
    }
}
