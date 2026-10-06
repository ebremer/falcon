import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes into copies of the Zarr v2 arrays zarr-python wrote (gen_zarr_v2_ext_fixtures.py: the v2 dtypes,
 * Fortran order, the numcodecs filters and compressors, blosc and zstd configurations) with Falcon, for
 * check_zarr_v2_writes.py to read back with zarr-python (P2 F4). Each copy holds the fixture's metadata only
 * (.zarray, .zattrs); Falcon writes the fixture's values in reverse C order through the translated pipeline,
 * so every chunk is new. One Fortran-ordered copy is then shrunk and grown again, and another has a box
 * written over it.
 *
 * <p>Falcon also creates each array again from scratch (created_NAME), from a spec with zarrFormat(2) built
 * from what it reads of the fixture, and writes the same reversed values: check_zarr_v2_writes.py compares
 * that .zarray and .zattrs with zarr-python's. And it creates a v2 hierarchy (hierarchy_v2: groups with
 * attributes, a string array in Fortran order, numbers with blosc, a delta filter with zlib) and
 * consolidates it, for zarr-python to open through its .zmetadata. Dev-time tool, run with the
 * JDK's source launcher from the repo root, after {@code mvn -pl zarr -am compile}:
 *
 * <pre>
 *     java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrV2Cases.java OUT_DIR
 *     python tools/fixtures/check_zarr_v2_writes.py OUT_DIR
 * </pre>
 *
 * (':' separates the classpath outside Windows). manifest.json lists each copy with every element it
 * should read as, in the sidecars' form (numbers, text, int64 time counts, hex byte strings). Arrays whose
 * codecs Falcon does not have, or only reads (zfpy), are skipped and listed.
 */
public class WriteZarrV2Cases {

