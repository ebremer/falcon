package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing the SZ filter (32017, P2 S10) as hdf5plugin's H5Z-SZ (SZ 2.1.12) writes it. Every dataset of
 * sz_write.h5, written again by Falcon from the input kept beside it with the same settings, has the same
 * filters (id, name, flags, and client data) and the same chunks: chunks H5Z-SZ stores as they are, byte for
 * byte; SZ streams, beneath their zstd stage (Falcon's zstd frames are its own), byte for byte but for what
 * libSZ itself leaves undefined (a parameter byte {@code convertSZParamsToBytes} never writes, and the two
 * values a 1-D integer copy reads from past its input) and with the point-wise relative coders' signs
 * compared as signs (they are zstd-compressed too). The exception is int64 data: hdf5plugin's MSVC libSZ
 * computes its range over 32-bit words and loses values that need more; Falcon's chunks hold them within the
 * bound.
 */
class WriteSzTest {

    @TempDir
    Path dir;

    private static final byte[] ZSTD_MAGIC = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

    @Test
    void writesWhatH5zSzWrote() throws IOException {
        Path out = dir.resolve("sz_again.h5");
        List<String> names = new ArrayList<>();
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("sz_write.h5"));
             Hdf5Writer w = Hdf5Writer.create(out)) {
            for (Hdf5Object object : h5.root().children()) {
                if (!(object instanceof Dataset dataset)) {
                    continue; // /input
                }
                long[] shape = dataset.dataspace().dimensions();
                String source = dataset.attribute("source").orElseThrow().readString();
                Hdf5Writer.DatasetWriter d = w.createDataset(dataset.name(), dataset.datatype(), shape)
                        .chunked(dataset.chunkShape().orElseThrow());
                apply(d, dataset.attribute("sz").orElseThrow().readString());
                if (dataset.filters().stream().anyMatch(f -> f.id() == Filter.FLETCHER32)) {
                    d.fletcher32();
                }
                d.writeRaw(new long[shape.length], shape, h5.root().dataset("input/" + source).readRawBytes());
                names.add(dataset.name());
            }
        }
        byte[] theirs = Files.readAllBytes(Fixtures.path("sz_write.h5"));
        byte[] ours = Files.readAllBytes(out);
        int streams = 0;
        int stored = 0;
        try (Hdf5File plugin = Hdf5File.open(Fixtures.path("sz_write.h5"));
             Hdf5File falcon = Hdf5File.open(out)) {
            for (String name : names) {
                Dataset expected = plugin.root().dataset(name);
                Dataset actual = falcon.root().dataset(name);
                assertEquals(expected.filters(), actual.filters(), name);
                Filter sz = expected.filters().getFirst();
                assertEquals(Filter.SZ, sz.id());
                int type = sz.clientData()[1];
                long count = elements(sz.clientData());
                boolean fletcher = expected.filters().size() > 1;
                if (name.startsWith("i8_")) {
                    continue; // hdf5plugin's chunks lose these values: see int64KeepsItsValues
                }
                assertArrayEquals(expected.readRawBytes(), actual.readRawBytes(), name); // decoded alike
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
                    if (fletcher) {
                        xs = Arrays.copyOf(xs, xs.length - 4);
                        ys = Arrays.copyOf(ys, ys.length - 4);
                    }
                    String what = name + " chunk " + Arrays.toString(x.offset());
                    if (count < 20 || Arrays.equals(xs, ys)) {
                        assertArrayEquals(xs, ys, what); // stored as it is (or 20 values, as SZ keeps them)
                        stored++;
                        continue;
                    }
                    assertArrayEquals(inner(xs, type, count), inner(ys, type, count), what);
                    streams++;
                }
            }
        }
        assertEquals(35, names.size());
        assertTrue(streams > 70, "SZ streams compared: " + streams);
        assertTrue(stored >= 10, "chunks stored as they are: " + stored);
    }

    /** The DatasetWriter call for a dataset's "sz" attribute. */
    private static void apply(Hdf5Writer.DatasetWriter d, String setting) {
        String[] p = setting.split(" ");
        switch (p[0]) {
            case "absolute" -> d.szAbsolute(Double.parseDouble(p[1]));
            case "relative" -> d.szRelative(Double.parseDouble(p[1]));
            case "pointwise_relative" -> d.szPointwiseRelative(Double.parseDouble(p[1]));
            case "defaults" -> d.sz();
            default -> throw new AssertionError(setting);
        }
    }

    /**
     * SZ's bytes beneath the zstd stage, with what libSZ leaves undefined set to 0 and a point-wise relative
     * stream's zstd-compressed signs replaced by the signs.
     */
    static byte[] inner(byte[] chunk, int type, long count) {
        byte[] b = Arrays.equals(Arrays.copyOf(chunk, 4), ZSTD_MAGIC)
                ? ZstdDecoder.decompress(chunk, 0, chunk.length, Integer.MAX_VALUE - 8) : chunk.clone();
        if (b.length > 19) {
            b[19] = 0; // convertSZParamsToBytes' byte 15: never written, malloc's
        }
        int size = type <= 1 ? 4 << type : type <= 3 ? 1 : type <= 5 ? 2 : type <= 7 ? 4 : 8;
        if (type >= 2 && (b[3] & 0xff) == 0x50 && b.length == 40 + size * (count + 2)) {
            Arrays.fill(b, b.length - 2 * size, b.length, (byte) 0); // read from past libSZ's input
        }
        return type <= 1 ? signs(b, type, (int) count) : b;
    }

    /** A point-wise relative classic stream with its zstd-compressed signs replaced by the signs. */
    private static byte[] signs(byte[] b, int type, int count) {
        int flag = b[3] & 0xff;
        if ((flag & 0x20) == 0 || (flag & 0x91) != 0) {
            return b;
        }
        int w = type == 0 ? 4 : 8;
        int pos = 4 + (type == 0 ? 28 : 36) + 8 + 4 + 1 + 8;
        int sizeAt = pos;
        ByteBuffer in = ByteBuffer.wrap(b);
        int signs = in.getInt(pos);
        pos += 4 + 4 + w + 1 + ((flag & 0x08) != 0 ? 2 : 0) + 8;
        long typeSize = in.getLong(pos);
        int signsAt = (int) (pos + 8 + 8 + 8 + w + typeSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b, 0, sizeAt);
        out.write(b, sizeAt + 4, signsAt - sizeAt - 4);
        if (signs > 0) {
            out.writeBytes(ZstdDecoder.decompress(b, signsAt, signs, count));
        }
        out.write(b, signsAt + signs, b.length - signsAt - signs);
        return out.toByteArray();
    }

    /** {@code computeDataLength} of SZ client data. */
    private static long elements(int[] cd) {
        if (cd[0] == 1) {
            return (cd[2] & 0xffffffffL) << 32 | (cd[3] & 0xffffffffL);
        }
        long n = 1;
        for (int i = 0; i < cd[0]; i++) {
            n *= cd[2 + i] & 0xffffffffL;
        }
        return n;
    }

    /**
     * int64 values that need more than 32 bits: hdf5plugin's libSZ (its MSVC build reads int64 data through a
     * 32-bit {@code long} when it computes the range) stores them far outside the bound; Falcon's chunks keep
     * each within it.
     */
    @Test
    void int64KeepsItsValues() throws IOException {
        Path out = dir.resolve("i8.h5");
        long[] values;
        long[] theirs;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("sz_write.h5"));
             Hdf5Writer w = Hdf5Writer.create(out)) {
            Dataset input = h5.root().dataset("input/i8_big");
            long[] shape = input.dataspace().dimensions();
            w.createDataset("d", input.datatype(), shape).chunked(8, 20).szAbsolute(3)
                    .writeRaw(new long[2], shape, input.readRawBytes());
            values = input.readLongs();
            theirs = h5.root().dataset("i8_big_abs_int").readLongs();
        }
        try (Hdf5File falcon = Hdf5File.open(out)) {
            long[] ours = falcon.root().dataset("d").readLongs();
            for (int i = 0; i < values.length; i++) {
                assertTrue(Math.abs(ours[i] - values[i]) <= 3, "value " + i + ": " + ours[i] + " for " + values[i]);
            }
        }
        int lost = 0;
        for (int i = 0; i < values.length; i++) {
            lost += Math.abs(theirs[i] - values[i]) > 3 ? 1 : 0;
        }
        assertTrue(lost > values.length / 2, "values hdf5plugin's libSZ lost: " + lost);
    }

    /** Falcon writes into the plugin's SZ datasets, with the client data the file holds. */
    @Test
    void writesIntoThePluginsDatasets() throws IOException {
        Path file = dir.resolve("edit.h5");
        Files.copy(Fixtures.path("sz_write.h5"), file, StandardCopyOption.REPLACE_EXISTING);
        float[] row = new float[100];
        Arrays.fill(row, 2.25f);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("f4_1d_abs3").write(new long[] {100}, new long[] {100}, row);
            w.dataset("i4_2d_abs_int").write(new long[] {3, 0}, new long[] {1, 20}, new int[20]);
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            float[] f = h5.root().dataset("f4_1d_abs3").readFloats();
            for (int i = 100; i < 200; i++) {
                assertEquals(2.25, f[i], 1e-3);
            }
            int[] ints = h5.root().dataset("i4_2d_abs_int").readInts();
            for (int j = 0; j < 20; j++) {
                assertTrue(Math.abs(ints[3 * 20 + j]) <= 3, "value " + j + ": " + ints[3 * 20 + j]);
            }
        }
    }

    /** What H5Z-SZ (or libSZ) refuses, or would mangle, Falcon refuses before writing. */
    @Test
    void refusesWhatH5zSzRefuses() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("refused.h5"))) {
            // big-endian values, a 2-byte float, no chunking, and strings
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("a", Datatype.float64().withByteOrder(ByteOrder.BIG_ENDIAN), new long[] {40})
                            .chunked(20).szAbsolute(1e-3));
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("b", Datatype.float16(), new long[] {40}).chunked(20).szAbsolute(1e-3));
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("c", Datatype.float32(), new long[] {40}).szAbsolute(1e-3));
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("d", Datatype.string(8), new long[] {40}).chunked(20).szAbsolute(1e-3));
            // a point-wise relative bound on integers (libSZ exits), five dimensions longer than 1
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("e", Datatype.int32(), new long[] {40}).chunked(20).szPointwiseRelative(1e-3));
            assertThrows(IllegalStateException.class, () -> w.createDataset("f", Datatype.float32(),
                    new long[] {2, 2, 2, 2, 2}).chunked(2, 2, 2, 2, 2).szAbsolute(1e-3));
            // SZ first; bounds it cannot take
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("g", Datatype.float32(), new long[] {40}).chunked(20).shuffle().szAbsolute(1));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("h", Datatype.float32(), new long[] {40}).chunked(20).szAbsolute(0));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("i", Datatype.float32(), new long[] {40}).chunked(20).szRelative(-1));
            assertThrows(IllegalArgumentException.class, () -> w.createDataset("j", Datatype.float32(),
                    new long[] {40}).chunked(20).szPointwiseRelative(Double.POSITIVE_INFINITY));
        }
    }

    /** A chunk of fewer than 20 values, and one of (1, n), H5Z-SZ stores as they are: read back exactly. */
    @Test
    void shortChunksAreStoredAsTheyAre() throws IOException {
        Path out = dir.resolve("short.h5");
        float[] values = new float[400];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) Math.sin(i * 0.37) * 1000;
        }
        try (Hdf5Writer w = Hdf5Writer.create(out)) {
            w.createDataset("short", Datatype.float32(), new long[] {20, 20}).chunked(4, 4).szAbsolute(10)
                    .write(new long[2], new long[] {20, 20}, values);
            w.createDataset("lead1", Datatype.float32(), new long[] {4, 100}).chunked(1, 100).szAbsolute(10)
                    .write(new long[2], new long[] {4, 100}, values);
        }
        try (Hdf5File h5 = Hdf5File.open(out)) {
            assertArrayEquals(values, h5.root().dataset("short").readFloats());
            assertArrayEquals(values, h5.root().dataset("lead1").readFloats());
            int[] cd = h5.root().dataset("lead1").filters().getFirst().clientData();
            assertEquals(1, cd[0]); // H5Z-SZ records one dimension, of the first dimension's length: 1
            assertEquals(1, cd[3]);
            assertFalse(cd.length < 13);
        }
    }

    private static List<ChunkRecord> chunks(Dataset dataset) {
        DataLayout.Chunked chunked = (DataLayout.Chunked) dataset.dataLayout();
        List<ChunkRecord> all = new ArrayList<>(dataset.chunkIndex(chunked).all());
        all.sort((p, q) -> Arrays.compare(p.offset(), q.offset()));
        return all;
    }
}
