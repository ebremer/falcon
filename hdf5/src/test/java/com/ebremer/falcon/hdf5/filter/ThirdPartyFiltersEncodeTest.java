package com.ebremer.falcon.hdf5.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The third-party filters' write side (P2 S8) with the client data a file may hold, odd values included:
 * each either fails the chunk, as its plugin would (null: libhdf5 skips the optional filter), or writes
 * what the read side decodes back. Byte-for-byte agreement with the plugins is in
 * {@code WriteFilterConformanceTest}.
 */
class ThirdPartyFiltersEncodeTest {

    private static final int[][] LZ4_DATA = {{}, {0}, {1}, {7}, {64}, {-1}, {0x7E000001}};
    private static final int[][] BLOSC_DATA = {
        {2, 2, 4, 4000}, {2, 2, 4, 4000, 9}, {2, 2, 4, 4000, 1, 2}, {2, 2, 4, 4000, 5, 0, 4},
        {2, 2, 0, 4000, 5, 1, 1}, {2, 2, -1, 4000, 5, 1, 2}, {2, 2, 300, 4000, 5, 1, 2}, {2, 2, 4, 4000, 5, 1, 3},
        {2, 2, 3, 4000, 9, 2, 5},
    };
    private static final int[][] BITSHUFFLE_DATA = {
        {0, 4, 4}, {0, 4, 4, 0}, {0, 4, 4, 8, 0}, {0, 4, 2, 64, 2}, {0, 4, 8, 0, 3}, {0, 4, 4, 16, 3, 22}, {0, 4, 1, 0, 2},
        {0, 4, 16, 0, 2}, {0, 4, 4000, 0, 2},
    };
    private static final int[][] ZSTD_DATA = {{}, {0}, {1}, {22}, {100}, {-5}, {-200000}};

    @Test
    void whatEachFilterWritesDecodes() {
        byte[] data = new byte[4000];
        Random random = new Random(2);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (random.nextInt(32) == 0 ? random.nextInt() : i / 50);
        }
        int written = 0;
        for (int id : new int[] {ThirdPartyFilters.LZ4, ThirdPartyFilters.BLOSC, ThirdPartyFilters.BITSHUFFLE,
            ThirdPartyFilters.ZSTD, ThirdPartyFilters.LZF}) {
            int[][] cases = switch (id) {
                case ThirdPartyFilters.LZ4 -> LZ4_DATA;
                case ThirdPartyFilters.BLOSC -> BLOSC_DATA;
                case ThirdPartyFilters.BITSHUFFLE -> BITSHUFFLE_DATA;
                case ThirdPartyFilters.ZSTD -> ZSTD_DATA;
                default -> new int[][] {{4, 261, 4000}, {}};
            };
            for (int[] cd : cases) {
                byte[] encoded = ThirdPartyFilters.encode(id, cd, data);
                assertNotNull(encoded, id + " " + Arrays.toString(cd));
                FilterPipeline.Filter filter = new FilterPipeline.Filter(id, 1, cd);
                assertArrayEquals(data, Filters.decode(filter, encoded, 1, data.length), id + " " + Arrays.toString(cd));
                written++;
            }
        }
        assertEquals(34, written);
    }

    /** Where each plugin returns 0, so libhdf5 stores the chunk unfiltered. */
    @Test
    void failsWhereThePluginsFail() {
        byte[] data = new byte[1000];
        new Random(4).nextBytes(data); // incompressible
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.LZF, new int[0], data));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.LZF, new int[0], new byte[2]));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4, 1000, 5, 1, 1}, data));
        // Blosc: clevel 0 and buffers under 128 bytes are copied whole, which needs 16 bytes more than the chunk
        byte[] zeros = new byte[1000];
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4, 1000, 0, 1, 1}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4, 100, 9, 1, 1}, new byte[100]));
        // snappy refuses a destination under its bound, so a chunk of one block never fits
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 1, 1000, 5, 1, 3}, zeros));
        // parameters c-blosc refuses, and too few of them
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4, 1000, 10, 1, 1}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4, 1000, 5, 3, 1}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4, 1000, 5, 1, 6}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BLOSC, new int[] {2, 2, 4}, zeros));
        // bitshuffle: too few parameters, a chunk that is not whole elements, a block not a multiple of 8
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BITSHUFFLE, new int[] {0, 4}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BITSHUFFLE, new int[] {0, 4, 3}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BITSHUFFLE, new int[] {0, 4, 0}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BITSHUFFLE, new int[] {0, 4, 4, 12, 2}, zeros));
        assertNull(ThirdPartyFilters.encode(ThirdPartyFilters.BITSHUFFLE, new int[] {0, 4, -1, 0, 2}, zeros));
        // LZ4 and zstd never fail; LZ4 stores incompressible blocks raw
        byte[] lz4 = ThirdPartyFilters.encode(ThirdPartyFilters.LZ4, new int[] {300}, data);
        assertEquals(12 + 4 * 4 + 1000, lz4.length);
        assertNotNull(ThirdPartyFilters.encode(ThirdPartyFilters.ZSTD, new int[] {3}, data));
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyFilters.encode(32026, new int[0], data));
    }

    @Test
    void namesAreThePlugins() {
        assertEquals("lzf", ThirdPartyFilters.pluginName(ThirdPartyFilters.LZF));
        assertEquals("blosc", ThirdPartyFilters.pluginName(ThirdPartyFilters.BLOSC));
        assertEquals("bitshuffle; see https://github.com/kiyo-masui/bitshuffle",
                ThirdPartyFilters.pluginName(ThirdPartyFilters.BITSHUFFLE));
        assertEquals("HDF5 lz4 filter; see https://github.com/HDFGroup/hdf5_plugins/blob/master/docs/RegisteredFilterPlugins.md",
                ThirdPartyFilters.pluginName(ThirdPartyFilters.LZ4));
        assertEquals("HDF5 zstd filter; see https://github.com/HDFGroup/hdf5_plugins/blob/master/docs/RegisteredFilterPlugins.md",
                ThirdPartyFilters.pluginName(ThirdPartyFilters.ZSTD));
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyFilters.pluginName(1));
    }
}