    static final Path FIXTURES = Path.of("zarr", "src", "test", "resources", "fixtures");
    static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        List<String> manifest = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Path> fixtures;
        try (Stream<Path> s = Files.list(FIXTURES)) {
            fixtures = s.filter(p -> p.getFileName().toString().matches("v2x?_.*") && Files.isDirectory(p))
                    .sorted().toList();
        }
        for (Path src : fixtures) {
            String name = src.getFileName().toString();
            JsonObject expected = Json.parse(Files.readAllBytes(FIXTURES.resolve(name + ".expected.json"))).asObject();
            List<JsonValue> values = new ArrayList<>(expected.get("values").asArray().values());
            Collections.reverse(values);
            try {
                ZarrArray a = Zarr.openArray(copy(src, out.resolve(name)));
                write(a, values);
                manifest.add(entry(name, values));
                if (name.equals("v2x_order_f_2d")) {
                    // shrink to 3 x 4 and grow back: what the shrink cut off reads as the fill value, 0
                    ZarrArray b = Zarr.openArray(copy(src, out.resolve(name + "_resized")));
                    write(b, values);
                    b.resize(3, 4).resize(5, 7);
                    List<JsonValue> resized = new ArrayList<>(values);
                    for (int i = 0; i < resized.size(); i++) {
                        if (i / 7 >= 3 || i % 7 >= 4) {
                            resized.set(i, JsonNumber.of(0));
                        }
                    }
                    manifest.add(entry(name + "_resized", resized));
                }
                if (name.equals("v2x_order_f_3d")) {
                    // a box across chunk boundaries, written over the whole: each chunk it touches is merged
                    ZarrArray c = Zarr.openArray(copy(src, out.resolve(name + "_box")));
                    write(c, values);
                    double[] box = new double[2 * 2 * 3];
                    Arrays.fill(box, -7.5);
                    c.select(new long[] {1, 1, 1}, new long[] {2, 2, 3}).writeDoubles(box);
                    List<JsonValue> merged = new ArrayList<>(values);
                    for (int i = 1; i < 3; i++) {
                        for (int j = 1; j < 3; j++) {
                            for (int k = 1; k < 4; k++) {
                                merged.set((i * 4 + j) * 5 + k, JsonNumber.of(-7.5));
                            }
                        }
                    }
                    manifest.add(entry(name + "_box", merged));
                }
            } catch (RuntimeException e) {
                // a codec Falcon does not have, or one it only reads (zfpy)
                if (!(e instanceof ZarrUnsupportedException) && !String.valueOf(e.getMessage()).contains("unknown codec")) {
                    throw e;
                }
                skipped.add(name + " (" + e.getMessage() + ")");
            }
            try {
                ArraySpec spec = specOf(src);
                Path dir = out.resolve("created_" + name);
                Files.createDirectories(dir);
                write(Zarr.createArray(FileSystemStore.open(dir), spec, true), values);
                manifest.add(JsonObject.builder().put("name", "created_" + name).put("like", name)
                        .put("values", new JsonArray(values)).build().toJson());
            } catch (IllegalArgumentException e) {
                if (!String.valueOf(e.getMessage()).contains("zfpy")) { // Falcon reads zfpy but cannot write it
                    throw e;
                }
                skipped.add("created_" + name + " (" + e.getMessage() + ")");
            }
        }
        manifest.add(hierarchy(out.resolve("hierarchy_v2")));
        Files.writeString(out.resolve("manifest.json"), "[\n" + String.join(",\n", manifest) + "\n]\n");
        System.out.println("wrote " + manifest.size() + " v2 nodes to " + out);
        skipped.forEach(s -> System.out.println("  skipped " + s));
    }

    /** The spec a fixture's .zarray describes, built with the public API from what Falcon reads of it. */
    static ArraySpec specOf(Path src) throws Exception {
        JsonObject zarray = Json.parse(Files.readAllBytes(src.resolve(".zarray"))).asObject();
        ZarrArray read = Zarr.openArray(FileSystemStore.open(src));
        ArraySpec.Builder b = ArraySpec.builder(read.shape(), read.dataType()).zarrFormat(2)
                .chunkShape(read.chunkShape())
                .order(zarray.get("order").asString().charAt(0))
                .separator(read.separator())
                .attributes(read.attributes())
                .endian(zarray.get("dtype").toJson().contains(">") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                .fillValue(zarray.get("fill_value") instanceof JsonNull ? JsonNull.INSTANCE : read.fillValue());
        List<JsonObject> filters = new ArrayList<>();
        if (zarray.get("filters") instanceof JsonArray list) {
            list.values().forEach(f -> filters.add(f.asObject()));
            if (read.dataType().isVariableLength()) {
                filters.remove(0); // the object codec, which the spec adds by itself
            }
        }
        if (!filters.isEmpty()) {
            b.filters(filters.toArray(JsonObject[]::new));
        }
        if (zarray.get("compressor") instanceof JsonObject compressor) {
            b.compressor(compressor);
        }
        return b.build();
    }

    /**
     * A v2 hierarchy created from scratch and consolidated, and its manifest entry: the attributes of each
     * group, and every element of each array.
     */
    static String hierarchy(Path dir) throws Exception {
        Files.createDirectories(dir);
        FileSystemStore store = FileSystemStore.open(dir);
        JsonObject rootAttrs = JsonObject.builder().put("title", "falcon").put("n", JsonArray.of(JsonNumber.of(1),
                JsonNumber.of(2))).build();
        JsonObject gAttrs = JsonObject.builder().put("level", 1).build();
        ZarrGroup root = Zarr.createGroup(store, rootAttrs, true, 2);
        ZarrGroup g = root.createGroup("g", gAttrs);
        g.createGroup("empty");

        String[] text = {"a", "bb", "", "ccc", "\u00e9t\u00e9", "z"};
        g.createArray("strings", ArraySpec.builder(new long[] {2, 3}, DataType.STRING).zarrFormat(2)
                .chunkShape(1, 2).order('F').zstd(3).build()).writeStrings(text);

        ZarrArray numbers = root.createArray("numbers", ArraySpec.builder(new long[] {4, 5}, DataType.FLOAT32)
                .chunkShape(3, 2).blosc().fillValue(-1).build());
        float[] box = {0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f};
        numbers.select(new long[] {1, 1}, new long[] {2, 3}).writeFloats(box);
        List<JsonValue> numberValues = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int r = i / 5;
            int c = i % 5;
            boolean in = r >= 1 && r < 3 && c >= 1 && c < 4;
            numberValues.add(JsonNumber.of(in ? box[(r - 1) * 3 + c - 1] : -1.0));
        }

        long[] longs = new long[10];
        List<JsonValue> longValues = new ArrayList<>();
        for (int i = 0; i < longs.length; i++) {
            longs[i] = 1_000_000_007L * i * i - 5;
            longValues.add(JsonNumber.of(longs[i]));
        }
        g.createArray("delta", ArraySpec.builder(new long[] {10}, DataType.INT64).zarrFormat(2).chunkShape(4)
                .filters(JsonObject.builder().put("id", "delta").put("dtype", "<i8").build())
                .compressor(JsonObject.builder().put("id", "zlib").put("level", 1).build()).build()).writeLongs(longs);
        root.consolidate();

        List<JsonValue> strings = new ArrayList<>();
        for (String s : text) {
            strings.add(new JsonString(s));
        }
        return JsonObject.builder().put("name", dir.getFileName().toString()).put("kind", "group")
                .put("attributes", JsonObject.builder().put("", rootAttrs).put("g", gAttrs)
                        .put("g/empty", JsonObject.builder().build()).build())
                .put("arrays", JsonObject.builder().put("numbers", new JsonArray(numberValues))
                        .put("g/strings", new JsonArray(strings)).put("g/delta", new JsonArray(longValues)).build())
                .build().toJson();
    }

    static FileSystemStore copy(Path src, Path dst) throws Exception {
        Files.createDirectories(dst);
        try (Stream<Path> s = Files.list(src)) {
            for (Path f : s.toList()) {
                if (f.getFileName().toString().startsWith(".")) {
                    Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return FileSystemStore.open(dst);
    }

    static void write(ZarrArray a, List<JsonValue> values) {
        switch (a.dataType().kind()) {
            case INT, UINT, DATETIME, TIMEDELTA -> a.writeLongs(values.stream().mapToLong(v -> v.asNumber().longValue()).toArray());
            case BOOL -> a.writeLongs(values.stream().mapToLong(v -> v.asBoolean() ? 1 : 0).toArray());
            case FLOAT -> a.writeDoubles(values.stream()
                    .mapToDouble(v -> v instanceof JsonNumber n ? n.doubleValue() : Double.NaN).toArray());
            case STRING, FIXED_STRING -> a.writeStrings(values.stream().map(JsonValue::asString).toArray(String[]::new));
            default -> a.writeByteArrays(values.stream().map(v -> HEX.parseHex(v.asString())).toArray(byte[][]::new));
        }
    }

    static String entry(String name, List<JsonValue> values) {
        return JsonObject.builder().put("name", name).put("values", new JsonArray(values)).build().toJson();
    }
}
