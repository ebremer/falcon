package com.ebremer.falcon.hdf5.superblock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.checksum.Lookup3;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The metadata cache image block (spec section III.J), from crafted blocks: its entries are read at their
 * addresses, and a block that is not what it says fails with a typed exception before anything is read
 * through it. (Files libhdf5 closed with an image are read in {@code CacheImageTest}.)
 */
class MetadataCacheImageTest {

    private static final int FILE = 2048;
    private static final int BLOCK = 1024; // where the image block is in the file

    /** An entry of an image: the address its bytes belong at, the bytes, and its flush dependency parents. */
    private record Entry(long address, byte[] image, long... parents) {
    }

    private static byte[] bytes(int length, int first) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (first + i);
        }
        return bytes;
    }

    private static void put(ByteBuffer b, long value, int width) {
        for (int i = 0; i < width; i++) {
            b.put((byte) (value >>> (8 * i)));
        }
    }

    /** An image block of the entries, as libhdf5 writes one, with addresses and lengths of {@code width} bytes. */
    private static byte[] block(int width, Entry... entries) {
        int size = 4 + 1 + 1 + width + 4 + 4;
        for (Entry entry : entries) {
            size += 14 + 2 * width + entry.parents().length * width + entry.image().length;
        }
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'M', 'D', 'C', 'I', 0, 0});
        put(b, size, width);
        b.putInt(entries.length);
        for (Entry entry : entries) {
            boolean child = entry.parents().length > 0;
            b.put((byte) 5).put((byte) (child ? 0x09 : 0x01)).put((byte) 1).put((byte) 0); // type, flags, ring, age
            b.putShort((short) 0).putShort((short) 0).putShort((short) entry.parents().length).putInt(-1);
            put(b, entry.address(), width);
            put(b, entry.image().length, width);
            for (long parent : entry.parents()) {
                put(b, parent, width);
            }
            b.put(entry.image());
        }
        return seal(b.array());
    }

    /** {@code block} with its checksum set to the hash of what precedes it. */
    private static byte[] seal(byte[] block) {
        ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(block.length - 4, Lookup3.hashLittle(block, 0, block.length - 4, 0));
        return block;
    }

    /** A file of {@link #FILE} bytes, every one 0x55, with {@code block} at {@link #BLOCK}. */
    private static byte[] file(byte[] block) {
        byte[] file = new byte[FILE];
        Arrays.fill(file, (byte) 0x55);
        System.arraycopy(block, 0, file, BLOCK, block.length);
        return file;
    }

    private static HdfBuffer read(byte[] block, int width) {
        return MetadataCacheImage.read(HdfBuffer.of(file(block)), width, width, BLOCK, block.length);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 4, 2})
    void entriesAreReadAtTheirAddresses(int width) {
        Entry[] entries = {
            new Entry(300, bytes(40, 1)),
            new Entry(48, bytes(100, 60), 300, 700), // listed out of order, with two parent addresses to skip
            new Entry(700, bytes(1, -7), 300),
            new Entry(340, bytes(16, 90)),           // right after the first
        };
        byte[] block = block(width, entries);
        byte[] file = file(block);
        HdfBuffer buffer = MetadataCacheImage.read(HdfBuffer.of(file), width, width, BLOCK, block.length);
        byte[] expected = file.clone();
        for (Entry entry : entries) {
            System.arraycopy(entry.image(), 0, expected, (int) entry.address(), entry.image().length);
            assertArrayEquals(entry.image(), buffer.getBytes(entry.address(), entry.image().length));
        }
        assertEquals(FILE, buffer.size());
        assertArrayEquals(expected, buffer.getBytes(0, FILE));
        assertArrayEquals(block, buffer.getBytes(BLOCK, block.length), "the image block itself is where it is");
    }

    /** Fails reading the block {@code change} makes of a good one; the block's checksum is then set right. */
    private static <T extends Throwable> T refused(Class<T> type, Consumer<ByteBuffer> change) {
        byte[] block = block(8, new Entry(48, bytes(100, 60), 300), new Entry(300, bytes(40, 1)));
        change.accept(ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN));
        seal(block);
        return assertThrows(type, () -> read(block, 8));
    }

    // In that block: the header is 18 bytes; the first entry's header is at 18 (its parent count at 26, its
    // address at 32, its length at 40), its parent address at 48, its image at 56; the second entry is at 156.

    @Test
    void aBlockThatIsNotAnImageIsRefused() {
        assertTrue(refused(HdfFormatException.class, b -> b.put(0, (byte) 'X')).getMessage().contains("'MDCI'"));
        assertTrue(refused(HdfFormatException.class, b -> b.put(4, (byte) 1)).getMessage().contains("version 1"));
        assertTrue(refused(HdfUnsupportedException.class, b -> b.put(5, (byte) 1)).getMessage().contains("resize status"));
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(6, b.capacity() + 1)).getMessage().contains("its message says"));
    }

    @Test
    void aBlockWhoseChecksumDoesNotMatchIsRefused() {
        byte[] block = block(8, new Entry(48, bytes(100, 60)));
        block[60] ^= 1; // in the entry's image
        assertTrue(assertThrows(HdfFormatException.class, () -> read(block, 8)).getMessage().contains("checksum mismatch"));
        byte[] other = block(8, new Entry(48, bytes(100, 60)));
        other[other.length - 1] ^= 0x40; // in the checksum
        assertTrue(assertThrows(HdfFormatException.class, () -> read(other, 8)).getMessage().contains("checksum mismatch"));
    }

    @Test
    void anEntryCountThatCannotBeIsRefused() {
        assertTrue(refused(HdfFormatException.class, b -> b.putInt(14, 0)).getMessage().contains("0 entries"));
        assertTrue(refused(HdfFormatException.class, b -> b.putInt(14, -1)).getMessage().contains("4294967295 entries"));
        // one entry more than there is: the third would start in the checksum
        assertTrue(refused(HdfFormatException.class, b -> b.putInt(14, 3)).getMessage().contains("runs past the image's end"));
    }

    @Test
    void anEntryThatDoesNotFitIsRefused() {
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(40, 0)).getMessage().contains("is 0 bytes long"));
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(40, -1)).getMessage().contains("bytes long"));
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(40, 5000)).getMessage().contains("runs past"));
        assertTrue(refused(HdfFormatException.class, b -> b.putShort(26, (short) 0xffff)).getMessage().contains("runs past"),
                "more parent addresses than the block holds");
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(32, FILE - 50)).getMessage().contains("lies outside"));
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(32, 1L << 40)).getMessage().contains("lies outside"));
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(32, -1)).getMessage().contains("an undefined address"));
    }

    @Test
    void entriesThatOverlapAreRefused() {
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(32, 300)).getMessage().contains("overlap"), "the same address");
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(32, 250)).getMessage().contains("overlap"));
        assertTrue(refused(HdfFormatException.class, b -> b.putLong(32, 339)).getMessage().contains("overlap"));
    }

    @Test
    void aBlockOutsideTheFileIsRefused() {
        byte[] block = block(8, new Entry(48, bytes(100, 60)));
        HdfBuffer file = HdfBuffer.of(file(block));
        for (long[] at : new long[][] {{FILE, block.length}, {FILE - 10, block.length}, {-1, block.length}, {BLOCK, FILE},
                {BLOCK, 21}, {BLOCK, 0}, {BLOCK, -5}, {Long.MAX_VALUE, block.length}, {BLOCK, Long.MAX_VALUE}}) {
            assertTrue(assertThrows(HdfFormatException.class, () -> MetadataCacheImage.read(file, 8, 8, at[0], at[1]))
                    .getMessage().contains("lies outside"), at[0] + ", " + at[1]);
        }
        // a block cut short in the file: its last bytes are not there to check
        HdfBuffer cut = HdfBuffer.of(Arrays.copyOf(file(block), BLOCK + block.length - 1));
        assertThrows(HdfFormatException.class, () -> MetadataCacheImage.read(cut, 8, 8, BLOCK, block.length));
    }

    /**
     * Blocks with a few bytes changed and the checksum set right again, so that what is changed is parsed:
     * each is read whole, or refused with a typed exception.
     */
    @Test
    void changedBlocksAreReadOrRefused() {
        Entry[] entries = {new Entry(48, bytes(100, 60), 300), new Entry(300, bytes(40, 1)), new Entry(700, bytes(9, 3), 48, 300)};
        Random random = new Random(20261113);
        int read = 0;
        for (int k = 0; k < 20_000; k++) {
            byte[] block = block(8, entries);
            for (int changes = 1 + random.nextInt(3); changes > 0; changes--) {
                block[random.nextInt(block.length - 4)] = (byte) (random.nextInt(4) == 0 ? random.nextInt(256) : random.nextInt(3) - 1);
            }
            seal(block);
            try {
                HdfBuffer buffer = read(block, 8);
                assertEquals(FILE, buffer.getBytes(0, FILE).length);
                read++;
            } catch (HdfFormatException | HdfUnsupportedException refused) {
                // typed: the block is not an image
            }
        }
        assertTrue(read > 1000 && read < 19_000, read + " of the changed blocks were read"); // both happen
    }

    @Test
    void aFileWithoutAnExtensionIsReadAsItIs() {
        HdfBuffer file = HdfBuffer.of(new byte[64]);
        assertSame(file, MetadataCacheImage.apply(file, 8, 8, HdfBuffer.UNDEFINED_ADDRESS));
        assertSame(file, MetadataCacheImage.apply(file, 8, 8, 16), "an extension that is not an object header");
        assertSame(file, MetadataCacheImage.apply(file, 8, 8, 1L << 40), "an extension outside the file");
    }
}
