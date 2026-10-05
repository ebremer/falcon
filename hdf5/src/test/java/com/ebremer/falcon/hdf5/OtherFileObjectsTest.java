package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Objects in other files (P2 A11): external links and revised references into other files, followed as
 * libhdf5 follows them under the file's {@link ExternalFileAccess} policy; and time values (P2 A12).
 */
class OtherFileObjectsTest {

    private static final Path FIXTURES = Fixtures.path("elinks.h5").getParent();

    /** The links of {@code elinks.h5}, in the order of its {@code h5py_names} attribute. */
    private static final List<String> LINKS = List.of("to_group", "to_dataset", "via_soft", "chain", "loop", "sub",
            "moved", "missing_file", "missing_object", "escape", "root");

    // ------------------------------------------------------------------ external links

    /** Each external link reaches the object h5py (libhdf5) reaches, named as libhdf5 names it. */
    @Test
    void externalLinksReachWhatLibhdf5Reaches() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("elinks.h5"))) {
            Group root = h5.root();
            String[] expected = root.attribute("h5py_names").orElseThrow().readStrings();
            assertEquals(LINKS.size(), expected.length);
            for (int i = 0; i < LINKS.size(); i++) {
                String link = LINKS.get(i);
                if (link.equals("escape")) {
                    continue; // refused by the policy: below
                }
                String reached = root.child(link).map(Hdf5Object::path).orElse("");
                assertEquals(expected[i], reached, link);
            }
            Dataset d = root.dataset("to_dataset");
            assertArrayEquals(new int[] {1, 2, 3}, d.readInts());
            assertEquals("m", d.attribute("unit").orElseThrow().readString());
            assertArrayEquals(new int[] {1, 2, 3}, root.group("to_group").dataset("d").readInts());
            assertArrayEquals(new int[] {1, 2, 3}, root.dataset("to_group/d").readInts()); // a path across the link
            assertArrayEquals(new int[] {1, 2, 3}, root.dataset("via_soft").readInts());
            assertArrayEquals(new int[] {9}, root.dataset("chain").readInts());      // through two files
            assertArrayEquals(new int[] {5}, root.dataset("sub").readInts());        // a subdirectory
            assertArrayEquals(new int[] {1, 2, 3}, root.dataset("moved").readInts()); // by its file name alone
            assertEquals(Set.of("grp", "back", "loop", "soft"), Set.copyOf(root.group("root").childNames()));
            assertInstanceOf(Link.Hard.class, root.link("to_group/d").orElseThrow());
        }
    }

    @Test
    void externalLinksThatReachNothingSayWhy() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("elinks.h5"))) {
            Group root = h5.root();
            NoSuchElementException file = assertThrows(NoSuchElementException.class, () -> root.dataset("missing_file"));
            assertTrue(file.getMessage().contains("elinks_missing.h5:/x, whose file is not found"), file.getMessage());
            NoSuchElementException object = assertThrows(NoSuchElementException.class, () -> root.dataset("missing_object"));
            assertTrue(object.getMessage().contains("which does not resolve in that file"), object.getMessage());
            // A loop between two files stops after 16 links, as libhdf5's does.
            assertTrue(root.child("loop").isEmpty());
            assertThrows(NoSuchElementException.class, () -> root.dataset("loop"));
            // A file outside the directory is refused by the default policy, not taken to be missing.
            HdfUnsupportedException escape = assertThrows(HdfUnsupportedException.class, () -> root.child("escape"));
            assertTrue(escape.getMessage().contains("elinks_escape.h5"), escape.getMessage());
            // The listing holds what the links reach, and leaves out the rest.
            assertEquals(Set.of("local", "grp", "d", "soft", "z", ""),
                    root.children().stream().map(Hdf5Object::name).collect(Collectors.toSet()));
            assertEquals(8, root.children().size());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("elinks.h5"), ExternalFileAccess.unrestricted())) {
            assertTrue(h5.root().child("escape").isEmpty()); // allowed now, and missing
        }
    }

    @Test
    void externalLinksFollowThePolicy() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("elinks.h5"), ExternalFileAccess.none())) {
            Group root = h5.root();
            HdfUnsupportedException refused = assertThrows(HdfUnsupportedException.class, () -> root.dataset("to_dataset"));
            assertTrue(refused.getMessage().contains("elinks_target.h5"), refused.getMessage());
            assertEquals(List.of("local"), root.children().stream().map(Hdf5Object::name).toList());
        }
        // A resolver opens the files of a file read from bytes, and the links in them too.
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        List<FileChannel> channels = Collections.synchronizedList(new ArrayList<>());
        ExternalFileAccess.Resolver resolver = (name, purpose) -> {
            requests.add(purpose + ":" + name);
            Path file = FIXTURES.resolve(name);
            if (!Files.exists(file)) {
                return null;
            }
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            channels.add(channel);
            return RangeReader.of(channel);
        };
        byte[] bytes = Files.readAllBytes(Fixtures.path("elinks.h5"));
        try (Hdf5File h5 = Hdf5File.open(bytes, OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(resolver)))) {
            assertArrayEquals(new int[] {1, 2, 3}, h5.root().dataset("to_dataset").readInts());
            assertArrayEquals(new int[] {9}, h5.root().dataset("chain").readInts());
            assertTrue(h5.root().child("missing_file").isEmpty());
            assertEquals(List.of("EXTERNAL_LINK:elinks_target.h5", "EXTERNAL_LINK:elinks.h5", "EXTERNAL_LINK:elinks_missing.h5"),
                    List.copyOf(requests));
        } finally {
            for (FileChannel channel : channels) {
                channel.close();
            }
        }
    }

    @Test
    void anExternalLinksFileClosesWithTheFile() throws IOException {
        Hdf5File h5 = Hdf5File.open(Fixtures.path("elinks.h5"));
        Dataset d = h5.root().dataset("to_dataset");
        assertArrayEquals(new int[] {1, 2, 3}, d.readInts());
        assertArrayEquals(new int[] {1, 2, 3}, h5.root().dataset("to_dataset").readInts()); // opened once, kept
        h5.close();
        assertThrows(HdfClosedException.class, d::readInts);
    }

    // ------------------------------------------------------------------ references into other files

    @Test
    void referencesIntoOtherFilesAreFollowed() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("refs_revised.h5"))) {
            Dataset external = h5.root().dataset("external"); // object, region, attribute: all in refs_revised_ext.h5
            Hdf5Object[] objects = external.readObjectReferences();
            for (Hdf5Object object : objects) {
                assertArrayEquals(new int[] {0, 1, 2}, ((Dataset) object).readInts());
                assertEquals("/data", object.path());
            }
            Selection[] regions = external.readRegionReferences();
            assertThrows(HdfUnsupportedException.class, regions[0]::readInts); // an object reference
            assertArrayEquals(new int[] {1, 2}, regions[1].readInts());
            assertEquals("/data", regions[1].dataset().path());
            Attribute unit = external.selectPoints(new long[][] {{2}}).readAttributeReferences()[0];
            assertEquals("unit", unit.name());
            assertEquals("m", unit.readString());
        }
    }

    @Test
    void referencesIntoOtherFilesFollowThePolicy(@TempDir Path dir) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("refs_revised.h5"), ExternalFileAccess.none())) {
            Dataset external = h5.root().dataset("external");
            HdfUnsupportedException refused = assertThrows(HdfUnsupportedException.class, external::readObjectReferences);
            assertTrue(refused.getMessage().contains("refs_revised_ext.h5"), refused.getMessage());
            assertThrows(HdfUnsupportedException.class, external.readRegionReferences()[1]::readInts);
        }
        // Without the other file, the read fails.
        Path alone = dir.resolve("refs_revised.h5");
        Files.copy(Fixtures.path("refs_revised.h5"), alone);
        try (Hdf5File h5 = Hdf5File.open(alone)) {
            Dataset external = h5.root().dataset("external");
            HdfException missing = assertThrows(HdfException.class, external::readObjectReferences);
            assertTrue(missing.getMessage().contains("not found"), missing.getMessage());
            Selection region = external.readRegionReferences()[1];
            assertNotNull(region);
            assertThrows(HdfException.class, region::readInts);
        }
        // A resolver opens it, wherever it is.
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        ExternalFileAccess.Resolver resolver = (name, purpose) -> {
            requests.add(purpose + ":" + name);
            return RangeReader.of(FileChannel.open(FIXTURES.resolve(name), StandardOpenOption.READ));
        };
        try (Hdf5File h5 = Hdf5File.open(alone, ExternalFileAccess.resolvedBy(resolver))) {
            assertArrayEquals(new int[] {0, 1, 2}, ((Dataset) h5.root().dataset("external").readObjectReferences()[0]).readInts());
            assertEquals(List.of("REFERENCE:refs_revised_ext.h5"), List.copyOf(requests));
        }
    }

    // ------------------------------------------------------------------ time values

    @Test
    void timeValuesReadAsUnixSeconds() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("typed.h5"))) {
            Dataset d32 = h5.root().dataset("time_d32le");
            Datatype.Time type = assertInstanceOf(Datatype.Time.class, d32.datatype());
            assertEquals(4, type.size());
            assertEquals(32, type.bitPrecision());
            assertArrayEquals(new long[] {0, 1_700_000_000L, -86_400}, d32.readLongs());
            assertArrayEquals(new int[] {0, 1_700_000_000, -86_400}, d32.readInts());
            assertArrayEquals(new double[] {0, 1.7e9, -86_400}, d32.readDoubles());
            assertArrayEquals(new Instant[] {Instant.EPOCH, Instant.parse("2023-11-14T22:13:20Z"),
                    Instant.parse("1969-12-31T00:00:00Z")}, (Instant[]) d32.read());
            assertArrayEquals(new Instant[] {Instant.parse("9999-12-31T23:59:59Z"), Instant.parse("1969-12-31T23:59:59Z")},
                    (Instant[]) h5.root().dataset("time_d64be").read());
            assertArrayEquals(new Instant[] {Instant.parse("2009-02-13T23:31:30Z")},
                    (Instant[]) h5.root().dataset("records").attribute("when").orElseThrow().read());
            assertArrayEquals(new Instant[] {Instant.parse("2023-11-14T22:13:20Z")},
                    (Instant[]) d32.selectPoints(new long[][] {{1}}).read());
        }
    }
}
