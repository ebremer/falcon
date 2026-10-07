package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line itself, and {@code ls} and {@code info} of both formats. */
class InspectTest {

    @TempDir
    Path dir;

    private String h5;

    @BeforeEach
    void write() throws IOException {
        h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::stripTrailing).toList();
    }

    @Test
    void theCommandLineTellsItsCommandsAndItsMistakes() {
        Cli.Result none = Cli.run();
        assertEquals(2, none.status());
        assertTrue(none.err().contains("convert      Convert an HDF5 file to Zarr"), none.err());
        assertTrue(Cli.ok("--help").contains("Usage: falcon <command>"));
        assertTrue(Cli.ok("--version").matches("falcon \\S+\n"));
        assertTrue(Cli.ok("ls", "--help").contains("Usage: falcon ls [options] <source> [<path>]"));

        Cli.Result unknown = Cli.run("frobnicate");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("Run 'falcon --help'"), unknown.err());
        Cli.Result tooMany = Cli.run("ls", h5, "/", "extra");
        assertEquals(2, tooMany.status());
        assertTrue(tooMany.err().contains("too many arguments: expected <source> [<path>]"), tooMany.err());
        Cli.Result badOption = Cli.run("dump", "--format", "xml", h5, "/cube");
        assertEquals(2, badOption.status());
        assertTrue(badOption.err().contains("--format is text, csv, or json"), badOption.err());

        Cli.Result missing = Cli.run("ls", dir.resolve("nothing.h5").toString());
        assertEquals(1, missing.status());
        assertTrue(missing.err().contains("no such file or directory"), missing.err());
        Cli.Result noObject = Cli.run("info", h5, "/nope");
        assertEquals(1, noObject.status());
        assertTrue(noObject.err().contains("no object at /nope"), noObject.err());
    }

    @Test
    void lsListsAGroupsMembersByName() {
        List<String> lines = lines(Cli.ok("ls", h5));
        assertEquals(List.of("big", "blobs", "colors", "cplx", "cube", "empty", "ext", "flags", "names", "pairs",
                "ragged", "ref", "run", "scalar", "soft", "table", "when", "words"),
                lines.stream().map(l -> l.split("\\s+")[0]).toList());
        assertTrue(lines.contains("ext     external link  -> other.h5:/x"), String.join("\n", lines));
        assertTrue(lines.contains("soft    soft link      -> /run/temperature"), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.matches("cube\\s+dataset\\s+\\(2, 3, 4\\)\\s+int64\\s+chunks \\(1, 3, 4\\)")),
                String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.matches("when\\s+dataset\\s+\\(2,\\)\\s+datetime64\\[D\\] \\(h5py's opaque\\).*")));
    }

    @Test
    void lsRecursiveListsEveryPathAndAnObjectAtAPath() {
        String all = Cli.ok("ls", "-r", h5);
        assertTrue(all.startsWith("/ "), all);
        assertTrue(all.lines().anyMatch(l -> l.matches("/run/temperature\\s+dataset\\s+\\(4, 6\\)\\s+float32\\s+"
                + "chunks \\(2, 6\\)\\s+shuffle, deflate\\(level=4\\)")), all);
        assertTrue(all.lines().anyMatch(l -> l.matches("/run/counts\\s+dataset\\s+\\(10,\\)\\s+uint16, big-endian\\s+contiguous")), all);
        assertTrue(Cli.ok("ls", h5, "run/counts").startsWith("/run/counts  dataset"));
    }

    @Test
    void infoDescribesADatasetAndTheFile() {
        String info = Cli.ok("info", h5, "run/temperature");
        assertTrue(info.startsWith("/run/temperature\n"), info);
        for (String expected : List.of("object      dataset (HDF5)", "shape       (4, 6)", "type        float32",
                "layout      chunked, chunks (2, 6)", "filters     shuffle, deflate(level=4)", "fill value  NaN",
                "attributes  1", "units  =  \"K\"  [string(1 bytes, ascii)]",
                "storage     97 bytes, for 96 bytes of elements (compression ratio 0.99)")) {
            assertTrue(info.contains(expected), () -> expected + " in\n" + info);
        }
        String root = Cli.ok("info", h5);
        for (String expected : List.of("object      group (HDF5)", "members     18", "attributes  4",
                "ranges   =  [[0,1],[2,3]]", "scale    =  0.1", "version  =  3")) {
            assertTrue(root.contains(expected), () -> expected + " in\n" + root);
        }
        String table = Cli.ok("info", h5, "table");
        assertTrue(table.contains("compound {id: int32, v: float64, big-endian, tag: string(2 bytes, ascii)}"), table);
    }

    @Test
    void zarrStoresAreListedAndDescribedToo() {
        String zarr = dir.resolve("sample.zarr").toString();
        Cli.run("convert", "-q", "--path", "/run", h5, zarr).ok();
        String ls = Cli.ok("ls", "-r", zarr);
        assertTrue(ls.lines().anyMatch(l -> l.matches("/temperature\\s+array\\s+\\(4, 6\\)\\s+float32\\s+chunks \\(2, 6\\)\\s+bytes, gzip")), ls);
        String info = Cli.ok("info", zarr, "counts");
        for (String expected : List.of("object      array (Zarr v3)", "type        uint16, big-endian",
                "chunks      (10,), 1 chunk", "codecs      bytes(endian=big)", "zstd(level=0, checksum=false)",
                "fill value  0", "chunk keys  default (separator '/')")) {
            assertTrue(info.contains(expected), () -> expected + " in\n" + info);
        }
        String group = Cli.ok("info", zarr);
        assertTrue(group.contains("object        group (Zarr v3)") && group.contains("started  =  \"yes\""), group);
    }
}
