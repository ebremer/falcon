package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * P2 PF14: the writer's per-type methods ({@code doubleDataset}, {@code intChunkedDataset}, a compound's
 * fields, ...) copy their arrays in one move. The file holds the bytes it held: every value little-endian,
 * and a NaN, whatever its bits, as the one NaN these methods have always written.
 */
class WriteTypedArraysTest {

    private static final int N = 6 * 7 * 4;
    private static final long[] SHAPE = {6, 28};

    private static final double[] DOUBLES = new double[N];
    private static final float[] FLOATS = new float[N];
    private static final long[] LONGS = new long[N];
    private static final int[] INTS = new int[N];
    private static final short[] SHORTS = new short[N];
    private static final byte[] BYTES = new byte[N];

    static {
        Random random = new Random(14);
        for (int i = 0; i < N; i++) {
            LONGS[i] = random.nextLong();
            INTS[i] = random.nextInt();
            SHORTS[i] = (short) random.nextInt();
            DOUBLES[i] = random.nextGaussian() * 1e9;
            FLOATS[i] = (float) random.nextGaussian();
        }
        random.nextBytes(BYTES);
        LONGS[0] = Long.MIN_VALUE;
        LONGS[1] = Long.MAX_VALUE;
        INTS[0] = Integer.MIN_VALUE;
        INTS[1] = Integer.MAX_VALUE;
        SHORTS[0] = Short.MIN_VALUE;
        SHORTS[1] = Short.MAX_VALUE;
        double[] special = {0.0, -0.0, Double.MIN_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN, Double.longBitsToDouble(0x7FF0000000000001L),
                Double.longBitsToDouble(0xFFF8000000000123L), Double.longBitsToDouble(0xFFFFFFFFFFFFFFFFL)};
        System.arraycopy(special, 0, DOUBLES, 3, special.length);
        DOUBLES[N - 1] = Double.longBitsToDouble(0x7FF4000000000000L); // a NaN in the last place, too
        float[] special32 = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NaN, Float.intBitsToFloat(0x7F800001), Float.intBitsToFloat(0xFFC00123),
                Float.intBitsToFloat(0xFFFFFFFF)};
        System.arraycopy(special32, 0, FLOATS, 3, special32.length);
        FLOATS[N - 1] = Float.intBitsToFloat(0x7FA00000);
    }

    /** The bytes these methods write for doubles: little-endian, every NaN the canonical one. */
    private static byte[] bytes(double[] values) {
        ByteBuffer out = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            out.putLong(Double.isNaN(v) ? 0x7FF8000000000000L : Double.doubleToRawLongBits(v));
        }
        return out.array();
    }

    private static byte[] bytes(float[] values) {
        ByteBuffer out = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            out.putInt(Float.isNaN(v) ? 0x7FC00000 : Float.floatToRawIntBits(v));
        }
        return out.array();
    }

    private static byte[] bytes(long[] values) {
        ByteBuffer out = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) {
            out.putLong(v);
        }
        return out.array();
    }

    private static byte[] bytes(int[] values) {
        ByteBuffer out = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            out.putInt(v);
        }
        return out.array();
    }

    private static byte[] bytes(short[] values) {
        ByteBuffer out = ByteBuffer.allocate(2 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : values) {
            out.putShort(v);
        }
        return out.array();
    }

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void theTypedMethodsWriteTheBytesTheyWrote(Hdf5Writer.Format format, @TempDir Path dir) throws IOException {
        Path file = dir.resolve("typed.h5");
        double[] imaginary = DOUBLES.clone();
        for (int i = 0; i < N / 2; i++) { // the same values, in another order
            double t = imaginary[i];
            imaginary[i] = imaginary[N - 1 - i];
            imaginary[N - 1 - i] = t;
        }
        int[][] intRows = {{1, -2, Integer.MIN_VALUE}, {}, {7}};
        double[][] doubleRows = {{1.5, Double.longBitsToDouble(0x7FF0000000000009L)}, {-0.0}, {}};
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            w.doubleDataset("doubles", DOUBLES, SHAPE);
            w.floatDataset("floats", FLOATS, SHAPE);
            w.longDataset("longs", LONGS, SHAPE);
            w.intDataset("ints", INTS, SHAPE).intAttribute("ints", INTS, SHAPE).doubleAttribute("doubles", DOUBLES, SHAPE);
            w.shortDataset("shorts", SHORTS, SHAPE);
            w.byteDataset("bytes", BYTES, SHAPE);
            w.doubleChunkedDataset("doubles chunked", DOUBLES, SHAPE, new long[] {4, 5});
            w.intChunkedDataset("ints chunked", INTS, SHAPE, new long[] {5, 9}).shuffle().deflate(3);
            w.float32ArrayDataset("float arrays", new long[] {6, 7}, new int[] {4}, FLOATS);
            w.int32ArrayDataset("int arrays", new long[] {6}, new int[] {7, 4}, INTS);
            w.complexDataset("complex", SHAPE, DOUBLES, imaginary);
            w.compoundDataset("records", SHAPE, Hdf5Writer.CompoundField.int32("i", INTS),
                    Hdf5Writer.CompoundField.float64("d", DOUBLES));
            w.intSequenceDataset("int rows", new long[] {3}, intRows);
            w.doubleSequenceDataset("double rows", new long[] {3}, doubleRows);
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertArrayEquals(bytes(DOUBLES), root.dataset("doubles").readRawBytes());
            assertArrayEquals(bytes(FLOATS), root.dataset("floats").readRawBytes());
            assertArrayEquals(bytes(LONGS), root.dataset("longs").readRawBytes());
            assertArrayEquals(bytes(INTS), root.dataset("ints").readRawBytes());
            assertArrayEquals(INTS, root.dataset("ints").attribute("ints").orElseThrow().readInts());
            double[] attribute = root.dataset("ints").attribute("doubles").orElseThrow().readDoubles();
            assertArrayEquals(bytes(DOUBLES), bytes(attribute));
            assertEquals(0x7FF8000000000000L, Double.doubleToRawLongBits(attribute[N - 1]), "a NaN of an attribute, too");
            assertArrayEquals(bytes(SHORTS), root.dataset("shorts").readRawBytes());
            assertArrayEquals(BYTES, root.dataset("bytes").readRawBytes());
            assertArrayEquals(bytes(DOUBLES), root.dataset("doubles chunked").readRawBytes());
            assertArrayEquals(bytes(INTS), root.dataset("ints chunked").readRawBytes());
            assertArrayEquals(bytes(FLOATS), root.dataset("float arrays").readRawBytes());
            assertArrayEquals(bytes(INTS), root.dataset("int arrays").readRawBytes());

            double[] pairs = new double[2 * N];
            for (int i = 0; i < N; i++) {
                pairs[2 * i] = DOUBLES[i];
                pairs[2 * i + 1] = imaginary[i];
            }
            assertArrayEquals(bytes(pairs), root.dataset("complex").readRawBytes());

            ByteBuffer records = ByteBuffer.allocate(12 * N).order(ByteOrder.LITTLE_ENDIAN);
            byte[] doubles = bytes(DOUBLES);
            for (int i = 0; i < N; i++) {
                records.putInt(INTS[i]).put(doubles, 8 * i, 8);
            }
            assertArrayEquals(records.array(), root.dataset("records").readRawBytes());

            int[][] ints = root.dataset("int rows").readVlenInts();
            assertEquals(3, ints.length);
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(intRows[i], ints[i]);
            }
            double[][] rows = root.dataset("double rows").readVlenDoubles();
            assertEquals(3, rows.length);
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(bytes(doubleRows[i]), bytes(rows[i]));
            }
            assertEquals(0x7FF8000000000000L, Double.doubleToRawLongBits(rows[0][1]));
        }
    }

    @Test
    void anEmptyArrayIsNoBytes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleDataset("d", new double[0], new long[] {0});
            w.intDataset("i", new int[0], new long[] {0, 5});
            w.floatDataset("f", new float[0], new long[] {0});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(0, h5.root().dataset("d").readRawBytes().length);
            assertEquals(0, h5.root().dataset("i").readRawBytes().length);
            assertEquals(0, h5.root().dataset("f").readRawBytes().length);
        }
    }
}
