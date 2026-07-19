package com.ebremer.falcon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test proving the Maven + JUnit 5 + JPMS build harness works end to end.
 * Real conformance tests arrive with each roadmap phase.
 */
class FalconTest {

    @Test
    void exposesLibraryMetadata() {
        assertEquals("Falcon", Falcon.NAME);
        assertEquals("4.0", Falcon.HDF5_FORMAT_VERSION);
        assertTrue(Falcon.SPEC_URL.startsWith("https://"), "spec URL should be https");
    }
}
