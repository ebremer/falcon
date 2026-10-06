package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Corrupt, truncated, and garbage input must fail with a typed {@link HdfException} (or an
 * {@link IOException}) &mdash; never a raw runtime exception, a JVM crash (OOM / StackOverflow), or an
 * infinite loop. Truncates and byte-flips a spread of fixtures and forces a full read of each mutation,
 * memory-mapped and, for every truncation and every fifth flip, also read on demand through a
 * {@link RangeReader}. A mutation may also read without failing: a byte that no checksum covers reads as
 * whatever it now holds, so the values read are not checked.
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
        "plugin_filters.h5", "legacy_layouts.h5", "vax.h5", "fsinfo_v0_persist.h5", "btree_k_earliest.h5",
        "family_latest_0.h5", "vds_views.h5", "conversions.h5", "oldstyle_big.h5", "typed.h5", "paths_latest.h5",
        "elinks.h5",
    };

    @Test
    void notAnHdf5File() {
        for (boolean throughReader : new boolean[] {false, true}) {
            assertTypedFailure(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, throughReader);
            assertTypedFailure(new byte[0], throughReader);
            assertTypedFailure("this is plainly not an HDF5 file at all".getBytes(), throughReader);
        }
    }

    @Test
    @Timeout(120)
    void truncatedFiles() throws IOException {
        for (String fixture : FIXTURES) {
            byte[] full = Files.readAllBytes(Fixtures.path(fixture));
            for (long len = 0; len < full.length; len += Math.max(8, full.length / 40)) {
                byte[] truncated = Arrays.copyOf(full, (int) len);
                assertTypedFailure(truncated, false);
                assertTypedFailure(truncated, true);
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
            for (int pos = 0, n = 0; pos < full.length; pos += step, n++) {
                byte[] copy = full.clone();
                copy[pos] ^= 0xFF; // flip one byte
                assertTypedFailure(copy, false);
                if (n % 5 == 0) {
                    assertTypedFailure(copy, true);
                }
            }
        }
    }

    /** A reader over {@code bytes} that, like a remote source, holds no more than it was given. */
    private static RangeReader inMemory(byte[] bytes) {
        return new RangeReader() {
            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public void read(long position, ByteBuffer destination) {
                destination.put(bytes, Math.toIntExact(position), destination.remaining());
            }
        };
    }

    /** Datasets declaring more than this are sampled instead of read whole (see {@link #readEverything}). */
    private static final long MAX_READ_BYTES = 16 << 20;

    private static long[] ones(int rank) {
        long[] a = new long[rank];
        Arrays.fill(a, 1);
        return a;
    }

    /**
     * Opens and fully reads {@code bytes}, from a temporary file or through a {@link RangeReader}; any
     * failure must be an {@link HdfException} or IOException.
     */
    private static void assertTypedFailure(byte[] bytes, boolean throughReader) {
        Path file = null;
        try {
            if (!throughReader) {
                file = Files.createTempFile("falcon-fuzz", ".h5");
                Files.write(file, bytes);
            }
            try (Hdf5File h5 = throughReader ? Hdf5File.open(inMemory(bytes)) : Hdf5File.open(file)) {
                h5.fileSpaceInfo();
                h5.btreeKValues();
                h5.driverInfo();
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
        // Lookups by name on a new handle go through the name indexes rather than the lists just read.
        Hdf5Object fresh = Hdf5Object.classify(object.ctx, object.name(), "", object.objectHeaderAddress());
        try {
            fresh.attribute(attributes.isEmpty() ? "absent" : attributes.getFirst().name());
        } catch (HdfUnsupportedException unsupported) {
            // fine: keep reading the rest of the file
        }
        if (object instanceof Group group) {
            List<Link> links = group.links();
            if (fresh instanceof Group freshGroup) {
                freshGroup.link(links.isEmpty() ? "absent" : links.getLast().name());
                freshGroup.link("absent");
            }
            for (Hdf5Object child : group.children()) {
                readEverything(child);
            }
        } else if (object instanceof Dataset dataset) {
            try {
                dataset.layout();
                dataset.chunkShape();
                dataset.filters();
                dataset.storageSize(); // walks the whole chunk index
                long[] dims = dataset.dataspace().dimensions();
                if (dataset.dataspace().elementCount() * dataset.datatype().size() > MAX_READ_BYTES) {
                    // A dataset this large may be valid (chunked and sparse): reading it whole needs that
                    // much memory, corrupt or not. Read one element, which still walks its storage.
                    if (Arrays.stream(dims).allMatch(d -> d > 0)) {
                        dataset.select(new long[dims.length], ones(dims.length)).readDoubles();
                    }
                    return;
                }
                if (Arrays.stream(dims).allMatch(d -> d > 0)) {
                    // A one-element box: the chunk index lookup, or a virtual dataset's lazy read.
                    long[] last = Arrays.stream(dims).map(d -> d - 1).toArray();
                    dataset.selectionData(last, ones(dims.length));
                    // Every other index in each dimension, and two points: the selected-element reads.
                    long[] stride = Arrays.stream(dims).map(d -> 2).toArray();
                    long[] count = Arrays.stream(dims).map(d -> (d + 1) / 2).toArray();
                    dataset.select(new long[dims.length], stride, count, null).readRawBytes();
                    dataset.selectPoints(new long[][] {last, new long[dims.length]}).readRawBytes();
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

    /** Reads every region a read returned, if it returned regions, and finds every referenced object's path. */
    private static void readSelections(Object value) {
        if (value instanceof Hdf5Object[] objects) {
            for (Hdf5Object object : objects) {
                if (object != null) {
                    object.path(); // walks the file's links to find it
                }
            }
        }
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
