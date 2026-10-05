package com.ebremer.falcon.hdf5.superblock;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * The HDF5 superblock (spec section II.A): the file's entry point. It carries the address/length
 * widths and locates the root group's object header.
 *
 * <ul>
 *   <li><b>Versions 0 and 1</b> reach the root group through a symbol-table entry; version 1 adds a
 *       4-byte "indexed storage internal node K" + reserved pair before the addresses.</li>
 *   <li><b>Versions 2 and 3</b> store the root group's object-header address directly and end with a
 *       Jenkins lookup3 checksum, which this parser verifies.</li>
 * </ul>
 *
 * <p>The superblock may sit at file offset 0, 512, 1024, 2048, … (after an optional user block).
 */
public final class Superblock {

    /** The eight magic bytes that begin every HDF5 file. */
    public static final byte[] SIGNATURE = {
        (byte) 0x89, (byte) 0x48, (byte) 0x44, (byte) 0x46,
        (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a
    };

    private final long location;
    private final int version;
    private final int sizeOfOffsets;
    private final int sizeOfLengths;
    private final long baseAddress;
    private final long superblockExtensionAddress;
    private final long endOfFileAddress;
    private final long rootObjectHeaderAddress;

    private Superblock(long location, int version, int sizeOfOffsets, int sizeOfLengths, long baseAddress,
                       long superblockExtensionAddress, long endOfFileAddress, long rootObjectHeaderAddress) {
        this.location = location;
        this.version = version;
        this.sizeOfOffsets = sizeOfOffsets;
        this.sizeOfLengths = sizeOfLengths;
        this.baseAddress = baseAddress;
        this.superblockExtensionAddress = superblockExtensionAddress;
        this.endOfFileAddress = endOfFileAddress;
        this.rootObjectHeaderAddress = rootObjectHeaderAddress;
    }

    /**
     * The absolute file offset at which the superblock was found (0, 512, 1024, ...). libhdf5 treats this
     * as the base address that every other file address is relative to, whatever the stored
     * {@link #baseAddress()} says, so a file with a prepended user block stays readable.
     */
    public long location() {
        return location;
    }

    public int version() {
        return version;
    }

    public int sizeOfOffsets() {
        return sizeOfOffsets;
    }

    public int sizeOfLengths() {
        return sizeOfLengths;
    }

    public long baseAddress() {
        return baseAddress;
    }

    /**
     * Address of the superblock extension object header (versions 2–3), or
     * {@link HdfBuffer#UNDEFINED_ADDRESS} if the file has none. The extension carries file-level
     * metadata messages (File Space Info, B-tree K Values, Driver Info, Shared Message Table).
     */
    public long superblockExtensionAddress() {
        return superblockExtensionAddress;
    }

    public long endOfFileAddress() {
        return endOfFileAddress;
    }

    public long rootObjectHeaderAddress() {
        return rootObjectHeaderAddress;
    }

    /** Locates and parses the superblock of a mapped HDF5 file. */
    public static Superblock parse(HdfBuffer buf) {
        long addr = findSuperblock(buf);
        int version = buf.getUnsignedByte(addr + 8);
        return switch (version) {
            case 0, 1 -> parseOriginal(buf, addr, version);
            case 2, 3 -> parseChecksummed(buf, addr, version);
            default -> throw new HdfFormatException("unsupported superblock version " + version);
        };
    }

    private static long findSuperblock(HdfBuffer buf) {
        long size = buf.size();
        long addr = 0;
        while (addr + SIGNATURE.length <= size) {
            if (buf.hasSignature(addr, SIGNATURE)) {
                return addr;
            }
            addr = (addr == 0) ? 512 : addr * 2;
        }
        throw new HdfFormatException("HDF5 superblock signature not found");
    }

    private static Superblock parseOriginal(HdfBuffer buf, long addr, int version) {
        int sizeOfOffsets = buf.getUnsignedByte(addr + 13);
        int sizeOfLengths = buf.getUnsignedByte(addr + 14);
        // Version 1 inserts "Indexed Storage Internal Node K" (2) + reserved (2) before the addresses.
        long addressesStart = addr + (version == 1 ? 28 : 24);
        long baseAddress = buf.getAddress(addressesStart, sizeOfOffsets);
        long endOfFile = buf.getAddress(addressesStart + 2L * sizeOfOffsets, sizeOfOffsets);
        // Root group symbol-table entry: link-name offset (O), then object-header address (O).
        long rootEntry = addressesStart + 4L * sizeOfOffsets;
        long rootObjectHeader = buf.getAddress(rootEntry + sizeOfOffsets, sizeOfOffsets);
        // Versions 0–1 have no superblock extension.
        return new Superblock(addr, version, sizeOfOffsets, sizeOfLengths, baseAddress,
                HdfBuffer.UNDEFINED_ADDRESS, endOfFile, rootObjectHeader);
    }

    private static Superblock parseChecksummed(HdfBuffer buf, long addr, int version) {
        int sizeOfOffsets = buf.getUnsignedByte(addr + 9);
        int sizeOfLengths = buf.getUnsignedByte(addr + 10);
        // Addresses in order: base, superblock extension, end-of-file, root group object header.
        long baseAddress = buf.getAddress(addr + 12, sizeOfOffsets);
        long extension = buf.getAddress(addr + 12 + sizeOfOffsets, sizeOfOffsets);
        long endOfFile = buf.getAddress(addr + 12 + 2L * sizeOfOffsets, sizeOfOffsets);
        long rootObjectHeader = buf.getAddress(addr + 12 + 3L * sizeOfOffsets, sizeOfOffsets);
        long checksumOffset = addr + 12 + 4L * sizeOfOffsets;
        int stored = buf.getInt(checksumOffset);
        int computed = Lookup3.hashLittle(buf.segment(), addr, checksumOffset - addr, 0);
        if (stored != computed) {
            throw new HdfFormatException(String.format(
                    "superblock checksum mismatch: stored=0x%08x computed=0x%08x", stored, computed));
        }
        return new Superblock(addr, version, sizeOfOffsets, sizeOfLengths, baseAddress,
                extension, endOfFile, rootObjectHeader);
    }
}
