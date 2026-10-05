package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.DriverInfo;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The file-driver configuration a non-default driver (multi / family / split) needs to reopen a file. It
 * is stored in one of two forms; actually stitching together a multi-file driver's members is out of
 * scope (a non-default file driver, per the roadmap).
 *
 * <ul>
 *   <li>the Driver Info message (type 20, spec section IV.A.2.t) in the superblock extension (superblock
 *       versions 2&ndash;3): {@code version(1)=0 · driver identification(8 ASCII) · info size(2) · driver
 *       info(size)};</li>
 *   <li>the driver information block a version 0&ndash;1 superblock points to (spec section II.B):
 *       {@code version(1)=0 · reserved(3) · info size(4) · driver identification(8 ASCII) · driver
 *       info(size)}.</li>
 * </ul>
 */
public final class DriverInfoMessage {

    private DriverInfoMessage() {
    }

    /** Parses a Driver Info message body. */
    public static DriverInfo parse(HdfBuffer body) {
        if (body.size() < 11 || body.getUnsignedByte(0) != 0) {
            throw new HdfFormatException("unsupported driver info message");
        }
        int infoSize = body.getUnsignedShort(9);
        if (11L + infoSize > body.size()) {
            throw new HdfFormatException("driver info message of " + body.size() + " bytes holds " + infoSize + " more");
        }
        return new DriverInfo(identifier(body.getBytes(1, 8)), body.getBytes(11, infoSize));
    }

    /** Parses the driver information block at {@code address} of {@code buf}. */
    public static DriverInfo parseBlock(HdfBuffer buf, long address) {
        if (address < 0 || address > buf.size() - 16 || buf.getUnsignedByte(address) != 0) {
            throw new HdfFormatException("invalid driver information block at " + address);
        }
        long infoSize = buf.getUnsignedInt(address + 4);
        if (infoSize > buf.size() - address - 16) {
            throw new HdfFormatException("driver information block at " + address + " overruns the file");
        }
        return new DriverInfo(identifier(buf.getBytes(address + 8, 8)), buf.getBytes(address + 16, (int) infoSize));
    }

    private static String identifier(byte[] name) {
        int length = 0;
        while (length < name.length && name[length] != 0) {
            length++;
        }
        return new String(name, 0, length, StandardCharsets.US_ASCII);
    }
}
