package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystem;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** Writing images, their pyramids, label images, plates, and scenes, in each version. */
class WriterTest {

    static final List<Axis> YX = List.of(Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));

    /** A uint16 ramp: value = y * w + x. */
    static PixelSource ramp(long h, long w) {
        return new PixelSource() {
            @Override
            public long[] shape() {
                return new long[] {h, w};
            }

            @Override
            public DataType dataType() {
                return DataType.UINT16;
            }

            @Override
            public byte[] read(long[] o, long[] s) {
                ByteBuffer b = ByteBuffer.allocate((int) (s[0] * s[1] * 2)).order(ByteOrder.LITTLE_ENDIAN);
                for (long y = 0; y < s[0]; y++) {
                    for (long x = 0; x < s[1]; x++) {
                        b.putShort((short) ((o[0] + y) * w + o[1] + x));
                    }
                }
                return b.array();
            }
        };
    }

    /** The 2x2 block means of an image, rounded half up, partial blocks at the far edges from their pixels. */
    static int[] means(int[] values, int h, int w) {
        int oh = (h + 1) / 2;
        int ow = (w + 1) / 2;
        int[] out = new int[oh * ow];
        for (int i = 0; i < oh; i++) {
            for (int j = 0; j < ow; j++) {
                long sum = 0;
                int n = 0;
                for (int y = 2 * i; y < Math.min(h, 2 * i + 2); y++) {
                    for (int x = 2 * j; x < Math.min(w, 2 * j + 2); x++) {
                        sum += values[y * w + x];
                        n++;
                    }
                }
                out[i * ow + j] = (int) Math.floorDiv(2 * sum + n, 2L * n);
            }
        }
        return out;
    }

    @ParameterizedTest
    @EnumSource(OmeVersion.class)
    void writesAPyramidOfMeansWithLevelsThatLineUp(OmeVersion version) {
        MemoryStore store = new MemoryStore();
        MultiscaleImageWriter writer = MultiscaleImageWriter.builder(version, YX).name("ramp").pixelSize(0.5, 0.25)
                .smallest(20).chunks(16, 16).threads(3).build();
        assertEquals(4, writer.levelShapes(new long[] {101, 77}).size()); // 101 -> 51 -> 26 -> 13
        MultiscaleImage image = writer.write(store, ramp(101, 77));
        assertEquals(version, image.version());
        assertEquals(4, image.levelCount());
        int[] level = image.level(0).readInts();
        int h = 101;
        int w = 77;
        for (int k = 1; k < 4; k++) {
            level = means(level, h, w);
            h = (h + 1) / 2;
            w = (w + 1) / 2;
            assertArrayEquals(new long[] {h, w}, image.level(k).shape());
            assertArrayEquals(level, image.level(k).readInts(), "level " + k);
        }
        assertArrayEquals(new double[] {4, 2}, image.scale(3), 1e-12);
        // the level's first pixel's center is the center of the first 8x8 block of level 0: 3.5 pixels in
        assertArrayEquals(new double[] {3.5 * 0.5, 3.5 * 0.25}, image.translation(3), 1e-12);
        assertEquals("mean", image.multiscale().type());
        assertEquals(List.of(16L, 16L), java.util.Arrays.stream(image.level(0).chunkShape()).boxed().toList());
        ValidationReport report = new OmeValidator().strict(true).validate(store);
        assertTrue(report.issues().isEmpty(), report::toString);
        if (version == OmeVersion.V0_5) {
            assertEquals(List.of("y", "x"), image.level(1).dimensionNames().orElseThrow());
        }
        if (version == OmeVersion.V0_6) {
            assertEquals("physical", image.multiscale().intrinsicCoordinateSystem().map(CoordinateSystem::name)
                    .orElseThrow());
        }
    }

    @Test
    void nearestKeepsTheFirstPixelsAndTheirPositions() {
        MultiscaleImage image = MultiscaleImageWriter.builder(OmeVersion.V0_5, YX).method(Downsampling.NEAREST)
                .levels(2).build().write(new MemoryStore(), ramp(4, 6));
        assertArrayEquals(new int[] {0, 2, 4, 12, 14, 16}, image.level(1).readInts());
        assertArrayEquals(new double[] {0, 0}, image.translation(1), 1e-12);
        assertEquals("nearest", image.multiscale().type());
    }

    @Test
    void readsAZarrArrayOfAnyByteOrderAndDownsamplesOnlyTheChosenAxes() {
        MemoryStore source = new MemoryStore();
        ZarrArray array = Zarr.createArray(source, ArraySpec.builder(new long[] {3, 4, 4}, DataType.INT32)
                .endian(ByteOrder.BIG_ENDIAN).chunkShape(1, 4, 4).build());
        int[] values = new int[48];
        for (int i = 0; i < values.length; i++) {
            values[i] = i - 24;
        }
        array.writeInts(values);
        List<Axis> zyx = List.of(Axis.space("z", "micrometer"), Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
        MultiscaleImage image = MultiscaleImageWriter.builder(OmeVersion.V0_4, zyx).levels(2).build()
                .write(new MemoryStore(), PixelSource.of(array));
        assertArrayEquals(values, image.level(0).readInts());
        assertArrayEquals(new long[] {3, 2, 2}, image.level(1).shape()); // z is not downsampled by default
        assertArrayEquals(new double[] {1, 2, 2}, image.scale(1), 1e-12);
        MultiscaleImage all = MultiscaleImageWriter.builder(OmeVersion.V0_4, zyx).downsample("z", "y", "x").levels(2)
                .build().write(new MemoryStore(), PixelSource.of(array));
        assertArrayEquals(new long[] {2, 2, 2}, all.level(1).shape());
    }

    @Test
    void writesShardedLevels() {
        MemoryStore store = new MemoryStore();
        MultiscaleImage image = MultiscaleImageWriter.builder(OmeVersion.V0_5, YX).chunks(8, 8).shards(32, 32)
                .levels(2).build().write(store, ramp(70, 50));
        assertArrayEquals(new long[] {8, 8}, image.level(0).innerChunkShape());
        assertArrayEquals(new long[] {32, 32}, image.level(0).chunkShape());
        assertArrayEquals(means(image.level(0).readInts(), 70, 50), image.level(1).readInts());
        assertThrows(IllegalArgumentException.class, () -> MultiscaleImageWriter.builder(OmeVersion.V0_4, YX).shards(8, 8));
    }

    @ParameterizedTest
    @EnumSource(OmeVersion.class)
    void writesLabelImagesWithTheirImagesLevels(OmeVersion version) {
        MemoryStore store = new MemoryStore();
        List<Axis> cyx = List.of(Axis.channel("c"), Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
        PixelSource channels = new PixelSource() {
            public long[] shape() { return new long[] {2, 20, 30}; }
            public DataType dataType() { return DataType.UINT8; }
            public byte[] read(long[] o, long[] s) { return new byte[(int) (s[0] * s[1] * s[2])]; }
        };
        MultiscaleImage image = MultiscaleImageWriter.builder(version, cyx).pixelSize(1, 0.5, 0.5).levels(3).build()
                .write(store, channels);
        PixelSource labels = new PixelSource() {
            public long[] shape() { return new long[] {1, 20, 30}; }
            public DataType dataType() { return DataType.UINT32; }
            public byte[] read(long[] o, long[] s) {
                ByteBuffer b = ByteBuffer.allocate((int) (s[1] * s[2] * 4)).order(ByteOrder.LITTLE_ENDIAN);
                for (long y = 0; y < s[1]; y++) {
                    for (long x = 0; x < s[2]; x++) {
                        b.putInt(o[1] + y < 10 ? 7 : 9);
                    }
                }
                return b.array();
            }
        };
        MultiscaleImageWriter labeller = MultiscaleImageWriter.builder(version, cyx).imageLabel(ImageLabel.of(List.of(
                new ImageLabel.LabelColor(7, new int[] {255, 0, 0, 255})))).build();
        MultiscaleImage label = labeller.writeLabel(image, "halves", labels);
        assertEquals(List.of("halves"), image.labelNames());
        assertEquals(3, label.levelCount());
        assertArrayEquals(new long[] {1, 5, 8}, label.level(2).shape());
        long[] small = label.level(1).readLongs();
        assertEquals(7, small[0]);
        assertEquals(9, small[small.length - 1]);
        assertEquals("nearest", label.multiscale().type()); // labels are not averaged
        assertArrayEquals(image.scale(2), label.scale(2), 1e-12);
        assertEquals(255, label.imageLabel().orElseThrow().colors().getFirst().rgba()[0]);
        assertEquals(image.group().path(), label.sourceImage().orElseThrow().group().path());
        // a second label image joins the list
        labeller.writeLabel(image, "again", labels);
        assertEquals(List.of("halves", "again"), image.labelNames());
        ValidationReport report = new OmeValidator().validate(store);
        assertTrue(report.isValid(), report::toString);
        assertThrows(IllegalArgumentException.class, () -> labeller.writeLabel(image, "floats", new PixelSource() {
            public long[] shape() { return new long[] {1, 20, 30}; }
            public DataType dataType() { return DataType.FLOAT32; }
            public byte[] read(long[] o, long[] s) { return new byte[0]; }
        }));
    }

    @ParameterizedTest
    @EnumSource(OmeVersion.class)
    void writesAPlateOfWellsOfImages(OmeVersion version) {
        MemoryStore store = new MemoryStore();
        Plate plate = OmeZarr.createPlate(store, version, new PlateMetadata("p", null, 1, List.of("A", "B"),
                List.of("1", "2"), List.of(new PlateMetadata.WellRef("B/2", 1, 1)),
                List.of(new PlateMetadata.Acquisition(0, "a", 1, null, null, null))));
        Well well = OmeZarr.createWell(plate, "B/2", new WellMetadata(null, List.of(new WellMetadata.FieldOfView("0", 0L))));
        MultiscaleImageWriter.builder(version, YX).name("f").levels(1).build().write(well.group(), "0", ramp(5, 6));
        Plate back = OmeZarr.open(store).asPlate();
        assertArrayEquals(ramp(5, 6).read(new long[] {0, 0}, new long[] {5, 6}).length == 60 ? new int[] {0, 1, 2}
                : null, java.util.Arrays.copyOf(back.well("B", "2").image(0).level(0).readInts(), 3));
        ValidationReport report = new OmeValidator().strict(true).validate(store);
        assertTrue(report.isValid(), report::toString);
        assertThrows(IllegalArgumentException.class, () -> OmeZarr.createWell(plate, "A/1", new WellMetadata(null,
                List.of(new WellMetadata.FieldOfView("0", null)))));
    }

    @Test
    void writesAScene() {
        MemoryStore store = new MemoryStore();
        Scene scene = OmeZarr.createScene(store, new SceneMetadata(List.of(new CoordinateSystem("world", YX)),
                List.of(new Transformation.Translation(new double[] {0, 100}, null, "tile",
                        new CoordinateSystemRef("physical", "t1"), CoordinateSystemRef.named("world")))));
        MultiscaleImageWriter.builder(OmeVersion.V0_6, YX).levels(1).build().write(scene.group(), "t1", ramp(4, 4));
        Scene back = OmeZarr.open(store).asScene();
        assertArrayEquals(new double[] {1, 102}, back.transformation("t1", 0, CoordinateSystemRef.named("world"))
                .orElseThrow().apply(new double[] {1, 2}), 1e-12);
        ValidationReport report = new OmeValidator().validate(store);
        assertTrue(report.isValid(), report::toString);
    }

    @Test
    void writesFiveDimensionalImagesTimeFirst() {
        List<Axis> tczyx = List.of(Axis.time("t", "second"), Axis.channel("c"), Axis.space("z", "micrometer"),
                Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
        PixelSource zeros = new PixelSource() {
            public long[] shape() { return new long[] {2, 1, 3, 8, 8}; }
            public DataType dataType() { return DataType.UINT8; }
            public byte[] read(long[] o, long[] s) { return new byte[(int) (s[0] * s[1] * s[2] * s[3] * s[4])]; }
        };
        MultiscaleImage image = MultiscaleImageWriter.builder(OmeVersion.V0_5, tczyx).levels(2).build()
                .write(new MemoryStore(), zeros);
        assertArrayEquals(new long[] {2, 1, 3, 4, 4}, image.level(1).shape());
        assertEquals("second", image.axes().getFirst().unit());
        assertThrows(IllegalArgumentException.class, () -> MultiscaleImageWriter.builder(OmeVersion.V0_5,
                List.of(Axis.time("t", null), Axis.time("u", null), Axis.space("y", null), Axis.space("x", null))));
    }

    @Test
    void refusesWhatNoImageCanBe() {
        assertThrows(IllegalArgumentException.class, () -> MultiscaleImageWriter.builder(OmeVersion.V0_5,
                List.of(Axis.space("x", null))));
        assertThrows(IllegalArgumentException.class, () -> MultiscaleImageWriter.builder(OmeVersion.V0_5,
                List.of(Axis.space("y", null), Axis.channel("c"), Axis.space("x", null))));
        assertThrows(IllegalArgumentException.class, () -> MultiscaleImageWriter.builder(OmeVersion.V0_5, YX)
                .pixelSize(1, 0));
        MultiscaleImageWriter writer = MultiscaleImageWriter.builder(OmeVersion.V0_5, YX).build();
        assertThrows(IllegalArgumentException.class, () -> writer.write(new MemoryStore(), new PixelSource() {
            public long[] shape() { return new long[] {2, 2, 2}; }
            public DataType dataType() { return DataType.UINT8; }
            public byte[] read(long[] o, long[] s) { return new byte[0]; }
        }));
        MemoryStore v2 = new MemoryStore();
        ZarrGroup parent = Zarr.createGroup(v2, new JsonObject(Map.of()), false, 2);
        assertThrows(IllegalArgumentException.class, () -> writer.write(parent, "i", ramp(2, 2)));
    }
}
