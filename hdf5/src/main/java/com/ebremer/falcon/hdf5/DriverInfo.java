package com.ebremer.falcon.hdf5;

import java.util.Arrays;

/**
 * The file driver a file was written with, when it is not the default, as libhdf5 records it so the file
 * can be reopened with the same driver (see {@link Hdf5File#driverInfo()}). Such a file is one member of a
 * set: {@code "NCSAfami"} is the family driver (the information is the member size, 8 bytes,
 * little-endian), {@code "NCSAmult"} the multi and split drivers. Falcon reads single files only.
 *
 * @param driverId    the driver's 8-character identifier
 * @param information the driver's own encoding of its settings
 */
public record DriverInfo(String driverId, byte[] information) {

    /**
     * The driver information, copying {@code information}.
     *
     * @param driverId    the driver's 8-character identifier
     * @param information the driver's own encoding of its settings
     */
    public DriverInfo {
        information = information.clone();
    }

    /**
     * A copy of the driver's settings.
     *
     * @return the driver's own encoding of its settings, copied
     */
    @Override
    public byte[] information() {
        return information.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DriverInfo d && driverId.equals(d.driverId) && Arrays.equals(information, d.information);
    }

    @Override
    public int hashCode() {
        return 31 * driverId.hashCode() + Arrays.hashCode(information);
    }

    @Override
    public String toString() {
        return "DriverInfo[" + driverId + ", " + information.length + " bytes]";
    }
}
