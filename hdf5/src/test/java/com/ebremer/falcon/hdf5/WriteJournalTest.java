package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Changing a file in place is journaled (P2 WF10): every write over the file's own structures is on disk,
 * after its data, before any is made; a change interrupted while they are made is redone by the next
 * {@link Hdf5Writer#open}, or by retrying {@link Hdf5Writer#close()}; a version-3 superblock is marked as open
 * by a writer meanwhile, as libhdf5 marks it.
 */
class WriteJournalTest {

    @TempDir
    Path dir;

    @AfterEach
    void noInterruption() {
        Hdf5Writer.interruptAfter = -1;
    }

    private Path copy(String fixture) throws IOException {
        Path file = dir.resolve(fixture);
        Files.copy(Fixtures.path(fixture), file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    /** A change of several headers: links added and deleted in two groups, and an attribute. */
    private static void change(Hdf5Writer w) {
        Hdf5Writer.GroupWriter alpha = w.group("alpha");
        for (int i = 0; i < 12; i++) {
            alpha.intDataset("n" + i, new int[] {i}, new long[] {1});
        }
        alpha.delete("delta");
        w.group("empty").stringAttribute("now", "not empty").intDataset("x", new int[] {7}, new long[] {1});
        w.root().stringAttribute("title", "changed");
    }

    private static void requireChanged(Path file) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(13, root.group("alpha").links().size()); // beta and the 12 added
            assertTrue(root.group("alpha").link("delta").isEmpty());
            assertArrayEquals(new int[] {11}, root.dataset("alpha/n11").readInts());
            assertArrayEquals(new int[] {7}, root.dataset("empty/x").readInts());
            assertEquals("not empty", root.group("empty").attribute("now").orElseThrow().readString());
            assertEquals("changed", root.attribute("title").orElseThrow().readString());
        }
    }

    /**
     * Interrupted after the superblock, before the headers: the file is torn (and a version-3 one marked as
     * open by a writer, which libhdf5 refuses); the next {@code open} redoes the change from the journal.
     */
    @ParameterizedTest
    @ValueSource(strings = {"new_style_groups.h5", "old_style_groups.h5", "userblock_v3.h5"})
    void anInterruptedChangeIsRedoneWhenTheFileIsNextOpened(String fixture) throws IOException {
        Path file = copy(fixture);
        boolean groups = !fixture.startsWith("userblock");
        Hdf5Writer w = Hdf5Writer.open(file);
        if (groups) {
            change(w);
        } else {
            w.group("added").intDataset("x", new int[] {1, 2}, new long[] {2});
            w.root().stringAttribute("title", "changed");
        }
        Hdf5Writer.interruptAfter = 0; // after the superblock, before any header
        assertThrows(IOException.class, w::close);
        w.abort(); // the journal stays: redone on the next open
        long size = Files.size(file);
        if (fixture.equals("userblock_v3.h5")) {
            assertEquals(1, Files.readAllBytes(file)[superblock(file) + 11], "marked as open by a writer while torn");
        }
        Hdf5Writer.interruptAfter = -1;
        try (Hdf5Writer again = Hdf5Writer.open(file)) {
            assertTrue(Files.size(file) < size, "the journal was cut off");
        }
        if (groups) {
            requireChanged(file);
        } else {
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {1, 2}, h5.root().dataset("added/x").readInts());
                assertEquals("changed", h5.root().attribute("title").orElseThrow().readString());
                assertEquals(0, Files.readAllBytes(file)[superblock(file) + 11]);
            }
        }
    }

    /** Where the superblock is: at 0, 512, 1024, ... (after a user block). */
    private static int superblock(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        for (int at = 0; at + 8 <= bytes.length; at = at == 0 ? 512 : at * 2) {
            if (bytes[at] == (byte) 0x89 && bytes[at + 1] == 'H' && bytes[at + 2] == 'D' && bytes[at + 3] == 'F') {
                return at;
            }
        }
        throw new AssertionError("no superblock");
    }

    /** A {@code close()} that failed while the file was written over completes when retried. */
    @Test
    void aFailedCloseCompletesWhenRetried() throws IOException {
        Path file = copy("new_style_groups.h5");
        Hdf5Writer w = Hdf5Writer.open(file);
        change(w);
        Hdf5Writer.interruptAfter = 2;
        assertThrows(IOException.class, w::close);
        Hdf5Writer.interruptAfter = -1;
        w.close();
        requireChanged(file);
    }

    /** A journal cut short (or corrupt) was never relied on: the file opens as it was, and the bytes go. */
    @Test
    void aJournalCutShortIsIgnored() throws IOException {
        Path file = copy("new_style_groups.h5");
        byte[] before = Files.readAllBytes(file);
        byte[] fake = new byte[64];
        System.arraycopy("FalconJ1".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, fake, 56, 8);
        fake[48] = 32; // a length within the file, a checksum that fails
        Files.write(file, fake, StandardOpenOption.APPEND);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.root().stringAttribute("a", "b");
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals("b", h5.root().attribute("a").orElseThrow().readString());
            assertEquals(List.of("alpha", "empty", "root_ds"), h5.root().links().stream().map(Link::name).sorted().toList());
        }
        assertTrue(Files.size(file) >= before.length);
    }
}
