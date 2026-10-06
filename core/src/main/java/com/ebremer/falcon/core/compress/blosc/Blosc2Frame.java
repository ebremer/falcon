package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads a c-blosc2 <em>contiguous frame</em> (cframe) held in memory: a super-chunk's header, its chunks, an
 * index chunk of their offsets, and a trailer. The layout is c-blosc2's {@code README_CFRAME_FORMAT.rst}; the
 * checks are those c-blosc2 3.3.2 makes when it opens a frame from a buffer ({@code frame.c}:
 * {@code frame_from_cframe}, {@code get_header_info}, {@code get_coffsets}, {@code validate_offsets_chunk},
 * {@code get_meta_from_header}, {@code get_vlmeta_from_trailer}) and reads a chunk of it
 * ({@code frame_get_lazychunk}, {@code get_coffset}, {@code frame_special_chunk}).
 *
 * <pre>
 *   header    msgpack: "b2frame\0", header_len (int32), frame_len (uint64), flags, nbytes and cbytes
 *             (int64), typesize, blocksize, chunksize (int32), ..., then the metalayers: a map of
 *             name &rarr; offset, each offset (from the frame's start) holding a bin32 of the content
 *   chunks    Blosc chunks back to back, then the index chunk: a Blosc chunk of nchunks int64 offsets,
 *             each from the end of the header, or with its top byte's bit 7 set, a special chunk
 *             (bit 0: zeros, bit 2: uninitialised, bit 1: NaN, tested in that order)
 *   trailer   msgpack: version, the variable-length metalayers (their contents compressed), trailer_len
 *             (uint32, 23 bytes from the end), fingerprint
 * </pre>
 *
 * <p>Only what decoding needs is kept: the type size, the chunk offsets, and the metalayers (as
 * {@code b2nd}'s shape lives in one, see {@link B2ndArray}). The trailer's variable-length metalayers are
 * checked as c-blosc2 checks them, not decompressed.
 *
 * <p>Every size is bounded before anything is allocated: a chunk decodes to at most the frame's
 * {@code maxBytes}, and a frame declaring more chunks than that many bytes is refused.
 */
public final class Blosc2Frame {

    // Positions in the header (c-blosc2's frame.h).
    private static final int FRAME_HEADER_LEN = 11;
    private static final int FRAME_LEN = 16;
    private static final int FRAME_FLAGS = 25;
    private static final int FRAME_TYPE = 26;
    private static final int FRAME_NBYTES = 30;
    private static final int FRAME_CBYTES = 39;
    private static final int FRAME_TYPESIZE = 48;
    private static final int FRAME_CHUNKSIZE = 58;
    private static final int FRAME_FILTER_PIPELINE = 70;
    /** The shortest header ({@code FRAME_HEADER_MINLEN}). */
    private static final int FRAME_HEADER_MINLEN = 87;
    private static final int FRAME_IDX_SIZE = 89;
    private static final int FRAME_TRAILER_MINLEN = 25;
    /** Where the trailer's length sits, counted back from the frame's end. */
    private static final int FRAME_TRAILER_LEN_OFFSET = 22;
    private static final int FRAME_TRAILER_VLMETALAYERS = 2;

    private static final int FRAME_CONTIGUOUS_TYPE = 0;
    /** The newest frame format c-blosc2 3.3 reads ({@code BLOSC2_VERSION_FRAME_FORMAT}). */
    private static final int VERSION_FRAME_FORMAT = 3;
    private static final int MAX_FILTERS = 6;
    private static final int MAX_METALAYERS = 16;
    private static final int MAX_VLMETALAYERS = 8 * 1024;
    /** A chunk's shortest length in a frame: c-blosc2's extended header. */
    private static final int EXTENDED_HEADER_LENGTH = 32;
    private static final byte[] MAGIC = "b2frame\0".getBytes(StandardCharsets.US_ASCII);

    // Special values in an offset's top byte (BLOSC2_SPECIAL_*), with bit 7 marking one.
    private static final long SPECIAL_ZERO = 1L << 56;
    private static final long SPECIAL_NAN = 2L << 56;
    private static final long SPECIAL_UNINIT = 4L << 56;

    private final byte[] src;
    private final int headerLen;
    private final long nbytes;
    private final long cbytes;
    private final int typeSize;
    private final int chunkSize;
    private final long[] offsets;
    private final Map<String, byte[]> metalayers;
    private final int maxBytes;

    private Blosc2Frame(byte[] src, int headerLen, long nbytes, long cbytes, int typeSize, int chunkSize,
                        long[] offsets, Map<String, byte[]> metalayers, int maxBytes) {
        this.src = src;
        this.headerLen = headerLen;
        this.nbytes = nbytes;
        this.cbytes = cbytes;
        this.typeSize = typeSize;
        this.chunkSize = chunkSize;
        this.offsets = offsets;
        this.metalayers = metalayers;
        this.maxBytes = maxBytes;
    }

    /**
     * Opens a frame that fills {@code src}, checking its header, metalayers, chunk offsets, and trailer.
     *
     * @param src      the frame; its length must be the one the frame records
     * @param maxBytes the most bytes a chunk (or a {@link B2ndArray}) may decode to
     * @return the frame
     * @throws CompressionFormatException     if the frame is malformed, or declares more chunks than
     *                                        {@code maxBytes}
     * @throws UnsupportedCompressionException if it is a newer frame format, or an index chunk uses a codec
     *                                        Falcon does not decode
     */
    public static Blosc2Frame read(byte[] src, int maxBytes) {
        int len = src.length;
        // frame_from_cframe
        if (len < FRAME_HEADER_MINLEN) {
            throw new CompressionFormatException("Blosc2 frame of " + len + " bytes is shorter than its "
                    + FRAME_HEADER_MINLEN + "-byte header");
        }
        if (!Arrays.equals(src, 2, 2 + MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new CompressionFormatException("not a Blosc2 frame: no b2frame magic");
        }
        long frameLen = be64(src, FRAME_LEN);
        if (frameLen != len) {
            throw new CompressionFormatException("Blosc2 frame records " + frameLen + " bytes, but is " + len);
        }
        int trailerMarker = len - FRAME_TRAILER_LEN_OFFSET - 1;
        if ((src[trailerMarker] & 0xff) != 0xce) {
            throw new CompressionFormatException("Blosc2 frame has no trailer length");
        }
        long trailerLen = be32(src, len - FRAME_TRAILER_LEN_OFFSET) & 0xffffffffL;
        if (trailerLen < FRAME_TRAILER_MINLEN || trailerLen > len - FRAME_HEADER_MINLEN) {
            throw new CompressionFormatException("Blosc2 frame trailer of " + trailerLen + " bytes");
        }

        // get_header_info
        int frameType = src[FRAME_TYPE] & 0xff;
        int version = src[FRAME_FLAGS] & 0x0f;
        if (version > VERSION_FRAME_FORMAT) {
            throw new UnsupportedCompressionException("Blosc2 frame format version " + version
                    + " is not supported (c-blosc2 3.3 reads up to " + VERSION_FRAME_FORMAT + ")");
        }
        if (frameType != FRAME_CONTIGUOUS_TYPE) {
            throw new CompressionFormatException("Blosc2 frame of type " + frameType + " is not contiguous");
        }
        int headerLen = be32(src, FRAME_HEADER_LEN);
        if (headerLen < FRAME_HEADER_MINLEN || headerLen > len) {
            throw new CompressionFormatException("Blosc2 frame header of " + headerLen + " bytes in a frame of " + len);
        }
        long nbytes = be64(src, FRAME_NBYTES);
        long cbytes = be64(src, FRAME_CBYTES);
        int chunkSize = be32(src, FRAME_CHUNKSIZE);
        int typeSize = be32(src, FRAME_TYPESIZE);
        if (typeSize <= 0) {
            throw new CompressionFormatException("Blosc2 frame type size of " + typeSize);
        }
        if ((src[FRAME_FILTER_PIPELINE] & 0xff) > MAX_FILTERS) {
            throw new CompressionFormatException("Blosc2 frame lists " + (src[FRAME_FILTER_PIPELINE] & 0xff)
                    + " filters, more than " + MAX_FILTERS);
        }
        long nchunks = 0;
        int[] index = null; // the index chunk's position and stored length, once found
        if (nbytes > 0) {
            if (chunkSize > 0) {
                nchunks = nbytes / chunkSize + (nbytes % chunkSize > 0 ? 1 : 0);
                if (cbytes < 0) {
                    throw new CompressionFormatException("Blosc2 frame records " + cbytes + " compressed bytes");
                }
            } else if (chunkSize == 0) {
                index = indexChunk(src, headerLen, cbytes);
                int offsetsBytes = BloscDecoder.le32(src, index[0] + 4);
                if (offsetsBytes < 0 || offsetsBytes % 8 != 0) {
                    throw new CompressionFormatException("Blosc2 frame index of " + offsetsBytes + " bytes");
                }
                nchunks = offsetsBytes / 8;
            } else {
                throw new CompressionFormatException("Blosc2 frame chunk size of " + chunkSize);
            }
        }
        if (nchunks > Integer.MAX_VALUE / 8) {
            throw new CompressionFormatException("Blosc2 frame declares " + nchunks + " chunks");
        }
        if (nchunks > Math.max(maxBytes, 1)) {
            throw new CompressionFormatException("Blosc2 frame declares " + nchunks + " chunks, more than the "
                    + maxBytes + " bytes it may decode to");
        }

        // validate_offsets_chunk: the index decodes to exactly nchunks offsets, each in the chunks section.
        long[] offsets = new long[0];
        if (nchunks > 0) {
            if (index == null) {
                index = indexChunk(src, headerLen, cbytes);
            }
            byte[] decoded = BloscDecoder.decompress(Arrays.copyOfRange(src, index[0], index[0] + index[1]),
                    (int) nchunks * 8);
            if (decoded.length != nchunks * 8) {
                throw new CompressionFormatException("Blosc2 frame index holds " + decoded.length / 8
                        + " offsets for " + nchunks + " chunks");
            }
            offsets = new long[(int) nchunks]; // as many as the index decoded to
            for (int i = 0; i < nchunks; i++) {
                long offset = le64(decoded, 8 * i);
                if (offset >= 0 && offset > cbytes - EXTENDED_HEADER_LENGTH) {
                    throw new CompressionFormatException("Blosc2 frame chunk " + i + " at " + offset
                            + " lies outside its " + cbytes + "-byte chunks section");
                }
                offsets[i] = offset;
            }
        }
        Map<String, byte[]> metalayers = metalayers(src, headerLen);
        checkTrailer(src, headerLen, nbytes > 0, (int) trailerLen);
        Blosc2Frame frame = new Blosc2Frame(src, headerLen, nbytes, cbytes, typeSize, chunkSize, offsets, metalayers,
                maxBytes);
        if (nchunks > 0) {
            frame.locate(0); // frame_to_schunk reads the first chunk's header
        }
        return frame;
    }

    /** The frame's type size, in bytes. */
    public int typeSize() {
        return typeSize;
    }

    /** The number of chunks in the frame. */
    public int chunkCount() {
        return offsets.length;
    }

    /** The most bytes this frame's chunks, or its {@link B2ndArray}, may decode to. */
    int maxBytes() {
        return maxBytes;
    }

    /**
     * A metalayer's content, by name.
     *
     * @param name the metalayer's name, such as {@code "b2nd"}
     * @return a copy of its content, or null if the frame has no such metalayer
     */
    public byte[] metalayer(String name) {
        byte[] content = metalayers.get(name);
        return content == null ? null : content.clone();
    }

    /** The names of the frame's metalayers, in the frame's order. */
    public Set<String> metalayerNames() {
        return Collections.unmodifiableSet(metalayers.keySet());
    }

    /**
     * Decodes one chunk, as c-blosc2's {@code frame_get_lazychunk} finds it and its decompressor decodes it.
     *
     * @param index the chunk's number
     * @return its bytes, at most the frame's {@code maxBytes}
     * @throws CompressionFormatException     if the chunk is missing, malformed, or larger than that
     * @throws UnsupportedCompressionException if it uses a codec or filter Falcon does not decode
     */
    public byte[] chunk(int index) {
        return chunk(index, maxBytes);
    }

    /** Decodes chunk {@code index} to at most {@code limit} bytes. */
    byte[] chunk(int index, int limit) {
        long position = locate(index);
        if (position < 0) {
            return special(index, offsets[index], limit);
        }
        int at = (int) position;
        return BloscDecoder.decompress(Arrays.copyOfRange(src, at, at + BloscDecoder.le32(src, at + 12)), limit);
    }

    /**
     * Where chunk {@code index} starts in the frame, its offset and stored length checked
     * ({@code get_coffset}, {@code frame_get_lazychunk}); or -1 for a special chunk, whose kind and size are
     * checked.
     */
    private long locate(int index) {
        if (index < 0 || index >= offsets.length) {
            throw new CompressionFormatException("Blosc2 frame has no chunk " + index + " (it holds "
                    + offsets.length + ")");
        }
        long offset = offsets[index];
        if (offset < 0) {
            specialSize(index, offset);
            return -1;
        }
        if (offset > Long.MAX_VALUE - headerLen || cbytes > Long.MAX_VALUE - headerLen) {
            throw new CompressionFormatException("Blosc2 frame chunk " + index + " offset overflows");
        }
        long position = headerLen + offset;
        if (position > headerLen + cbytes - EXTENDED_HEADER_LENGTH || position > src.length - EXTENDED_HEADER_LENGTH) {
            throw new CompressionFormatException("Blosc2 frame chunk " + index + " at " + position
                    + " lies outside the frame");
        }
        int chunkCbytes = BloscDecoder.le32(src, (int) position + 12);
        if (chunkCbytes < EXTENDED_HEADER_LENGTH || offset + chunkCbytes > cbytes) {
            throw new CompressionFormatException("Blosc2 frame chunk " + index + " of " + chunkCbytes
                    + " bytes does not fit the chunks section");
        }
        return position;
    }

    /**
     * The size of a chunk the index marks as special ({@code frame_special_chunk}), its kind and size checked as
     * {@code blosc2_chunk_zeros}, {@code _uninit}, and {@code _nans} check them.
     */
    private long specialSize(int index, long offset) {
        if (chunkSize <= 0) {
            throw new CompressionFormatException("Blosc2 frame special chunk without a fixed chunk size");
        }
        long size = chunkSize;
        if (index == offsets.length - 1 && nbytes % chunkSize != 0) {
            size = nbytes % chunkSize; // the last chunk is short
        }
        if ((offset & (SPECIAL_ZERO | SPECIAL_UNINIT | SPECIAL_NAN)) == 0) {
            throw new CompressionFormatException(String.format(
                    "Blosc2 frame chunk %d has unknown special value 0x%02x", index, offset >>> 56));
        }
        if (size % typeSize != 0) {
            throw new CompressionFormatException("Blosc2 special chunk of " + size + " bytes is not whole "
                    + typeSize + "-byte values");
        }
        if ((offset & (SPECIAL_ZERO | SPECIAL_UNINIT)) == 0 && typeSize != 4 && typeSize != 8) {
            throw new CompressionFormatException(
                    "Blosc2 NaN chunk has a " + typeSize + "-byte type; NaN needs 4 or 8 bytes");
        }
        return size;
    }

    /** A special chunk's bytes: zeros (as uninitialised values also read), or NaNs. */
    private byte[] special(int index, long offset, int limit) {
        long size = specialSize(index, offset);
        if (size > limit) {
            throw new CompressionFormatException("Blosc2 frame chunk " + index + " declares " + size
                    + " bytes, more than the " + limit + " expected");
        }
        byte[] out = new byte[(int) size];
        if ((offset & (SPECIAL_ZERO | SPECIAL_UNINIT)) != 0) {
            return out; // zeros are tested first, then uninitialised values, then NaNs
        }
        byte[] nan = typeSize == 4 ? new byte[] {0, 0, (byte) 0xc0, 0x7f} // nanf(""), little-endian
                : new byte[] {0, 0, 0, 0, 0, 0, (byte) 0xf8, 0x7f};
        for (int p = 0; p < out.length; p += typeSize) {
            System.arraycopy(nan, 0, out, p, typeSize);
        }
        return out;
    }

    /**
     * The index chunk's position and stored length: right after the chunks section
     * ({@code get_coffsets_nbytes}, {@code get_coffsets}).
     */
    private static int[] indexChunk(byte[] src, int headerLen, long cbytes) {
        long position = headerLen;
        if (cbytes < Long.MAX_VALUE - headerLen) {
            position += cbytes;
        }
        if (position < 0 || position + EXTENDED_HEADER_LENGTH > src.length) {
            throw new CompressionFormatException("Blosc2 frame index lies outside the frame");
        }
        int at = (int) position;
        int indexCbytes = BloscDecoder.le32(src, at + 12);
        if (indexCbytes < 0 || at + (long) indexCbytes > src.length) {
            throw new CompressionFormatException("Blosc2 frame index of " + indexCbytes + " bytes overruns the frame");
        }
        return new int[] {at, indexCbytes};
    }

    /** The header's metalayers ({@code get_meta_from_header}): name to content, in the frame's order. */
    private static Map<String, byte[]> metalayers(byte[] header, int headerLen) {
        int pos = FRAME_IDX_SIZE + 2; // the index's size (uint16) is not needed
        if (headerLen < pos + 1) {
            throw new CompressionFormatException("Blosc2 frame header is too short for its metalayers");
        }
        if ((header[pos] & 0xff) != 0xde) {
            throw new CompressionFormatException("Blosc2 frame metalayers are not a msgpack map");
        }
        pos++;
        if (headerLen < pos + 2) {
            throw new CompressionFormatException("Blosc2 frame header is too short for its metalayers");
        }
        int count = be16(header, pos);
        pos += 2;
        if (count > MAX_METALAYERS) {
            throw new CompressionFormatException("Blosc2 frame lists " + count + " metalayers, more than "
                    + MAX_METALAYERS);
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int m = 0; m < count; m++) {
            Entry entry = indexEntry(header, headerLen, pos, "metalayer");
            pos = entry.next();
            out.putIfAbsent(entry.name(), Arrays.copyOfRange(header, entry.content(), entry.content() + entry.length()));
        }
        return out;
    }

    /**
     * Checks the trailer as c-blosc2 reads it ({@code frame_get_vlmetalayers}): where it starts, and its index
     * of variable-length metalayers, whose contents stay unread.
     */
    private static void checkTrailer(byte[] src, int headerLen, boolean hasChunks, int trailerLen) {
        long start = hasChunks ? (long) src.length - trailerLen : headerLen;
        if (start < EXTENDED_HEADER_LENGTH || start + trailerLen > src.length) {
            throw new CompressionFormatException("Blosc2 frame trailer lies outside the frame");
        }
        int base = (int) start;
        byte[] trailer = Arrays.copyOfRange(src, base, base + trailerLen);
        int pos = FRAME_TRAILER_VLMETALAYERS + 2 + 2; // past the index's size (uint16), not needed
        if (trailerLen < pos + 1) {
            throw new CompressionFormatException("Blosc2 frame trailer is too short for its metalayers");
        }
        if ((trailer[pos] & 0xff) != 0xde) {
            throw new CompressionFormatException("Blosc2 frame trailer metalayers are not a msgpack map");
        }
        pos++;
        if (trailerLen < pos + 2) {
            throw new CompressionFormatException("Blosc2 frame trailer is too short for its metalayers");
        }
        int count = be16(trailer, pos);
        pos += 2;
        if (count > MAX_VLMETALAYERS) {
            throw new CompressionFormatException("Blosc2 frame trailer lists " + count + " metalayers");
        }
        for (int m = 0; m < count; m++) {
            pos = indexEntry(trailer, trailerLen, pos, "variable-length metalayer").next();
        }
    }

    /** One metalayer of an index: its name, its content's place, and where the next entry starts. */
    private record Entry(String name, int content, int length, int next) {
    }

    /**
     * Reads one index entry at {@code pos}: a fixstr name, an int32 offset into {@code area}, and there a bin32
     * holding the content.
     */
    private static Entry indexEntry(byte[] area, int areaLen, int pos, String what) {
        if (areaLen < pos + 1) {
            throw new CompressionFormatException("Blosc2 frame " + what + " index is truncated");
        }
        int marker = area[pos] & 0xff;
        if ((marker & 0xe0) != 0xa0) {
            throw new CompressionFormatException("Blosc2 frame " + what + " name is not a msgpack fixstr");
        }
        int nameLen = marker & 0x1f;
        pos++;
        if (areaLen < pos + nameLen + 1 + 4) {
            throw new CompressionFormatException("Blosc2 frame " + what + " index is truncated");
        }
        String name = new String(area, pos, nameLen, StandardCharsets.ISO_8859_1);
        pos += nameLen;
        if ((area[pos] & 0xff) != 0xd2) {
            throw new CompressionFormatException("Blosc2 frame " + what + " offset is not a msgpack int32");
        }
        int offset = be32(area, pos + 1);
        pos += 5;
        if (offset < 0 || offset >= areaLen) {
            throw new CompressionFormatException("Blosc2 frame " + what + " " + name + " at " + offset
                    + " lies outside its " + areaLen + " bytes");
        }
        if ((long) offset + 1 + 4 > areaLen) {
            throw new CompressionFormatException("Blosc2 frame " + what + " " + name + " is truncated");
        }
        if ((area[offset] & 0xff) != 0xc6) {
            throw new CompressionFormatException("Blosc2 frame " + what + " " + name + " is not a msgpack bin32");
        }
        int length = be32(area, offset + 1);
        if (length < 0 || (long) offset + 1 + 4 + length > areaLen) {
            throw new CompressionFormatException("Blosc2 frame " + what + " " + name + " of " + length
                    + " bytes is truncated");
        }
        return new Entry(name, offset + 5, length, pos);
    }

    private static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static int be32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static long be64(byte[] b, int off) {
        return ((long) be32(b, off) << 32) | (be32(b, off + 4) & 0xffffffffL);
    }

    private static long le64(byte[] b, int off) {
        return (BloscDecoder.le32(b, off) & 0xffffffffL) | ((long) BloscDecoder.le32(b, off + 4) << 32);
    }
}
