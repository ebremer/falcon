package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.ome.MultiscaleImage;
import com.ebremer.falcon.ome.OmeVersion;
import com.ebremer.falcon.ome.OmeZarr;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code falcon ome validate} and {@code falcon ome pyramid}, and what {@code ls} and {@code info} say of OME-Zarr. */
class OmeCommandTest {

    @TempDir
    Path dir;

    private String h5;

    @BeforeEach
    void write() throws IOException {
        h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
    }

    @Test
    void pyramidOfAnHdf5DatasetInEachVersion() {
        for (String version : new String[] {"0.4", "0.5", "0.6"}) {
            Path out = dir.resolve("t" + version + ".ome.zarr");
            String printed = Cli.ok("ome", "pyramid", h5, "run/temperature", out.toString(), "--ome-version", version,
                    "--levels", "2", "--pixel-size", "0.5,0.25");
            assertTrue(printed.contains("level 1: (2, 3)"), printed);
            assertTrue(printed.contains("Wrote OME-Zarr " + version + " image 'temperature', 2 levels"), printed);
            MultiscaleImage image = OmeZarr.open(FileSystemStore.openReadOnly(out)).asImage();
            assertEquals(OmeVersion.of(version).orElseThrow(), image.version());
            assertEquals(List.of("y", "x"), image.multiscale().axisNames());
            assertEquals("micrometer", image.axes().getFirst().unit());
            float[] full = image.level(0).readFloats();
            assertEquals(0.7f, full[7], 1e-6);
            // the first 2x2 block: 0.0, 0.1, 0.6, 0.7
            assertEquals(0.35f, image.level(1).readFloats()[0], 1e-6);
            assertArrayEquals(new double[] {1, 0.5}, image.scale(1), 1e-12);
            assertEquals("", Cli.run("ome", "validate", "--strict", "--errors-only", out.toString()).out()
                    .replace(out + ": valid\n", ""));
        }
    }

    @Test
    void pyramidChoosesAxesByRankOrAsTold() {
        Path out = dir.resolve("cube.ome.zarr");
        Cli.ok("ome", "pyramid", h5, "cube", out.toString(), "--levels", "2", "-q", "--method", "nearest",
                "--channel-names", "first,second", "--channel-colors", "ff0000,00ff00");
        MultiscaleImage image = OmeZarr.open(FileSystemStore.openReadOnly(out)).asImage();
        assertEquals(List.of("c", "y", "x"), image.multiscale().axisNames());
        assertArrayEquals(new long[] {2, 2, 2}, image.level(1).shape());
        assertEquals("nearest", image.multiscale().type());
        assertEquals("00FF00", image.omero().orElseThrow().channels().get(1).color());
        Path zyx = dir.resolve("zyx.ome.zarr");
        Cli.ok("ome", "pyramid", h5, "cube", zyx.toString(), "--axes", "zyx", "--downsample", "z,y,x", "--levels", "2",
                "-q", "--ome-version", "0.4", "-c", "gzip:5", "--chunks", "1,2,2");
        MultiscaleImage z = OmeZarr.open(FileSystemStore.openReadOnly(zyx)).asImage();
        assertArrayEquals(new long[] {1, 2, 2}, z.level(1).shape());
        assertArrayEquals(new long[] {1, 2, 2}, z.level(0).chunkShape());
        assertTrue(z.level(0).codecNames().stream().anyMatch(c -> c.contains("gzip") || c.contains("zlib")),
                z.level(0).codecNames()::toString);
    }

    @Test
    void labelImagesGoIntoAnImage() {
        Path out = dir.resolve("cube.ome.zarr");
        Cli.ok("ome", "pyramid", h5, "cube", out.toString(), "--levels", "2", "-q");
        String printed = Cli.ok("ome", "pyramid", h5, "cube", out.toString(), "--label", "seg", "-q");
        assertTrue(printed.contains("label image 'seg', 2 levels"), printed);
        MultiscaleImage image = OmeZarr.open(FileSystemStore.openReadOnly(out)).asImage();
        assertEquals(List.of("seg"), image.labelNames());
        assertEquals("nearest", image.label("seg").multiscale().type());
        assertEquals(1, Cli.run("ome", "pyramid", h5, "cube", out.toString(), "--label", "seg", "-q").status());
        Cli.ok("ome", "pyramid", h5, "cube", out.toString(), "--label", "seg", "-q", "--overwrite");
        assertEquals(2, Cli.run("ome", "pyramid", h5, "cube", dir.resolve("x.zip").toString(), "--label", "seg")
                .status());
        // a label image takes its image's version and axes, whatever --ome-version says
        Path v4 = dir.resolve("v4.ome.zarr");
        Cli.ok("ome", "pyramid", h5, "cube", v4.toString(), "--levels", "2", "-q", "--ome-version", "0.4",
                "--axes", "zyx");
        Cli.ok("ome", "pyramid", h5, "cube", v4.toString(), "--label", "seg", "-q");
        MultiscaleImage label = OmeZarr.open(FileSystemStore.openReadOnly(v4)).asImage().label("seg");
        assertEquals(OmeVersion.V0_4, label.version());
        assertEquals(List.of("z", "y", "x"), label.multiscale().axisNames());
        assertEquals(0, Cli.run("ome", "validate", v4.toString()).status());
    }

