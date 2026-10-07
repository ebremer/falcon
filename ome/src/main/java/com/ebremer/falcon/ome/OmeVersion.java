package com.ebremer.falcon.ome;

import java.util.Optional;

/** A version of the OME-Zarr specification that Falcon reads and writes. */
public enum OmeVersion {

    /** OME-NGFF 0.4: Zarr v2; the metadata in a group's attributes, the version inside each part. */
    V0_4("0.4", 2),
    /** OME-Zarr 0.5: Zarr v3; the metadata under the attributes' {@code ome} key, with its version. */
    V0_5("0.5", 3),
    /** OME-Zarr 0.6: as 0.5, with named coordinate systems, more transformations, and scenes. */
    V0_6("0.6", 3);

    private final String id;
    private final int zarrFormat;

    OmeVersion(String id, int zarrFormat) {
        this.id = id;
        this.zarrFormat = zarrFormat;
    }

    /** {@return the version as the metadata writes it, such as {@code "0.5"}} */
    public String id() {
        return id;
    }

    /** {@return the Zarr format this version stores its hierarchy in: 2 or 3} */
    public int zarrFormat() {
        return zarrFormat;
    }

    /** {@return the latest version, which Falcon writes by default when none is chosen: 0.5} */
    public static OmeVersion defaultVersion() {
        return V0_5;
    }

    /**
     * The version a metadata {@code version} string names. The 0.6 release candidate's {@code "0.6rc0"},
     * which the 0.6 schemas accept, is 0.6.
     *
     * @param id the string, such as {@code "0.4"}
     * @return the version, or empty if Falcon does not support it
     */
    public static Optional<OmeVersion> of(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return switch (id) {
            case "0.4" -> Optional.of(V0_4);
            case "0.5" -> Optional.of(V0_5);
            case "0.6", "0.6rc0" -> Optional.of(V0_6);
            default -> Optional.empty();
        };
    }

    /** {@return whether this version names coordinate systems and has their transformations: 0.6} */
    boolean hasCoordinateSystems() {
        return this == V0_6;
    }

    @Override
    public String toString() {
        return id;
    }
}
