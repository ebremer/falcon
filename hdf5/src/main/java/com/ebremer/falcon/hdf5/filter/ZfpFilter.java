package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.zfp.ZfpDecoder;
import com.ebremer.falcon.core.compress.zfp.ZfpEncoder;
import com.ebremer.falcon.core.compress.zfp.ZfpHeader;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;

/**
 * The ZFP filter (32013, LLNL's H5Z-ZFP, {@code H5Zzfp.c} 1.1.1), decoded and encoded with Falcon Core's zfp
 * decoder and encoder, every stream libzfp 1.0.1's (built, as hdf5plugin builds it, with 8-bit stream words).
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

    /** The name H5Z-ZFP 1.1.1 registers, with zfp 1.0.1, which libhdf5 stores for the filter. */
    static final String NAME = "H5Z-ZFP-1.1.1 (ZFP-1.0.1)";

    /** Client data 0 as H5Z-ZFP 1.1.1 writes it with zfp 1.0.1: zfp's version, its codec, H5Z-ZFP's version. */
    static final int VERSION = (0x1010 << 16) | (ZfpHeader.CODEC << 12) | 0x111;

    /** H5Z-ZFP's modes, the first of the values hdf5plugin passes ({@code H5Pset_zfp_*_cdata}). */
    static final int MODE_RATE = 1;
    static final int MODE_PRECISION = 2;
    static final int MODE_ACCURACY = 3;
    static final int MODE_EXPERT = 4;
    static final int MODE_REVERSIBLE = 5;

    private ZfpFilter() {
    }

    /**
     * The client data H5Z-ZFP's {@code set_local} stores for a dataset: its {@link #VERSION}, then the full zfp
     * header written into the following values, little-endian. The field is a chunk without its dimensions of
     * size 1, its last HDF5 dimension zfp's {@code x}; the mode comes from {@code options}, the values
     * hdf5plugin passes: {@code {1, 0, rate}} (the rate set knowing the scalar type), {@code {2, 0, precision}},
     * {@code {3, 0, tolerance}} (each double as two values, low first), {@code {4, 0, minbits, maxbits,
     * maxprec, minexp}}, or {@code {5, 0}} (reversible).
     *
     * @param floating   whether the elements are floating point (else integers)
     * @param size       the element size, 4 or 8
     * @param chunkShape the chunk's shape, in HDF5's order
     * @param options    the mode and its parameters
     * @return the client data
     * @throws IllegalArgumentException if H5Z-ZFP would refuse the dataset (elements other than 4- or 8-byte
     *                                  integers and floats, or a chunk of other than 1 to 4 dimensions longer
     *                                  than 1) or the mode
     */
    static int[] clientData(boolean floating, int size, long[] chunkShape, int[] options) {
        if (size != 4 && size != 8) {
            throw new IllegalArgumentException("zfp compresses 4- and 8-byte integers and floats, not "
                    + size + "-byte elements");
        }
        ZfpHeader.Type type = floating ? (size == 4 ? ZfpHeader.Type.FLOAT : ZfpHeader.Type.DOUBLE)
                : (size == 4 ? ZfpHeader.Type.INT32 : ZfpHeader.Type.INT64);
        long[] used = java.util.Arrays.stream(chunkShape).filter(n -> n > 1).toArray();
        if (used.length < 1 || used.length > 4) {
            throw new IllegalArgumentException("zfp needs a chunk of 1 to 4 dimensions longer than 1, not "
                    + java.util.Arrays.toString(chunkShape));
        }
        long[] n = new long[4];
        for (int i = 0; i < used.length; i++) {
            n[i] = used[used.length - 1 - i];
        }
        ZfpHeader field = ZfpHeader.of(type, n[0], n[1], n[2], n[3]);
        ZfpHeader header = switch (options[0]) {
            case MODE_RATE -> field.withRate(real(options), true);
            case MODE_PRECISION -> field.withPrecision((int) Math.min(options[2] & 0xffffffffL, 64));
            case MODE_ACCURACY -> field.withAccuracy(real(options));
            case MODE_EXPERT -> field.withParameters(options[2], options[3], options[4], options[5]);
            case MODE_REVERSIBLE -> field.withReversible();
            default -> throw new IllegalArgumentException("invalid zfp mode " + options[0]);
        };
        byte[] bytes = ZfpEncoder.header(header, 8);
        int[] cd = new int[1 + (bytes.length + 3) / 4];
        cd[0] = VERSION;
        for (int i = 0; i < bytes.length; i++) {
            cd[1 + i / 4] |= (bytes[i] & 0xff) << (8 * (i % 4));
        }
        return cd;
    }

    /** A double hdf5plugin passes as client data values 2 and 3, low first. */
    private static double real(int[] options) {
        return Double.longBitsToDouble((options[2] & 0xffffffffL) | (long) options[3] << 32);
    }

    /**
     * Encodes one chunk as H5Z-ZFP does: {@code zfp_compress} of the field and mode its client data's header
     * holds, in 8-bit words.
     *
     * @throws HdfUnsupportedException if the header was written on a big-endian machine, or by a later zfp
     */
    static byte[] encode(int[] clientData, byte[] data) {
        ZfpHeader h = writableHeader(clientData);
        if (data.length != h.elements() * h.type().size()) {
            throw new HdfFormatException("zfp filter: a chunk of " + data.length + " bytes, but the zfp header"
                    + " describes " + h.elements() + " " + h.type().size() + "-byte values");
        }
        return ZfpEncoder.compress(h, data, 0, 8);
    }

    /** The header a dataset's client data hold, for encoding: little-endian, of a codec Falcon writes. */
    private static ZfpHeader writableHeader(int[] clientData) {
        try {
            if (clientData.length < 2) {
                throw new HdfFormatException("zfp filter: " + clientData.length
                        + " client-data values cannot hold its version and zfp header");
            }
            if (writerCodec(clientData[0]) != ZfpHeader.CODEC) {
                throw new HdfUnsupportedException("zfp filter: Falcon writes data of zfp codec version "
                        + ZfpHeader.CODEC + " only, not " + writerCodec(clientData[0]));
            }
            byte[] header = headerBytes(clientData, false);
            if (!hasMagic(header)) {
                if (hasMagic(headerBytes(clientData, true))) {
                    throw new HdfUnsupportedException("zfp filter: the dataset's zfp header was written on a"
                            + " big-endian machine; Falcon writes into little-endian ones only");
                }
                throw new HdfFormatException("zfp filter: the client data hold no zfp header");
            }
            return ZfpHeader.read(header, 0, header.length);
        } catch (CompressionFormatException e) {
            throw new HdfFormatException("zfp filter: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new HdfUnsupportedException("zfp filter: " + e.getMessage());
        }
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
