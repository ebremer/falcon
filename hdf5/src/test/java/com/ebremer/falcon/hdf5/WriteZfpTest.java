package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing the ZFP filter (32013, P2 S10) as hdf5plugin's H5Z-ZFP 1.1.1 with zfp 1.0.1 writes it. Every
 * dataset of zfp_write.h5, written again by Falcon from the input kept beside it with the same settings, has
 * the same filters (id, name, flags, and client data: H5Z-ZFP's version and zfp's header) and the same chunks,
 * byte for byte: every scalar type, 1 to 4 used dimensions and dimensions of size 1, partial chunks, every
 * mode, and values zfp does not expect.
 */
class WriteZfpTest {

    @TempDir
    Path dir;

    @Test
    void writesWhatH5zZfpWrote() throws IOException {
        Path out = dir.resolve("zfp_again.h5");
        List<String> names = new ArrayList<>();
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("zfp_write.h5"));
             Hdf5Writer w = Hdf5Writer.create(out)) {
            for (Hdf5Object object : h5.root().children()) {
                if (!(object instanceof Dataset dataset)) {
                    continue; // /input
                }
                long[] shape = dataset.dataspace().dimensions();
                String source = dataset.attribute("source").orElseThrow().readString();
                Hdf5Writer.DatasetWriter d = w.createDataset(dataset.name(), dataset.datatype(), shape)
                        .chunked(dataset.chunkShape().orElseThrow());
                apply(d, dataset.attribute("zfp").orElseThrow().readString());
                if (dataset.filters().stream().anyMatch(f -> f.id() == Filter.FLETCHER32)) {
                    d.fletcher32();
                }
                d.writeRaw(new long[shape.length], shape, h5.root().dataset("input/" + source).readRawBytes());
                names.add(dataset.name());
            }
        }
        byte[] theirs = Files.readAllBytes(Fixtures.path("zfp_write.h5"));
        byte[] ours = Files.readAllBytes(out);
        int same = 0;
        try (Hdf5File plugin = Hdf5File.open(Fixtures.path("zfp_write.h5"));
             Hdf5File falcon = Hdf5File.open(out)) {
            for (String name : names) {
                Dataset expected = plugin.root().dataset(name);
                Dataset actual = falcon.root().dataset(name);
                assertEquals(expected.filters(), actual.filters(), name);
                assertArrayEquals(expected.readRawBytes(), actual.readRawBytes(), name); // libzfp's decoding of each
                List<ChunkRecord> a = chunks(expected);
                List<ChunkRecord> b = chunks(actual);
                assertEquals(a.size(), b.size(), name);
                for (int i = 0; i < a.size(); i++) {
                    ChunkRecord x = a.get(i);
                    ChunkRecord y = b.get(i);
                    assertArrayEquals(x.offset(), y.offset(), name);
                    assertEquals(x.filterMask(), y.filterMask(), name);
                    byte[] xs = Arrays.copyOfRange(theirs, (int) x.address(), (int) (x.address() + x.size()));
                    byte[] ys = Arrays.copyOfRange(ours, (int) y.address(), (int) (y.address() + y.size()));
                    assertArrayEquals(xs, ys, name + " chunk " + Arrays.toString(x.offset()));
                    same++;
                }
            }
        }
        assertEquals(43, names.size());
        assertTrue(same > 150, "chunks compared: " + same);
    }

    /** The DatasetWriter call for a dataset's "zfp" attribute. */
    private static void apply(Hdf5Writer.DatasetWriter d, String setting) {
        String[] p = setting.split(" ");
        switch (p[0]) {
            case "rate" -> d.zfpRate(Double.parseDouble(p[1]));
            case "precision" -> d.zfpPrecision(Integer.parseInt(p[1]));
            case "accuracy" -> d.zfpAccuracy(Double.parseDouble(p[1]));
            case "reversible" -> d.zfpReversible();
            case "expert" -> d.zfpExpert(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]));
            case "defaults" -> d.zfpExpert(1, 16658, 64, -1074); // H5Z-ZFP's defaults: zfp's
            default -> throw new AssertionError(setting);
        }
    }

    /** The reversible mode keeps every value, read back through Falcon's own decoder. */
    @Test
    void reversibleRoundTrips() throws IOException {
        Path out = dir.resolve("rev.h5");
        double[] values = {Double.NaN, Double.NEGATIVE_INFINITY, -0.0, 0x1p-1074, 1e300, -2.5, 7, 0.1, 3, 4};
        ByteBuffer raw = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            raw.putDouble(v);
        }
        try (Hdf5Writer w = Hdf5Writer.create(out)) {
            w.createDataset("d", Datatype.float64(), new long[] {2, 5}).chunked(2, 3).zfpReversible()
                    .writeRaw(new long[2], new long[] {2, 5}, raw.array());
        }
        try (Hdf5File h5 = Hdf5File.open(out)) {
            assertArrayEquals(raw.array(), h5.root().dataset("d").readRawBytes());
        }
    }

    /** What H5Z-ZFP refuses, Falcon refuses before writing. */
    @Test
    void refusesWhatH5zZfpRefuses() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("refused.h5"))) {
            // 2-byte elements, big-endian ones, and no chunking
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("a", Datatype.int16(), new long[] {8}).chunked(4).zfpReversible());
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("b", Datatype.float64().withByteOrder(ByteOrder.BIG_ENDIAN), new long[] {8})
                            .chunked(4).zfpReversible());
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("c", Datatype.float64(), new long[] {8}).zfpReversible());
            // a chunk of a single value, and one of five dimensions longer than 1
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("d", Datatype.float64(), new long[] {8, 8}).chunked(1, 1).zfpReversible());
            assertThrows(IllegalStateException.class, () -> w.createDataset("e", Datatype.float32(),
                    new long[] {2, 2, 2, 2, 2}).chunked(2, 2, 2, 2, 2).zfpReversible());
            // zfp first; parameters it cannot take
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("f", Datatype.float32(), new long[] {8}).chunked(4).shuffle().zfpRate(8));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("g", Datatype.float32(), new long[] {8}).chunked(4).zfpRate(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("h", Datatype.float32(), new long[] {8}).chunked(4).zfpPrecision(65));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("i", Datatype.float32(), new long[] {8}).chunked(4).zfpExpert(10, 5, 20, 0));
        }
    }

    private static List<ChunkRecord> chunks(Dataset dataset) {
        DataLayout.Chunked chunked = (DataLayout.Chunked) dataset.dataLayout();
        List<ChunkRecord> all = new ArrayList<>(dataset.chunkIndex(chunked).all());
        all.sort((p, q) -> Arrays.compare(p.offset(), q.offset()));
        return all;
    }
}
