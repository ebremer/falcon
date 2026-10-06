package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Blosc2 filter (32026), from blosc2.h5, made by h5py and hdf5plugin's hdf5-blosc2 (c-blosc2 3.3.2):
 * every dataset -- plain frames for rank-1 chunks, b2nd frames padded or not for ranks 2 to 4, every internal
 * codec, clevel and filter, special chunks, and frames written directly (several b2nd chunks, the caterva
 * metalayer, special chunks in the index) -- reads back exactly as the unfiltered copy under /expected.
 */
class Blosc2FilterTest {

    @Test
    void everyFilteredDatasetMatchesItsUnfilteredCopy() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("blosc2.h5"))) {
            Group root = h5.root();
            Group expected = root.group("expected");
            List<String> names = root.childNames().stream().filter(n -> !n.equals("expected")).sorted().toList();
            assertEquals(25, names.size());
            for (String name : names) {
                Dataset filtered = root.dataset(name);
                assertEquals(Filter.BLOSC2, filtered.filters().getFirst().id(), name);
                assertArrayEquals(expected.dataset(name).readRawBytes(), filtered.readRawBytes(), name);
            }
            Dataset padded = root.dataset("r2_lz4_padded_f4");
            assertEquals(List.of(new Filter(Filter.BLOSC2, "blosc2", true, new int[] {1, 32, 4, 140, 5, 1, 1, 2, 7, 5})),
                    padded.filters());
            // A partial read decodes only the chunks it touches: rows 5-9, columns 3-7 of 7 x 5 chunks.
            float[] all = expected.dataset("r2_lz4_padded_f4").readFloats();
            float[] part = padded.select(new long[] {5, 3}, new long[] {5, 5}).readFloats();
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 5; c++) {
                    assertEquals(all[(5 + r) * 13 + 3 + c], part[r * 5 + c]);
                }
            }
        }
    }

    /** One stored chunk of a dataset of blosc2.h5, as it is in the file. */
    private static byte[] storedChunk(String name, int index) throws IOException {
        byte[] file = Files.readAllBytes(Fixtures.path("blosc2.h5"));
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("blosc2.h5"))) {
            Dataset dataset = h5.root().dataset(name);
            ChunkRecord chunk = dataset.chunkIndex((DataLayout.Chunked) dataset.dataLayout()).all().get(index);
            return Arrays.copyOfRange(file, (int) chunk.address(), (int) (chunk.address() + chunk.size()));
        }
    }

    private static byte[] decode(int[] clientData, byte[] chunk, int chunkBytes) {
        return new FilterPipeline(List.of(new FilterPipeline.Filter(Filter.BLOSC2, 1, clientData))).decode(chunk, 0, 4, chunkBytes);
    }

    @Test
    void checksTheFrameAgainstTheClientData() throws IOException {
        byte[] b2nd = storedChunk("r2_lz4_padded_f4", 0);
        int[] cd = {1, 32, 4, 140, 5, 1, 1, 2, 7, 5};
        assertEquals(140, decode(cd, b2nd, 140).length);
        assertEquals(140, decode(Arrays.copyOf(cd, 7), b2nd, 140).length); // no rank: nothing to check against
        assertThrows(HdfFormatException.class, () -> decode(new int[] {1, 32, 4}, b2nd, 140));
        assertThrows(HdfFormatException.class, () -> decode(new int[] {1, 32, 4, 140, 5, 1, 1, 1, 7}, b2nd, 140));
        assertThrows(HdfFormatException.class, () -> decode(new int[] {1, 32, 4, 140, 5, 1, 1, 3, 7, 5, 1}, b2nd, 140));
        assertThrows(HdfFormatException.class, () -> decode(new int[] {1, 32, 4, 140, 5, 1, 1, 2, 7}, b2nd, 140));
        assertThrows(HdfFormatException.class, () -> decode(new int[] {1, 32, 4, 140, 5, 1, 1, 2, 5, 7}, b2nd, 140));
        assertThrows(HdfFormatException.class, () -> decode(new int[] {1, 32, 4, 139, 5, 1, 1, 2, 7, 5}, b2nd, 140));
        assertThrows(HdfFormatException.class, () -> decode(cd, Arrays.copyOf(b2nd, b2nd.length - 1), 140));
        // A type size in the client data other than the frame's sizes the result as the plugin does.
        assertEquals(70, decode(new int[] {1, 32, 2, 140, 5, 1, 1, 2, 7, 5}, b2nd, 140).length);

        byte[] plain = storedChunk("r1_zstd_bitshuffle_f8", 0);
        int[] plainCd = {1, 0, 8, 1024, 9, 2, 5};
        assertEquals(1024, decode(plainCd, plain, 1024).length);
        assertThrows(HdfFormatException.class, () -> decode(plainCd, Arrays.copyOf(plain, 100), 1024));
    }
}
