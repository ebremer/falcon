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
