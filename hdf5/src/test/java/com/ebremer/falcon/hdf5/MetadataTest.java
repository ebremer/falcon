package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Object metadata messages: comment, modification time, and hard-link (reference) count. */
class MetadataTest {

    private static final Instant YEAR_2000 = Instant.parse("2000-01-01T00:00:00Z");

    @Test
    void newStyleMetadata() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("metadata.h5"))) {
            // Two hard links to /plain -> reference count 2 (object reference count message).
            assertEquals(2, h5.root().dataset("plain").referenceCount());
            // Times tracked only for the track_times dataset (version-2 header prefix time).
            assertTrue(h5.root().dataset("plain").modificationTime().isEmpty());
            var timed = h5.root().dataset("timed").modificationTime();
            assertTrue(timed.isPresent());
            assertTrue(timed.orElseThrow().isAfter(YEAR_2000));
            assertEquals("a helpful comment", h5.root().dataset("commented").comment().orElseThrow());
        }
    }

    @Test
    void oldStyleMetadata() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("metadata_old.h5"))) {
            // Version-1 headers carry the reference count in the prefix and a modification-time message.
            assertEquals(2, h5.root().dataset("plain").referenceCount());
            assertTrue(h5.root().dataset("timed").modificationTime().orElseThrow().isAfter(YEAR_2000));
            assertEquals("a helpful comment", h5.root().dataset("commented").comment().orElseThrow());
            assertEquals(1, h5.root().dataset("timed").referenceCount());
        }
    }

    @Test
    void objectWithoutCommentReturnsEmpty() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("metadata.h5"))) {
            assertFalse(h5.root().dataset("plain").comment().isPresent());
        }
    }
}
