package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ZFP filter (32013), from zfp.h5, made by h5py and hdf5plugin's H5Z-ZFP: every dataset reads back bit
 * for bit as libzfp decodes it (the copies under /expected, read through the filter by libhdf5).
 */
class ZfpFilterTest {

    @Test
    void everyDatasetReadsAsLibzfpDecodesIt() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("zfp.h5"))) {
            Group root = h5.root();
            Group expected = root.group("expected");
            List<String> names = root.childNames().stream().filter(n -> !n.equals("expected")).sorted().toList();
            assertEquals(14, names.size());
            for (String name : names) {
                Dataset filtered = root.dataset(name);
                assertEquals(Filter.ZFP, filtered.filters().getFirst().id(), name);
                assertArrayEquals(expected.dataset(name).readRawBytes(), filtered.readRawBytes(), name);
            }
            // A partial read decodes only the chunks it touches: here the last, cut by the dataset's edge.
            Dataset rate = root.dataset("f4_1d_rate");
            float[] all = expected.dataset("f4_1d_rate").readFloats();
            assertArrayEquals(Arrays.copyOfRange(all, 240, 250), rate.select(new long[] {240}, new long[] {10}).readFloats());
            Dataset unitDims = root.dataset("f8_unit_dims_expert"); // (3, 1, 16, 1, 20) in chunks of (1, 1, 16, 1, 20)
            double[] cube = expected.dataset("f8_unit_dims_expert").readDoubles();
            assertArrayEquals(Arrays.copyOfRange(cube, 2 * 320 + 5 * 20, 2 * 320 + 5 * 20 + 20),
                    unitDims.select(new long[] {2, 0, 5, 0, 0}, new long[] {1, 1, 1, 1, 20}).readDoubles());
        }
    }

    @Test
    void readsAHeaderWrittenOnABigEndianMachine() throws IOException {
        // H5Z-ZFP keeps the header in the client data in the writer's memory order: a big-endian writer's reads
        // byte-swapped, and the values are then swapped back to the dataset's (big-endian) order.
        Chunk chunk = firstChunk("f8_2d_precision");
        int[] cd = chunk.filter.clientData();
        int[] swapped = cd.clone();
        for (int i = 1; i < swapped.length; i++) {
            swapped[i] = Integer.reverseBytes(swapped[i]);
        }
        byte[] little = Filters.decode(chunk.filter, chunk.bytes, 8, chunk.decodedSize);
        byte[] big = Filters.decode(new FilterPipeline.Filter(Filter.ZFP, 1, swapped), chunk.bytes, 8, chunk.decodedSize);
        assertEquals(chunk.decodedSize, little.length);
        for (int i = 0; i < little.length; i++) {
            assertEquals(little[i], big[(i / 8) * 8 + 7 - i % 8], "byte " + i);
        }
    }

    @Test
    void refusesWhatItCannotRead() throws IOException {
        Chunk chunk = firstChunk("i4_2d_precision");
        int[] cd = chunk.filter.clientData();
        // a newer zfp codec than 5, as H5Z-ZFP 1.1 records it (bits 12 to 15)
        int[] newer = cd.clone();
        newer[0] = (cd[0] & ~0xf000) | (6 << 12);
        assertThrows(HdfUnsupportedException.class, () -> decode(newer, chunk));
        // no zfp header in the client data, and too few client data
        int[] garbage = cd.clone();
        garbage[1] = 0x12345678;
        assertThrows(HdfFormatException.class, () -> decode(garbage, chunk));
        assertThrows(HdfFormatException.class, () -> decode(new int[] {cd[0]}, chunk));
        // a chunk cut short
        byte[] cut = Arrays.copyOf(chunk.bytes, chunk.bytes.length / 3);
        assertThrows(HdfFormatException.class, () -> Filters.decode(chunk.filter, cut, 4, chunk.decodedSize));
    }

    private static byte[] decode(int[] clientData, Chunk chunk) {
        return Filters.decode(new FilterPipeline.Filter(Filter.ZFP, 1, clientData), chunk.bytes, 4, chunk.decodedSize);
    }

    private record Chunk(FilterPipeline.Filter filter, byte[] bytes, int decodedSize) {
    }

    /** The stored bytes of the first chunk of a dataset of zfp.h5, and its filter. */
    private static Chunk firstChunk(String name) throws IOException {
        byte[] file = Files.readAllBytes(Fixtures.path("zfp.h5"));
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("zfp.h5"))) {
            Dataset dataset = h5.root().dataset(name);
            DataLayout.Chunked chunked = (DataLayout.Chunked) dataset.dataLayout();
            int size = dataset.datatype().size();
            for (int c : chunked.chunkDimensions()) {
                size *= c;
            }
            ChunkRecord record = dataset.chunkIndex(chunked).all().getFirst();
            byte[] bytes = Arrays.copyOfRange(file, (int) record.address(), (int) (record.address() + record.size()));
            return new Chunk(dataset.filterPipeline().filters().getFirst(), bytes, size);
        }
    }
}
