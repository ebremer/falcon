package com.ebremer.falcon.hdf5;

/**
 * Library metadata for Falcon's HDF5 reader/writer.
 *
 * <p>Targets the <em>HDF5 File Format Specification, Version 4.0</em> (as shipped with HDF5 2.0).
 * This class intentionally contains no format logic; the reader/writer entry points are added as the
 * roadmap in {@code hdf5/PLAN.md} is implemented.
 */
public final class Hdf5 {

    /** Human-readable library name. */
    public static final String NAME = "Falcon HDF5";

    /** Library version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /** Version of the HDF5 File Format Specification this library targets. */
    public static final String HDF5_FORMAT_VERSION = "4.0";

    /** Canonical URL of the targeted specification. */
    public static final String SPEC_URL =
            "https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html";

    private Hdf5() {
        // No instances.
    }
}
