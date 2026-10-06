package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.zfp.ZfpDecoder;
import com.ebremer.falcon.core.compress.zfp.ZfpHeader;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;

/**
 * The ZFP filter (32013, LLNL's H5Z-ZFP, {@code H5Zzfp.c} 1.1.1), decoded with Falcon Core's zfp decoder.
 *
 * <p>H5Z-ZFP keeps the zfp header in the client data and stores each chunk as the bare zfp stream:
 * <ul>
 *   <li>client data 0 is version information: the zfp library version (bits 16 to 31), the zfp codec
 *       version (bits 12 to 15; from H5Z-ZFP 1.1.0) and H5Z-ZFP's own (bits 0 to 11);</li>
 *   <li>client data 1 onward hold the full zfp header ({@code zfp_write_header}) as a bit stream of bytes, in
 *       the writer's memory order: the field is the chunk without its dimensions of size 1, its last HDF5
 *       dimension zfp's fastest ({@code x}); then the mode.</li>
 * </ul>
 * A header that does not read in little-endian order was written on a big-endian machine: H5Z-ZFP then
 * reads it byte-swapped, and byte-swaps the decoded values back to the (big-endian) dataset's order.
 */
final class ZfpFilter {

    static final int ID = 32013;

    private ZfpFilter() {
    }

    /**
     * Decodes one chunk.
     *
     * @param maxBytes the most bytes the chunk may decode to
     */
    static byte[] decode(int[] clientData, byte[] data, long maxBytes) {
        try {
            if (clientData.length < 2) {
                throw new HdfFormatException("zfp filter: " + clientData.length
                        + " client-data values cannot hold its version and zfp header");
            }
            int codec = writerCodec(clientData[0]);
            if (codec > ZfpHeader.CODEC) {
                throw new HdfUnsupportedException("zfp filter: data of zfp codec version " + codec
                        + " is not supported, only up to " + ZfpHeader.CODEC);
            }
            boolean swap = false;
            byte[] header = headerBytes(clientData, false);
            if (!hasMagic(header)) {
                header = headerBytes(clientData, true);
                swap = true;
                if (!hasMagic(header)) {
                    throw new HdfFormatException("zfp filter: the client data hold no zfp header");
                }
            }
            ZfpHeader h = ZfpHeader.read(header, 0, header.length);
            byte[] out = ZfpDecoder.decompress(h, data, 0, data.length, maxBytes);
            if (swap) {
                swapBytes(out, h.type().size());
            }
            return out;
        } catch (CompressionFormatException e) {
            throw new HdfFormatException("zfp filter: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new HdfUnsupportedException("zfp filter: " + e.getMessage());
        }
    }

    /**
     * The zfp codec version the data was written with ({@code zfp_codec_version_mismatch}): recorded since
     * H5Z-ZFP 1.1.0, inferred from the zfp library version before.
     */
    private static int writerCodec(int version) {
        int h5zZfp = version & 0xfff;
        int codec = (version >>> 12) & 0xf;
        int zfp = (version >>> 16) & 0xffff;
        if (h5zZfp >= 0x0110) {
            return codec;
        }
        zfp <<= 4;
        if (zfp < 0x0500) {
            return 4;
        }
        return zfp < 0x1000 ? (zfp & 0x0f00) >> 8 : 5;
    }

    /** Client data 1 onward as bytes: each value little-endian, or big-endian ({@code swap}). */
    private static byte[] headerBytes(int[] clientData, boolean swap) {
        byte[] out = new byte[4 * (clientData.length - 1)];
        for (int i = 1; i < clientData.length; i++) {
            int v = swap ? Integer.reverseBytes(clientData[i]) : clientData[i];
            for (int b = 0; b < 4; b++) {
                out[4 * (i - 1) + b] = (byte) (v >>> (8 * b));
            }
        }
        return out;
    }

    private static boolean hasMagic(byte[] header) {
        return header.length >= 3 && header[0] == 'z' && header[1] == 'f' && header[2] == 'p';
    }

    private static void swapBytes(byte[] data, int size) {
        for (int at = 0; at + size <= data.length; at += size) {
            for (int i = 0, j = size - 1; i < j; i++, j--) {
                byte t = data[at + i];
                data[at + i] = data[at + j];
                data[at + j] = t;
            }
        }
    }
}
