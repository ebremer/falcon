package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.filter.Nbit;
import com.ebremer.falcon.hdf5.filter.ScaleOffset;
import com.ebremer.falcon.hdf5.filter.Szip;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Falcon's encoders for the filters libhdf5 writes with parameters of their own (P2 WF7, WF10): every chunk
 * of the szip, n-bit and scale-offset datasets libhdf5 wrote, decoded and encoded again with the dataset's
 * client data, is libhdf5's chunk byte for byte. So Falcon writes into such datasets what libhdf5 would.
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
}
