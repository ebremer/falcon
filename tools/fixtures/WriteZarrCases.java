import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Selection;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import java.nio.ByteBuffer;
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
 * back with zarr-python (P1 T1). Dev-time tool, run with the JDK's source launcher from the repo root,
 * after {@code mvn -pl zarr -am compile}:
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
        String fill = dtype.equals("string") ? "\"\"" : "0";
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[13,7],\"data_type\":\"" + dtype + "\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":" + fill + ",\"codecs\":" + codecs
                + ",\"attributes\":{}}";
        store.set(name + "/zarr.json", json.getBytes(StandardCharsets.UTF_8));
        write(root.array(name), dtype, ByteOrder.LITTLE_ENDIAN, null);
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
            case "complex64", "complex128" -> {
                boolean wide = dtype.equals("complex128");
                ByteBuffer b = ByteBuffer.allocate(n * (wide ? 16 : 8)).order(order);
                for (int i : index) {
                    if (wide) {
                        b.putDouble(i).putDouble(-i * 0.5);
                    } else {
                        b.putFloat(i).putFloat(-i * 0.5f);
                    }
                }
                s.writeRawBytes(b.array());
            }
            default -> throw new IllegalArgumentException(dtype);
        }
        MANIFEST.add("  {\"name\":\"" + a.name() + "\",\"dtype\":\"" + dtype + "\",\"written\":"
                + (written == null ? "null" : Arrays.toString(written).replace(" ", "")) + "}");
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
