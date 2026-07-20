package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Write path: Falcon writes a minimal file and reads it back identically. */
class WriteTest {

    @Test
    void roundTripIntDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-write", ".h5");
        try {
            try (Hdf5Writer writer = Hdf5Writer.create(file)) {
                writer.intDataset("counts", new int[] {10, 20, 30, 40, 50}, new long[] {5})
                        .intAttribute("scale", new int[] {100}, new long[] {})   // scalar attribute
                        .intAttribute("range", new int[] {10, 50}, new long[] {2});
                writer.intDataset("grid", new int[] {0, 1, 2, 3, 4, 5}, new long[] {2, 3});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertEquals(List.of("counts", "grid"), h5.root().childNames());

                Dataset counts = h5.root().dataset("counts");
                assertArrayEquals(new long[] {5}, counts.dataspace().dimensions());
                assertArrayEquals(new int[] {10, 20, 30, 40, 50}, counts.readInts());
                assertArrayEquals(new int[] {100}, counts.attribute("scale").orElseThrow().readInts());
                assertArrayEquals(new int[] {10, 50}, counts.attribute("range").orElseThrow().readInts());

                Dataset grid = h5.root().dataset("grid");
                assertArrayEquals(new long[] {2, 3}, grid.dataspace().dimensions());
                assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, grid.readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    @Test
    void roundTripChunkedDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-chunked", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("c", range(10), new long[] {10}, new long[] {4}); // 3 chunks, last partial
                w.intChunkedDataset("grid", range(24), new long[] {4, 6}, new long[] {2, 3}); // 2x2 chunks
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(range(10), h5.root().dataset("c").readInts());
                assertArrayEquals(range(24), h5.root().dataset("grid").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripDeflateChunkedDataset() throws IOException {
        Path file = Files.createTempFile("falcon-deflate", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("z", range(100), new long[] {100}, new long[] {16}).deflate(6);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(range(100), h5.root().dataset("z").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripFilteredChunkedDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-filters", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("shuf", range(50), new long[] {50}, new long[] {8}).shuffle();
                w.intChunkedDataset("flet", range(50), new long[] {50}, new long[] {8}).fletcher32();
                w.intChunkedDataset("all", range(50), new long[] {50}, new long[] {8})
                        .shuffle().deflate(4).fletcher32();
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(range(50), h5.root().dataset("shuf").readInts());
                assertArrayEquals(range(50), h5.root().dataset("flet").readInts());
                assertArrayEquals(range(50), h5.root().dataset("all").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripScaleOffsetDataset() throws IOException {
        Path file = Files.createTempFile("falcon-so", ".h5");
        try {
            int[] data = new int[16];
            for (int i = 0; i < 16; i++) {
                data[i] = 100 + i;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("so", data, new long[] {16}, new long[] {8}).scaleOffset();
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(data, h5.root().dataset("so").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripNbitDataset() throws IOException {
        Path file = Files.createTempFile("falcon-nbit", ".h5");
        try {
            int[] data = new int[16];
            for (int i = 0; i < 16; i++) {
                data[i] = 100 + i;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("nb", data, new long[] {16}, new long[] {8}).nbit(16);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(data, h5.root().dataset("nb").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripSzipDataset() throws IOException {
        // h5py has szip disabled in this environment, so this verifies Falcon's own szip round-trip
        // (its AEC decoder is validated byte-for-byte against libaec).
        Path file = Files.createTempFile("falcon-szip", ".h5");
        try {
            int[] data = new int[64];
            for (int i = 0; i < 64; i++) {
                data[i] = 1000 + i * 3;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("z", data, new long[] {64}, new long[] {16}).szip();
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(data, h5.root().dataset("z").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripSubgroupsAndStrings() throws IOException {
        Path file = Files.createTempFile("falcon-tree", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("top", new int[] {1, 2}, new long[] {2});
                Hdf5Writer.GroupWriter run = w.group("run");
                run.doubleDataset("signal", new double[] {0.5, 1.5, 2.5}, new long[] {3});
                run.stringDataset("labels", new String[] {"alpha", "beta", "gamma"}, new long[] {3});
                run.intAttribute("count", new int[] {3}, new long[] {});
                run.group("nested").intDataset("inner", new int[] {7, 8, 9}, new long[] {3});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {1, 2}, h5.root().dataset("top").readInts());
                Group run = h5.root().group("run");
                assertArrayEquals(new double[] {0.5, 1.5, 2.5}, run.dataset("signal").readDoubles());
                assertArrayEquals(new String[] {"alpha", "beta", "gamma"}, run.dataset("labels").readStrings());
                assertArrayEquals(new int[] {3}, run.attribute("count").orElseThrow().readInts());
                assertArrayEquals(new int[] {7, 8, 9}, run.group("nested").dataset("inner").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripAtomicDatatypes() throws IOException {
        Path file = Files.createTempFile("falcon-atoms", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.byteDataset("i1", new byte[] {-128, 0, 127}, new long[] {3});
                w.shortDataset("i2", new short[] {-30000, 0, 30000}, new long[] {3});
                w.longDataset("i8", new long[] {-9000000000L, 0, 9000000000L}, new long[] {3});
                w.floatDataset("f4", new float[] {0.5f, -1.25f, 3.5f}, new long[] {3});
                w.fixedStringDataset("s", new String[] {"ab", "cde", ""}, new long[] {3});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {-128, 0, 127}, h5.root().dataset("i1").readInts());
                assertArrayEquals(new int[] {-30000, 0, 30000}, h5.root().dataset("i2").readInts());
                assertArrayEquals(new long[] {-9000000000L, 0, 9000000000L}, h5.root().dataset("i8").readLongs());
                assertArrayEquals(new float[] {0.5f, -1.25f, 3.5f}, h5.root().dataset("f4").readFloats(), 0f);
                assertArrayEquals(new String[] {"ab", "cde", ""}, h5.root().dataset("s").readStrings());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripCompactLayout() throws IOException {
        Path file = Files.createTempFile("falcon-compact", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("c", new int[] {10, 20, 30, 40}, new long[] {4}).compact();
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {10, 20, 30, 40}, h5.root().dataset("c").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripCustomFillValue() throws IOException {
        Path file = Files.createTempFile("falcon-fill", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("i", new int[] {1, 2, 3}, new long[] {3}).fillValue(7);
                w.doubleDataset("d", new double[] {1.5}, new long[] {1}).fillValue(2.25);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                byte[] iFill = h5.root().dataset("i").fillValueBytes().orElseThrow();
                assertEquals(7, ByteBuffer.wrap(iFill).order(ByteOrder.LITTLE_ENDIAN).getInt());
                byte[] dFill = h5.root().dataset("d").fillValueBytes().orElseThrow();
                assertEquals(2.25, ByteBuffer.wrap(dFill).order(ByteOrder.LITTLE_ENDIAN).getDouble());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripCompoundDataset() throws IOException {
        Path file = Files.createTempFile("falcon-compound", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.compoundDataset("records", new long[] {3},
                        Hdf5Writer.CompoundField.int32("a", new int[] {1, 2, 3}),
                        Hdf5Writer.CompoundField.float64("b", new double[] {1.5, 2.5, 3.5}));
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset ds = h5.root().dataset("records");
                Datatype.Compound type = (Datatype.Compound) ds.datatype();
                assertEquals(12, type.size());
                assertEquals(2, type.members().size());
                assertEquals("a", type.members().get(0).name());
                assertEquals(0, type.members().get(0).offset());
                assertEquals("b", type.members().get(1).name());
                assertEquals(4, type.members().get(1).offset());
                ByteBuffer raw = ByteBuffer.wrap(ds.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
                for (int r = 0; r < 3; r++) {
                    assertEquals(r + 1, raw.getInt(r * 12));
                    assertEquals(r + 1.5, raw.getDouble(r * 12 + 4));
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripEnumDataset() throws IOException {
        Path file = Files.createTempFile("falcon-enum", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.enumDataset("colors", new long[] {3},
                        Hdf5Writer.enumType().add("RED", 0).add("GREEN", 1).add("BLUE", 2),
                        new int[] {2, 0, 1});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset ds = h5.root().dataset("colors");
                Datatype.Enumeration type = (Datatype.Enumeration) ds.datatype();
                assertEquals(4, type.size());
                assertEquals(List.of("RED", "GREEN", "BLUE"),
                        type.members().stream().map(Datatype.Enumeration.Member::name).toList());
                assertEquals(2, type.members().get(2).value());
                ByteBuffer raw = ByteBuffer.wrap(ds.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
                assertArrayEquals(new int[] {2, 0, 1},
                        new int[] {raw.getInt(0), raw.getInt(4), raw.getInt(8)});
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripArrayDataset() throws IOException {
        Path file = Files.createTempFile("falcon-array", ".h5");
        try {
            float[] data = {0, 1, 2, 3, 4, 5, 10, 11, 12, 13, 14, 15}; // two 2x3 elements
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.float32ArrayDataset("arr", new long[] {2}, new int[] {2, 3}, data);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset ds = h5.root().dataset("arr");
                Datatype.Array type = (Datatype.Array) ds.datatype();
                assertEquals(24, type.size());
                assertArrayEquals(new int[] {2, 3}, type.dimensions());
                assertTrue(type.base() instanceof Datatype.FloatingPoint);
                ByteBuffer raw = ByteBuffer.wrap(ds.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
                float[] got = new float[12];
                for (int i = 0; i < 12; i++) {
                    got[i] = raw.getFloat(i * 4);
                }
                assertArrayEquals(data, got, 0f);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripComplexDataset() throws IOException {
        Path file = Files.createTempFile("falcon-complex", ".h5");
        try {
            double[] real = {1, 3, -5};
            double[] imaginary = {2, -4, 0};
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.complexDataset("cx", new long[] {3}, real, imaginary);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset ds = h5.root().dataset("cx");
                Datatype.Complex type = (Datatype.Complex) ds.datatype();
                assertEquals(16, type.size());
                assertTrue(type.base() instanceof Datatype.FloatingPoint);
                ByteBuffer raw = ByteBuffer.wrap(ds.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 3; i++) {
                    assertEquals(real[i], raw.getDouble(i * 16));
                    assertEquals(imaginary[i], raw.getDouble(i * 16 + 8));
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripVlenSequenceDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-seq", ".h5");
        try {
            int[][] ints = {{10}, {20, 21}, {}, {30, 31, 32}}; // includes an empty row
            double[][] doubles = {{1.5, 2.5}, {}, {9.25}};
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intSequenceDataset("vi", new long[] {4}, ints);
                w.doubleSequenceDataset("vd", new long[] {3}, doubles);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                int[][] gi = h5.root().dataset("vi").readVlenInts();
                assertEquals(4, gi.length);
                for (int i = 0; i < 4; i++) {
                    assertArrayEquals(ints[i], gi[i]);
                }
                double[][] gd = h5.root().dataset("vd").readVlenDoubles();
                assertEquals(3, gd.length);
                for (int i = 0; i < 3; i++) {
                    assertArrayEquals(doubles[i], gd[i], 0.0);
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripReferenceDataset() throws IOException {
        Path file = Files.createTempFile("falcon-refs", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("target_a", new int[] {0, 1, 2, 3, 4}, new long[] {5});
                Hdf5Writer.GroupWriter g = w.group("target_g");
                g.intDataset("inner", new int[] {7, 8, 9}, new long[] {3});
                w.referenceDataset("refs", new long[] {5},
                        new String[] {"/target_a", "/target_g", "/target_g/inner", "/later", null});
                w.intDataset("later", new int[] {42}, new long[] {1}); // target defined after the ref dataset
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Hdf5Object[] refs = h5.root().dataset("refs").readObjectReferences();
                assertEquals(5, refs.length);
                assertArrayEquals(new int[] {0, 1, 2, 3, 4}, ((Dataset) refs[0]).readInts());
                assertTrue(refs[1].isGroup());
                assertArrayEquals(new int[] {7, 8, 9}, ((Dataset) refs[2]).readInts());
                assertArrayEquals(new int[] {42}, ((Dataset) refs[3]).readInts()); // forward reference resolved
                assertNull(refs[4]); // null reference
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripDenseLinks() throws IOException {
        Path file = Files.createTempFile("falcon-dense-links", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                Hdf5Writer.GroupWriter g = w.group("g");
                for (int i = 0; i < 10; i++) { // > 8 children -> dense link storage
                    g.intDataset(String.format("item%02d", i), new int[] {i}, new long[] {1});
                }
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Group g = h5.root().group("g");
                assertEquals(10, g.childNames().size());
                for (int i = 0; i < 10; i++) {
                    assertArrayEquals(new int[] {i}, g.dataset(String.format("item%02d", i)).readInts());
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripDenseAttributes() throws IOException {
        Path file = Files.createTempFile("falcon-dense-attrs", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                Hdf5Writer.DatasetWriter d = w.intDataset("d", new int[] {1, 2, 3}, new long[] {3});
                for (int i = 0; i < 12; i++) { // > 8 attributes -> dense storage (fractal heap + v2 B-tree)
                    d.intAttribute(String.format("attr%02d", i), new int[] {i * 10}, new long[] {});
                }
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset d = h5.root().dataset("d");
                assertEquals(12, d.attributes().size());
                for (int i = 0; i < 12; i++) {
                    assertArrayEquals(new int[] {i * 10},
                            d.attribute(String.format("attr%02d", i)).orElseThrow().readInts());
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripEarliestManyChildren() throws IOException {
        Path file = Files.createTempFile("falcon-msnod", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
                Hdf5Writer.GroupWriter g = w.group("g");
                for (int i = 0; i < 20; i++) { // > 8 children -> multiple symbol-table nodes
                    g.intDataset(String.format("item%02d", i), new int[] {i}, new long[] {1});
                }
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Group g = h5.root().group("g");
                assertEquals(20, g.childNames().size());
                for (int i = 0; i < 20; i++) {
                    assertArrayEquals(new int[] {i}, g.dataset(String.format("item%02d", i)).readInts());
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripEarliestFormat() throws IOException {
        Path file = Files.createTempFile("falcon-legacy", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
                Hdf5Writer.GroupWriter alpha = w.group("alpha");
                alpha.intDataset("gamma", new int[] {0, 1, 2, 3, 4, 5}, new long[] {6});
                alpha.group("beta").stringDataset("labels", new String[] {"a", "bb", "ccc"}, new long[] {3});
                w.intDataset("root_ds", new int[] {10, 20, 30, 40}, new long[] {4})
                        .intAttribute("scale", new int[] {100}, new long[] {});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertEquals(0, h5.superblockVersion()); // the original (v0 superblock) format
                Group alpha = h5.root().group("alpha");
                assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, alpha.dataset("gamma").readInts());
                assertArrayEquals(new String[] {"a", "bb", "ccc"},
                        alpha.group("beta").dataset("labels").readStrings());
                Dataset rootDs = h5.root().dataset("root_ds");
                assertArrayEquals(new int[] {10, 20, 30, 40}, rootDs.readInts());
                assertArrayEquals(new int[] {100}, rootDs.attribute("scale").orElseThrow().readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripDoubleDatasetAndAttribute() throws IOException {
        Path file = Files.createTempFile("falcon-write-f8", ".h5");
        try {
            try (Hdf5Writer writer = Hdf5Writer.create(file)) {
                writer.doubleDataset("values", new double[] {1.5, -2.25, 3.0}, new long[] {3})
                        .doubleAttribute("offset", new double[] {0.125}, new long[] {});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset values = h5.root().dataset("values");
                assertArrayEquals(new double[] {1.5, -2.25, 3.0}, values.readDoubles());
                assertArrayEquals(new double[] {0.125}, values.attribute("offset").orElseThrow().readDoubles());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
