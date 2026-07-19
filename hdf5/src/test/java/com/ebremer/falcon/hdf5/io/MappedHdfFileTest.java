package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappedHdfFileTest {

    private static final byte[] HDF5_SIGNATURE = {
        (byte) 0x89, (byte) 0x48, (byte) 0x44, (byte) 0x46,
        (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a
    };

    @Test
    void mapsFileAndReadsArbitraryOffsetsAndWidths(@TempDir Path tmp) throws IOException {
        byte[] data = new byte[32];
        System.arraycopy(HDF5_SIGNATURE, 0, data, 0, 8);
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(8, 0x11223344);
        bb.putLong(12, 0x0102030405060708L);
        bb.putShort(20, (short) 0xBEEF);

        Path file = tmp.resolve("probe.h5");
        Files.write(file, data);

        try (MappedHdfFile mapped = MappedHdfFile.openReadOnly(file)) {
            assertEquals(32, mapped.size());
            assertEquals(file, mapped.path());

            HdfBuffer b = mapped.buffer();
            assertTrue(b.hasSignature(0, HDF5_SIGNATURE), "HDF5 signature should be recognized");
            assertEquals(0x11223344, b.getInt(8));
            assertEquals(0x0102030405060708L, b.getLong(12));
            assertEquals(0xBEEF, b.getUnsignedShort(20));
        }
    }
}
