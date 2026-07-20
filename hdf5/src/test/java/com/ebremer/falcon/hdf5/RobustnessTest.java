package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Corrupt, truncated, and garbage input must fail with a typed {@link HdfException} (or an
 * {@link IOException}) &mdash; never a raw runtime exception, a JVM crash (OOM / StackOverflow), an
 * infinite loop, or silently-wrong data. Truncates and byte-flips a spread of fixtures and forces a
 * full read of each mutation.
 */
class RobustnessTest {

    private static final String[] FIXTURES = {
        "new_style_groups.h5", "old_style_groups.h5", "data_contiguous.h5", "chunked_data.h5",
        "references.h5", "attributes.h5", "vlen_data.h5", "chunk_indexes.h5", "datatypes.h5",
        "dense_links.h5", "dense_attrs.h5", "committed_types.h5", "metadata.h5", "nbit_data.h5",
        "compound_nbit.h5", "external.h5", "free_space.h5", "implicit.h5", "committed_types_old.h5",
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
    @Timeout(180)
    void byteFlippedFiles() throws IOException {
        for (String fixture : FIXTURES) {
            byte[] full = Files.readAllBytes(Fixtures.path(fixture));
            int step = Math.max(1, full.length / 250);
            for (int pos = 0; pos < full.length; pos += step) {
                byte[] copy = full.clone();
                copy[pos] ^= 0xFF; // flip one byte
                assertTypedFailure(copy);
            }
        }
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

    /** Forces reads of every attribute and dataset reachable from {@code object}. */
    private static void readEverything(Hdf5Object object) {
        for (Attribute attribute : object.attributes()) {
            attribute.read();
        }
        if (object instanceof Group group) {
            for (Hdf5Object child : group.children()) {
                readEverything(child);
            }
        } else if (object instanceof Dataset dataset) {
            dataset.readRawBytes();
        }
    }
}
