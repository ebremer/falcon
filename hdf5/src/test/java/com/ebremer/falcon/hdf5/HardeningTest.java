package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.btree.GroupBTreeV1;
import com.ebremer.falcon.core.checksum.Fletcher32;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.message.DatatypeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Crafted corrupt input that once made the reader hang, overflow the stack, exhaust the heap, or return
 * silently wrong data. Each must now fail fast with a typed {@link HdfException}.
 */
@Timeout(30)
class HardeningTest {

    private static FileContext context(byte[] bytes) {
        return new FileContext(HdfBuffer.of(bytes), 8, 8);
    }

    /** A global-heap object of size -16 used to leave the scan cursor in place forever. */
    @Test
    void globalHeapObjectWithANegativeSizeFails() {
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'G', 'C', 'O', 'L', 1, 0, 0, 0}).putLong(4096);
        b.putShort((short) 2).putShort((short) 1).putInt(0).putLong(-16L); // object 2, size -16
        assertThrows(HdfFormatException.class, () -> GlobalHeap.readObject(context(b.array()), 0, 1));
    }

    /**
     * A link message whose name claims 2 GB, in an 896-byte file (CVE-2018-13870's, of HDFGroup/cve_hdf5): the
     * name's bytes were allocated before their range was checked, which exhausted the heap.
     */
    @Test
    void linkNameLongerThanTheFileFailsWithoutAllocatingIt() {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 1).put((byte) 0x03).putLong(Integer.MAX_VALUE); // version 1, an 8-byte name length
        HdfFormatException e = assertThrows(HdfFormatException.class,
                () -> com.ebremer.falcon.hdf5.message.LinkMessage.parse(HdfBuffer.of(b.array()), 0, 8));
        assertTrue(e.getMessage().contains("out of bounds"), e.getMessage());
    }

    /** A continuation message pointing back at its own chunk used to recurse until the stack overflowed. */
    @Test
    void objectHeaderContinuationCycleFails() {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 1).put((byte) 0).putShort((short) 1).putInt(1).putInt(24).putInt(0); // v1 prefix
        b.putShort((short) 0x10).putShort((short) 16).putInt(0);                          // continuation message
        b.putLong(16).putLong(24);                                                          // -> this very chunk
        assertThrows(HdfFormatException.class, () -> ObjectHeader.parse(context(b.array()), 0));
    }

    /** A group B-tree node whose child is itself. */
    @Test
    void selfReferencingBTreeNodeFails() {
        ByteBuffer b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'T', 'R', 'E', 'E', 0, 1}).putShort((short) 1);  // group node, level 1, 1 entry
        b.putLong(-1).putLong(-1);                                        // siblings
        b.putLong(0).putLong(0).putLong(0);                               // key, child = this node, key
        assertThrows(HdfFormatException.class, () -> GroupBTreeV1.readEntries(context(b.array()), 0));
    }

    /** Datatypes nest through vlen/array/compound bases; a deep chain used to overflow the stack. */
    @Test
    void deeplyNestedDatatypeFails() {
        ByteBuffer b = ByteBuffer.allocate(200 * 8 + 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 200; i++) {
            b.put((byte) 0x19).put((byte) 0).putShort((short) 0).putInt(16); // vlen sequence of ...
        }
        b.put(new byte[] {0x10, 0x08, 0, 0, 4, 0, 0, 0, 0, 0, 32, 0});      // ... int32
        assertThrows(HdfFormatException.class, () -> DatatypeMessage.parse(HdfBuffer.of(b.array()), 0));
    }

    /** A deflate chunk that inflates far past the chunk size (a zip bomb) is refused, not buffered. */
    @Test
    void deflateBombFails() {
        Deflater deflater = new Deflater(9);
        deflater.setInput(new byte[4 << 20]);
        deflater.finish();
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        byte[] scratch = new byte[8192];
        while (!deflater.finished()) {
            bomb.write(scratch, 0, deflater.deflate(scratch));
        }
        FilterPipeline pipeline = new FilterPipeline(List.of(new FilterPipeline.Filter(Filters.DEFLATE, 0, new int[] {6})));
        assertThrows(HdfFormatException.class, () -> pipeline.decode(bomb.toByteArray(), 0, 4, 16));
    }

    /** szip parameters with zero pixels per block used to loop forever. */
    @Test
    void invalidSzipParametersFail() {
        FilterPipeline pipeline = new FilterPipeline(List.of(
                new FilterPipeline.Filter(Filters.SZIP, 1, new int[] {0xA9, 0, 8, 8})));
        assertThrows(HdfFormatException.class, () -> pipeline.decode(new byte[] {16, 0, 0, 0, 1, 2, 3}, 0, 1, 16));
    }

    /** fletcher32 is verified (the correct and the pre-1.6.3 byte-swapped value), not just stripped. */
    @Test
    void fletcher32IsVerified() {
        byte[] data = {1, 2, 3, 4, 5, 6, 7};
        FilterPipeline pipeline = new FilterPipeline(List.of(new FilterPipeline.Filter(Filters.FLETCHER32, 0, new int[0])));
        int checksum = Fletcher32.checksum(data, data.length);
        assertArrayEquals(data, pipeline.decode(withChecksum(data, checksum), 0, 1, data.length));
        assertArrayEquals(data, pipeline.decode(withChecksum(data, Fletcher32.legacyByteSwapped(checksum)), 0, 1, data.length));
        byte[] corrupt = withChecksum(data, checksum);
        corrupt[3] ^= 0x10;
        assertThrows(HdfFormatException.class, () -> pipeline.decode(corrupt, 0, 1, data.length));
    }

    private static byte[] withChecksum(byte[] data, int checksum) {
        byte[] out = Arrays.copyOf(data, data.length + 4);
        ByteBuffer.wrap(out, data.length, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(checksum);
        return out;
    }

    /**
     * A flipped byte inside any version-2 object header is caught by its checksum. Unverified, it could
     * silently change a dataset's shape, type, or data location.
     */
    @Test
    void corruptObjectHeadersAreDetected() throws IOException {
        byte[] original = Files.readAllBytes(Fixtures.path("new_style_groups.h5"));
        int headers = 0;
        for (int at = 0; at + 4 <= original.length; at++) {
            if (original[at] != 'O' || original[at + 1] != 'H' || original[at + 2] != 'D' || original[at + 3] != 'R') {
                continue;
            }
            headers++;
            byte[] corrupt = original.clone();
            corrupt[at + 20] ^= 0x01; // a byte inside the header's messages
            Path file = Files.createTempFile("falcon-ohdr", ".h5");
            try {
                Files.write(file, corrupt);
                assertThrows(HdfException.class, () -> {
                    try (Hdf5File h5 = Hdf5File.open(file)) {
                        readEverything(h5.root());
                    }
                }, "flipped byte in the object header at " + at);
            } finally {
                Files.deleteIfExists(file);
            }
        }
        assertTrue(headers >= 4, "expected several object headers, found " + headers);
    }

    /** A virtual dataset whose source is itself used to recurse until the stack overflowed. */
    @Test
    void selfReferencingVirtualDatasetFails() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_loop.h5"))) {
            assertThrows(HdfFormatException.class, () -> h5.root().dataset("v").readInts());
        }
    }

    private static void readEverything(Hdf5Object object) {
        for (Attribute attribute : object.attributes()) {
            attribute.read();
        }
        if (object instanceof Group group) {
            for (Hdf5Object child : group.children()) {
                readEverything(child);
            }
        } else if (object instanceof Dataset dataset) {
            dataset.readRawBytes();
        }
    }
}
