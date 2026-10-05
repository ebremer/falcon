package com.ebremer.falcon.hdf5.checksum;

/**
 * The Fletcher-32 checksum used by HDF5's {@code fletcher32} filter (filter id 3), a transcription of
 * {@code H5_checksum_fletcher32}: big-endian 16-bit words summed in batches of 360, with an odd final
 * byte treated as the high byte of a last word. The filter stores the result little-endian after the
 * chunk data.
 */
public final class Fletcher32 {

    private Fletcher32() {
    }

    /** The checksum of the first {@code length} bytes of {@code data}. */
    public static int checksum(byte[] data, int length) {
        long sum1 = 0;
        long sum2 = 0;
        int words = length / 2;
        int i = 0;
        while (words > 0) {
            int batch = Math.min(words, 360);
            words -= batch;
            do {
                sum1 += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
                sum2 += sum1;
                i += 2;
            } while (--batch > 0);
            sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
            sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        }
        if ((length & 1) != 0) {
            sum1 += (data[i] & 0xff) << 8;
            sum2 += sum1;
            sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
            sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        }
        sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
        sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        return (int) ((sum2 << 16) | sum1);
    }

    /**
     * The checksum with the bytes of each 16-bit half swapped: the value libhdf5 releases before 1.6.3
     * stored on little-endian machines, which readers still accept alongside the correct one.
     */
    public static int legacyByteSwapped(int checksum) {
        return ((checksum & 0x00ff00ff) << 8) | ((checksum >>> 8) & 0x00ff00ff);
    }
}
