package com.ebremer.falcon.hdf5.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the szip filter end to end: an szip-parameterized AEC stream (produced by libaec with
 * {@code SZ_LSB | SZ_NN}, simulating a little-endian HDF5 szip chunk) decodes back to the original
 * chunk bytes.
 */
class SzipFilterTest {

    @Test
    void decodesSzipChunksAgainstLibaec() throws IOException {
        int count = 0;
        for (String line : AecTest.lines("/fixtures/szip_chunks.txt")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split(" ");
            int mask = Integer.parseInt(p[0]);
            int pixelsPerBlock = Integer.parseInt(p[1]);
            int bitsPerPixel = Integer.parseInt(p[2]);
            int pixelsPerScanline = Integer.parseInt(p[3]);
            byte[] encoded = AecTest.hex(p[4]);
            byte[] expected = AecTest.hex(p[5]);
            int elementSize = bitsPerPixel / 8;

            FilterPipeline pipeline = new FilterPipeline(List.of(new FilterPipeline.Filter(
                    Filters.SZIP, 0, new int[] {mask, pixelsPerBlock, bitsPerPixel, pixelsPerScanline})));
            byte[] actual = pipeline.decode(encoded, 0, elementSize, expected.length);

            assertArrayEquals(expected, actual,
                    "szip chunk mask=" + mask + " ppb=" + pixelsPerBlock + " bpp=" + bitsPerPixel);
            count++;
        }
        assertTrue(count >= 4, "expected several szip chunk vectors, parsed " + count);
    }
}
