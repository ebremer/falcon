import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Selection;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;

/**
 * Writes one Zarr v3 array per data type and codec layout with Falcon, for check_zarr_writer.py to read
 * back with zarr-python (P1 T1), plus P2's variable-length bytes (F5), arrays written with
 * {@code withWriteEmptyChunks} (F7), resized arrays (F6), nested shards (F11), and uint64 and complex values
 * written with {@code writeUnsignedLongs}/{@code writeComplex} (F8). Dev-time tool, run with the JDK's
 * source launcher from the repo root, after {@code mvn -pl zarr -am compile}:
 *
 * <pre>
 *     java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrCases.java OUT_DIR
 *     python tools/fixtures/check_zarr_writer.py OUT_DIR
 * </pre>
 *
 * (':' separates the classpath outside Windows). Element i of every array (C order) is a fixed formula
 * of i, which check_zarr_writer.py computes too; manifest.json lists each array and the part of it that
 * was written (the rest is the fill value).
 */
public class WriteZarrCases {

    static final String[] NUMERIC = {"bool", "int8", "uint8", "int16", "uint16", "int32", "uint32", "int64",
        "uint64", "float16", "float32", "float64", "complex64", "complex128"};
    static final String[] LAYOUTS = {"plain", "gzip", "zstd", "blosc", "gzip_crc32c", "big_endian", "sharded",
        "sharded_start_zstd", "sharded_blosc_crc32c", "v2_keys"};
    static final String[] POOL = {"alpha", "", "gamma-δ", "中文", "emoji-😀", "x"};
    static final long[] SHAPE = {13, 7};
    static final long[] PARTIAL = {2, 9, 1, 5}; // rows 2..8, columns 1..4

