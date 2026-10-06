package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.bzip2.Bzip2Decoder;
import com.ebremer.falcon.core.compress.bzip2.Bzip2Encoder;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bzip2 filter (307), from bzip2.h5, made by h5py and hdf5plugin (PyTables' {@code H5Zbzip2.c} over
 * libbzip2 1.0.8): every dataset reads back exactly as its unfiltered copy under /expected, and every chunk
 * is what Falcon's encoder writes for the chunk's contents, byte for byte.
 */
class Bzip2FilterTest {

    private static final int BZIP2 = 307;

    @Test
    void everyDatasetMatchesItsUnfilteredCopy() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("bzip2.h5"))) {
            Group root = h5.root();
            Group expected = root.group("expected");
            List<String> names = root.childNames().stream().filter(n -> !n.equals("expected")).sorted().toList();
            assertEquals(11, names.size());
            for (String name : names) {
                Dataset filtered = root.dataset(name);
                assertArrayEquals(expected.dataset(name).readRawBytes(), filtered.readRawBytes(), name);
                assertTrue(filtered.filters().stream().anyMatch(f -> f.id() == BZIP2), name);
            }
            // A partial read decodes only the chunks it touches; unwritten chunks read as the fill value.
            assertArrayEquals(new int[] {249, 250, 251}, root.dataset("bzip2_i4").select(new long[] {249}, new long[] {3}).readInts());
            assertArrayEquals(new int[] {0, -7, -7}, root.dataset("bzip2_sparse_i4").select(new long[] {299}, new long[] {3}).readInts());
            assertArrayEquals(new int[] {-7, 0, 1}, root.dataset("bzip2_sparse_i4").select(new long[] {519}, new long[] {3}).readInts());
            // The filter's name and client data as stored: the block size, or nothing (the filter's default, 9).
            Filter filter = root.dataset("bzip2_f8_2d").filters().getFirst();
            assertEquals(new Filter(BZIP2, "bzip2", true, new int[] {1}), filter);
            assertArrayEquals(new int[0], root.dataset("bzip2_no_options_i4").filters().getFirst().clientData());
        }
    }

    @Test
    void falconsEncoderWritesEveryChunkAsLibbzip2Did() throws IOException {
        byte[] file = Files.readAllBytes(Fixtures.path("bzip2.h5"));
        int chunks = 0;
        int multiBlock = 0;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("bzip2.h5"))) {
            for (Hdf5Object object : h5.root().children()) {
                if (!(object instanceof Dataset dataset) || !(dataset.dataLayout() instanceof DataLayout.Chunked chunked)) {
                    continue;
                }
                FilterPipeline pipeline = dataset.filterPipeline();
                List<FilterPipeline.Filter> filters = pipeline.filters();
                int at = 0;
                while (filters.get(at).id() != BZIP2) {
                    at++;
                }
                int[] cd = filters.get(at).clientData();
                int level = cd.length > 0 ? cd[0] : 9;
                int elementSize = dataset.datatype().size();
                int chunkBytes = elementSize;
                for (int c : chunked.chunkDimensions()) {
                    chunkBytes *= c;
                }
                for (ChunkRecord chunk : dataset.chunkIndex(chunked).all()) {
                    byte[] stream = Arrays.copyOfRange(file, (int) chunk.address(), (int) (chunk.address() + chunk.size()));
                    // Undo the filters applied after bzip2 (fletcher32), to reach the bzip2 stream.
                    for (int i = filters.size() - 1; i > at; i--) {
                        stream = Filters.decode(filters.get(i), stream, elementSize, chunkBytes);
                    }
                    byte[] contents = Bzip2Decoder.decompress(stream, 0, stream.length, chunkBytes);
                    assertEquals(chunkBytes, contents.length);
                    assertArrayEquals(stream, Bzip2Encoder.compress(contents, level),
                            dataset.name() + " chunk at " + Arrays.toString(chunk.offset()));
                    multiBlock += countBlocks(stream) > 1 ? 1 : 0;
                    chunks++;
                }
            }
        }
        assertEquals(63, chunks);
        assertEquals(1, multiBlock);
    }

    @Test
    void aCorruptChunkFailsTyped(@TempDir Path dir) throws IOException {
        byte[] file = Files.readAllBytes(Fixtures.path("bzip2.h5"));
        long address;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("bzip2.h5"))) {
            Dataset dataset = h5.root().dataset("bzip2_i4");
            address = dataset.chunkIndex((DataLayout.Chunked) dataset.dataLayout()).all().getFirst().address();
        }
        file[(int) address + 20] ^= 0x40; // inside the first block: its CRC no longer holds
        Path corrupt = dir.resolve("corrupt.h5");
        Files.write(corrupt, file);
        try (Hdf5File h5 = Hdf5File.open(corrupt)) {
            Dataset dataset = h5.root().dataset("bzip2_i4");
            assertThrows(HdfFormatException.class, dataset::readInts);
        }
    }

    /** The block magics in a stream: whole bytes of it only for the first block, so scan bit by bit. */
    private static int countBlocks(byte[] stream) {
        int blocks = 0;
        long window = 0;
        for (int bit = 0; bit < stream.length * 8; bit++) {
            window = ((window << 1) | ((stream[bit >> 3] >> (7 - (bit & 7))) & 1)) & 0xffffffffffffL;
            if (bit >= 47 && window == 0x314159265359L) {
                blocks++;
            }
        }
        return blocks;
    }
}
