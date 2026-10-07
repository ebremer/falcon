package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Reads what ome-zarr-py writes (0.4 and 0.5 images, labels, plates, and a bioformats2raw collection) and a
 * 0.6 scene built from the specification's examples ({@code tools/fixtures/gen_ome_fixtures.py}).
 */
class FixturesTest {

    static Store fixture(String name) {
        try {
            return FileSystemStore.openReadOnly(Path.of(FixturesTest.class.getResource("/fixtures/" + name).toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"v04", "v05"})
    void readsOmeZarrPyImagesWithTheirLevelsChannelsAndLabels(String version) {
        OmeZarr.OmeGroup root = OmeZarr.open(fixture(version + "_image.ome.zarr"));
        assertEquals(version.equals("v04") ? OmeVersion.V0_4 : OmeVersion.V0_5, root.version());
        assertEquals("image", root.kind());
        MultiscaleImage image = root.asImage();
        assertEquals("cells", image.name());
        assertEquals(List.of("c", "y", "x"), image.multiscale().axisNames());
        assertEquals("micrometer", image.axes().get(2).unit());
        assertTrue(image.axes().get(0).isChannel());
        assertEquals(2, image.levelCount());
        assertArrayEquals(new long[] {2, 40, 50}, image.level(0).shape());
        assertArrayEquals(new long[] {2, 20, 25}, image.level(1).shape());
        assertArrayEquals(new double[] {1, 0.5, 0.5}, image.scale(0), 1e-12);
        assertArrayEquals(new double[] {1, 1, 1}, image.scale(1), 1e-12);
        assertArrayEquals(new double[] {0, 0.25, 0.25}, image.translation(1), 1e-12);
        int[] values = image.level(0).readInts();
        for (int c = 0; c < 2; c++) {
            for (int y = 0; y < 40; y++) {
                for (int x = 0; x < 50; x++) {
                    assertEquals(c * 1000 + y * 50 + x, values[(c * 40 + y) * 50 + x]);
                }
            }
        }
        Omero omero = image.omero().orElseThrow();
        assertEquals(List.of("DAPI", "GFP"), omero.channels().stream().map(Omero.Channel::label).toList());
        assertEquals("00FF00", omero.channels().get(1).color());
        assertEquals(4000, omero.channels().get(0).window().end());

        assertEquals(List.of("cells"), image.labelNames());
        MultiscaleImage label = image.label("cells");
        assertTrue(label.isLabel());
        ImageLabel meta = label.imageLabel().orElseThrow();
        assertEquals(2, meta.colors().size());
        assertArrayEquals(new int[] {255, 0, 0, 255}, meta.colors().getFirst().rgba());
        assertEquals("cell", meta.properties().getFirst().get("class").asString());
        int[] labels = label.level(0).readInts();
        assertEquals(1, labels[20 * 50 + 20]);
        assertEquals(2, labels[2 * 50 + 2]);
        assertEquals(0, labels[35 * 50 + 45]);
        assertEquals(image.group().path(), label.sourceImage().orElseThrow().group().path());
    }

    @Test
    void validatesOmeZarrPyImagesAndFindsTheVersionItsZeroFiveLabelsGroupLeavesOut() {
        assertTrue(new OmeValidator().validate(fixture("v04_image.ome.zarr")).isValid());
        // ome-zarr-py 0.21 writes a 0.5 labels group with no "version"; ome-zarr-models rejects it too
        ValidationReport report = new OmeValidator().validate(fixture("v05_image.ome.zarr"));
        assertEquals(1, report.errors().size(), report::toString);
        assertEquals("labels", report.errors().getFirst().node());
        assertTrue(report.errors().getFirst().message().contains("version"));
        assertTrue(report.warnings().stream().anyMatch(w -> w.message().contains("downsampling method")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v04", "v05"})
    void readsAPlateItsWellsAndTheirFields(String version) {
        OmeZarr.OmeGroup root = OmeZarr.open(fixture(version + "_plate.ome.zarr"));
        assertTrue(root.isPlate());
        Plate plate = root.asPlate();
        assertEquals("screen", plate.metadata().name());
        assertEquals(List.of("A"), plate.metadata().rows());
        assertEquals(List.of("1", "2"), plate.metadata().columns());
        assertEquals(List.of("A/1", "A/2"), plate.wellPaths());
        assertEquals(0, plate.metadata().acquisitions().getFirst().id());
        Well well = plate.well("A", "2");
        assertEquals(List.of("0"), well.imagePaths());
        assertEquals(0L, well.metadata().images().getFirst().acquisition());
        MultiscaleImage field = well.image(0);
        int[] values = field.level(0).readInts();
        assertEquals((100 + 3 * 20 + 4) % 256, values[3 * 20 + 4]);
        assertTrue(new OmeValidator().validate(fixture(version + "_plate.ome.zarr")).isValid());
    }

    @Test
    void readsABioformats2rawCollectionInItsSeriesOrder() {
        OmeZarr.OmeGroup root = OmeZarr.open(fixture("v04_bf2raw.ome.zarr"));
        assertTrue(root.isCollection());
        ImageCollection collection = root.asCollection();
        assertEquals(List.of("0", "1"), collection.imagePaths());
        assertEquals(2, collection.size());
        MultiscaleImage second = collection.image(1);
        assertEquals("second", second.name());
        assertEquals(7 * 10 + 9, second.level(0).readInts()[7 * 10 + 9]);
        assertTrue(collection.omeXml().orElseThrow().contains("Image:1"));
        assertTrue(collection.plate().isEmpty());
        assertTrue(new OmeValidator().validate(fixture("v04_bf2raw.ome.zarr")).isValid());
    }

    @Test
    void placesASceneTilesInTheWorldAndBack() {
        Scene scene = OmeZarr.open(fixture("v06_scene.ome.zarr")).asScene();
        assertEquals(List.of("tile_0", "tile_1"), scene.imagePaths());
        assertEquals("world", scene.coordinateSystems().getFirst().name());
        CoordinateSystemRef tile0 = new CoordinateSystemRef("physical", "tile_0");
        CoordinateSystemRef tile1 = new CoordinateSystemRef("physical", "tile_1");
        CoordinateSystemRef world = CoordinateSystemRef.named("world");
        assertArrayEquals(new double[] {1, 12}, scene.transformation(tile1, world).orElseThrow().apply(new double[] {1, 2}));
        // against a translation: through its inverse
        assertArrayEquals(new double[] {3, -6}, scene.transformation(world, tile1).orElseThrow().apply(new double[] {3, 4}));
        // tile to tile, through the world
        assertArrayEquals(new double[] {5, 16}, scene.transformation(tile1, tile0).orElseThrow().apply(new double[] {5, 6}));
        // a level's array coordinates to the world: pixels of 0.5
        assertArrayEquals(new double[] {1, 12}, scene.transformation("tile_1", 0, world).orElseThrow()
                .apply(new double[] {2, 4}), 1e-12);
        assertTrue(new OmeValidator().validate(fixture("v06_scene.ome.zarr")).isValid());
    }

    @Test
    void loadsParametersStoredInArraysAndADisplacementField() {
        Scene scene = OmeZarr.open(fixture("v06_scene.ome.zarr")).asScene();
        Transformation rotate = scene.image("tile_1").multiscale().transformations().getFirst();
        Transformation.Rotation rotation = assertInstanceOf(Transformation.Rotation.class, rotate);
        assertEquals("rotationParams", rotation.path());
        assertArrayEquals(new double[] {2, -1}, rotation.apply(new double[] {1, 2}));
        // tile_1's rotated system is reachable from the world: world -> tile_1 physical -> rotated
        assertArrayEquals(new double[] {-8, -1}, scene.transformation(CoordinateSystemRef.named("world"),
                new CoordinateSystemRef("rotated", "tile_1")).orElseThrow().apply(new double[] {1, 2}), 1e-12);

        Transformation warp = scene.image("tile_0").multiscale().transformations().getFirst();
        Transformation.Displacements d = assertInstanceOf(Transformation.Displacements.class, warp);
        // the field's grid point (1, 1) is at (5, 5) micrometers and holds (1, 10)
        assertArrayEquals(new double[] {6, 15}, d.apply(new double[] {5, 5}), 1e-12);
        // between grid points, linearly: (0.5, 1.5) holds (0.5, 15)
        assertArrayEquals(new double[] {3, 22.5}, d.apply(new double[] {2.5, 7.5}), 1e-12);
        // beyond the grid, the edge's vector
        assertArrayEquals(new double[] {100 + 2, 100 + 30}, d.apply(new double[] {100, 100}), 1e-12);
        assertFalse(d.inverse().isPresent());
    }

    @Test
    void theAxesOfAZeroSixImageAreItsIntrinsicSystems() {
        MultiscaleImage tile = OmeZarr.open(fixture("v06_scene.ome.zarr")).asScene().image("tile_0");
        assertEquals(OmeVersion.V0_6, tile.version());
        assertEquals("physical", tile.multiscale().intrinsicCoordinateSystem().orElseThrow().name());
        assertEquals(List.of(Axis.space("y", "micrometer"), Axis.space("x", "micrometer")), tile.axes());
        assertArrayEquals(new double[] {0.5, 0.5}, tile.scale(0), 1e-12);
    }
}
