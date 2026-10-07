package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import com.ebremer.falcon.zarr.store.ZipStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code copy} and {@code consolidate}. */
class CopyTest {

    @TempDir
    Path dir;

    private Path source;

    /** A Zarr v3 store with a sharded array, a rectilinear one, strings, dimension names, and attributes. */
    @BeforeEach
    void write() {
        source = dir.resolve("source.zarr");
        ZarrGroup root = Zarr.createGroup(FileSystemStore.open(source));
        root.updateAttributes(com.ebremer.falcon.zarr.json.JsonObject.builder().put("title", "copy me").build());
        ZarrArray sharded = root.createGroup("g").createArray("sharded", ArraySpec.builder(new long[] {40, 40},
                DataType.INT32).chunkShape(20, 20).sharding(10, 10).dimensionNames("y", "x").fillValue(-1).build());
        int[] values = new int[1600];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        sharded.writeInts(values);
        root.createArray("ragged", ArraySpec.builder(new long[] {10}, DataType.FLOAT64).chunkLengths(0, 3, 7).build())
                .writeDoubles(new double[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        root.createArray("text", ArraySpec.builder(new long[] {3}, DataType.STRING).build())
                .writeStrings(new String[] {"a", "", "γ"});
    }

    private static List<String> keys(Store store) {
        return store.list().stream().sorted().toList();
    }

    @Test
    void aCopyAsItIsHasEveryKeyByteForByte() throws IOException {
        Path copy = dir.resolve("copy.zarr");
        String out = Cli.ok("copy", source.toString(), copy.toString());
        assertTrue(out.startsWith("Copied "), out);
        Store a = FileSystemStore.openReadOnly(source);
        Store b = FileSystemStore.openReadOnly(copy);
        assertEquals(keys(a), keys(b));
        for (String key : keys(a)) {
            assertArrayEquals(a.get(key).orElseThrow(), b.get(key).orElseThrow(), key);
        }
        Path zip = dir.resolve("copy.zip");
        Cli.ok("copy", source.toString(), zip.toString());
        try (ZipStore z = ZipStore.openReadOnly(zip)) {
            assertEquals(keys(a), keys(z));
        }
        Cli.Result again = Cli.run("copy", source.toString(), copy.toString());
        assertEquals(1, again.status());
        assertTrue(again.err().contains("already holds a Zarr store (pass --overwrite"), again.err());
        Files.writeString(copy.resolve("stray"), "x");
        Cli.ok("copy", "--overwrite", source.toString(), copy.toString());
        assertFalse(Files.exists(copy.resolve("stray")));

        Path one = dir.resolve("one.zarr");
        Cli.ok("copy", "--path", "g/sharded", source.toString(), one.toString());
        assertArrayEquals(Zarr.openArray(a, "g/sharded").readInts(), Zarr.openArray(FileSystemStore.openReadOnly(one)).readInts());
    }

    @Test
    void reencodingChangesTheFormatAndKeepsTheValues() {
        Path v2 = dir.resolve("v2.zarr");
        Cli.ok("copy", "-q", "--zarr-format", "2", "-c", "zlib:3", source.toString(), v2.toString());
        ZarrGroup two = Zarr.openGroup(FileSystemStore.openReadOnly(v2));
        assertEquals(2, two.zarrFormat());
        assertEquals("copy me", two.attributes().find("title").orElseThrow().toJson().replace("\"", ""));
        ZarrArray sharded = two.array("g/sharded");
        assertArrayEquals(new long[] {10, 10}, sharded.chunkShape()); // v2 has no shards: their sub-chunks
        assertEquals(List.of("bytes", "gzip"), sharded.codecNames());
        assertEquals("-1", sharded.fillValue().toJson());
        Store original = FileSystemStore.openReadOnly(source);
        assertArrayEquals(Zarr.openArray(original, "g/sharded").readInts(), sharded.readInts());
        assertArrayEquals(new String[] {"a", "", "γ"}, two.array("text").readStrings());
        assertArrayEquals(Zarr.openArray(original, "ragged").readDoubles(), two.array("ragged").readDoubles());

        Path v3 = dir.resolve("v3.zarr");
        Cli.ok("copy", "-q", "--zarr-format", "3", "--consolidate", v2.toString(), v3.toString());
        ZarrGroup three = Zarr.openGroup(FileSystemStore.openReadOnly(v3));
        assertTrue(three.isConsolidated());
        assertArrayEquals(sharded.readInts(), three.array("g/sharded").readInts());

        Path auto = dir.resolve("auto.zarr");
        Cli.ok("copy", "-q", "--chunks", "auto", source.toString(), auto.toString());
        ZarrArray kept = Zarr.openGroup(FileSystemStore.openReadOnly(auto)).array("g/sharded");
        assertArrayEquals(new long[] {40, 40}, kept.chunkShape());
        assertEquals(java.util.Optional.of(List.of("y", "x")), kept.dimensionNames());
        assertTrue(Zarr.openGroup(FileSystemStore.openReadOnly(dir.resolve("v3.zarr"))).array("ragged") != null);
        Path keep = dir.resolve("keep.zarr");
        Cli.ok("copy", "-q", "-c", "keep", source.toString(), keep.toString());
        ZarrGroup kg = Zarr.openGroup(FileSystemStore.openReadOnly(keep));
        assertTrue(kg.array("ragged").isRectilinear());
        assertArrayEquals(new long[] {10, 10}, kg.array("g/sharded").innerChunkShape());
    }

    @Test
    void consolidateWritesTheSnapshot() {
        assertFalse(Zarr.openGroup(FileSystemStore.openReadOnly(source)).isConsolidated());
        String out = Cli.ok("consolidate", source.toString());
        assertTrue(out.startsWith("Consolidated 3 members of /"), out);
        assertTrue(Zarr.openGroup(FileSystemStore.openReadOnly(source)).isConsolidated());
        assertEquals(2, Cli.run("consolidate", "https://example.com/x.zarr").status());
        Cli.Result notZarr = Cli.run("copy", dir.resolve("missing").toString(), dir.resolve("x.zarr").toString());
        assertEquals(1, notZarr.status());
    }
}
