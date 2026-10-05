package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Corrupt, truncated, and garbage input must fail with a typed {@link HdfException} (or an
 * {@link IOException}) &mdash; never a raw runtime exception, a JVM crash (OOM / StackOverflow), an
 * infinite loop, or silently-wrong data. Truncates and byte-flips a spread of fixtures and forces a
 * full read of each mutation.
 *
 * <p>Runs in its own surefire execution ({@code fuzz} in the module POM) with a small stack and heap,
 * so a regression to unbounded recursion or allocation fails here rather than passing on a big JVM.
 */
class RobustnessTest {

    private static final String[] FIXTURES = {
        "new_style_groups.h5", "old_style_groups.h5", "data_contiguous.h5", "chunked_data.h5",
        "references.h5", "attributes.h5", "vlen_data.h5", "chunk_indexes.h5", "datatypes.h5",
        "dense_links.h5", "dense_attrs.h5", "committed_types.h5", "metadata.h5", "nbit_data.h5",
        "compound_nbit.h5", "external.h5", "free_space.h5", "implicit.h5", "committed_types_old.h5",
        "vds.h5", "numeric.h5", "vds_byteorder.h5", "dense_links_big.h5", "chunk_maxshape.h5", "layout_v4.h5", "filtered_single.h5",
        "unwritten_latest.h5", "scaleoffset.h5", "szip.h5", "userblock_v3.h5", "filter_edge.h5",
        "links.h5", "links_old.h5", "heap_limits.h5", "vds_default.h5", "vds_latest.h5",
        "regionrefs_default.h5", "regionrefs_latest.h5", "sohm.h5", "external_paths.h5", "ea_paged.h5",
        "sohm_latest.h5", "refs_revised.h5", "vds_unlimited.h5",
    };

    @Test
    void notAnHdf5File() {
        assertTypedFailure(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertTypedFailure(new byte[0]);
        assertTypedFailure("this is plainly not an HDF5 file at all".getBytes());
    }

    @Test
    @Timeout(120)
    void truncatedFiles() throws IOException {
        for (String fixture : FIXTURES) {
            byte[] full = Files.readAllBytes(Fixtures.path(fixture));
            for (long len = 0; len < full.length; len += Math.max(8, full.length / 40)) {
                assertTypedFailure(Arrays.copyOf(full, (int) len));
            }
        }
    }

    @Test
    @Timeout(300)
    void byteFlippedFiles() throws IOException {
        for (String fixture : FIXTURES) {
            byte[] full = Files.readAllBytes(Fixtures.path(fixture));
            // Large fixtures (150k chunks, 2100 links) cost far more per read: flip fewer bytes in them.
            int step = Math.max(1, full.length / (full.length > 500_000 ? 60 : 250));
            for (int pos = 0; pos < full.length; pos += step) {
                byte[] copy = full.clone();
                copy[pos] ^= 0xFF; // flip one byte
                assertTypedFailure(copy);
            }
        }
    }

    /** Datasets declaring more than this are sampled instead of read whole (see {@link #readEverything}). */
    private static final long MAX_READ_BYTES = 16 << 20;

    private static long[] ones(int rank) {
        long[] a = new long[rank];
        Arrays.fill(a, 1);
        return a;
    }

    /** Opens and fully reads {@code bytes}; any failure must be an {@link HdfException} or IOException. */
    private static void assertTypedFailure(byte[] bytes) {
        Path file = null;
        try {
            file = Files.createTempFile("falcon-fuzz", ".h5");
            Files.write(file, bytes);
            try (Hdf5File h5 = Hdf5File.open(file)) {
                readEverything(h5.root());
            }
        } catch (HdfException | IOException typed) {
            // acceptable: a typed format/unsupported error or an I/O error
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * Forces reads of every attribute and dataset reachable from {@code object}: the raw bytes, and the
     * typed read (which also resolves variable-length data and references through the heaps). A datatype
     * the typed read does not support is skipped for that object only.
     */
    private static void readEverything(Hdf5Object object) {
        List<Attribute> attributes;
        try {
            attributes = object.attributes();
        } catch (HdfUnsupportedException unsupported) {
            attributes = List.of(); // e.g. an attribute of a datatype Falcon does not parse
        }
        for (Attribute attribute : attributes) {
            try {
                readSelections(attribute.read());
            } catch (HdfUnsupportedException unsupported) {
                // fine: keep reading the rest of the file
            }
        }
        if (object instanceof Group group) {
            group.links();
            for (Hdf5Object child : group.children()) {
                readEverything(child);
            }
        } else if (object instanceof Dataset dataset) {
            try {
                long[] dims = dataset.dataspace().dimensions();
                if (dataset.dataspace().elementCount() * dataset.datatype().size() > MAX_READ_BYTES) {
                    // A dataset this large may be valid (chunked and sparse): reading it whole needs that
                    // much memory, corrupt or not. Read one element, which still walks its storage.
                    if (Arrays.stream(dims).allMatch(d -> d > 0)) {
                        dataset.select(new long[dims.length], ones(dims.length)).readDoubles();
                    }
                    return;
                }
                dataset.readRawBytes();
                readSelections(dataset.read());
                if (Hdf5Object.isRevisedReference(dataset.datatype())) {
                    // A revised reference may hold any kind: read its regions and attributes too.
                    readSelections(dataset.readRegionReferences());
                    dataset.readAttributeReferences();
                }
            } catch (HdfUnsupportedException unsupported) {
                // fine: keep reading the rest of the file
            }
        }
    }

    /** Reads every region a read returned, if it returned regions. */
    private static void readSelections(Object value) {
        if (value instanceof Selection[] regions) {
            for (Selection region : regions) {
                try {
                    if (region != null) {
                        region.readDoubles();
                    }
                } catch (HdfUnsupportedException unsupported) {
                    // e.g. a revised reference that is not a region: keep reading the others
                }
            }
        }
    }
}
