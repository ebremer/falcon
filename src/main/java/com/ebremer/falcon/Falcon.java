package com.ebremer.falcon;

/**
 * Library metadata for Falcon, a pure-JDK HDF5 reader/writer.
 *
 * <p>Falcon targets the <em>HDF5 File Format Specification, Version 4.0</em> (as shipped with
 * HDF5 2.0). This class intentionally contains no format logic; the reader/writer entry points are
 * added as the roadmap in {@code PLAN.md} is implemented.
 */
public final class Falcon {

    /** Human-readable library name. */
    public static final String NAME = "Falcon";

    /** Library version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /** Version of the HDF5 File Format Specification this library targets. */
    public static final String HDF5_FORMAT_VERSION = "4.0";

    /** Canonical URL of the targeted specification. */
    public static final String SPEC_URL =
            "https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html";

    private Falcon() {
        // No instances.
    }
}
