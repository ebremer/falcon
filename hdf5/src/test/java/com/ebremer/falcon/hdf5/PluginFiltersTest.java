package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The third-party filters (LZF, Blosc, LZ4, bitshuffle, Zstandard), from plugin_filters.h5, made by h5py
 * and hdf5plugin: every dataset reads back exactly as the unfiltered copy under /expected.
 */
class PluginFiltersTest {

    @Test
    void everyFilteredDatasetMatchesItsUnfilteredCopy() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("plugin_filters.h5"))) {
            Group root = h5.root();
            Group expected = root.group("expected");
            List<String> names = root.childNames().stream().filter(n -> !n.equals("expected")).sorted().toList();
            assertEquals(20, names.size());
            for (String name : names) {
                Dataset filtered = root.dataset(name);
                assertArrayEquals(expected.dataset(name).readRawBytes(), filtered.readRawBytes(), name);
            }
            // A partial read decodes only the chunks it touches.
            assertArrayEquals(new int[] {249, 250, 251}, root.dataset("lz4_i4").select(new long[] {249}, new long[] {3}).readInts());
        }
    }
}