    @Test
    void pyramidRefusesWhatCannotBeAnImage() {
        Path out = dir.resolve("o.ome.zarr");
        assertEquals(2, Cli.run("ome", "pyramid", h5, "words", out.toString()).status());
        assertEquals(2, Cli.run("ome", "pyramid", h5, "run/counts", out.toString()).status()); // one dimension
        assertEquals(2, Cli.run("ome", "pyramid", h5, "cube", out.toString(), "--axes", "xyc").status());
        assertEquals(2, Cli.run("ome", "pyramid", h5, "cube", out.toString(), "--ome-version", "0.3").status());
        assertEquals(2, Cli.run("ome", "pyramid", h5, "cube", out.toString(), "--pixel-size", "1,2,3,4").status());
        Cli.ok("ome", "pyramid", h5, "cube", out.toString(), "-q");
        Cli.Result again = Cli.run("ome", "pyramid", h5, "cube", out.toString(), "-q");
        assertEquals(1, again.status());
        assertTrue(again.err().contains("--overwrite"), again.err());
        Cli.ok("ome", "pyramid", h5, "cube", out.toString(), "-q", "--overwrite");
    }

    @Test
    void validateSaysValidOrNotAndWhy() throws IOException {
        Path out = dir.resolve("t.ome.zarr");
        Cli.ok("ome", "pyramid", h5, "run/temperature", out.toString(), "--levels", "2", "-q");
        Cli.Result ok = Cli.run("ome", "validate", out.toString());
        assertEquals(0, ok.status(), ok.out());
        assertTrue(ok.out().endsWith(": valid\n") || ok.out().contains("valid,"), ok.out());
        // a level gone
        try (var s = Files.walk(out.resolve("1"))) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Cli.Result bad = Cli.run("ome", "validate", out.toString());
        assertEquals(1, bad.status());
        assertTrue(bad.out().contains("level '1' is not stored"), bad.out());
        assertEquals(0, Cli.run("ome", "validate", "--metadata-only", out.toString()).status());
        JsonObject json = Json.parse(Cli.ok("ome", "validate", "--json", out.toString())).asObject();
        assertEquals(false, json.get("valid").asBoolean());
        assertTrue(json.get("message").asString().contains("not stored"));
        Path attributes = dir.resolve("attributes.json");
        Files.writeString(attributes, "{\"ome\": {\"version\": \"0.5\", \"well\": {\"images\": [{\"path\": \"0\"}]}}}");
        assertEquals("{\"valid\":true}\n", Cli.ok("ome", "validate", "--json", "--attributes", attributes.toString()));
        assertEquals(2, Cli.run("ome", "validate", out.toString(), "0").status()); // an array
        assertEquals(2, Cli.run("ome", "validate", h5).status()); // an HDF5 file is no Zarr store
    }

    @Test
    void lsAndInfoNameOmeZarrGroups() {
        Path out = dir.resolve("t.ome.zarr");
        Cli.ok("ome", "pyramid", h5, "run/temperature", out.toString(), "--levels", "2", "-q", "--name", "temps");
        String ls = Cli.ok("ls", "-r", out.toString());
        assertTrue(ls.lines().findFirst().orElseThrow().matches("/ +group +v3 +OME-Zarr 0\\.5 image"), ls);
        String info = Cli.ok("info", out.toString());
        assertTrue(info.contains("OME-Zarr      0.5 image"), info);
        assertTrue(info.contains("name          temps"), info);
        assertTrue(info.contains("1: (2, 3) float32; scale 2, 2, translation 0.5, 0.5"), info);
    }

    @Test
    void theGroupAloneShowsItsCommands() {
        Cli.Result alone = Cli.run("ome");
        assertEquals(2, alone.status());
        assertTrue(alone.err().contains("validate"), alone.err());
        assertTrue(Cli.ok("ome", "--help").contains("pyramid"));
        assertTrue(Cli.ok("ome", "validate", "--help").contains("--strict"));
        Cli.Result unknown = Cli.run("ome", "validate", "--attributes");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("falcon ome validate --help"), unknown.err());
    }
}
