package com.ebremer.falcon.hdf5.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the szip filter end to end against chunks in the exact form libhdf5 + libaec store them
 * (produced by libaec's SZ compatibility layer: a 4-byte size header, byte-interleaved 32/64-bit
 * pixels, padded scanlines, EC and NN coding, both byte orders). Whole datasets of such chunks are
 * covered by {@code SzipDatasetTest}.
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
            byte[] actual = pipeline.decode(encoded, 0, Math.max(1, elementSize), expected.length);

            assertArrayEquals(expected, actual,
                    "szip chunk mask=" + mask + " ppb=" + pixelsPerBlock + " bpp=" + bitsPerPixel);
            count++;
        }
        assertTrue(count >= 8, "expected several szip chunk vectors, parsed " + count);
    }
}
