import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.ZipStore;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes Zarr hierarchies straight into ZIP archives with Falcon's writable ZipStore (P2 F13), for
 * check_zip_store.py to read back with zarr-python's ZipStore and Python's zipfile. Dev-time tool, run with
 * the JDK's source launcher from the repo root, after {@code mvn -pl zarr -am compile}:
 *
 * <pre>
 *     java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZipCases.java OUT_DIR
 *     python tools/fixtures/check_zip_store.py OUT_DIR
 * </pre>
 *
 * (':' separates the classpath outside Windows). The archives:
 * <ul>
 *   <li>hierarchy.zip: arrays (zstd, sharded, strings), a group, a group deleted, attributes changed, a chunk
 *       written again, then consolidated: keys written over and deleted leave dead space;</li>
 *   <li>appended.zip: hierarchy.zip opened again (mode "a"): an array added, a group deleted, a chunk written
 *       again, consolidated again;</li>
 *   <li>zip64_entries.zip: an array of 70,000 one-byte chunks, so the Zip64 end records hold the count;</li>
 *   <li>zip64_offsets.zip: every offset and the directory's size and offset in Zip64 form, as an archive over
 *       4 GiB has them (the store's threshold set to 0 through reflection, so no gigabytes are written).</li>
 * </ul>
 * manifest.json records, for each, the nodes below the root, the attributes and values Falcon wrote, whether
 * the root is consolidated, and whether Zip64 records are expected.
 */
public class WriteZipCases {

    static final List<JsonValue> MANIFEST = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);

        Path hierarchy = out.resolve("hierarchy.zip");
        try (ZipStore zip = ZipStore.create(hierarchy)) {
            ZarrGroup root = Zarr.createGroup(zip, json("{\"title\":\"falcon zip\",\"version\":1}"));
            root.createArray("plain", ArraySpec.builder(new long[] {10}, DataType.INT32).chunkShape(4).zstd().build())
                    .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
            root.array("plain").select(new long[] {0}, new long[] {2}).writeInts(new int[] {-1, -2}); // c/0 again
            ZarrArray sharded = root.createArray("sharded", ArraySpec.builder(new long[] {8, 8}, DataType.FLOAT32)
                    .chunkShape(8, 8).sharding(4, 4).zstd().build());
            double[] values = new double[64];
            for (int i = 0; i < 64; i++) {
                values[i] = i * 0.5;
            }
            sharded.writeDoubles(values);
            root.createArray("text", ArraySpec.builder(new long[] {3}, DataType.STRING).chunkShape(2).build())
                    .writeStrings(new String[] {"zip", "größe", ""});
            root.createGroup("g", json("{\"level\":1}"))
                    .createArray("x", ArraySpec.builder(new long[] {2}, DataType.UINT8).build()).writeInts(new int[] {7, 8});
            root.createGroup("gone").createArray("y", ArraySpec.builder(new long[] {2}, DataType.INT8).build())
                    .writeInts(new int[] {1, 2});
            root.delete("gone");
            root.updateAttributes(json("{\"version\":2}")); // zarr.json written again
            root.consolidate();
            record("hierarchy", zip, false);
        }

        Path appended = out.resolve("appended.zip");
        Files.copy(hierarchy, appended, StandardCopyOption.REPLACE_EXISTING);
        try (ZipStore zip = ZipStore.open(appended)) {
            ZarrGroup root = Zarr.openGroup(zip);
            root.createArray("late", ArraySpec.builder(new long[] {3}, DataType.FLOAT64).build())
                    .writeDoubles(new double[] {0.5, 1.5, 2.5});
            root.delete("g");
            root.array("plain").select(new long[] {8}, new long[] {2}).writeInts(new int[] {80, 90}); // c/2 again
            root.consolidate();
            record("appended", zip, false);
        }

        Path entries = out.resolve("zip64_entries.zip");
        try (ZipStore zip = ZipStore.create(entries)) {
            int n = 70_000;
            int[] many = new int[n];
            for (int i = 0; i < n; i++) {
                many[i] = i % 250 + 1; // never the fill value, so every chunk is stored
            }
            Zarr.createGroup(zip).createArray("many", ArraySpec.builder(new long[] {n}, DataType.UINT8).chunkShape(1).build())
                    .writeInts(many);
            record("zip64_entries", zip, true);
        }

        Path offsets = out.resolve("zip64_offsets.zip");
        try (ZipStore zip = ZipStore.create(offsets)) {
            Field threshold = ZipStore.class.getDeclaredField("zip64Threshold");
            threshold.setAccessible(true);
            threshold.setLong(zip, 0);
            ZarrGroup root = Zarr.createGroup(zip, json("{\"zip64\":true}"));
            root.createArray("plain", ArraySpec.builder(new long[] {6}, DataType.INT16).chunkShape(4).build())
                    .writeInts(new int[] {-3, -2, -1, 0, 1, 2});
            ZarrArray sharded = root.createArray("sharded", ArraySpec.builder(new long[] {4, 4}, DataType.FLOAT64)
                    .chunkShape(4, 4).sharding(2, 2).build());
            double[] values = new double[16];
            for (int i = 0; i < 16; i++) {
                values[i] = i - 7.25;
            }
            sharded.writeDoubles(values);
            record("zip64_offsets", zip, true);
        }

        Files.writeString(out.resolve("manifest.json"), Json.writePretty(new JsonArray(MANIFEST)), StandardCharsets.UTF_8);
        System.out.println("wrote " + MANIFEST.size() + " archives to " + out);
    }

    static JsonObject json(String text) {
        return Json.parse(text).asObject();
    }

    /** What Falcon reads back from the archive before closing it: zarr-python must read the same after. */
    static void record(String name, ZipStore zip, boolean zip64) {
        ZarrGroup root = Zarr.openGroup(zip, false);
        List<JsonValue> nodes = new ArrayList<>();
        JsonObject.Builder attributes = JsonObject.builder().put("", root.attributes());
        JsonObject.Builder arrays = JsonObject.builder();
        walk(root, "", nodes, attributes, arrays);
        MANIFEST.add(JsonObject.builder().put("name", name).put("zip64", JsonBool.of(zip64))
                .put("consolidated", JsonBool.of(Zarr.openGroup(zip).isConsolidated()))
                .put("nodes", new JsonArray(nodes)).put("attributes", attributes.build()).put("arrays", arrays.build())
                .build());
    }

    static void walk(ZarrGroup group, String prefix, List<JsonValue> nodes, JsonObject.Builder attributes,
                     JsonObject.Builder arrays) {
        for (ZarrNode node : group.children()) {
            String path = prefix + node.name();
            nodes.add(new JsonString(path));
            attributes.put(path, node.attributes());
            if (node instanceof ZarrArray a) {
                List<JsonValue> values = new ArrayList<>();
                switch (a.dataType().name()) {
                    case "string" -> {
                        for (String s : a.readStrings()) {
                            values.add(new JsonString(s));
                        }
                    }
                    case "float32", "float64" -> {
                        for (double d : a.readDoubles()) {
                            values.add(JsonNumber.of(d));
                        }
                    }
                    default -> {
                        for (long v : a.readLongs()) {
                            values.add(JsonNumber.of(v));
                        }
                    }
                }
                arrays.put(path, JsonObject.builder().put("dtype", a.dataType().name())
                        .put("values", new JsonArray(values)).build());
            }
            if (node instanceof ZarrGroup child) {
                walk(child, path + "/", nodes, attributes, arrays);
            }
        }
    }
}
