package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.Hdf5Writer;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code dump}: every element type, the three formats, and selections. */
class DumpTest {

    @TempDir
    Path dir;

    private String h5;
    private String zarr;

    @BeforeEach
    void write() throws IOException {
        h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
        zarr = dir.resolve("sample.zarr").toString();
        Cli.run("convert", "-q", h5, zarr).ok();
    }

    /**
     * A sequence of records (h5py's, in the hdf5 module's typed.h5) once failed with "Argument is not an array":
     * a record column is a map of its members' columns, not an array (the HDF5 conformance harness found it).
     */
    @Test
    void dumpsASequenceOfRecords() {
        String typed = Path.of("..", "hdf5", "src", "test", "resources", "fixtures", "typed.h5").toString();
        assertEquals("[[{\"a\":1,\"b\":0.5}], [], [{\"a\":2,\"b\":1.5},{\"a\":3,\"b\":2.5}]]\n",
                Cli.ok("dump", "-f", "json", typed, "vlen_rec"));
    }

    /** What a sequence's row holds, counted by its type: records, arrays (n values each), complex numbers (2). */
    @Test
    void countsAColumnsElementsByTheirType() {
        assertEquals(4, Hdf5Values.count(Datatype.int32(), new int[4]));
        assertEquals(2, Hdf5Values.count(Datatype.arrayOf(Datatype.int32(), 2, 2), new int[8]));
        assertEquals(3, Hdf5Values.count(Datatype.complexOf(Datatype.float32()), new double[6]));
        assertEquals(2, Hdf5Values.count(Datatype.compound(Map.of("a", Datatype.int32())),
                Map.of("a", new int[2])));
        assertEquals(1, Hdf5Values.count(Datatype.arrayOf(Datatype.compound(Map.of("a", Datatype.int32())), 3),
                Map.of("a", new int[3])));
    }

    @Test
    void textLabelsEachRowWithItsFirstValuesCoordinates() {
        assertEquals("""
                (0, 0): 0.0, 0.1, 0.2, 0.3, 0.4, 0.5
                (1, 0): 0.6, 0.7, 0.8, 0.9, 1.0, 1.1
                (2, 0): 1.2, 1.3, 1.4, 1.5, 1.6, 1.7
                (3, 0): 1.8, 1.9, 2.0, 2.1, 2.2, 2.3
                """, Cli.ok("dump", h5, "run/temperature"));
        assertEquals("42.0\n", Cli.ok("dump", h5, "scalar"));
        assertEquals("", Cli.ok("dump", h5, "empty"));
    }

    @Test
    void everyTypePrintsItsValues() {
        for (String source : new String[] {h5, zarr}) {
            assertEquals("(0): 0, 18446744073709551615, 9223372036854775808\n", Cli.ok("dump", source, "big"));
            assertEquals("(0): true, false, true\n", Cli.ok("dump", source, "flags"));
            assertEquals("(0): \"x\", \"yy\", \"zzz\"\n", Cli.ok("dump", source, "words"));
            assertEquals("(0): 1.0+2.0j, -3.5-0.25j\n", Cli.ok("dump", source, "cplx"));
            assertEquals("(0): 2020-01-01, 1999-12-31\n", Cli.ok("dump", source, "when"));
            assertEquals("(0): 0, 1, 2, 3, 4, 5, 6, 7, 8, 9\n", Cli.ok("dump", source, "run/counts"));
        }
        assertEquals("(0): \"alpha\", \"b\", \"\"\n", Cli.ok("dump", h5, "names"));
        assertEquals("(0): \"BLUE\", \"RED\", \"GREEN\"\n", Cli.ok("dump", h5, "colors"));
        assertEquals("(0): 2, 0, 1\n", Cli.ok("dump", zarr, "colors"));
        assertEquals("(0): {\"id\":1,\"v\":2.5,\"tag\":\"ab\"}, {\"id\":2,\"v\":-1.0,\"tag\":\"c\"}\n",
                Cli.ok("dump", h5, "table"));
        assertEquals(Cli.ok("dump", h5, "table"), Cli.ok("dump", zarr, "table"));
        assertEquals("(0): [1,2], [3,4], [5,6]\n", Cli.ok("dump", h5, "pairs"));
        assertEquals("(0, 0): 1, 2\n(1, 0): 3, 4\n(2, 0): 5, 6\n", Cli.ok("dump", zarr, "pairs"));
        assertEquals("(0): [1,2,255], []\n", Cli.ok("dump", h5, "blobs"));
        assertEquals("(0): b\"\\x01\\x02\\xff\", b\"\"\n", Cli.ok("dump", zarr, "blobs"));
        assertEquals("(0): [1,2,3], []\n", Cli.ok("dump", h5, "ragged"));
        assertEquals("\"/run\"\n", Cli.ok("dump", h5, "ref").replace("(0): ", ""));
    }

