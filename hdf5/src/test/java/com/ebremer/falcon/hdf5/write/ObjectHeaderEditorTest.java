package com.ebremer.falcon.hdf5.write;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.checksum.Lookup3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Object headers changed in place (P2 WF6): both versions, null space, continuation chunks, gaps. */
class ObjectHeaderEditorTest {

    private static final int NEW_SPACE = 4096; // where the edits' new chunks go in the test "file"

    /** A version-2 header (no creation order) of the given messages, with {@code gap} bytes after them. */
    private static byte[] headerV2(List<byte[]> messages, int gap) {
        GrowBuffer body = new GrowBuffer();
        for (byte[] message : messages) {
            body.bytes(message);
        }
        for (int i = 0; i < gap; i++) {
            body.u8(0);
        }
        GrowBuffer b = new GrowBuffer();
        b.bytes(new byte[] {'O', 'H', 'D', 'R'});
        b.u8(2);
        b.u8(0);              // flags: 1-byte chunk size
        b.u8(body.size());
        b.bytes(body.toByteArray());
        b.u32(b.checksum(0, b.position()));
        return b.toByteArray();
    }

    private static byte[] messageV2(int type, byte[] body) {
        GrowBuffer b = new GrowBuffer();
        b.u8(type);
        b.u16(body.length);
        b.u8(0);
        b.bytes(body);
        return b.toByteArray();
    }

    /** A version-1 header of the given messages (each body a multiple of 8 bytes). */
    private static byte[] headerV1(List<byte[]> bodies, int[] types) {
        GrowBuffer b = new GrowBuffer();
        int size = 0;
        for (byte[] body : bodies) {
            size += 8 + body.length;
        }
        b.u8(1);
        b.u8(0);
        b.u16(bodies.size());
        b.u32(1);
        b.u32(size);
        b.u32(0);
        for (int i = 0; i < bodies.size(); i++) {
            b.u16(types[i]);
            b.u16(bodies.get(i).length);
            b.u32(0);
            b.bytes(bodies.get(i));
        }
        return b.toByteArray();
    }

    /** The file after the edits: the header's changed chunks written over it, the new ones after it. */
    private static byte[] apply(byte[] header, ObjectHeaderEditor editor) {
        GrowBuffer buf = new GrowBuffer(NEW_SPACE);
        List<ObjectHeaderEditor.Patch> patches = editor.write(buf);
        byte[] file = new byte[NEW_SPACE + buf.size()];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(buf.toByteArray(), 0, file, NEW_SPACE, buf.size());
        for (ObjectHeaderEditor.Patch patch : patches) {
            System.arraycopy(patch.bytes(), 0, file, (int) patch.address(), patch.bytes().length);
        }
        return file;
    }

    private static ObjectHeaderEditor load(byte[] file) {
        return ObjectHeaderEditor.load((address, length) -> Arrays.copyOfRange(file, (int) address, (int) address + length), 0);
    }

    private static List<Integer> types(ObjectHeaderEditor editor) {
        List<Integer> types = new ArrayList<>();
        for (ObjectHeaderEditor.Message message : editor.messages()) {
            types.add(message.type());
        }
        return types;
    }

    @Test
    void aGapIsMergedIntoTheNullSpaceAMessageLeaves() {
        byte[] header = headerV2(List.of(messageV2(6, new byte[12]), messageV2(6, new byte[13]), messageV2(12, new byte[5])), 3);
        ObjectHeaderEditor editor = load(header);
        editor.remove(editor.messages(6).getFirst());
        byte[] file = apply(header, editor);
        // As libhdf5 leaves it: the messages first, then one null message ending the chunk, and no gap.
        int at = 7;
        assertEquals(6, file[at]);
        assertEquals(13, file[at + 1]);
        at += 4 + 13;
        assertEquals(12, file[at]);
        at += 4 + 5;
        assertEquals(0, file[at]);
        assertEquals(12 + 3, file[at + 1] & 0xff); // the removed message's bytes and the gap
        int end = 7 + (file[6] & 0xff);
        assertEquals(end, at + 4 + 15);
        assertEquals(Lookup3.hashLittle(Arrays.copyOf(file, end)), (int) u32(file, end));
        assertEquals(List.of(6, 12), types(load(file)));
    }

