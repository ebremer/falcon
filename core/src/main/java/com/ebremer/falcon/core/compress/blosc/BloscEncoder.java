package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;

/**
 * A pure-Java Blosc encoder producing buffers that c-blosc (numcodecs, zarr-python) and
 * {@link BloscDecoder} both read. It writes the c-blosc format-version-2 container: a 16-byte header, a
 * block offset table, and per-block payloads.
 *
 * <p>It applies the byte shuffle (when the type is multi-byte) and compresses each block with Falcon's
 * own {@link ZstdEncoder} as the internal codec &mdash; both already validated against their references.
 * A block that would not shrink, or a whole buffer that compression does not help, is stored raw
 * (the {@code memcpy} path). Bit-shuffle encoding and the other internal codecs are not offered on the
 * write side; reading still supports them.
 */
public final class BloscEncoder {

    private static final int HEADER = 16;
    private static final int VERSION = 2;
    private static final int VERSION_LZ = 1;
    private static final int COMPRESSOR_ZSTD = 4;
    private static final int FLAG_SHUFFLE = 0x01;
    private static final int FLAG_MEMCPYED = 0x02;
    // Bit 4 tells c-blosc a block is not split into type-size streams. zstd never splits, so it must be
    // set for c-blosc to parse the single stream (Falcon's own decoder infers this, but c-blosc reads it).
    private static final int FLAG_DONT_SPLIT = 0x10;

    private BloscEncoder() {
    }

    /** Compresses {@code data} whose elements are {@code typeSize} bytes into a Blosc buffer. */
    public static byte[] compress(byte[] data, int typeSize) {
        int nbytes = data.length;
        if (nbytes == 0) {
            return memcpy(data, Math.max(typeSize, 1));
        }
        int ts = Math.max(typeSize, 1);
        boolean shuffle = ts > 1;

        byte[] blockData = data;
        if (shuffle) {
            blockData = new byte[nbytes];
            Shuffle.shuffle(data, 0, blockData, 0, nbytes, ts);
        }
        byte[] payload = ZstdEncoder.compress(blockData);

        // One block, one stream. If the payload does not beat storing the block raw, fall back to memcpy.
        if (payload.length >= nbytes) {
            return memcpy(data, ts);
        }

        int cbytes = HEADER + 4 + 4 + payload.length;
        byte[] out = new byte[cbytes];
        int flags = (shuffle ? FLAG_SHUFFLE : 0) | FLAG_DONT_SPLIT | (COMPRESSOR_ZSTD << 5);
        writeHeader(out, flags, ts, nbytes, nbytes, cbytes);
        putLe32(out, HEADER, HEADER + 4);        // block 0 begins right after the 1-entry offset table
        putLe32(out, HEADER + 4, payload.length); // stream length (< block size, so read as compressed)
        System.arraycopy(payload, 0, out, HEADER + 8, payload.length);
        return out;
    }

    /** Stores {@code data} verbatim (the c-blosc memcpy path): a header with the memcpy flag, then bytes. */
    private static byte[] memcpy(byte[] data, int typeSize) {
        int nbytes = data.length;
        byte[] out = new byte[HEADER + nbytes];
        writeHeader(out, FLAG_MEMCPYED, typeSize, nbytes, nbytes, HEADER + nbytes);
        System.arraycopy(data, 0, out, HEADER, nbytes);
        return out;
    }

    private static void writeHeader(byte[] out, int flags, int typeSize, int nbytes, int blocksize,
                                    int cbytes) {
        out[0] = (byte) VERSION;
        out[1] = (byte) VERSION_LZ;
        out[2] = (byte) flags;
        out[3] = (byte) typeSize;
        putLe32(out, 4, nbytes);
        putLe32(out, 8, blocksize);
        putLe32(out, 12, cbytes);
    }

    private static void putLe32(byte[] out, int off, int value) {
        out[off] = (byte) value;
        out[off + 1] = (byte) (value >>> 8);
        out[off + 2] = (byte) (value >>> 16);
        out[off + 3] = (byte) (value >>> 24);
    }
}
