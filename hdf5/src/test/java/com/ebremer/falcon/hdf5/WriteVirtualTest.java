package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing through virtual datasets (P2 WF11): {@link Hdf5Writer#open} writes a virtual dataset's elements
 * into its sources, as libhdf5's {@code H5Dwrite} does, in this file and in others, through every kind of
 * mapping; and refuses, before writing anything, elements no mapping (or a missing source) covers.
 */
class WriteVirtualTest {

    @TempDir
    Path dir;

    /** Copies fixtures into the test's directory, returning the first. */
    private Path copy(String... names) throws IOException {
        for (String name : names) {
            Files.copy(Fixtures.path(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        return dir.resolve(names[0]);
    }

    private int[] read(String file, String dataset) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(dir.resolve(file))) {
            return h5.root().dataset(dataset).readInts();
        }
    }

    private static int[] range(int from, int count) {
        int[] a = new int[count];
        for (int i = 0; i < count; i++) {
            a[i] = from + i;
        }
        return a;
    }

    @Test
    void writesEachMappingIntoItsSourceFile() throws IOException {
        Path vds = copy("vds.h5", "vds_src0.h5", "vds_src1.h5");
        try (Hdf5Writer w = Hdf5Writer.open(vds)) {
            Hdf5Writer.DatasetWriter d = w.dataset("vds");
            d.write(range(100, 8));
            w.dataset("vds_cols").write(new long[] {1, 0}, new long[] {2, 2}, new int[] {7, 8, 9, 10});
        }
        // vds_cols puts src0 in its first column, src1 in its second: rows 1-2 are elements 1-2 of each.
        assertArrayEquals(new int[] {100, 7, 9, 103}, read("vds_src0.h5", "data"));
        assertArrayEquals(new int[] {104, 8, 10, 107}, read("vds_src1.h5", "data"));
        try (Hdf5File h5 = Hdf5File.open(vds)) {
            assertArrayEquals(new int[] {100, 7, 9, 103, 104, 8, 10, 107}, h5.root().dataset("vds").readInts());
        }
    }

    @Test
    void refusesElementsNoMappingCoversBeforeWritingAny() throws IOException {
        Path vds = copy("vds.h5", "vds_src0.h5", "vds_src1.h5");
        byte[] before = Files.readAllBytes(dir.resolve("vds_src0.h5"));
        try (Hdf5Writer w = Hdf5Writer.open(vds)) {
            Hdf5Writer.DatasetWriter gap = w.dataset("vds_gap"); // row 1 is mapped by nothing
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> gap.write(range(0, 12)));
            assertTrue(e.getMessage().contains("map 8 of them"), e.getMessage());
            Hdf5Writer.DatasetWriter step = w.dataset("vds_step"); // only its even elements are mapped
            assertThrows(IllegalArgumentException.class, () -> step.write(new long[] {0}, new long[] {2}, new int[] {1, 2}));
            step.write(new long[] {4}, new long[] {1}, new int[] {44}); // source element 2
            gap.write(new long[] {2, 0}, new long[] {1, 4}, new int[] {5, 6, 7, 8});
        }
        assertArrayEquals(new int[] {0, 1, 44, 3}, read("vds_src0.h5", "data"));
        assertArrayEquals(new int[] {5, 6, 7, 8}, read("vds_src1.h5", "data"));
        assertEquals(before.length, Files.size(dir.resolve("vds_src0.h5")), "contiguous data is written in place");
    }

    @Test
    void convertsToTheSourcesByteOrder() throws IOException {
        Path vds = copy("vds_byteorder.h5", "vds_byteorder_src.h5");
        try (Hdf5Writer w = Hdf5Writer.open(vds)) {
            w.dataset("i4").write(new int[] {5, -6, 70000, -80000});
            w.dataset("f8").write(new double[] {0.5, -1e-300, 3, 4});
            Hdf5Writer.DatasetWriter u4 = w.dataset("u4_as_i4"); // a uint32 source under int32: a conversion
            assertThrows(HdfUnsupportedException.class, () -> u4.write(new int[] {1, 2, 3, 4}));
        }
        try (Hdf5File h5 = Hdf5File.open(dir.resolve("vds_byteorder_src.h5"))) {
            assertEquals(java.nio.ByteOrder.BIG_ENDIAN,
                    ((com.ebremer.falcon.hdf5.datatype.Datatype.FixedPoint) h5.root().dataset("i4_be").datatype()).byteOrder());
            assertArrayEquals(new int[] {5, -6, 70000, -80000}, h5.root().dataset("i4_be").readInts());
            assertArrayEquals(new double[] {0.5, -1e-300, 3, 4}, h5.root().dataset("f8_be").readDoubles());
            assertArrayEquals(new long[] {1, 2, 3, 4000000000L}, h5.root().dataset("u4").readLongs());
        }
    }

    @Test
    void writesUnlimitedAndPrintfMappings() throws IOException {
        Path vds = copy("vds_unlimited.h5", "vds_unlim_src.h5", "vds_printf_0.h5", "vds_printf_1.h5",
                "vds_printf_2.h5");
        try (Hdf5Writer w = Hdf5Writer.open(vds)) {
            Hdf5Writer.DatasetWriter rows = w.dataset("rows"); // its extent, (5, 3), is its source's
            assertArrayEquals(new long[] {5, 3}, rows.shape());
            rows.write(range(500, 15));
            Hdf5Writer.DatasetWriter printf = w.dataset("printf"); // blocks of two rows, one source each
            assertArrayEquals(new long[] {6, 3}, printf.shape());
            printf.write(new long[] {1, 0}, new long[] {4, 3}, range(600, 12)); // rows 1-4: three sources
            assertThrows(IllegalStateException.class, () -> rows.extend(9, 3));
        }
        assertArrayEquals(range(500, 15), read("vds_unlim_src.h5", "data"));
        assertArrayEquals(new int[] {10, 10, 10, 600, 601, 602}, read("vds_printf_0.h5", "data"));
        assertArrayEquals(range(603, 6), read("vds_printf_1.h5", "data"));
        assertArrayEquals(new int[] {609, 610, 611, 30, 30, 30}, read("vds_printf_2.h5", "data"));
    }

    @Test
    void writesSourcesInTheSameFileInTheSession() throws IOException {
        Path vds = copy("vds_unlimited.h5", "vds_unlim_src.h5");
        try (Hdf5Writer w = Hdf5Writer.open(vds)) {
            // cols interleaves a (2 x 3) and b (2 x 2) of this file, column by column: (2, 5).
            Hdf5Writer.DatasetWriter cols = w.dataset("cols");
            assertArrayEquals(new long[] {2, 5}, cols.shape());
            cols.write(range(0, 10));
            w.dataset("a").stringAttribute("note", "written through cols"); // the same dataset, opened again
            // printf_names: part0 and part1 fill [1, 4) and [5, 8); 0 and 4 are mapped by nothing.
            Hdf5Writer.DatasetWriter names = w.dataset("printf_names");
            assertThrows(IllegalArgumentException.class, () -> names.write(range(0, 8)));
            names.write(new long[] {5}, new long[] {3}, new int[] {-5, -6, -7});
            w.dataset("floored").write(new int[] {9, 8, 7, 6}); // its first four elements are "four"'s
        }
        try (Hdf5File h5 = Hdf5File.open(vds)) {
            assertArrayEquals(new int[] {0, 2, 4, 5, 7, 9}, h5.root().dataset("a").readInts());
            assertArrayEquals(new int[] {1, 3, 6, 8}, h5.root().dataset("b").readInts());
            assertArrayEquals(range(0, 10), h5.root().dataset("cols").readInts());
            assertEquals("written through cols", h5.root().dataset("a").attribute("note").orElseThrow().readStrings()[0]);
            assertArrayEquals(new int[] {20, 20, 20}, h5.root().dataset("part0").readInts());
            assertArrayEquals(new int[] {-5, -6, -7}, h5.root().dataset("part1").readInts());
            assertArrayEquals(new int[] {9, 8, 7, 6}, h5.root().dataset("four").readInts());
        }
    }

    @Test
    void writesScatteredMappings() throws IOException {
        Path vds = copy("vds_scatter.h5");
        try (Hdf5Writer w = Hdf5Writer.open(vds)) {
            w.dataset("strided").write(range(-64, 64));                         // flat[0::1024]
            int[] column = new int[128];
            java.util.Arrays.fill(column, -1000);
            w.dataset("reshaped").write(new long[] {0, 7}, new long[] {128, 1}, column); // flat[7::512]
        }
        try (Hdf5File h5 = Hdf5File.open(vds)) {
            int[] flat = h5.root().dataset("flat").readInts();
            for (int i = 0; i < flat.length; i++) {
                int expected = i % 1024 == 0 ? -64 + i / 1024 : i % 512 == 7 ? -1000 : i;
                assertEquals(expected, flat[i], "element " + i);
            }
        }
    }

    @Test
    void abortLeavesChunkedSourcesAsTheyWere() throws IOException {
        Path vds = copy("vds_unlimited.h5", "vds_unlim_src.h5");
        byte[] before = Files.readAllBytes(dir.resolve("vds_unlim_src.h5"));
        Hdf5Writer w = Hdf5Writer.open(vds);
        w.dataset("rows").write(range(500, 15));
        w.abort();
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("vds_unlim_src.h5")));
    }

    @Test
    void aVirtualDatasetMappingItselfIsRefused() throws IOException {
        Path loop = copy("vds_loop.h5");
        try (Hdf5Writer w = Hdf5Writer.open(loop)) {
            Hdf5Writer.DatasetWriter v = w.dataset("v");
            assertThrows(HdfFormatException.class, () -> v.write(new int[] {1, 2, 3, 4}));
            w.abort();
        }
    }
}
