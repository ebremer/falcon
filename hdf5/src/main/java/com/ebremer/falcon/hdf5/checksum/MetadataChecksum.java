package com.ebremer.falcon.hdf5.checksum;

import com.ebremer.falcon.core.checksum.Lookup3;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;

/**
 * Verifies the Jenkins lookup3 checksum that ends every checksummed HDF5 metadata structure (object
 * headers and their continuation chunks, version-2 B-tree nodes, fractal-heap blocks, and the
 * fixed/extensible array blocks of the chunk indexes): a little-endian 32-bit value stored immediately
 * after the bytes it covers. A mismatch means the structure is corrupt, so it is reported rather than
 * parsed &mdash; libhdf5 refuses such metadata the same way ("incorrect metadata checksum").
 */
public final class MetadataChecksum {

    private MetadataChecksum() {
    }

    /**
     * Checks that the 4 bytes at {@code start + length} hold the lookup3 hash of the {@code length}
     * bytes at {@code start}.
     *
     * @param structure a short name for the structure (e.g. {@code "OHDR"}), used in the error message
     * @throws HdfFormatException if the range is out of bounds or the checksum does not match
     */
    public static void verify(HdfBuffer buf, long start, long length, String structure) {
        MemorySegment bytes = buf.segmentSlice(start, length + 4); // bounds-checked (throws HdfFormatException)
        int stored = buf.getInt(start + length);
        int computed = Lookup3.hashLittle(bytes, 0, length, 0);
        if (stored != computed) {
            throw new HdfFormatException(String.format(
                    "%s checksum mismatch at %d: stored=0x%08x computed=0x%08x", structure, start, stored, computed));
        }
    }
}
