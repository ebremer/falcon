package com.ebremer.falcon.core.compress.zstd;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;

/**
 * A pure-Java Zstandard decompressor (RFC&nbsp;8878), written from the specification so the module keeps
 * its zero-dependency guarantee &mdash; the same decision the HDF5 module made for szip.
 *
 * <p>Supports the whole single-frame decode path: raw, RLE, and compressed blocks; raw, RLE, Huffman, and
 * treeless literals in the one- and four-stream layouts; predefined, RLE, FSE-compressed, and repeated
 * sequence tables; and the three repeat offsets. Dictionaries are not supported (Zarr does not use them),
 * and the optional frame checksum is skipped rather than verified.
 */
public final class ZstdDecoder {

    private static final int MAGIC = 0xFD2FB528;
    private static final int MAX_BLOCK_SIZE = 128 * 1024;

    // Predefined distributions (RFC 8878 section 3.1.1.3.2.2).
    private static final short[] LL_DEFAULT = {
        4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
        -1, -1, -1, -1};
    private static final short[] ML_DEFAULT = {
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
        -1, -1, -1, -1, -1};
    private static final short[] OF_DEFAULT = {
        1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};

    private static final int[] LL_BASE = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
        8192, 16384, 32768, 65536};
    private static final int[] LL_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
        13, 14, 15, 16};
    private static final int[] ML_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
        19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
        4099, 8195, 16387, 32771, 65539};
    private static final int[] ML_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
        12, 13, 14, 15, 16};

    private final byte[] in;
    private int ip;
    private final int end;
    private final int limit; // the most bytes the frame may decode to

    private byte[] out = new byte[0];
    private int op;

    // Entropy tables persist across blocks of a frame so "repeat"/"treeless" modes can reuse them.
    private ZstdHuffman.Table huffman;
    private ZstdFse.Table literalLengthTable;
    private ZstdFse.Table offsetTable;
    private ZstdFse.Table matchLengthTable;
    private final int[] repeatOffsets = {1, 4, 8};

    private ZstdDecoder(byte[] in, int off, int length, int limit) {
        this.in = in;
        this.ip = off;
        this.end = off + length;
        this.limit = limit;
    }

    /** Decompresses a single Zstandard frame. */
    public static byte[] decompress(byte[] input) {
        return decompress(input, 0, input.length);
    }

    /** Decompresses a single Zstandard frame from a region of {@code input}. */
    public static byte[] decompress(byte[] input, int off, int length) {
        return decompress(input, off, length, Integer.MAX_VALUE - 8);
    }

    /**
     * Decompresses a single Zstandard frame from a region of {@code input}, failing if it would decode to
     * more than {@code maxSize} bytes (so a corrupt frame cannot claim an enormous output).
     */
    public static byte[] decompress(byte[] input, int off, int length, int maxSize) {
        if (off < 0 || length < 0 || length > input.length - off) {
            throw new IllegalArgumentException("invalid range " + off + "+" + length + " of " + input.length);
        }
        ZstdDecoder decoder = new ZstdDecoder(input, off, length, Math.min(maxSize, Integer.MAX_VALUE - 8));
        decoder.decodeFrame();
        return Arrays.copyOf(decoder.out, decoder.op);
    }

    private void decodeFrame() {
        if (remaining() < 4 || readLe32() != MAGIC) {
            throw new CompressionFormatException("not a Zstandard frame (bad magic number)");
        }
        int descriptor = readByte();
        int contentSizeFlag = descriptor >>> 6;
        boolean singleSegment = (descriptor & 0x20) != 0;
        boolean hasChecksum = (descriptor & 0x04) != 0;
        int dictionaryIdFlag = descriptor & 0x03;
        if ((descriptor & 0x08) != 0) {
            throw new CompressionFormatException("reserved bit set in the frame header descriptor");
        }
        if (!singleSegment) {
            readByte(); // window descriptor: the whole frame is buffered, so it is not needed
        }
        int dictionaryIdBytes = switch (dictionaryIdFlag) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
        if (dictionaryIdBytes > 0) {
            long id = readLe(dictionaryIdBytes);
            if (id != 0) {
                throw new CompressionFormatException("dictionaries are not supported (dictionary id " + id + ")");
            }
        }
        int contentSizeBytes = switch (contentSizeFlag) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        long contentSize = -1;
        if (contentSizeBytes > 0) {
            contentSize = readLe(contentSizeBytes);
            if (contentSizeBytes == 2) {
                contentSize += 256; // the 2-byte form is stored biased
            }
        }
        if (contentSize > limit) {
            throw new CompressionFormatException("frame content size " + contentSize + " exceeds " + limit + " bytes");
        }
        if (contentSize >= 0) {
            out = new byte[(int) contentSize];
        }

        boolean last = false;
        while (!last) {
            int header = (int) readLe(3);
            last = (header & 1) != 0;
            int type = (header >>> 1) & 3;
            int size = header >>> 3;
            switch (type) {
                case 0 -> copyRawBlock(size);
                case 1 -> writeRleBlock(size);
                case 2 -> decodeCompressedBlock(size);
                default -> throw new CompressionFormatException("reserved block type");
            }
        }
        if (hasChecksum) {
            if (remaining() < 4) {
                throw new CompressionFormatException("frame checksum is truncated");
            }
            ip += 4; // the content checksum is not verified
        }
    }

    private void copyRawBlock(int size) {
        require(size);
        ensure(size);
        System.arraycopy(in, ip, out, op, size);
        ip += size;
        op += size;
    }

    private void writeRleBlock(int size) {
        require(1);
        byte value = in[ip++];
        ensure(size);
        Arrays.fill(out, op, op + size, value);
        op += size;
    }

    private void decodeCompressedBlock(int blockSize) {
        require(blockSize);
        if (blockSize > MAX_BLOCK_SIZE) {
            throw new CompressionFormatException("block of " + blockSize + " bytes exceeds the format maximum");
        }
        int blockStart = ip;
        int blockEnd = ip + blockSize;

        byte[] literals = readLiteralsSection(blockEnd);
        decodeSequences(blockEnd, literals);
        ip = blockEnd;
        if (ip > end) {
            throw new CompressionFormatException("block overran the frame");
        }
        if (blockStart > blockEnd) {
            throw new CompressionFormatException("negative block size");
        }
    }

    // ---- literals ---------------------------------------------------------------------------------

    private byte[] readLiteralsSection(int blockEnd) {
        need(1, blockEnd, "literals section header");
        int header = in[ip] & 0xff;
        int type = header & 3;
        int sizeFormat = (header >>> 2) & 3;
        int regenerated;
        int compressed = -1;
        int streams = 1;

        if (type == 0 || type == 1) { // raw or RLE
            switch (sizeFormat) {
                case 0, 2 -> {
                    regenerated = header >>> 3;
                    ip += 1;
                }
                case 1 -> {
                    need(2, blockEnd, "literals section header");
                    regenerated = (header >>> 4) | ((in[ip + 1] & 0xff) << 4);
                    ip += 2;
                }
                default -> {
                    need(3, blockEnd, "literals section header");
                    regenerated = (header >>> 4) | ((in[ip + 1] & 0xff) << 4) | ((in[ip + 2] & 0xff) << 12);
                    ip += 3;
                }
            }
        } else { // compressed or treeless
            switch (sizeFormat) {
                case 0, 1 -> {
                    need(3, blockEnd, "literals section header");
                    int value = (header >>> 4) | ((in[ip + 1] & 0xff) << 4) | ((in[ip + 2] & 0xff) << 12);
                    regenerated = value & 0x3FF;
                    compressed = (value >>> 10) & 0x3FF;
                    streams = sizeFormat == 0 ? 1 : 4;
                    ip += 3;
                }
                case 2 -> {
                    need(4, blockEnd, "literals section header");
                    long value = (header >>> 4) | ((long) (in[ip + 1] & 0xff) << 4)
                            | ((long) (in[ip + 2] & 0xff) << 12) | ((long) (in[ip + 3] & 0xff) << 20);
                    regenerated = (int) (value & 0x3FFF);
                    compressed = (int) ((value >>> 14) & 0x3FFF);
                    streams = 4;
                    ip += 4;
                }
                default -> {
                    need(5, blockEnd, "literals section header");
                    long value = (header >>> 4) | ((long) (in[ip + 1] & 0xff) << 4)
                            | ((long) (in[ip + 2] & 0xff) << 12) | ((long) (in[ip + 3] & 0xff) << 20)
                            | ((long) (in[ip + 4] & 0xff) << 28);
                    regenerated = (int) (value & 0x3FFFF);
                    compressed = (int) ((value >>> 18) & 0x3FFFF);
                    streams = 4;
                    ip += 5;
                }
            }
        }

        byte[] literals = new byte[regenerated];
        switch (type) {
            case 0 -> {
                if (ip + regenerated > blockEnd) {
                    throw new CompressionFormatException("raw literals overrun the block");
                }
                System.arraycopy(in, ip, literals, 0, regenerated);
                ip += regenerated;
            }
            case 1 -> {
                if (ip + 1 > blockEnd) {
                    throw new CompressionFormatException("RLE literals overrun the block");
                }
                Arrays.fill(literals, in[ip]);
                ip += 1;
            }
            default -> {
                if (ip + compressed > blockEnd) {
                    throw new CompressionFormatException("compressed literals overrun the block");
                }
                int streamsOffset = ip;
                int streamsLength = compressed;
                if (type == 2) { // a new Huffman tree precedes the streams
                    int[] consumed = new int[1];
                    huffman = ZstdHuffman.readTable(in, ip, compressed, consumed);
                    streamsOffset += consumed[0];
                    streamsLength -= consumed[0];
                } else if (huffman == null) {
                    throw new CompressionFormatException("treeless literals with no previous Huffman table");
                }
                if (streamsLength < 0) {
                    throw new CompressionFormatException("Huffman streams have a negative size");
                }
                if (streams == 1) {
                    ZstdHuffman.decodeStream(huffman, in, streamsOffset, streamsLength,
                            literals, 0, regenerated);
                } else {
                    ZstdHuffman.decodeFourStreams(huffman, in, streamsOffset, streamsLength,
                            literals, regenerated);
                }
                ip += compressed;
            }
        }
        return literals;
    }

    // ---- sequences --------------------------------------------------------------------------------

    private void decodeSequences(int blockEnd, byte[] literals) {
        if (ip >= blockEnd) {
            // No sequences section at all: the block is just its literals.
            appendLiterals(literals, 0, literals.length);
            return;
        }
        int first = in[ip++] & 0xff;
        int sequenceCount;
        if (first == 0) {
            sequenceCount = 0;
        } else if (first < 128) {
            sequenceCount = first;
        } else if (first < 255) {
            need(1, blockEnd, "sequence count");
            sequenceCount = ((first - 128) << 8) + (in[ip++] & 0xff);
        } else {
            need(2, blockEnd, "sequence count");
            sequenceCount = (in[ip] & 0xff) | ((in[ip + 1] & 0xff) << 8);
            sequenceCount += 0x7F00;
            ip += 2;
        }
        if (sequenceCount == 0) {
            appendLiterals(literals, 0, literals.length);
            return;
        }

        need(1, blockEnd, "sequence compression modes");
        int modes = in[ip++] & 0xff;
        if ((modes & 3) != 0) {
            throw new CompressionFormatException("reserved bits set in the sequence compression modes");
        }
        literalLengthTable = readSequenceTable((modes >>> 6) & 3, literalLengthTable,
                LL_DEFAULT, 6, 35, "literal lengths");
        offsetTable = readSequenceTable((modes >>> 4) & 3, offsetTable,
                OF_DEFAULT, 5, 28, "offsets");
        matchLengthTable = readSequenceTable((modes >>> 2) & 3, matchLengthTable,
                ML_DEFAULT, 6, 52, "match lengths");

        int streamLength = blockEnd - ip;
        if (streamLength <= 0) {
            throw new CompressionFormatException("sequence bitstream is empty");
        }
        ZstdBitReader bits = new ZstdBitReader(in, ip, streamLength);

        // Initial states are read in the order literal-length, offset, match-length.
        ZstdFse.State llState = new ZstdFse.State(literalLengthTable, bits);
        ZstdFse.State ofState = new ZstdFse.State(offsetTable, bits);
        ZstdFse.State mlState = new ZstdFse.State(matchLengthTable, bits);

        int literalsUsed = 0;
        for (int i = 0; i < sequenceCount; i++) {
            int llCode = llState.symbol();
            int mlCode = mlState.symbol();
            int ofCode = ofState.symbol();
            if (llCode >= LL_BASE.length || mlCode >= ML_BASE.length || ofCode > 31) {
                throw new CompressionFormatException("sequence code out of range");
            }

            // Extra bits are read offset-first, then match length, then literal length.
            long offsetValue = (1L << ofCode) + (ofCode == 0 ? 0 : bits.readBits(ofCode));
            int matchLength = ML_BASE[mlCode] + (ML_BITS[mlCode] == 0 ? 0 : bits.readBits(ML_BITS[mlCode]));
            int literalLength = LL_BASE[llCode] + (LL_BITS[llCode] == 0 ? 0 : bits.readBits(LL_BITS[llCode]));

            int offset = resolveOffset(offsetValue, literalLength);

            if (literalsUsed + literalLength > literals.length) {
                throw new CompressionFormatException("sequence consumes more literals than the block holds");
            }
            appendLiterals(literals, literalsUsed, literalLength);
            literalsUsed += literalLength;
            copyMatch(offset, matchLength);

            if (i + 1 < sequenceCount) {
                llState.advance(bits);
                mlState.advance(bits);
                ofState.advance(bits);
            }
        }
        appendLiterals(literals, literalsUsed, literals.length - literalsUsed);
    }

    /** Applies the repeat-offset rules and returns the actual match distance. */
    private int resolveOffset(long offsetValue, int literalLength) {
        int offset;
        if (offsetValue > 3) {
            offset = (int) (offsetValue - 3);
            repeatOffsets[2] = repeatOffsets[1];
            repeatOffsets[1] = repeatOffsets[0];
            repeatOffsets[0] = offset;
            return offset;
        }
        int index = (int) offsetValue;
        if (literalLength == 0) {
            index++; // with no literals the codes shift by one
        }
        switch (index) {
            case 1 -> offset = repeatOffsets[0];
            case 2 -> {
                offset = repeatOffsets[1];
                repeatOffsets[1] = repeatOffsets[0];
                repeatOffsets[0] = offset;
            }
            case 3 -> {
                offset = repeatOffsets[2];
                repeatOffsets[2] = repeatOffsets[1];
                repeatOffsets[1] = repeatOffsets[0];
                repeatOffsets[0] = offset;
            }
            default -> {
                offset = repeatOffsets[0] - 1;
                if (offset < 1) {
                    throw new CompressionFormatException("invalid repeat offset");
                }
                repeatOffsets[2] = repeatOffsets[1];
                repeatOffsets[1] = repeatOffsets[0];
                repeatOffsets[0] = offset;
            }
        }
        return offset;
    }

    private ZstdFse.Table readSequenceTable(int mode, ZstdFse.Table previous, short[] predefined,
                                            int predefinedLog, int maxSymbol, String what) {
        switch (mode) {
            case 0 -> {
                return ZstdFse.predefined(predefined, predefinedLog);
            }
            case 1 -> {
                require(1);
                int symbol = in[ip++] & 0xff;
                return ZstdFse.rleTable(symbol);
            }
            case 2 -> {
                short[] counts = new short[maxSymbol + 2];
                int[] header = {maxSymbol, 0};
                int used = ZstdFse.readNCount(counts, header, in, ip, end - ip);
                ip += used;
                return ZstdFse.buildTable(counts, header[0], header[1]);
            }
            default -> {
                if (previous == null) {
                    throw new CompressionFormatException("repeat mode for " + what + " with no previous table");
                }
                return previous;
            }
        }
    }

    // ---- output -----------------------------------------------------------------------------------

    private void appendLiterals(byte[] literals, int off, int length) {
        if (length == 0) {
            return;
        }
        ensure(length);
        System.arraycopy(literals, off, out, op, length);
        op += length;
    }

    private void copyMatch(int offset, int length) {
        if (offset <= 0 || offset > op) {
            throw new CompressionFormatException("match offset " + offset + " is outside the decoded output");
        }
        ensure(length);
        int from = op - offset;
        // Overlapping matches are legal and must be copied byte by byte.
        for (int i = 0; i < length; i++) {
            out[op + i] = out[from + i];
        }
        op += length;
    }

    private void ensure(int extra) {
        if (op + (long) extra <= out.length) {
            return;
        }
        if (op + (long) extra > limit) {
            throw new CompressionFormatException("frame decodes to more than " + limit + " bytes");
        }
        int target = (int) Math.min(limit, Math.max(op + (long) extra, Math.max(64, out.length * 2L)));
        out = Arrays.copyOf(out, target);
    }

    // ---- input ------------------------------------------------------------------------------------

    private int remaining() {
        return end - ip;
    }

    /** Fails unless {@code n} more bytes lie before {@code limit} (a block's end). */
    private void need(int n, int limit, String what) {
        if (limit - ip < n) {
            throw new CompressionFormatException(what + " is truncated");
        }
    }

    private void require(int n) {
        if (remaining() < n) {
            throw new CompressionFormatException("frame is truncated (needed " + n + " more bytes)");
        }
    }

    private int readByte() {
        require(1);
        return in[ip++] & 0xff;
    }

    private int readLe32() {
        require(4);
        int v = (in[ip] & 0xff) | ((in[ip + 1] & 0xff) << 8)
                | ((in[ip + 2] & 0xff) << 16) | ((in[ip + 3] & 0xff) << 24);
        ip += 4;
        return v;
    }

    private long readLe(int bytes) {
        require(bytes);
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value |= (long) (in[ip + i] & 0xff) << (8 * i);
        }
        ip += bytes;
        return value;
    }
}
