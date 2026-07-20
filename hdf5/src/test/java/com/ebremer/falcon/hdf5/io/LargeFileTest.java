package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Foreign Function &amp; Memory backend maps and reads files larger than 2&nbsp;GB &mdash; the
 * reason Falcon uses {@code MemorySegment} rather than {@code MappedByteBuffer} (whose offsets are
 * {@code int}-bounded). A sparse file is written with a marker past the 2&nbsp;GB boundary and read
 * back through the mapping.
 */
class LargeFileTest {

    @Test
    @Timeout(120)
    void readsBeyondTwoGigabytes() throws IOException {
        long offset = 0x8400_0000L; // ~2.06 GB, comfortably past Integer.MAX_VALUE (0x7FFFFFFF)
        byte[] marker = {1, 2, 3, 4, 5, 6, 7, 8};
        Path file = Files.createTempFile("falcon-large", ".h5");
        try {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(marker), offset); // extends the file; the gap is a sparse hole
            }
            assumeTrue(Files.size(file) > Integer.MAX_VALUE, "environment could not create a >2GB sparse file");

            try (MappedHdfFile mapped = MappedHdfFile.openReadOnly(file)) {
                HdfBuffer buffer = mapped.buffer();
                assertTrue(buffer.size() > Integer.MAX_VALUE, "mapped size should exceed 2GB");
                assertArrayEquals(marker, buffer.getBytes(offset, marker.length)); // read past the 2GB boundary
                assertEquals(0, buffer.getUnsignedByte(offset - 1)); // the sparse hole reads as zero
                assertEquals(0x0807060504030201L, buffer.getLong(offset)); // 8 bytes little-endian at a >2GB offset
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
