package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.filter.Nbit;
import com.ebremer.falcon.hdf5.filter.ScaleOffset;
import com.ebremer.falcon.hdf5.filter.Szip;
import com.ebremer.falcon.hdf5.filter.ThirdPartyFilters;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Falcon's encoders for the filters libhdf5 writes with parameters of their own (P2 WF7, WF10): every chunk
 * of the szip, n-bit and scale-offset datasets libhdf5 wrote, decoded and encoded again with the dataset's
 * client data, is libhdf5's chunk byte for byte. So Falcon writes into such datasets what libhdf5 would. The
 * same holds for the third-party filters' plugins (P2 S8).
 */
class WriteFilterConformanceTest {

    @ParameterizedTest
    @ValueSource(strings = {"szip.h5", "scaleoffset.h5", "chunked_data.h5", "nbit_data.h5", "compound_nbit.h5",
        "filter_edge.h5"})
    void encodesEveryChunkAsLibhdf5Did(String fixture) throws IOException {
        byte[] file = Files.readAllBytes(Fixtures.path(fixture));
        int chunks = 0;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            for (Hdf5Object object : h5.root().children()) {
                if (!(object instanceof Dataset dataset) || !(dataset.dataLayout() instanceof DataLayout.Chunked chunked)) {
                    continue;
                }
                FilterPipeline pipeline = dataset.filterPipeline();
                if (pipeline == null || pipeline.filters().size() != 1) {
                    continue;
                }
                FilterPipeline.Filter filter = pipeline.filters().getFirst();
                if (filter.id() != Filters.SZIP && filter.id() != Filters.NBIT && filter.id() != Filters.SCALEOFFSET) {
                    continue;
                }
                int elementSize = dataset.datatype().size();
                int chunkBytes = elementSize;
                for (int c : chunked.chunkDimensions()) {
                    chunkBytes *= c;
                }
                for (ChunkRecord chunk : dataset.chunkIndex(chunked).all()) {
                    if (chunk.filterMask() != 0) {
                        continue; // stored unfiltered: the filter could not shrink it
                    }
                    byte[] stored = Arrays.copyOfRange(file, (int) chunk.address(), (int) (chunk.address() + chunk.size()));
                    byte[] decoded = pipeline.decode(stored, 0, elementSize, chunkBytes);
                    int[] cd = filter.clientData();
                    byte[] encoded = switch (filter.id()) {
                        case Filters.SZIP -> Szip.encode(decoded, cd);
                        case Filters.NBIT -> Nbit.encode(decoded, cd);
                        default -> ScaleOffset.encode(decoded, cd);
                    };
                    assertArrayEquals(stored, encoded, fixture + " " + dataset.name() + " chunk at "
                            + Arrays.toString(chunk.offset()));
                    chunks++;
                }
            }
        }
        assertTrue(chunks > 0, "no filtered chunks in " + fixture);
    }

    /**
     * The third-party filters (P2 S8): every chunk h5py and hdf5plugin wrote with LZF, Blosc (but with zstd
     * inside), LZ4, and bitshuffle alone or with LZ4 &mdash; after the filters before it, and before
     * fletcher32 &mdash; decoded and encoded again with its dataset's client data, is the plugin's own bytes;
     * and where the plugin gave up on a chunk (its filter-mask bit: LZF or Blosc output no smaller than the
     * chunk, Blosc at clevel 0 or under 128 bytes), Falcon's encoder gives up too.
     *
     * <p>One LZF chunk differs, as valid: h5py builds liblzf with an uninitialised hash table, and there a
     * stale entry (left, it seems, by the dataset's previous chunk, compressed in the same buffer) pointed at
     * a position this chunk never hashed, which happened to match, so liblzf's stream is 2 bytes shorter.
     * Falcon's tables start empty. Falcon's zstd frames are its own, so those datasets are checked by
     * reading them ({@link WritePluginFiltersTest}).
     */
    @Test
    void encodesEveryPluginChunkAsThePluginDid() throws IOException {
        // per filter: chunks reproduced, chunks the plugin skipped (and Falcon's encoder refuses), chunks that differ
        Map<String, int[]> counts = new TreeMap<>();
        for (String fixture : List.of("plugin_filters_write.h5", "plugin_filters.h5")) {
            byte[] file = Files.readAllBytes(Fixtures.path(fixture));
            try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
                for (Hdf5Object object : h5.root().children()) {
                    if (object instanceof Dataset dataset && dataset.dataLayout() instanceof DataLayout.Chunked chunked) {
                        reencode(file, dataset, chunked, fixture, counts);
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        counts.forEach((filter, n) -> summary.append(filter).append(' ').append(n[0]).append('/').append(n[1]).append('/')
                .append(n[2]).append(' '));
        assertEquals("bitshuffle 130/0/0 blosc 190/53/0 lz4 55/0/0 lzf 58/22/1 ", summary.toString(),
                "chunks reproduced / skipped / different, per filter");
    }

    private static void reencode(byte[] file, Dataset dataset, DataLayout.Chunked chunked, String fixture,
                                 Map<String, int[]> counts) {
        List<FilterPipeline.Filter> filters = dataset.filterPipeline().filters();
        int k = 0;
        while (k < filters.size() && !List.of(Filter.LZF, Filter.BLOSC, Filter.LZ4, Filter.BITSHUFFLE)
                .contains(filters.get(k).id())) {
            k++;
        }
        if (k == filters.size()) {
            return;
        }
        FilterPipeline.Filter filter = filters.get(k);
        int[] cd = filter.clientData();
        if (filter.id() == Filter.BLOSC && cd.length > 6 && cd[6] == 5
                || filter.id() == Filter.BITSHUFFLE && cd.length > 4 && cd[4] == 3) {
            return; // zstd inside: Falcon's frames are not libzstd's
        }
        String name = switch (filter.id()) {
            case Filter.LZF -> "lzf";
            case Filter.BLOSC -> "blosc";
            case Filter.LZ4 -> "lz4";
            default -> "bitshuffle";
        };
        int[] count = counts.computeIfAbsent(name, n -> new int[3]);
        int elementSize = dataset.datatype().size();
        int chunkBytes = elementSize;
        for (int c : chunked.chunkDimensions()) {
            chunkBytes *= c;
        }
        for (ChunkRecord chunk : dataset.chunkIndex(chunked).all()) {
            byte[] data = Arrays.copyOfRange(file, (int) chunk.address(), (int) (chunk.address() + chunk.size()));
            for (int i = filters.size() - 1; i > k; i--) { // undo the filters after it (fletcher32)
                if ((chunk.filterMask() & (1 << i)) == 0) {
                    data = Filters.decode(filters.get(i), data, elementSize, chunkBytes);
                }
            }
            String where = fixture + " " + dataset.name() + " chunk at " + Arrays.toString(chunk.offset());
            if ((chunk.filterMask() & (1 << k)) != 0) {
                assertNull(ThirdPartyFilters.encode(filter.id(), cd, data), where + ": the plugin skipped it");
                count[1]++;
                continue;
            }
            byte[] input = Filters.decode(filter, data, elementSize, chunkBytes);
            byte[] encoded = ThirdPartyFilters.encode(filter.id(), cd, input);
            if (filter.id() == Filter.LZF && !Arrays.equals(data, encoded)) {
                assertArrayEquals(input, Filters.decode(filter, encoded, elementSize, chunkBytes), where);
                count[2]++;
            } else {
                assertArrayEquals(data, encoded, where);
                count[0]++;
            }
        }
    }
}