    static final String BYTES_LE = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}";

    static FileSystemStore store;
    static ZarrGroup root;
    static final List<String> MANIFEST = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        store = FileSystemStore.open(out);
        root = Zarr.createGroup(store, new JsonObject(Map.of()), true);

        for (String dtype : NUMERIC) {
            for (String layout : LAYOUTS) {
                ArraySpec.Builder b = ArraySpec.builder(SHAPE, DataType.of(dtype)).chunkShape(6, 4);
                switch (layout) {
                    case "gzip" -> b.gzip(5);
                    case "zstd" -> b.zstd();
                    case "blosc" -> b.blosc();
                    case "gzip_crc32c" -> b.gzip(1).crc32c();
                    case "big_endian" -> b.endian(ByteOrder.BIG_ENDIAN);
                    case "sharded" -> b.sharding(3, 2);
                    case "sharded_start_zstd" -> b.sharding(3, 2).shardIndexAtStart().zstd();
                    case "sharded_blosc_crc32c" -> b.sharding(3, 2).blosc().crc32c();
                    case "v2_keys" -> b.chunkKeyEncoding("v2");
                    default -> {
                    }
                }
                ByteOrder order = layout.equals("big_endian") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
                write(root.createArray(dtype + "_" + layout, b.build()), dtype, order, null);
            }
            // Only part written: the rest stays fill, some shards and sub-chunks absent.
            write(root.createArray(dtype + "_partial", ArraySpec.builder(SHAPE, DataType.of(dtype)).chunkShape(4, 3)
                    .sharding(2, 3).build()), dtype, ByteOrder.LITTLE_ENDIAN, PARTIAL);
        }
        for (String layout : new String[] {"plain", "zstd", "gzip", "sharded", "sharded_zstd"}) {
            ArraySpec.Builder b = ArraySpec.builder(SHAPE, DataType.STRING).chunkShape(6, 4);
            switch (layout) {
                case "zstd" -> b.zstd();
                case "gzip" -> b.gzip(3);
                case "sharded" -> b.sharding(3, 2);
                case "sharded_zstd" -> b.sharding(3, 2).zstd();
                default -> {
                }
            }
            write(root.createArray("string_" + layout, b.build()), "string", ByteOrder.LITTLE_ENDIAN, null);
        }
        write(root.createArray("string_partial", ArraySpec.builder(SHAPE, DataType.STRING).chunkShape(6, 4)
                .sharding(3, 2).fillValue(new JsonString("?")).build()), "string", ByteOrder.LITTLE_ENDIAN, PARTIAL);

        // P2 F5: variable_length_bytes, in the string layouts.
        for (String layout : new String[] {"plain", "zstd", "gzip", "sharded", "sharded_zstd"}) {
            ArraySpec.Builder b = ArraySpec.builder(SHAPE, DataType.BYTES).chunkShape(6, 4);
            switch (layout) {
                case "zstd" -> b.zstd();
                case "gzip" -> b.gzip(3);
                case "sharded" -> b.sharding(3, 2);
                case "sharded_zstd" -> b.sharding(3, 2).zstd().crc32c();
                default -> {
                }
            }
            write(root.createArray("bytes_" + layout, b.build()), "bytes", ByteOrder.LITTLE_ENDIAN, null);
        }
        write(root.createArray("bytes_partial", ArraySpec.builder(SHAPE, DataType.BYTES).chunkShape(6, 4)
                .sharding(3, 2).fillValue(new JsonString("AP8/")).build()), "bytes", ByteOrder.LITTLE_ENDIAN, PARTIAL);

        // P2 F7: every chunk first written as all fill with withWriteEmptyChunks, so all are stored (and every
        // sub-chunk of a shard), then the partial region with values.
        for (String dtype : new String[] {"int32", "string", "bytes"}) {
            for (boolean sharded : new boolean[] {false, true}) {
                ArraySpec.Builder b = ArraySpec.builder(SHAPE, DataType.of(dtype)).chunkShape(6, 4);
                if (sharded) {
                    b.sharding(3, 2);
                }
                ZarrArray a = root.createArray(dtype + "_write_empty" + (sharded ? "_sharded" : ""), b.build())
                        .withWriteEmptyChunks(true);
                switch (dtype) {
                    case "int32" -> a.writeInts(new int[91]);
                    case "string" -> a.writeStrings(new String[91]);
                    default -> a.writeByteArrays(new byte[91][]);
                }
                write(a, dtype, ByteOrder.LITTLE_ENDIAN, PARTIAL);
            }
        }

        // P2 F6: written whole, shrunk to 10 x 5, grown back to 13 x 7: what was cut off reads as fill (where
        // zarr-python's own resize brings the old values back). And one grown from 10 x 5.
        for (String dtype : new String[] {"int32", "float64", "string", "bytes"}) {
            for (boolean sharded : new boolean[] {false, true}) {
                ArraySpec.Builder b = ArraySpec.builder(SHAPE, DataType.of(dtype)).chunkShape(6, 4);
                if (sharded) {
                    b.sharding(3, 2).zstd();
                }
                ZarrArray a = root.createArray(dtype + "_resized" + (sharded ? "_sharded" : ""), b.build());
                write(a, dtype, ByteOrder.LITTLE_ENDIAN, null);
                MANIFEST.remove(MANIFEST.size() - 1);
                a.resize(10, 5).resize(SHAPE);
                MANIFEST.add(entry(a.name(), dtype, new long[] {0, 10, 0, 5}));
            }
        }
        ZarrArray grown = root.createArray("int16_grown", ArraySpec.builder(new long[] {10, 5}, DataType.INT16)
                .chunkShape(4, 3).fillValue(-7).build());
        write(grown, "int16", ByteOrder.LITTLE_ENDIAN, new long[] {0, 10, 0, 5});
        grown.resize(SHAPE);

        // Layouts ArraySpec does not build, written into hand-made metadata (as zarr-python would make it).
        handMade("int32_transpose", "int32",
                "[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                        + "{\"name\":\"zstd\",\"configuration\":{\"level\":0,\"checksum\":false}}]");
        handMade("string_transpose", "string",
                "[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},{\"name\":\"vlen-utf8\",\"configuration\":{}},"
                        + "{\"name\":\"gzip\",\"configuration\":{\"level\":5}}]");
        handMade("int32_zstd_checksum", "int32",
                "[" + BYTES_LE + ",{\"name\":\"zstd\",\"configuration\":{\"level\":3,\"checksum\":true}}]");
        for (String shuffle : new String[] {"noshuffle", "shuffle", "bitshuffle"}) {
            handMade("float64_blosc_" + shuffle, "float64",
                    "[" + BYTES_LE + ",{\"name\":\"blosc\",\"configuration\":{\"cname\":\"zstd\",\"clevel\":5,"
                            + "\"shuffle\":\"" + shuffle + "\",\"typesize\":8,\"blocksize\":0}}]", "[13,7]");
        }
        handMade("bytes_transpose", "bytes",
                "[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},{\"name\":\"vlen-bytes\",\"configuration\":{}},"
                        + "{\"name\":\"zstd\",\"configuration\":{\"level\":0,\"checksum\":false}}]");
        // P2 F11: shards nested in shards (ArraySpec builds one level, so the metadata is hand-made).
        String bytes = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}";
        handMade("int32_nested", "int32", "[" + shard("[3,2]", "[" + shard("[1,2]", "[" + bytes + "]") + "]") + "]");
        handMade("float64_nested_zstd_crc32c", "float64", "[" + shard("[3,4]", "[" + shard("[3,2]", "[" + bytes
                + ",{\"name\":\"zstd\",\"configuration\":{\"level\":0,\"checksum\":false}}]")
                + ",{\"name\":\"crc32c\"}]") + "]");
        handMade("int8_nested_three", "int8", "[" + shard("[6,4]", "[" + shard("[3,2]", "["
                + shard("[1,1]", "[" + bytes + "]") + "]") + "]") + "]", "[12,4]", null);
        handMade("int16_nested_partial", "int16", "[" + shard("[3,2]", "[" + shard("[1,2]", "[" + bytes + "]") + "]")
                + "]", "[6,4]", PARTIAL);
        handMade("string_nested", "string",
                "[" + shard("[3,2]", "[" + shard("[1,2]", "[{\"name\":\"vlen-utf8\",\"configuration\":{}}]")
                        + "]") + "]");
        handMade("bytes_nested", "bytes",
                "[" + shard("[3,2]", "[" + shard("[1,2]", "[{\"name\":\"vlen-bytes\",\"configuration\":{}}]")
                        + "]") + "]");
        // P2 F8: uint64 values over the whole range, most with no exact double (writeUnsignedLongs); the
        // complex arrays above are written with writeComplex.
        for (String layout : new String[] {"plain", "sharded_zstd"}) {
            ArraySpec.Builder b = ArraySpec.builder(SHAPE, DataType.UINT64).chunkShape(6, 4);
            if (layout.equals("sharded_zstd")) {
                b.sharding(3, 2).zstd();
            }
            write(root.createArray("uint64x_" + layout, b.build()), "uint64x", ByteOrder.LITTLE_ENDIAN, null);
        }
        handMade("int16_blosc_lz4", "int16",
                "[" + BYTES_LE + ",{\"name\":\"blosc\",\"configuration\":{\"cname\":\"lz4\",\"clevel\":5,"
                        + "\"shuffle\":\"shuffle\",\"typesize\":2,\"blocksize\":0}}]", "[13,7]");

        Files.writeString(out.resolve("manifest.json"), "[\n" + String.join(",\n", MANIFEST) + "\n]\n");
        System.out.println(MANIFEST.size() + " arrays written to " + out);
    }

    /** An array of 13 x 7, chunks 6 x 4, fill 0 (or ""), with hand-written codecs. */
    static void handMade(String name, String dtype, String codecs) throws Exception {
        handMade(name, dtype, codecs, "[6,4]");
    }

    /** An array of 13 x 7 with the given chunk shape, fill 0 (or ""), with hand-written codecs. */
    static void handMade(String name, String dtype, String codecs, String chunks) throws Exception {
        handMade(name, dtype, codecs, chunks, null);
    }

    /** {@link #handMade(String, String, String, String)}, written only in {@code written} if it is given. */
    static void handMade(String name, String dtype, String codecs, String chunks, long[] written) throws Exception {
        String fill = dtype.equals("string") || dtype.equals("bytes") ? "\"\"" : "0";
        String dataType = dtype.equals("bytes") ? DataType.BYTES.name() : dtype;
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[13,7],\"data_type\":\"" + dataType + "\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":" + fill + ",\"codecs\":" + codecs
                + ",\"attributes\":{}}";
        store.set(name + "/zarr.json", json.getBytes(StandardCharsets.UTF_8));
        write(root.array(name), dtype, ByteOrder.LITTLE_ENDIAN, written);
    }

    /** A sharding_indexed codec of {@code chunkShape} sub-chunks with the given codecs, as zarr-python writes it. */
    static String shard(String chunkShape, String codecs) {
        return "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":" + chunkShape + ",\"codecs\":"
                + codecs + ",\"index_codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                + "{\"name\":\"crc32c\"}],\"index_location\":\"end\"}}";
    }

    /** Writes element i = value(dtype, i) everywhere, or only in rows [w0, w1) x columns [w2, w3). */
    static void write(ZarrArray a, String dtype, ByteOrder order, long[] written) {
        long[] w = written != null ? written : new long[] {0, SHAPE[0], 0, SHAPE[1]};
        Selection s = a.select(new long[] {w[0], w[2]}, new long[] {w[1] - w[0], w[3] - w[2]});
        int n = (int) s.elementCount();
        int[] index = new int[n];
        for (int k = 0, r = (int) w[0]; r < w[1]; r++) {
            for (int c = (int) w[2]; c < w[3]; c++) {
                index[k++] = r * (int) SHAPE[1] + c;
            }
        }
        switch (dtype) {
            case "string" -> s.writeStrings(Arrays.stream(index).mapToObj(i -> POOL[i % POOL.length] + i)
                    .toArray(String[]::new));
            case "bytes" -> s.writeByteArrays(Arrays.stream(index).mapToObj(WriteZarrCases::bytesValue)
                    .toArray(byte[][]::new));
            case "bool" -> s.writeInts(map(index, i -> i % 3 == 0 ? 1 : 0));
            case "int8" -> s.writeInts(map(index, i -> (i * 37 + 11) % 256 - 128));
            case "uint8" -> s.writeInts(map(index, i -> (i * 37 + 11) % 256));
            case "int16" -> s.writeInts(map(index, i -> (i * 4099 + 7) % 65536 - 32768));
            case "uint16" -> s.writeInts(map(index, i -> (i * 4099 + 7) % 65536));
            case "int32" -> s.writeInts(map(index, i -> (int) (i * 2654435761L)));
            case "uint32" -> s.writeLongs(mapLong(index, i -> (i * 2654435761L) & 0xffffffffL));
            case "int64" -> s.writeLongs(mapLong(index, i -> i * 0x9E3779B97F4A7C15L));
            case "uint64" -> s.writeDoubles(mapDouble(index, i -> i % 2 == 0 ? i : 0x1p63 + i * 2048.0));
            case "float16" -> s.writeDoubles(mapDouble(index, i -> i % 17 == 5 ? Double.NaN
                    : i % 19 == 7 ? Double.NEGATIVE_INFINITY : (i - 45) * 0.5));
            case "float32" -> s.writeDoubles(mapDouble(index, i -> i % 23 == 3 ? Double.NaN : i * 0.1));
            case "float64" -> s.writeDoubles(mapDouble(index, i -> i * 0.1 - 3));
            case "uint64x" -> s.writeUnsignedLongs(mapLong(index, i -> i * 0x9E3779B97F4A7C15L + 0xFFFFL));
            case "complex64", "complex128" -> {
                double[] parts = new double[2 * n]; // F8: real, imaginary, ...
                for (int k = 0; k < n; k++) {
                    parts[2 * k] = index[k];
                    parts[2 * k + 1] = -index[k] * 0.5;
                }
                s.writeComplex(parts);
            }
            default -> throw new IllegalArgumentException(dtype);
        }
        MANIFEST.add(entry(a.name(), dtype, written));
    }

    static String entry(String name, String dtype, long[] written) {
        return "  {\"name\":\"" + name + "\",\"dtype\":\"" + dtype + "\",\"written\":"
                + (written == null ? "null" : Arrays.toString(written).replace(" ", "")) + "}";
    }

    /** Byte strings of 0 to 8 bytes, zeros and high bytes included (as in BytesArrayTest). */
    static byte[] bytesValue(int i) {
        byte[] b = new byte[i % 9];
        for (int j = 0; j < b.length; j++) {
            b[j] = (byte) (i * 31 + j * 7);
        }
        return b;
    }

    static int[] map(int[] index, IntUnaryOperator f) {
        return Arrays.stream(index).map(f).toArray();
    }

    static long[] mapLong(int[] index, IntToLongFunction f) {
        return Arrays.stream(index).mapToLong(f).toArray();
    }

    static double[] mapDouble(int[] index, IntToDoubleFunction f) {
        return Arrays.stream(index).mapToDouble(f).toArray();
    }
}
