package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** The pyramid's kernel, for each method and data type, with partial blocks at the edges. */
class DownsamplerTest {

    static byte[] shorts(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            b.putShort((short) v);
        }
        return b.array();
    }

    static int[] toShorts(byte[] bytes, boolean signed) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] values = new int[bytes.length / 2];
        for (int i = 0; i < values.length; i++) {
            short s = b.getShort();
            values[i] = signed ? s : s & 0xFFFF;
        }
        return values;
    }

    @Test
    void meanRoundsHalfUpAndAveragesPartialBlocksOverTheirPixels() {
        // 3 x 3 uint16: blocks of 2x2, 2x1, 1x2, 1x1
        byte[] in = shorts(1, 2, 9,
                           3, 5, 10,
                           7, 8, 65535);
        byte[] out = Downsampler.downsample(in, new long[] {3, 3}, new long[] {2, 2}, new int[] {2, 2},
                DataType.UINT16, Downsampling.MEAN);
        // (1+2+3+5)/4 = 2.75 -> 3; (9+10)/2 = 9.5 -> 10; (7+8)/2 = 7.5 -> 8; 65535
        assertArrayEquals(new int[] {3, 10, 8, 65535}, toShorts(out, false));
    }

    @Test
    void signedMeansRoundTowardsPositive() {
        byte[] in = shorts(-1, -2, -3, -4);
        byte[] out = Downsampler.downsample(in, new long[] {1, 4}, new long[] {1, 2}, new int[] {1, 2},
                DataType.INT16, Downsampling.MEAN);
        assertArrayEquals(new int[] {-1, -3}, toShorts(out, true)); // -1.5 -> -1, -3.5 -> -3
    }

    @Test
    void nearestTakesTheFirstPixelAndModeTheMostFrequent() {
        byte[] in = {1, 2, 5, 5,
                     2, 2, 5, 7};
        long[] shape = {2, 4};
        assertArrayEquals(new byte[] {1, 5}, Downsampler.downsample(in, shape, new long[] {1, 2}, new int[] {2, 2},
                DataType.UINT8, Downsampling.NEAREST));
        assertArrayEquals(new byte[] {2, 5}, Downsampler.downsample(in, shape, new long[] {1, 2}, new int[] {2, 2},
                DataType.UINT8, Downsampling.MODE));
        // a tie: the first of the most frequent
        assertArrayEquals(new byte[] {3}, Downsampler.downsample(new byte[] {3, 4, 4, 3}, new long[] {2, 2},
                new long[] {1, 1}, new int[] {2, 2}, DataType.UINT8, Downsampling.MODE));
    }

    @Test
    void floatsBooleansAndWideIntegers() {
        ByteBuffer f = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(1).putFloat(2).putFloat(4)
                .putFloat(Float.NaN);
        byte[] fo = Downsampler.downsample(f.array(), new long[] {4}, new long[] {2}, new int[] {2},
                DataType.FLOAT32, Downsampling.MEAN);
        ByteBuffer fr = ByteBuffer.wrap(fo).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.5f, fr.getFloat());
        assertEquals(Float.NaN, fr.getFloat());

        ByteBuffer h = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(Float.floatToFloat16(1))
                .putShort(Float.floatToFloat16(2));
        byte[] ho = Downsampler.downsample(h.array(), new long[] {2}, new long[] {1}, new int[] {2},
                DataType.FLOAT16, Downsampling.MEAN);
        assertEquals(1.5f, Float.float16ToFloat(ByteBuffer.wrap(ho).order(ByteOrder.LITTLE_ENDIAN).getShort()));

        // booleans: true when at least half the block is
        assertArrayEquals(new byte[] {1, 0, 1}, Downsampler.downsample(new byte[] {1, 1, 0, 1, 0, 0, 0, 1},
                new long[] {8}, new long[] {3}, new int[] {3}, DataType.BOOL, Downsampling.MEAN));
        ByteBuffer u = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(-1L).putLong(-3L); // 2^64-1, 2^64-3
        byte[] uo = Downsampler.downsample(u.array(), new long[] {2}, new long[] {1}, new int[] {2},
                DataType.UINT64, Downsampling.MEAN);
        assertEquals(-2L, ByteBuffer.wrap(uo).order(ByteOrder.LITTLE_ENDIAN).getLong()); // 2^64-2
        byte[] so = Downsampler.downsample(u.array(), new long[] {2}, new long[] {1}, new int[] {2},
                DataType.INT64, Downsampling.MEAN);
        assertEquals(-2L, ByteBuffer.wrap(so).order(ByteOrder.LITTLE_ENDIAN).getLong());
    }

    @Test
    void anInputLongerThanTheOutputNeedsIsCutShort() {
        // ome-zarr-py's levels are floor-sized: 5 -> 2, the last pixel unused
        byte[] out = Downsampler.downsample(new byte[] {1, 3, 5, 7, 100}, new long[] {5}, new long[] {2},
                new int[] {2}, DataType.UINT8, Downsampling.MEAN);
        assertArrayEquals(new byte[] {2, 6}, out);
    }
}
