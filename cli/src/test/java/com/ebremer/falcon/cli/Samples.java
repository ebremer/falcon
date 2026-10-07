package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Hdf5Writer;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The files the tests run the command on, written with Falcon's own writers. */
final class Samples {

    private Samples() {
    }

    /** The datasets of {@link #hdf5} that every conversion carries over (the others it leaves out). */
    static final String[] CONVERTED = {"/big", "/blobs", "/colors", "/cplx", "/cube", "/empty", "/flags", "/names",
        "/pairs", "/run/counts", "/run/temperature", "/scalar", "/table", "/when", "/words"};

    /**
     * An HDF5 file of many types: integers of both byte orders, uint64 past a long, floats with a NaN fill,
     * h5py's booleans and complex numbers, strings fixed and variable, an enumeration, a compound with a
     * big-endian member and a fixed string, an array element type, bytes, numpy dates as h5py tags them, a
     * scalar, an empty dataset; and what Zarr cannot hold: a ragged int32 sequence, a reference, a soft link
     * and an external link.
     */
    static Path hdf5(Path file) throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.GroupWriter root = w.root();
            root.stringAttribute("title", "sample");
            root.attribute("version", Datatype.int64(), new long[0], new long[] {3});
            root.attribute("ranges", Datatype.int16(), new long[] {2, 2}, new int[] {0, 1, 2, 3});
            root.attribute("scale", Datatype.float32(), new long[0], new float[] {0.1f});
            Hdf5Writer.GroupWriter run = w.group("run");
            run.stringAttribute("started", "yes");
            float[] temperature = new float[24];
            for (int i = 0; i < 24; i++) {
                temperature[i] = i / 10f;
            }
            run.createDataset("temperature", Datatype.float32(), 4, 6).chunked(2, 6).shuffle().deflate(4)
                    .fillValue(Double.NaN).write(temperature).stringAttribute("units", "K");
            run.createDataset("counts", Datatype.uint16().withByteOrder(ByteOrder.BIG_ENDIAN), 10)
                    .write(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
            root.createDataset("big", Datatype.uint64(), 3).write(new BigInteger[] {BigInteger.ZERO,
                BigInteger.TWO.pow(64).subtract(BigInteger.ONE), BigInteger.TWO.pow(63)});
            root.createDataset("flags", Datatype.bool(), 3).write(new boolean[] {true, false, true});
            root.createDataset("names", new Datatype.StringType(5, Datatype.StringPadding.NULL_PAD,
                    Datatype.CharacterSet.ASCII), 3).write(new String[] {"alpha", "b", ""});
            root.createDataset("words", Datatype.variableString(), 3).write(new String[] {"x", "yy", "zzz"});
            Map<String, Datatype> parts = new LinkedHashMap<>();
            parts.put("r", Datatype.float32());
            parts.put("i", Datatype.float32());
            Map<String, Object> cplx = new LinkedHashMap<>();
            cplx.put("r", new float[] {1, -3.5f});
            cplx.put("i", new float[] {2, -0.25f});
            root.createDataset("cplx", Datatype.compound(parts), 2).write(cplx);
            Map<String, Datatype> members = new LinkedHashMap<>();
            members.put("id", Datatype.int32());
            members.put("v", Datatype.float64().withByteOrder(ByteOrder.BIG_ENDIAN));
            members.put("tag", new Datatype.StringType(2, Datatype.StringPadding.NULL_PAD, Datatype.CharacterSet.ASCII));
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("id", new int[] {1, 2});
            table.put("v", new double[] {2.5, -1});
            table.put("tag", new String[] {"ab", "c"});
            root.createDataset("table", Datatype.compound(members), 2).write(table);
            root.createDataset("scalar", Datatype.float64()).write(new double[] {42});
            long[] cube = new long[24];
            for (int i = 0; i < 24; i++) {
                cube[i] = i;
            }
            root.createDataset("cube", Datatype.int64(), 2, 3, 4).chunked(1, 3, 4).write(cube);
            root.createDataset("empty", Datatype.float64(), 0, 3);
            root.enumDataset("colors", new long[] {3},
                    Hdf5Writer.enumType().add("RED", 0).add("GREEN", 1).add("BLUE", 2), new int[] {2, 0, 1});
            root.createDataset("pairs", Datatype.arrayOf(Datatype.int16(), 2), 3).write(new short[] {1, 2, 3, 4, 5, 6});
            root.createDataset("blobs", Datatype.sequenceOf(Datatype.uint8()), 2).write(new int[][] {{1, 2, 255}, {}});
            root.createDataset("when", Datatype.opaque(8, "NUMPY:<M8[D]"), 2)
                    .write(new byte[][] {le(18262), le(10956)}); // 2020-01-01, 1999-12-31
            root.createDataset("ragged", Datatype.sequenceOf(Datatype.int32()), 2).write(new int[][] {{1, 2, 3}, {}});
            w.referenceDataset("ref", new long[] {1}, new String[] {"/run"});
            w.softLink("soft", "/run/temperature");
            w.externalLink("ext", "other.h5", "/x");
        }
        return file;
    }

    static byte[] le(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }
}
