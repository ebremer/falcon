package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed Array chunk index (spec Appendix C.C): the index for chunked datasets with fixed maximum
 * dimensions (a known, constant number of chunks). The header ({@code "FAHD"}) points to a single data
 * block ({@code "FADB"}) holding one entry per chunk of the <em>maximum</em> chunk grid, addressed by
 * the chunk's linear index (see {@link ChunkGrid}).
 *
 * <p>Non-filtered entries (client id 0) are just a chunk address; filtered entries (client id 1) add
 * the stored chunk size and the filter mask. The width of the size field is whatever the header's entry
 * size leaves after the address and mask: libhdf5 1.10&ndash;1.14 (layout version 4) sizes it to the
 * unfiltered chunk size plus one byte, HDF5 2.0 (layout version 5) uses "size of lengths".
 *
 * <p>A data block with more entries than one page ({@code 2^pageBits}) is <b>paged</b>: the block holds
 * only a page-init bitmap (one bit per page, most-significant bit first) and its checksum, followed by
 * the pages, each a run of entries plus its own checksum. Pages whose bit is clear were never written,
 * so their chunks are unallocated.
 */
public final class FixedArray {

    private static final byte[] FAHD = {'F', 'A', 'H', 'D'};
    private static final byte[] FADB = {'F', 'A', 'D', 'B'};

