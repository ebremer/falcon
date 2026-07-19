package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test proving the Maven (reactor) + JUnit 5 + JPMS build harness works end to end.
 * Real conformance tests arrive with each roadmap stage.
 */
class Hdf5Test {

    @Test
    void exposesLibraryMetadata() {
        assertEquals("Falcon HDF5", Hdf5.NAME);
        assertEquals("4.0", Hdf5.HDF5_FORMAT_VERSION);
        assertTrue(Hdf5.SPEC_URL.startsWith("https://"), "spec URL should be https");
    }
}
