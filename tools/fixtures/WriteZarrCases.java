import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Selection;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataType.Field;
import com.ebremer.falcon.zarr.json.Json;
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
 * back with zarr-python (P1 T1), plus P2's variable-length bytes (F5), arrays written with
 * {@code withWriteEmptyChunks} (F7), resized arrays (F6), nested shards (F11), uint64 and complex values
 * written with {@code writeUnsignedLongs}/{@code writeComplex} (F8), the extension data types zarr-python
 * writes (F14: numpy.datetime64, numpy.timedelta64, fixed_length_utf32, null_terminated_bytes, raw_bytes,
 * and struct), and rectilinear chunk grids (F14; arrays named {@code *_rectilinear*}, which zarr-python
 * reads with {@code array.rectilinear_chunks}). Dev-time tool, run with the JDK's source launcher from the
 * repo root, after {@code mvn -pl zarr -am compile}:
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

    /** F14: the extension data types, and the text check_zarr_writer.py's value() mirrors. */
    static final String[] EXTENSION = {"datetime64", "timedelta64", "utf32", "nullbytes", "rawbytes", "struct"};
    static final String[] UTF = {"a", "", "\u03b4", "\u4e2d\u6587", "\ud83d\ude00", "x\0y"};
    static final DataType RECORD = DataType.struct(new Field("a", DataType.INT32), new Field("b", DataType.FLOAT64),
            new Field("c", DataType.nullTerminatedBytes(2)), new Field("d", DataType.fixedLengthUtf32(2)),
            new Field("t", DataType.datetime64("s", 1)), new Field("f", DataType.BOOL),
            new Field("p", DataType.struct(new Field("x", DataType.INT16), new Field("y", DataType.UINT8))));
    /** Each extension type's fill value in the partial arrays: none of them the default but NaT's "NaT". */
    static final Map<String, String> EXTENSION_FILL = Map.of(
            "datetime64", "\"NaT\"", "timedelta64", "5", "utf32", "\"fill\"", "nullbytes", "\"YWI=\"",
            "rawbytes", "\"AQID\"", "struct", "{\"a\":7,\"b\":1.5,\"c\":\"cQ==\",\"d\":\"z\","
                    + "\"t\":-9223372036854775808,\"f\":true,\"p\":{\"x\":-1,\"y\":255}}");

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
        // P2 F14: the extension data types zarr-python writes, in the layouts that matter to them (byte order,
        // sharding, compression with their type size), partly written with a fill that is not the default,
        // write_empty_chunks, resized, and transposed (hand-made metadata).
        for (String dtype : EXTENSION) {
            DataType type = extension(dtype);
            for (String layout : new String[] {"plain", "big_endian", "sharded_zstd", "blosc"}) {
                if (layout.equals("big_endian") && !type.hasByteOrder()) {
                    continue;
                }
                ArraySpec.Builder b = ArraySpec.builder(SHAPE, type).chunkShape(6, 4);
                switch (layout) {
                    case "big_endian" -> b.endian(ByteOrder.BIG_ENDIAN);
                    case "sharded_zstd" -> b.sharding(3, 2).zstd();
                    case "blosc" -> b.blosc().crc32c();
                    default -> {
                    }
                }
                write(root.createArray(dtype + "_" + layout, b.build()), dtype, ByteOrder.LITTLE_ENDIAN, null);
            }
            write(root.createArray(dtype + "_partial", ArraySpec.builder(SHAPE, type).chunkShape(4, 3).sharding(2, 3)
                    .fillValue(Json.parse(EXTENSION_FILL.get(dtype))).build()), dtype, ByteOrder.LITTLE_ENDIAN, PARTIAL);
            handMadeExtension(dtype + "_transpose", dtype,
                    "[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},"
                            + "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}},"
                            + "{\"name\":\"gzip\",\"configuration\":{\"level\":1}}]");
        }
        for (String dtype : new String[] {"datetime64", "struct"}) { // F7 for the extension types
            ZarrArray a = root.createArray(dtype + "_write_empty_sharded", ArraySpec.builder(SHAPE, extension(dtype))
                    .chunkShape(6, 4).sharding(3, 2).build()).withWriteEmptyChunks(true);
            if (dtype.equals("struct")) {
                byte[] fill = RECORD.decodeFillValue(RECORD.defaultFillValue(), ByteOrder.LITTLE_ENDIAN);
                byte[][] all = new byte[91][];
                Arrays.fill(all, fill);
                a.writeByteArrays(all);
            } else {
                long[] nat = new long[91];
                Arrays.fill(nat, Long.MIN_VALUE);
                a.writeLongs(nat);
            }
            write(a, dtype, ByteOrder.LITTLE_ENDIAN, PARTIAL);
        }
        for (String dtype : new String[] {"utf32", "struct"}) { // F6 for the extension types
            ZarrArray a = root.createArray(dtype + "_resized_sharded", ArraySpec.builder(SHAPE, extension(dtype))
                    .chunkShape(6, 4).sharding(3, 2).zstd().build());
            write(a, dtype, ByteOrder.LITTLE_ENDIAN, null);
            MANIFEST.remove(MANIFEST.size() - 1);
            a.resize(10, 5).resize(SHAPE);
            MANIFEST.add(entry(a.name(), dtype, new long[] {0, 10, 0, 5}));
        }
        handMade("int16_blosc_lz4", "int16",
                "[" + BYTES_LE + ",{\"name\":\"blosc\",\"configuration\":{\"cname\":\"lz4\",\"clevel\":5,"
                        + "\"shuffle\":\"shuffle\",\"typesize\":2,\"blocksize\":0}}]", "[13,7]");

        // P2 F3: Blosc writes the compressor its cname names (every one zarr-python's numcodecs has), and
        // numcodecs' Zlib and LZ4 under zarr-python 3's names; check_zarr_writer.py re-encodes each chunk with
        // numcodecs and compares the bytes.
        String[][] cnames = {{"blosclz", "9"}, {"lz4", "3"}, {"lz4hc", "9"}, {"zlib", "4"}};
        for (String[] c : cnames) {
            for (String shuffle : new String[] {"noshuffle", "shuffle", "bitshuffle"}) {
                handMade("float64_blosc_" + c[0] + "_" + shuffle, "float64",
                        "[" + BYTES_LE + ",{\"name\":\"blosc\",\"configuration\":{\"cname\":\"" + c[0] + "\",\"clevel\":"
                                + c[1] + ",\"shuffle\":\"" + shuffle + "\",\"typesize\":8,\"blocksize\":0}}]", "[13,7]");
            }
            handMade("int32_blosc_" + c[0], "int32",
                    "[" + BYTES_LE + ",{\"name\":\"blosc\",\"configuration\":{\"cname\":\"" + c[0] + "\",\"clevel\":5,"
                            + "\"shuffle\":\"shuffle\",\"typesize\":4,\"blocksize\":0}}]");
        }
        // and ArraySpec's own Blosc settings (F3)
        write(root.createArray("int32_blosc_lz4hc_spec", ArraySpec.builder(SHAPE, DataType.INT32).chunkShape(6, 4)
                .blosc("lz4hc", 7, "bitshuffle").build()), "int32", ByteOrder.LITTLE_ENDIAN, null);
        write(root.createArray("uint16_blosc_blosclz_spec", ArraySpec.builder(SHAPE, DataType.UINT16)
                .blosc("blosclz", 9, "shuffle").build()), "uint16", ByteOrder.LITTLE_ENDIAN, null);
        handMade("int32_numcodecs_zlib", "int32", "[" + BYTES_LE + ",{\"name\":\"numcodecs.zlib\",\"configuration\":"
                + "{\"level\":3}}]");
        handMade("float64_numcodecs_zlib_default", "float64", "[" + BYTES_LE + ",{\"name\":\"numcodecs.zlib\","
                + "\"configuration\":{}}]", "[13,7]");
        handMade("int16_numcodecs_lz4", "int16", "[" + BYTES_LE + ",{\"name\":\"numcodecs.lz4\",\"configuration\":{}}]");
        handMade("uint8_numcodecs_lz4_accel", "uint8", "[" + BYTES_LE + ",{\"name\":\"numcodecs.lz4\","
                + "\"configuration\":{\"acceleration\":8}}]", "[13,7]");
        handMade("int32_numcodecs_lz4_sharded", "int32", "[" + shard("[3,2]", "[" + bytes
                + ",{\"name\":\"numcodecs.lz4\",\"configuration\":{}}]") + ",{\"name\":\"numcodecs.zlib\","
                + "\"configuration\":{}}]"); // zarr-python requires the configuration, even an empty one

        // P2 F14: rectilinear chunk grids. Rows in chunks of 2, 5, 6 and columns of 3, 1, 3, in the core types and
        // strings and bytes; then lengths in runs, lengths past the array, shards of two shapes, write_empty,
        // the v2 keys, a transpose, and resizes (grown past the listed lengths, so a chunk is added; and shrunk
        // and grown back, the cut-off part reading as fill).
        for (String dtype : new String[] {"int32", "float64", "uint8", "string", "bytes"}) {
            write(root.createArray(dtype + "_rectilinear", rectilinear(dtype).build()), dtype,
                    ByteOrder.LITTLE_ENDIAN, null);
        }
        write(root.createArray("int16_rectilinear_runs", ArraySpec.builder(SHAPE, DataType.INT16)
                .chunkLengths(0, 1, 1, 1, 1, 1, 4, 4).chunkShape(1, 3).zstd().build()), "int16",
                ByteOrder.LITTLE_ENDIAN, null);
        write(root.createArray("int32_rectilinear_past", ArraySpec.builder(SHAPE, DataType.INT32)
                .chunkLengths(0, 6, 6, 6).chunkLengths(1, 4, 4).fillValue(-9).build()), "int32",
                ByteOrder.LITTLE_ENDIAN, null);
        for (String dtype : new String[] {"int32", "string"}) {
            write(root.createArray(dtype + "_rectilinear_sharded", ArraySpec.builder(SHAPE, DataType.of(dtype))
                    .chunkLengths(0, 6, 3, 6).chunkLengths(1, 4, 4).sharding(3, 2).zstd().build()), dtype,
                    ByteOrder.LITTLE_ENDIAN, null);
        }
        write(root.createArray("int32_rectilinear_sharded_partial", ArraySpec.builder(SHAPE, DataType.INT32)
                .chunkLengths(0, 6, 3, 6).chunkLengths(1, 4, 4).sharding(3, 2).crc32c().fillValue(5).build()),
                "int32", ByteOrder.LITTLE_ENDIAN, PARTIAL);
        ZarrArray empty = root.createArray("int32_rectilinear_write_empty", rectilinear("int32").build())
                .withWriteEmptyChunks(true);
        empty.writeInts(new int[91]);
        write(empty, "int32", ByteOrder.LITTLE_ENDIAN, PARTIAL);
        write(root.createArray("int8_rectilinear_v2_keys", rectilinear("int8").chunkKeyEncoding("v2").build()),
                "int8", ByteOrder.LITTLE_ENDIAN, null);
        handMadeGrid("int32_rectilinear_transpose", "int32", "{\"name\":\"rectilinear\",\"configuration\":"
                + "{\"kind\":\"inline\",\"chunk_shapes\":[[2,5,6],[[3,2],1]]}}",
                "[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}," + BYTES_LE + "]");
        ZarrArray grownRect = root.createArray("int32_rectilinear_grown", ArraySpec.builder(new long[] {10, 5},
                DataType.INT32).chunkLengths(0, 4, 6).chunkLengths(1, 2, 3).fillValue(-3).build());
        write(grownRect, "int32", ByteOrder.LITTLE_ENDIAN, new long[] {0, 10, 0, 5});
        grownRect.resize(SHAPE); // rows gain a chunk of 3, columns one of 2
        ZarrArray regrown = root.createArray("float64_rectilinear_regrown", rectilinear("float64").zstd().build());
        write(regrown, "float64", ByteOrder.LITTLE_ENDIAN, null);
        MANIFEST.remove(MANIFEST.size() - 1);
        regrown.resize(10, 5).resize(SHAPE);
        MANIFEST.add(entry(regrown.name(), "float64", new long[] {0, 10, 0, 5}));

        Files.writeString(out.resolve("manifest.json"), "[\n" + String.join(",\n", MANIFEST) + "\n]\n");
        System.out.println(MANIFEST.size() + " arrays written to " + out);
    }

    /** A 13 x 7 array of {@code dtype} on the rectilinear grid of rows 2, 5, 6 and columns 3, 1, 3. */
    static ArraySpec.Builder rectilinear(String dtype) {
        return ArraySpec.builder(SHAPE, DataType.of(dtype)).chunkLengths(0, 2, 5, 6).chunkLengths(1, 3, 1, 3);
    }

    /** An array of 13 x 7 on a hand-written chunk grid, fill 0, with hand-written codecs. */
    static void handMadeGrid(String name, String dtype, String grid, String codecs) throws Exception {
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[13,7],\"data_type\":\"" + dtype + "\","
                + "\"chunk_grid\":" + grid + ",\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
                + "\"codecs\":" + codecs + ",\"attributes\":{}}";
        store.set(name + "/zarr.json", json.getBytes(StandardCharsets.UTF_8));
        write(root.array(name), dtype, ByteOrder.LITTLE_ENDIAN, null);
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

    /** An extension type's array of 13 x 7, chunks 6 x 4, its default fill, with hand-written codecs (F14). */
    static void handMadeExtension(String name, String dtype, String codecs) throws Exception {
        DataType type = extension(dtype);
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[13,7],\"data_type\":"
                + type.toJson().toJson() + ",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":"
                + "{\"chunk_shape\":[6,4]}},\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":"
                + type.defaultFillValue().toJson() + ",\"codecs\":" + codecs + ",\"attributes\":{}}";
        store.set(name + "/zarr.json", json.getBytes(StandardCharsets.UTF_8));
        write(root.array(name), dtype, ByteOrder.LITTLE_ENDIAN, null);
    }

    static DataType extension(String dtype) {
        return switch (dtype) {
            case "datetime64" -> DataType.datetime64("ms", 1);
            case "timedelta64" -> DataType.timedelta64("s", 10);
            case "utf32" -> DataType.fixedLengthUtf32(6);
            case "nullbytes" -> DataType.nullTerminatedBytes(5);
            case "rawbytes" -> DataType.rawBytes(3);
            case "struct" -> RECORD;
            default -> throw new IllegalArgumentException(dtype);
        };
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
            case "datetime64" -> s.writeLongs(mapLong(index, i -> i % 11 == 4 ? Long.MIN_VALUE
                    : i * 86_400_000L - 1_000_000_000_000L));
            case "timedelta64" -> s.writeLongs(mapLong(index, i -> i % 13 == 6 ? Long.MIN_VALUE : (i - 40) * 7L));
            case "utf32" -> s.writeStrings(Arrays.stream(index).mapToObj(i -> UTF[i % UTF.length] + i)
                    .toArray(String[]::new));
            case "nullbytes" -> s.writeByteArrays(Arrays.stream(index).mapToObj(WriteZarrCases::nullBytes)
                    .toArray(byte[][]::new));
            case "rawbytes" -> s.writeByteArrays(Arrays.stream(index).mapToObj(i -> new byte[] {(byte) (i * 31),
                (byte) (i * 31 + 7), (byte) (i * 31 + 14)}).toArray(byte[][]::new));
            case "struct" -> s.writeByteArrays(Arrays.stream(index).mapToObj(WriteZarrCases::record)
                    .toArray(byte[][]::new));
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

    /** null_terminated_bytes of 0 to 5 bytes, never ending in NUL (numpy drops trailing NULs), one inside. */
    static byte[] nullBytes(int i) {
        byte[] b = new byte[i % 6];
        for (int j = 0; j < b.length; j++) {
            b[j] = (byte) (j == 1 && b.length > 2 ? 0 : (i * 31 + j * 7) % 255 + 1);
        }
        return b;
    }

    /** RECORD's element i, packed little-endian (readByteArrays' order). */
    static byte[] record(int i) {
        ByteBuffer bb = ByteBuffer.allocate(RECORD.byteCount()).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) (i * 2654435761L)).putDouble(i * 0.25 - 3);
        bb.put(i % 3 > 0 ? (byte) (65 + i % 26) : 0).put(i % 3 > 1 ? (byte) (65 + i % 26) : 0);
        bb.putInt(i % 5 == 0 ? 0x1F600 : 0x3B4).putInt('0' + i % 10);
        bb.putLong(i % 9 == 2 ? Long.MIN_VALUE : i * 1000L).put((byte) (i % 2 == 0 ? 1 : 0));
        bb.putShort((short) (i - 500)).put((byte) i);
        return bb.array();
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