    @Test
    void slicesSelectAsNumpyDoes() {
        String cube = Cli.ok("dump", h5, "cube", "--slice", "1,::2,-2:");
        assertEquals("(1, 0, 2): 14, 15\n(1, 2, 2): 22, 23\n", cube);
        assertEquals(cube, Cli.ok("dump", zarr, "cube", "-s", "1,::2,-2:"));
        assertEquals("7\n", Cli.ok("dump", h5, "cube", "-s", "0,1,3")); // every dimension indexed: a scalar
        assertEquals("(0, 0, 0): 0, 2\n", Cli.ok("dump", h5, "cube", "-s", "0,0,::2"));

        Cli.Result tooMany = Cli.run("dump", h5, "cube", "-s", "0,0,0,0");
        assertEquals(2, tooMany.status());
        assertTrue(tooMany.err().contains("--slice gives 4 dimensions, but the array has 3"), tooMany.err());
        assertEquals(2, Cli.run("dump", h5, "cube", "-s", "5").status());
        assertEquals(2, Cli.run("dump", h5, "cube", "-s", "::0").status());
        assertEquals(2, Cli.run("dump", h5, "run").status());
    }

    @Test
    void csvAndJsonTakeTheResultsShape() {
        assertEquals("0.0,0.1,0.2\n0.6,0.7,0.8\n", Cli.ok("dump", "-f", "csv", h5, "run/temperature", "-s", ":2,:3"));
        assertEquals("0,0,0,1\n0,1,4,5\n1,0,12,13\n1,1,16,17\n", Cli.ok("dump", "-f", "csv", h5, "cube", "-s", ":,:2,:2"));
        assertEquals("x\nyy\nzzz\n", Cli.ok("dump", "-f", "csv", h5, "words"));
        assertEquals("[[[0, 1],\n  [4, 5]],\n [[12, 13],\n  [16, 17]]]\n",
                Cli.ok("dump", "-f", "json", h5, "cube", "-s", ":,:2,:2"));
        assertEquals("[2.0, 2.1]\n", Cli.ok("dump", "-f", "json", h5, "run/temperature", "-s", "3,2:4"));
        assertEquals("42.0\n", Cli.ok("dump", "-f", "json", h5, "scalar"));
        assertEquals("[]\n", Cli.ok("dump", "-f", "json", h5, "empty"));
        assertEquals("[{\"id\":1,\"v\":2.5,\"tag\":\"ab\"}, {\"id\":2,\"v\":-1.0,\"tag\":\"c\"}]\n",
                Cli.ok("dump", "-f", "json", zarr, "table"));
    }

    @Test
    void csvQuotesWhatNeedsIt() {
        assertEquals("plain", DumpPrinter.Csv.quote("plain"));
        assertEquals("\"a,b\"", DumpPrinter.Csv.quote("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", DumpPrinter.Csv.quote("say \"hi\""));
        assertEquals("\" edge\"", DumpPrinter.Csv.quote(" edge"));
    }

    @Test
    void attributesOfEitherFormat() {
        assertEquals("[[0, 1],\n [2, 3]]\n", Cli.ok("dump", "-f", "json", h5, "/", "-a", "ranges"));
        assertEquals("\"sample\"\n", Cli.ok("dump", h5, "-a", "title"));
        assertEquals("[[0,1],[2,3]]\n", Cli.ok("dump", zarr, "-a", "ranges"));
        assertEquals("\"K\"\n", Cli.ok("dump", zarr, "run/temperature", "-a", "units"));
        Cli.Result none = Cli.run("dump", zarr, "-a", "nope");
        assertEquals(1, none.status());
        assertTrue(none.err().contains("has no attribute 'nope'"), none.err());
    }

    @Test
    void aLargeArrayStreamsInBlocks() throws IOException {
        Path big = dir.resolve("big.h5");
        int n = 1 << 21;
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        try (Hdf5Writer w = Hdf5Writer.create(big)) {
            w.createDataset("v", Datatype.int32(), 2, n / 2).chunked(1, 1 << 16).write(values);
        }
        String csv = Cli.ok("dump", "-f", "csv", big.toString(), "v", "-s", ":,::65536");
        String[] rows = csv.split("\n");
        assertEquals(2, rows.length);
        assertArrayEquals(new String[] {"0", "65536"}, java.util.Arrays.copyOf(rows[0].split(","), 2));
        assertEquals(16, rows[1].split(",").length);
        assertEquals(Integer.toString(n / 2 + 15 * 65536), rows[1].split(",")[15]);
    }

    @Test
    void slicesParse() {
        Slices s = Slices.parse("2, 1:7:3 , -3", new long[] {5, 10, 4});
        assertArrayEquals(new long[] {2, 1, 1}, s.start());
        assertArrayEquals(new long[] {1, 2, 1}, s.count());
        assertArrayEquals(new long[] {1, 3, 1}, s.step());
        assertArrayEquals(new long[] {2}, s.resultShape());
        Slices whole = Slices.parse(null, new long[] {3, 4});
        assertArrayEquals(new long[] {3, 4}, whole.resultShape());
        Slices past = Slices.parse("8:100,-100:2", new long[] {10, 4});
        assertArrayEquals(new long[] {8, 0}, past.start());
        assertArrayEquals(new long[] {2, 2}, past.count());
        assertArrayEquals(new long[] {0, 4}, Slices.parse("5:2", new long[] {10, 4}).resultShape());
        assertThrows(UsageException.class, () -> Slices.parse("a", new long[] {3}));
        assertThrows(UsageException.class, () -> Slices.parse("1:2:3:4", new long[] {3}));
        assertThrows(UsageException.class, () -> Slices.parse("-4", new long[] {3}));
    }
}
