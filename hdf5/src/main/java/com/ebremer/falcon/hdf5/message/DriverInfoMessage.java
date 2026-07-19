package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Driver Info message (type 20, spec section IV.A.2.t): the file-driver configuration a non-default
 * driver (multi / family / split) needs to reopen a file, stored in the superblock extension. Falcon
 * exposes the driver identification and its opaque info block; actually stitching together a
 * multi-file driver's members is out of scope (a non-default file driver, per the roadmap).
 *
 * <p>Body: {@code version(1) · driver identification(8 ASCII) · info size(2) · driver info(size)}.
 *
 * @param driverName the 8-character driver tag (e.g. {@code "NCSAfami"} for the family driver)
 * @param info       the driver's opaque configuration bytes
 */
public record DriverInfoMessage(String driverName, byte[] info) {

    public static DriverInfoMessage parse(HdfBuffer body) {
        byte[] name = body.getBytes(1, 8);
        int length = 0;
        while (length < name.length && name[length] != 0) {
            length++;
        }
        int infoSize = body.getUnsignedShort(9);
        return new DriverInfoMessage(new String(name, 0, length, StandardCharsets.US_ASCII),
                body.getBytes(11, infoSize));
    }
}