    private FixedArray() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes, ChunkGrid grid) {
        Header header = Header.parse(ctx, headerAddress);
        if (header == null) {
            return List.of();
        }
        HdfBuffer buf = ctx.buffer();
        List<ChunkRecord> chunks = new ArrayList<>();
        header.verifyBlock(buf);
        if (!header.paged()) {
            for (long i = 0; i < header.maxEntries; i++) {
                header.format.read(buf, header.prefix + i * header.entrySize, i, chunkBytes, grid, chunks);
            }
            return chunks;
        }
        // Paged: bitmap + checksum, then each page's entries followed by the page's checksum.
        for (long page = 0; page < header.pages(); page++) {
            if (!bitSet(buf, header.prefix, page)) {
                continue; // page never initialized: every chunk in it is unallocated
            }
            long first = page * header.pageEntries;
            long count = header.verifyPage(buf, page);
            long pageAddress = header.pageAddress(page);
            for (long j = 0; j < count; j++) {
                header.format.read(buf, pageAddress + j * header.entrySize, first + j, chunkBytes, grid, chunks);
            }
        }
        return chunks;
    }

    /**
     * Finds a chunk by its linear index (P2 PF5): its entry, in the data block or in its page, which is
     * verified against its checksum the first time it is read.
     */
    public static ChunkLookup lookup(FileContext ctx, long headerAddress, int chunkBytes, ChunkGrid grid) {
        Header header = Header.parse(ctx, headerAddress);
        if (header == null) {
            return cell -> null;
        }
        Set<Long> verified = ConcurrentHashMap.newKeySet(); // the pages verified, and -1 for the block
        return cell -> {
            HdfBuffer buf = ctx.buffer();
            long linear = grid.linearIndex(cell);
            if (linear < 0 || linear >= header.maxEntries) {
                return null;
            }
            if (!verified.contains(-1L)) {
                header.verifyBlock(buf);
                verified.add(-1L);
            }
            if (!header.paged()) {
                return header.format.decode(buf, header.prefix + linear * header.entrySize, grid.offsetOf(cell),
                        chunkBytes);
            }
            long page = linear / header.pageEntries;
            if (!bitSet(buf, header.prefix, page)) {
                return null; // page never initialized
            }
            if (!verified.contains(page)) {
                header.verifyPage(buf, page);
                verified.add(page);
            }
            return header.format.decode(buf, header.pageAddress(page) + (linear % header.pageEntries) * header.entrySize,
                    grid.offsetOf(cell), chunkBytes);
        };
    }

    /** A fixed array's header, and where its data block's entries are. */
    private record Header(EntryFormat format, int entrySize, long maxEntries, long dataBlock, long prefix,
                          long pageEntries, int offsets) {

        /** The header at {@code headerAddress}, verified; null if no chunk was ever stored. */
        static Header parse(FileContext ctx, long headerAddress) {
            HdfBuffer buf = ctx.buffer();
            if (!buf.hasSignature(headerAddress, FAHD)) {
                throw new HdfFormatException("expected fixed array header 'FAHD' at " + headerAddress);
            }
            int offsets = ctx.sizeOfOffsets();
            int lengths = ctx.sizeOfLengths();
            MetadataChecksum.verify(buf, headerAddress, 8L + lengths + offsets, "fixed array header");
            int clientId = buf.getUnsignedByte(headerAddress + 5);
            int entrySize = buf.getUnsignedByte(headerAddress + 6);
            int pageBits = buf.getUnsignedByte(headerAddress + 7);
            long maxEntries = buf.getUnsignedValue(headerAddress + 8, lengths);
            long dataBlock = buf.getAddress(headerAddress + 8 + lengths, offsets);
            EntryFormat format = EntryFormat.of(clientId, entrySize, offsets, headerAddress);
            if (pageBits < 1 || pageBits > 31) {
                throw new HdfFormatException("invalid fixed array page bits " + pageBits + " at " + headerAddress);
            }
            if (maxEntries < 0 || maxEntries > buf.size() / entrySize + 1) {
                throw new HdfFormatException("fixed array entry count " + maxEntries + " exceeds the file at " + headerAddress);
            }
            if (dataBlock == HdfBuffer.UNDEFINED_ADDRESS || maxEntries == 0) {
                return null;
            }
            if (!buf.hasSignature(dataBlock, FADB)) {
                throw new HdfFormatException("expected fixed array data block 'FADB' at " + dataBlock);
            }
            // The entries (or, paged, the page-init bitmap) follow the signature, version, client id, and
            // header address.
            return new Header(format, entrySize, maxEntries, dataBlock, dataBlock + 6 + offsets, 1L << pageBits, offsets);
        }

        boolean paged() {
            return maxEntries > pageEntries;
        }

        long pages() {
            return (maxEntries + pageEntries - 1) / pageEntries;
        }

        /** Verifies the data block's checksum: over its entries, or, paged, over its page-init bitmap. */
        void verifyBlock(HdfBuffer buf) {
            long covered = paged() ? (pages() + 7) / 8 : maxEntries * entrySize;
            MetadataChecksum.verify(buf, dataBlock, 6L + offsets + covered, "fixed array data block");
        }

        /** Where page {@code page}'s entries start: after the bitmap and its checksum, and the pages before. */
        long pageAddress(long page) {
            return prefix + (pages() + 7) / 8 + 4 + page * (pageEntries * entrySize + 4);
        }

        /** Verifies page {@code page}'s checksum, returning how many entries it holds. */
        long verifyPage(HdfBuffer buf, long page) {
            long count = Math.min(pageEntries, maxEntries - page * pageEntries);
            MetadataChecksum.verify(buf, pageAddress(page), count * entrySize, "fixed array data block page");
            return count;
        }
    }

    /** Bit {@code index} of a most-significant-bit-first bitmap starting at {@code at}. */
    static boolean bitSet(HdfBuffer buf, long at, long index) {
        return (buf.getUnsignedByte(at + index / 8) & (0x80 >>> (int) (index % 8))) != 0;
    }

    /** How the entries of a fixed or extensible array (they share the encoding) are laid out. */
    record EntryFormat(boolean filtered, int offsets, int sizeWidth) {

        static EntryFormat of(int clientId, int entrySize, int offsets, long at) {
            if (clientId == 0) {
                if (entrySize != offsets) {
                    throw new HdfFormatException("chunk index entry size " + entrySize + " for unfiltered chunks at " + at);
                }
                return new EntryFormat(false, offsets, 0);
            }
            if (clientId == 1) {
                int sizeWidth = entrySize - offsets - 4; // address + size + 4-byte filter mask
                if (sizeWidth < 1 || sizeWidth > 8) {
                    throw new HdfFormatException("chunk index entry size " + entrySize + " for filtered chunks at " + at);
                }
                return new EntryFormat(true, offsets, sizeWidth);
            }
            throw new HdfUnsupportedException("unsupported chunk index client id " + clientId + " at " + at);
        }

        /** Decodes the entry at {@code at} (linear chunk index {@code linear}) into {@code out}, if allocated. */
        void read(HdfBuffer buf, long at, long linear, int chunkBytes, ChunkGrid grid, List<ChunkRecord> out) {
            long[] offset = grid.chunkOffset(linear);
            ChunkRecord chunk = decode(buf, at, offset, chunkBytes);
            if (chunk != null && grid.isWithinCurrentExtent(offset)) {
                out.add(chunk);
            }
        }

        /** The chunk at element {@code offset} that the entry at {@code at} describes, or null if unallocated. */
        ChunkRecord decode(HdfBuffer buf, long at, long[] offset, int chunkBytes) {
            long address = buf.getAddress(at, offsets);
            if (address == HdfBuffer.UNDEFINED_ADDRESS) {
                return null; // unallocated chunk -> fill value
            }
            if (address == 0) {
                // Offset 0 holds the superblock, so no chunk can live there; zeroed (never written) index
                // memory decodes this way.
                throw new HdfFormatException("chunk index entry at " + at + " points at address 0");
            }
            long size = chunkBytes;
            int filterMask = 0;
            if (filtered) {
                size = buf.getUnsignedValue(at + offsets, sizeWidth);
                filterMask = buf.getInt(at + offsets + sizeWidth);
                if (size < 0 || size > Integer.MAX_VALUE) {
                    throw new HdfFormatException("invalid stored chunk size " + size + " at " + at);
                }
            }
            return new ChunkRecord(offset, address, (int) size, filterMask);
        }
    }
}
