package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ZipStoreTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void packsAndReadsBackAStore(@TempDir Path tmp) {
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/zarr.json", bytes("group-a"));
        source.set("a/c/0", bytes("chunk-0"));
        source.set("a/c/1", bytes("chunk-1"));

        Path archive = tmp.resolve("store.zip");
        ZipStore.pack(source, archive);

        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertFalse(zip.isWritable());
            assertEquals(List.of("a/c/0", "a/c/1", "a/zarr.json", "zarr.json"), zip.list());
            assertEquals(List.of("a/", "zarr.json"), zip.listDir(""));
            assertEquals(List.of("a/c/", "a/zarr.json"), zip.listDir("a/"));
            assertArrayEquals(bytes("chunk-0"), zip.get("a/c/0").orElseThrow());
            assertTrue(zip.exists("a/c/1"));
            assertFalse(zip.exists("missing"));
            assertEquals(OptionalLong.of(7), zip.size("a/c/0"));
            assertTrue(zip.size("missing").isEmpty());
            // byte ranges are sliced from the whole entry
            assertArrayEquals(bytes("unk"), zip.get("a/c/0").map(b -> new byte[] {b[2], b[3], b[4]}).orElseThrow());
            assertArrayEquals(new byte[] {'c', 'h'}, zip.getRange("a/c/0", 0, 2).orElseThrow());
            assertArrayEquals(new byte[0], zip.getRange("a/c/0", 100, 5).orElseThrow());
            assertThrows(UnsupportedOperationException.class, () -> zip.set("x", bytes("y")));
            assertThrows(UnsupportedOperationException.class, () -> zip.delete("a/c/0"));
        }
    }

    /**
     * {@code pack} deflated every entry, though Zarr archives (zarr-python's ZipStore) store entries
     * uncompressed: the chunks are compressed already, and only a stored entry can be read a range at a
     * time (P1 PF4).
     */
    @Test
    void packStoresEntriesUncompressed(@TempDir Path tmp) throws Exception {
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/c/0", new byte[100_000]);
        source.set("empty", new byte[0]);
        Path archive = tmp.resolve("store.zip");
        ZipStore.pack(source, archive);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                assertEquals(ZipEntry.STORED, entry.getMethod(), entry.getName());
            }
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertArrayEquals(new byte[100_000], zip.get("a/c/0").orElseThrow());
            assertArrayEquals(new byte[0], zip.get("empty").orElseThrow());
        }
    }

    /**
     * {@code getRange} decompressed or read the whole entry on every call, so sharded data in a ZIP cost a
     * shard per sub-chunk (P1 PF4). A stored entry is now read at the range; a deflated one is inflated only
     * up to the range's end. Both must still give the same bytes.
     */
    @Test
    void rangesAndSuffixesOfStoredAndDeflatedEntries(@TempDir Path tmp) throws Exception {
        byte[] value = new byte[300_000];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (i % 251 * 3 + i / 4096);
        }
        Path stored = tmp.resolve("stored.zip");
        MemoryStore source = new MemoryStore();
        source.set("v", value);
        ZipStore.pack(source, stored);
        Path deflated = tmp.resolve("deflated.zip");
        try (OutputStream out = Files.newOutputStream(deflated); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("v")); // DEFLATED, the ZipOutputStream default
            zip.write(value);
            zip.closeEntry();
        }
        for (Path archive : new Path[] {stored, deflated}) {
            try (ZipStore zip = ZipStore.openReadOnly(archive)) {
                assertArrayEquals(value, zip.get("v").orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 0, 10), zip.getRange("v", 0, 10).orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 123_456, 133_456), zip.getRange("v", 123_456, 10_000).orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 299_990, 300_000), zip.getRange("v", 299_990, 100).orElseThrow());
                assertArrayEquals(new byte[0], zip.getRange("v", 300_000, 5).orElseThrow());
                assertArrayEquals(new byte[0], zip.getRange("v", 5, 0).orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 299_000, 300_000), zip.getSuffix("v", 1000).orElseThrow());
                assertArrayEquals(value, zip.getSuffix("v", 1_000_000).orElseThrow());
                assertTrue(zip.getRange("absent", 0, 4).isEmpty());
                assertTrue(zip.getSuffix("absent", 4).isEmpty());
            }
        }
    }

    @Test
    void readsAZarrHierarchyFromAZip(@TempDir Path tmp) {
        // Build a real Zarr store, pack it, and read the array back out of the archive.
        MemoryStore memory = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(memory);
        root.createArray("data", ArraySpec.builder(new long[] {10}, DataType.INT32)
                .chunkShape(4).gzip(5).build())
                .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        Path archive = tmp.resolve("data.zip");
        ZipStore.pack(memory, archive);

        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            ZarrGroup group = Zarr.open(zip).asGroup();
            assertEquals(List.of("data"), group.childNames());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, group.array("data").readInts());
            assertArrayEquals(new int[] {3, 4, 5, 6},
                    group.array("data").select(new long[] {3}, new long[] {4}).readInts());
        }
    }

    // ---- writing (F13) ----------------------------------------------------------------------------

    /**
     * The archive's files as the JDK's {@code ZipFile} reads them, from the central directory: a second
     * implementation's view. Each must be STORED, named once, and match its CRC-32.
     */
    private static Map<String, byte[]> readWithTheJdk(Path archive) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                assertEquals(ZipEntry.STORED, e.getMethod(), e.getName());
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] value = in.readAllBytes();
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(value);
                    assertEquals(e.getCrc(), crc.getValue(), e.getName());
                    assertEquals(null, out.put(e.getName(), value), "a name twice in the directory: " + e.getName());
                }
            }
        }
        return out;
    }

    /**
     * The archive as a streaming reader sees it, local header after local header, each checked against its
     * CRC-32 and sizes: an archive without dead space must hold just its files this way too.
     */
    private static Map<String, byte[]> streamWithTheJdk(Path archive) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (InputStream in = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                assertEquals(null, out.put(e.getName(), zip.readAllBytes()), e.getName());
            }
        }
        return out;
    }

    @Test
    void writesAnArchiveThatReadsBackBeforeAndAfterClosing(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("w.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            assertTrue(zip.isWritable());
            assertTrue(zip.list().isEmpty());
            zip.set("zarr.json", bytes("root"));
            zip.set("a/c/0", bytes("chunk-0"));
            zip.set("a/c/1", new byte[0]);
            zip.set("größe/zarr.json", bytes("ü")); // a UTF-8 name
            // everything reads back before the central directory exists
            assertEquals(List.of("a/c/0", "a/c/1", "größe/zarr.json", "zarr.json"), zip.list());
            assertEquals(List.of("a/", "größe/", "zarr.json"), zip.listDir(""));
            assertArrayEquals(bytes("chunk-0"), zip.get("a/c/0").orElseThrow());
            assertArrayEquals(bytes("unk-"), zip.getRange("a/c/0", 2, 4).orElseThrow());
            assertArrayEquals(bytes("-0"), zip.getSuffix("a/c/0", 2).orElseThrow());
            assertEquals(OptionalLong.of(0), zip.size("a/c/1"));
            assertArrayEquals(new byte[0], zip.get("a/c/1").orElseThrow());
        }
        Map<String, byte[]> jdk = readWithTheJdk(archive);
        assertEquals(4, jdk.size());
        assertArrayEquals(bytes("ü"), jdk.get("größe/zarr.json"));
        Map<String, byte[]> streamed = streamWithTheJdk(archive);
        assertEquals(jdk.keySet(), streamed.keySet());
        assertArrayEquals(bytes("chunk-0"), streamed.get("a/c/0"));
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertEquals(List.of("a/c/0", "a/c/1", "größe/zarr.json", "zarr.json"), zip.list());
            assertArrayEquals(bytes("root"), zip.get("zarr.json").orElseThrow());
        }
    }

    /**
     * Writing a key again appends an entry; the central directory names only the newest, so the archive
     * has no duplicate names (zarr-python's has, with a warning). A delete drops the key. Both leave the old
     * bytes as dead space, which packing into a new archive leaves out.
     */
    @Test
    void writingAKeyAgainOrDeletingItKeepsOnlyTheNewestEntry(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("w.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            zip.set("k", bytes("first"));
            zip.set("other", bytes("x"));
            zip.set("k", bytes("second, longer"));
            zip.set("gone", bytes("soon"));
            assertArrayEquals(bytes("second, longer"), zip.get("k").orElseThrow());
            zip.delete("gone");
            zip.delete("never-there");
            assertFalse(zip.exists("gone"));
            assertEquals(List.of("k", "other"), zip.list());
        }
        Map<String, byte[]> jdk = readWithTheJdk(archive);
        assertEquals(List.of("k", "other"), jdk.keySet().stream().sorted().toList());
        assertArrayEquals(bytes("second, longer"), jdk.get("k"));

        Path packed = tmp.resolve("packed.zip");
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            ZipStore.pack(zip, packed);
        }
        assertTrue(Files.size(packed) < Files.size(archive), "packing leaves out the dead space");
        try (ZipStore zip = ZipStore.openReadOnly(archive)) { // not into its own file, which create() would empty
            assertThrows(IllegalArgumentException.class, () -> ZipStore.pack(zip, archive));
        }
        assertEquals(List.of("k", "other"), readWithTheJdk(packed).keySet().stream().sorted().toList());
        // A streaming reader, walking local headers rather than the directory, sees the dead entries too...
        assertThrows(AssertionError.class, () -> streamWithTheJdk(archive));
        // ... and none once the archive is packed.
        assertEquals(List.of("k", "other"), streamWithTheJdk(packed).keySet().stream().sorted().toList());
    }

    /**
     * Opening an archive to add to it writes new entries where its central directory was and a new
     * directory at close; the archive's entries, deflated ones included, and its comment are kept.
     */
    @Test
    void addsToAnExistingArchive(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("a.zip");
        byte[] text = bytes("deflated ".repeat(1000));
        try (OutputStream out = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setComment("made by the JDK");
            zip.putNextEntry(new ZipEntry("old/deflated"));
            zip.write(text);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("old/dir/")); // a directory entry: kept, never listed
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("old/replaced"));
            zip.write(bytes("before"));
            zip.closeEntry();
        }
        try (ZipStore zip = ZipStore.open(archive)) {
            assertEquals(List.of("old/deflated", "old/replaced"), zip.list());
            assertArrayEquals(text, zip.get("old/deflated").orElseThrow());
            zip.set("new/key", bytes("added"));
            zip.set("old/replaced", bytes("after"));
            assertArrayEquals(bytes("after"), zip.get("old/replaced").orElseThrow());
        }
        try (ZipFile jdk = new ZipFile(archive.toFile())) {
            assertEquals("made by the JDK", jdk.getComment());
            assertEquals(4, jdk.size());
            assertTrue(jdk.getEntry("old/dir/").isDirectory());
            assertArrayEquals(text, jdk.getInputStream(jdk.getEntry("old/deflated")).readAllBytes());
            assertArrayEquals(bytes("after"), jdk.getInputStream(jdk.getEntry("old/replaced")).readAllBytes());
            assertArrayEquals(bytes("added"), jdk.getInputStream(jdk.getEntry("new/key")).readAllBytes());
        }
        // a second session, deleting an entry it did not write
        try (ZipStore zip = ZipStore.open(archive)) {
            zip.delete("old/deflated");
            zip.set("new/second", bytes("2"));
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertEquals(List.of("new/key", "new/second", "old/replaced"), zip.list());
            assertArrayEquals(bytes("after"), zip.get("old/replaced").orElseThrow());
        }
        try (ZipFile jdk = new ZipFile(archive.toFile())) {
            assertEquals(4, jdk.size()); // the directory entry is still there
        }
        // a session that only deletes still rewrites the directory
        try (ZipStore zip = ZipStore.open(archive)) {
            zip.delete("new/key");
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertEquals(List.of("new/second", "old/replaced"), zip.list());
        }
    }

    @Test
    void openingWithoutChangesLeavesTheFileAsItWas(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("a.zip");
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/c/0", bytes("chunk"));
        ZipStore.pack(source, archive);
        byte[] before = Files.readAllBytes(archive);
        try (ZipStore zip = ZipStore.open(archive)) {
            assertArrayEquals(bytes("chunk"), zip.get("a/c/0").orElseThrow());
            zip.delete("absent");
        }
        assertArrayEquals(before, Files.readAllBytes(archive));
    }

    @Test
    void openingAMissingOrEmptyFileStartsAnArchive(@TempDir Path tmp) throws Exception {
        Path missing = tmp.resolve("missing.zip");
        try (ZipStore zip = ZipStore.open(missing)) {
            assertTrue(zip.list().isEmpty());
        }
        assertEquals(0, readWithTheJdk(missing).size()); // an empty archive: just the end record
        assertEquals(22, Files.size(missing));

        Path empty = Files.createFile(tmp.resolve("empty.zip"));
        try (ZipStore zip = ZipStore.open(empty)) {
            zip.set("k", bytes("v"));
        }
        assertArrayEquals(bytes("v"), readWithTheJdk(empty).get("k"));
    }

    @Test
    void aFileThatIsNotAnArchiveIsRefused(@TempDir Path tmp) throws Exception {
        Path text = Files.writeString(tmp.resolve("notes.txt"), "not a zip archive at all");
        assertThrows(ZarrFormatException.class, () -> ZipStore.openReadOnly(text));
        assertThrows(ZarrFormatException.class, () -> ZipStore.open(text));
        assertEquals("not a zip archive at all", Files.readString(text)); // untouched
        assertThrows(ZarrException.class, () -> ZipStore.openReadOnly(tmp.resolve("missing.zip")));
    }

    @Test
    void aClosedStoreRefusesEverythingButClose(@TempDir Path tmp) {
        ZipStore zip = ZipStore.create(tmp.resolve("c.zip"));
        zip.set("k", bytes("v"));
        zip.close();
        zip.close(); // a second close does nothing
        assertThrows(IllegalStateException.class, () -> zip.get("k"));
        assertThrows(IllegalStateException.class, () -> zip.getRange("k", 0, 1));
        assertThrows(IllegalStateException.class, () -> zip.exists("k"));
        assertThrows(IllegalStateException.class, zip::list);
        assertThrows(IllegalStateException.class, () -> zip.set("k", bytes("w")));
        assertThrows(IllegalStateException.class, () -> zip.delete("k"));
    }

    /** 70,000 entries need the Zip64 end records (the end record counts to 65,535). */
    @Test
    void manyEntriesWriteZip64EndRecords(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("many.zip");
        int n = 70_000;
        try (ZipStore zip = ZipStore.create(archive)) {
            for (int i = 0; i < n; i++) {
                zip.set("c/" + i, new byte[] {(byte) i});
            }
        }
        byte[] file = Files.readAllBytes(archive);
        ByteBuffer end = ByteBuffer.wrap(file, file.length - 22, 22).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x06054b50, end.getInt(file.length - 22));
        assertEquals(0xFFFF, Short.toUnsignedInt(end.getShort(file.length - 22 + 10)), "count in Zip64 only");
        assertEquals(0x07064b50, ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getInt(file.length - 42));
        try (ZipFile jdk = new ZipFile(archive.toFile())) {
            assertEquals(n, jdk.size());
            assertArrayEquals(new byte[] {(byte) 69_999}, jdk.getInputStream(jdk.getEntry("c/69999")).readAllBytes());
        }
        try (ZipStore zip = ZipStore.open(archive)) {
            assertEquals(n, zip.list().size());
            zip.set("c/70000", new byte[] {1});
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertEquals(n + 1, zip.list().size());
            assertArrayEquals(new byte[] {(byte) 12_345}, zip.get("c/12345").orElseThrow());
        }
    }

    /**
     * Offsets and sizes past 4 GiB go in Zip64 fields. Writing gigabytes is too slow for a unit test, so the
     * threshold is lowered to 0: every offset, the directory's size, and its offset take the Zip64 form,
     * which the JDK must read as it reads a large archive.
     */
    @Test
    void offsetsInZip64FieldsReadBack(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("zip64.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            zip.zip64Threshold = 0;
            zip.set("zarr.json", bytes("root"));
            zip.set("a/c/0", new byte[1000]);
            zip.set("a/c/1", bytes("one"));
        }
        byte[] file = Files.readAllBytes(archive);
        ByteBuffer le = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xFFFFFFFFL, Integer.toUnsignedLong(le.getInt(file.length - 22 + 16)), "directory offset");
        assertEquals(0x06064b50, le.getInt(file.length - 22 - 20 - 56), "Zip64 end record");
        Map<String, byte[]> jdk = readWithTheJdk(archive);
        assertArrayEquals(bytes("one"), jdk.get("a/c/1"));
        try (ZipStore zip = ZipStore.open(archive)) { // read Zip64 extra fields, and rewrite them
            assertArrayEquals(bytes("root"), zip.get("zarr.json").orElseThrow());
            zip.set("a/c/2", bytes("two"));
        }
        assertArrayEquals(bytes("two"), readWithTheJdk(archive).get("a/c/2"));
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertArrayEquals(new byte[1000], zip.get("a/c/0").orElseThrow());
        }
    }

    /** An archive after other bytes (a self-extractor): its offsets are off by their length, read anyway. */
    @Test
    void anArchiveAfterOtherBytesReads(@TempDir Path tmp) throws Exception {
        Path plain = tmp.resolve("plain.zip");
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/c/0", bytes("chunk"));
        ZipStore.pack(source, plain);
        Path prefixed = tmp.resolve("prefixed.zip");
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(new byte[777]);
        joined.write(Files.readAllBytes(plain));
        Files.write(prefixed, joined.toByteArray());
        try (ZipStore zip = ZipStore.openReadOnly(prefixed)) {
            assertArrayEquals(bytes("chunk"), zip.get("a/c/0").orElseThrow());
        }
        try (ZipStore zip = ZipStore.open(prefixed)) {
            zip.set("a/c/1", bytes("added"));
        }
        try (ZipFile jdk = new ZipFile(prefixed.toFile())) {
            assertArrayEquals(bytes("chunk"), jdk.getInputStream(jdk.getEntry("a/c/0")).readAllBytes());
            assertArrayEquals(bytes("added"), jdk.getInputStream(jdk.getEntry("a/c/1")).readAllBytes());
        }
    }

    /**
     * zarr-python writes a key again by adding an entry of the same name, so its archives hold duplicate
     * names; Python's zipfile reads the last. So must Falcon.
     */
    @Test
    void theLastEntryOfANameIsItsValue(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("dup.zip");
        // Written by hand: the JDK's ZipOutputStream refuses a duplicate name.
        try (ZipStore zip = ZipStore.create(archive)) {
            zip.set("k", bytes("first"));
            zip.set("other", bytes("x"));
        }
        byte[] once = Files.readAllBytes(archive);
        Path duplicated = tmp.resolve("dup2.zip");
        Files.write(duplicated, duplicate(once, "k", bytes("third, the newest")));
        try (ZipStore zip = ZipStore.openReadOnly(duplicated)) {
            assertEquals(List.of("k", "other"), zip.list());
            assertArrayEquals(bytes("third, the newest"), zip.get("k").orElseThrow());
            assertEquals(OptionalLong.of(17), zip.size("k"));
        }
        try (ZipFile jdk = new ZipFile(duplicated.toFile())) {
            assertEquals(3, jdk.size()); // the archive really holds "k" twice
        }
        try (ZipStore zip = ZipStore.open(duplicated)) {
            zip.set("new", bytes("n"));
        }
        assertArrayEquals(bytes("third, the newest"), readWithTheJdk(duplicated).get("k")); // and once only now
    }

    /** {@code archive} with one more STORED entry {@code name} at the end, its directory listing both. */
    private static byte[] duplicate(byte[] archive, String name, byte[] value) {
        ByteBuffer le = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int endAt = archive.length - 22;
        int directoryAt = le.getInt(endAt + 16);
        int directorySize = le.getInt(endAt + 12);
        int count = Short.toUnsignedInt(le.getShort(endAt + 10));
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(value);
        byte[] n = bytes(name);
        ByteBuffer local = ByteBuffer.allocate(30 + n.length + value.length).order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0x21).putInt((int) crc.getValue()).putInt(value.length).putInt(value.length)
                .putShort((short) n.length).putShort((short) 0).put(n).put(value);
        ByteBuffer central = ByteBuffer.allocate(46 + n.length).order(ByteOrder.LITTLE_ENDIAN);
        central.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0x21).putInt((int) crc.getValue()).putInt(value.length)
                .putInt(value.length).putShort((short) n.length).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt(directoryAt).put(n);
        int newDirectoryAt = directoryAt + local.capacity();
        ByteBuffer out = ByteBuffer.allocate(archive.length + local.capacity() + central.capacity())
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(archive, 0, directoryAt).put(local.array()).put(archive, directoryAt, directorySize)
                .put(central.array());
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) (count + 1))
                .putShort((short) (count + 1)).putInt(directorySize + central.capacity()).putInt(newDirectoryAt)
                .putShort((short) 0);
        return out.array();
    }

    @Test
    void damagedArchivesFailWithFormatErrors(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("ok.zip");
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/c/0", bytes("chunk-zero"));
        ZipStore.pack(source, archive);
        byte[] good = Files.readAllBytes(archive);

        // a flipped data byte: the CRC-32 check catches it on a whole read
        byte[] flipped = good.clone();
        int at = indexOf(flipped, bytes("chunk-zero"));
        flipped[at + 3] ^= 1;
        Path bad = Files.write(tmp.resolve("flipped.zip"), flipped);
        try (ZipStore zip = ZipStore.openReadOnly(bad)) {
            assertThrows(ZarrFormatException.class, () -> zip.get("a/c/0"));
            assertArrayEquals(bytes("ch"), zip.getRange("a/c/0", 0, 2).orElseThrow()); // a range is not checked
        }
        // truncated: the end record is gone
        Path truncated = Files.write(tmp.resolve("truncated.zip"), Arrays.copyOf(good, good.length - 30));
        assertThrows(ZarrFormatException.class, () -> ZipStore.openReadOnly(truncated));
        // a directory that claims more bytes than the file holds before it
        byte[] oversize = good.clone();
        ByteBuffer.wrap(oversize).order(ByteOrder.LITTLE_ENDIAN).putInt(oversize.length - 22 + 12, 1 << 30);
        Path big = Files.write(tmp.resolve("oversize.zip"), oversize);
        assertThrows(ZarrFormatException.class, () -> ZipStore.openReadOnly(big));
        // a local header renamed: the directory and the header disagree
        byte[] renamed = good.clone();
        int local = indexOf(renamed, bytes("a/c/0"));
        renamed[local] = 'b';
        Path misnamed = Files.write(tmp.resolve("renamed.zip"), renamed);
        try (ZipStore zip = ZipStore.openReadOnly(misnamed)) {
            assertThrows(ZarrFormatException.class, () -> zip.get("a/c/0"));
        }
        // an entry's offset past its directory
        byte[] offset = good.clone();
        ByteBuffer le = ByteBuffer.wrap(offset).order(ByteOrder.LITTLE_ENDIAN);
        int directoryAt = le.getInt(offset.length - 22 + 16);
        le.putInt(directoryAt + 42, directoryAt + 5);
        Path outside = Files.write(tmp.resolve("outside.zip"), offset);
        assertThrows(ZarrFormatException.class, () -> ZipStore.openReadOnly(outside));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("not found");
    }

    @Test
    void writersAndReadersRunTogether(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("threads.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                threads.add(new Thread(() -> {
                    try {
                        for (int i = 0; i < 1000; i++) {
                            zip.set("w" + id + "/c/" + i, bytes(id + ":" + i));
                            if (i % 25 == 0) {
                                zip.listDir("");
                                int j = i / 2;
                                assertArrayEquals(bytes(id + ":" + j), zip.get("w" + id + "/c/" + j).orElseThrow());
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }));
            }
            threads.forEach(Thread::start);
            for (Thread thread : threads) {
                thread.join();
            }
            assertTrue(errors.isEmpty(), () -> "a thread failed: " + errors.peek());
            assertEquals(8000, zip.list().size());
        }
        Map<String, byte[]> jdk = readWithTheJdk(archive);
        assertEquals(8000, jdk.size());
        assertArrayEquals(bytes("7:999"), jdk.get("w7/c/999"));
    }

    /**
     * The JDK closes a {@code FileChannel} when a thread reading or writing it is interrupted, for every
     * thread. The interrupted read fails; the store opens the file again, and the others carry on.
     */
    @Test
    @Timeout(60) // a store that never opens its file again could retry forever
    void anInterruptedThreadDoesNotBreakTheStore(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("interrupt.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            zip.set("k", bytes("value"));
            Throwable[] seen = new Throwable[2];
            Thread reader = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    zip.get("k");
                } catch (Throwable e) {
                    seen[0] = e;
                }
            });
            reader.start();
            reader.join();
            assertTrue(seen[0] instanceof ZarrException, () -> "the interrupted read: " + seen[0]);
            Thread writer = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    zip.set("w", bytes("interrupted"));
                } catch (Throwable e) {
                    seen[1] = e;
                }
            });
            writer.start();
            writer.join();
            assertTrue(seen[1] instanceof ZarrException, () -> "the interrupted write: " + seen[1]);
            assertFalse(zip.exists("w"));
            assertArrayEquals(bytes("value"), zip.get("k").orElseThrow());
            zip.set("after", bytes("ok"));
        }
        Map<String, byte[]> jdk = readWithTheJdk(archive);
        assertEquals(List.of("after", "k"), jdk.keySet().stream().sorted().toList());
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            Thread reader = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    zip.get("k");
                } catch (ZarrException expected) {
                    // the interrupted thread's read fails
                }
            });
            reader.start();
            reader.join();
            assertArrayEquals(bytes("value"), zip.get("k").orElseThrow());
        }
    }

    /** A hierarchy written through the Zarr API straight into an archive, then added to in a second session. */
    @Test
    void aZarrHierarchyWrittenIntoAnArchive(@TempDir Path tmp) {
        Path archive = tmp.resolve("hierarchy.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            ZarrGroup root = Zarr.createGroup(zip, Json.parse("{\"title\":\"zip\"}").asObject());
            root.createArray("plain", ArraySpec.builder(new long[] {10}, DataType.INT32).chunkShape(4).zstd().build())
                    .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
            ZarrArray sharded = root.createArray("sharded", ArraySpec.builder(new long[] {8, 8}, DataType.FLOAT32)
                    .chunkShape(8, 8).sharding(4, 4).build());
            double[] values = new double[64];
            for (int i = 0; i < values.length; i++) {
                values[i] = i * 0.5;
            }
            sharded.writeDoubles(values);
            root.createArray("text", ArraySpec.builder(new long[] {3}, DataType.STRING).chunkShape(2).build())
                    .writeStrings(new String[] {"a", "ü", ""});
            root.createGroup("gone").createArray("x", ArraySpec.builder(new long[] {2}, DataType.INT8).build());
            root.delete("gone");
            root.updateAttributes(Json.parse("{\"title\":\"changed\"}").asObject()); // zarr.json again
            root.array("plain").select(new long[] {0}, new long[] {2}).writeInts(new int[] {-1, -2}); // c/0 again
            root.consolidate();
        }
        try (ZipStore zip = ZipStore.open(archive)) {
            ZarrGroup root = Zarr.openGroup(zip);
            assertTrue(root.isConsolidated());
            assertEquals(List.of("plain", "sharded", "text"), root.childNames());
            root.createArray("late", ArraySpec.builder(new long[] {2}, DataType.UINT8).build())
                    .writeInts(new int[] {7, 8});
            root.consolidate();
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            ZarrGroup root = Zarr.openGroup(zip);
            assertEquals("changed", root.attributes().get("title").asString());
            assertEquals(List.of("late", "plain", "sharded", "text"), root.childNames());
            assertArrayEquals(new int[] {-1, -2, 2, 3, 4, 5, 6, 7, 8, 9}, root.array("plain").readInts());
            assertEquals(31.5, root.array("sharded").readDoubles()[63]);
            assertArrayEquals(new String[] {"a", "ü", ""}, root.array("text").readStrings());
            assertArrayEquals(new int[] {7, 8}, root.array("late").readInts());
            assertTrue(zip.listPrefix("gone/").isEmpty());
        }
    }
}