    @Test
    void aMessageWithoutRoomGoesToANewChunkWithAMessageMovedForItsContinuation() {
        byte[] header = headerV2(List.of(messageV2(1, new byte[24]), messageV2(3, new byte[8])), 0);
        ObjectHeaderEditor editor = load(header);
        byte[] added = new byte[100];
        Arrays.fill(added, (byte) 7);
        editor.add(12, 0, added);
        byte[] file = apply(header, editor);
        ObjectHeaderEditor reloaded = load(file);
        assertEquals(3, types(reloaded).size());
        assertTrue(types(reloaded).containsAll(List.of(1, 3, 12)), "the moved message is still in the header");
        assertArrayEquals(added, reloaded.find(12).body());
        assertArrayEquals(new byte[24], reloaded.find(1).body());
    }

    @Test
    void replacingKeepsTheSlotOfAMessageOfTheSameSize() {
        byte[] header = headerV2(List.of(messageV2(2, new byte[18]), messageV2(0, new byte[40])), 0);
        ObjectHeaderEditor editor = load(header);
        byte[] info = new byte[18];
        info[2] = 9;
        editor.replace(editor.find(2), info);
        editor.add(6, 0, new byte[20]); // into the null message, whose rest stays null
        byte[] file = apply(header, editor);
        ObjectHeaderEditor reloaded = load(file);
        assertArrayEquals(info, reloaded.find(2).body());
        assertEquals(List.of(2, 6), types(reloaded));
        assertEquals(file.length, NEW_SPACE, "no new chunk");
        // A larger body moves to new space.
        ObjectHeaderEditor again = load(file);
        again.replace(again.find(2), new byte[30]);
        assertArrayEquals(new byte[30], load(apply(file, again)).find(2).body());
    }

    @Test
    void referenceCountsInBothVersions() {
        byte[] v2 = headerV2(List.of(messageV2(1, new byte[8]), messageV2(0, new byte[16])), 0);
        ObjectHeaderEditor editor = load(v2);
        assertEquals(1, editor.referenceCount());
        editor.setReferenceCount(3);
        ObjectHeaderEditor three = load(apply(v2, editor));
        assertEquals(3, three.referenceCount());
        three.setReferenceCount(1);
        assertEquals(1, load(apply(apply(v2, editor), three)).referenceCount());
        assertFalse(types(load(apply(apply(v2, editor), three))).contains(0x16));

        byte[] v1 = headerV1(List.of(new byte[8], new byte[16]), new int[] {1, 0});
        ObjectHeaderEditor old = load(v1);
        old.setReferenceCount(5);
        old.add(12, 0, new byte[5]); // a 5-byte body takes 8 bytes of the 16-byte null message
        byte[] file = apply(v1, old);
        ObjectHeaderEditor reloaded = load(file);
        assertEquals(5, reloaded.referenceCount());
        assertEquals(List.of(1, 12), types(reloaded));
        assertEquals(3, file[2], "the prefix counts the null message the rest became");
    }

    @Test
    void aVersion1HeaderGrowsAContinuationChunk() {
        byte[] v1 = headerV1(List.of(new byte[24], new byte[8]), new int[] {1, 3});
        ObjectHeaderEditor editor = load(v1);
        for (int i = 0; i < 5; i++) {
            editor.add(12, 0, new byte[40 + i]);
        }
        ObjectHeaderEditor reloaded = load(apply(v1, editor));
        assertEquals(5, reloaded.messages(12).size());
        assertEquals(1, reloaded.messages(1).size());
        assertEquals(1, reloaded.messages(3).size());
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24;
    }
}
