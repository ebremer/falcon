import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes Zarr hierarchies with Falcon's consolidated metadata, attribute changes, and deletes (P2 F2, F6),
 * for check_zarr_hierarchies.py to read back with zarr-python. Dev-time tool, run with the JDK's source
 * launcher from the repo root, after {@code mvn -pl zarr -am compile}:
 *
 * <pre>
 *     java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrHierarchies.java OUT_DIR
 *     python tools/fixtures/check_zarr_hierarchies.py OUT_DIR
 * </pre>
 *
 * (':' separates the classpath outside Windows). Each case is a store directory under OUT_DIR;
 * manifest.json records what Falcon sees in each, opened by default (using consolidated metadata where there
 * is some) and node by node, in the form gen_zarr_consolidated_fixtures.py records zarr-python's view.
 */
public class WriteZarrHierarchies {

    static final List<JsonValue> MANIFEST = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);

        // the tree, consolidated at the root
        Store consolidated = tree(out, "consolidated");
        Zarr.openGroup(consolidated).consolidate();
        record("consolidated", consolidated);

        // consolidated at group "g" only
        Store subgroup = tree(out, "subgroup");
        Zarr.openGroup(subgroup).group("g").consolidate();
        record("subgroup", subgroup);

        // attributes replaced and merged, not consolidated
        Store attributes = tree(out, "attributes");
        ZarrGroup root = Zarr.openGroup(attributes);
        root.updateAttributes(json("{\"title\":\"changed\",\"added\":[1,2]}"));
        root.group("g").setAttributes(json("{\"level\":2,\"tags\":[\"a\",\"ä\"]}"));
        root.array("a1").updateAttributes(json("{\"units\":\"m\"}"));
        record("attributes", attributes);

        // consolidated, then a group deleted: gone from the stored consolidated metadata too
        Store deleted = tree(out, "deleted");
        Zarr.openGroup(deleted).consolidate().delete("g");
        record("deleted", deleted);

        // consolidated, then changed: the snapshot still shows the tree as it was
        Store stale = tree(out, "stale");
        ZarrGroup staleRoot = Zarr.openGroup(stale).consolidate();
        staleRoot.createArray("late", spec(DataType.INT8, 3)).writeInts(new int[] {1, 2, 3});
        staleRoot.group("g").setAttributes(json("{\"level\":2}"));
        record("stale", stale);

        // a v2 hierarchy with .zmetadata: attributes changed (.zattrs), then a group deleted (.zmetadata)
        Store v2 = v2Tree(out, "v2");
        ZarrGroup v2Root = Zarr.openGroup(v2);
        v2Root.array("a").setAttributes(json("{\"k\":1}"));
        Zarr.openGroup(v2, false).group("g").updateAttributes(json("{\"level\":1}"));
        Zarr.openGroup(v2).delete("g");
        record("v2", v2);

        Files.writeString(out.resolve("manifest.json"), Json.writePretty(new JsonArray(MANIFEST)),
                StandardCharsets.UTF_8);
        System.out.println("wrote " + MANIFEST.size() + " hierarchies to " + out);
    }

    static JsonObject json(String text) {
        return Json.parse(text).asObject();
    }

    static ArraySpec spec(DataType type, long n) {
        return ArraySpec.builder(new long[] {n}, type).chunkShape(2).build();
    }

    /** Root (attributes); arrays a1, B2, größe; group g (attributes) with x and sub/leaf; empty group. */
    static Store tree(Path out, String name) {
        FileSystemStore store = FileSystemStore.open(out.resolve(name));
        ZarrGroup root = Zarr.createGroup(store,
                json("{\"title\":\"falcon\",\"nested\":{\"list\":[1,2.5,\"x\"],\"flag\":true}}"), true);
        root.createArray("a1", spec(DataType.INT32, 4)).writeInts(new int[] {1, 2, 3, 4});
        root.createArray("B2", spec(DataType.FLOAT64, 3)).writeDoubles(new double[] {0.5, 1.5, 2.5});
        root.createArray("größe", spec(DataType.INT16, 2)).writeInts(new int[] {-1, 1});
        ZarrGroup g = root.createGroup("g", json("{\"level\":1}"));
        g.createArray("x", spec(DataType.UINT8, 2)).writeInts(new int[] {7, 8});
        g.createGroup("sub").createArray("leaf", spec(DataType.FLOAT32, 1)).writeDoubles(new double[] {0.25});
        root.createGroup("empty");
        return store;
    }

    /** A v2 hierarchy written key by key, as zarr-python 2 wrote one, with its .zmetadata. */
    static Store v2Tree(Path out, String name) throws Exception {
        Path dir = out.resolve(name);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        FileSystemStore store = FileSystemStore.open(dir);
        String zarray = "{\"zarr_format\":2,\"shape\":[4],\"chunks\":[2],\"dtype\":\"<i4\",\"compressor\":null,"
                + "\"fill_value\":0,\"order\":\"C\",\"filters\":null}";
        String[][] docs = {
            {".zgroup", "{\"zarr_format\":2}"}, {".zattrs", "{\"title\":\"v2\"}"},
            {"a/.zarray", zarray}, {"g/.zgroup", "{\"zarr_format\":2}"}, {"g/x/.zarray", zarray},
            {"keep/.zgroup", "{\"zarr_format\":2}"}};
        StringBuilder metadata = new StringBuilder();
        for (String[] doc : docs) {
            store.set(doc[0], doc[1].getBytes(StandardCharsets.UTF_8));
            metadata.append(metadata.isEmpty() ? "" : ",").append('"').append(doc[0]).append("\":").append(doc[1]);
        }
        store.set(".zmetadata", ("{\"zarr_consolidated_format\":1,\"metadata\":{" + metadata + "}}")
                .getBytes(StandardCharsets.UTF_8));
        ZarrGroup root = Zarr.openGroup(store, false);
        root.array("a").writeInts(new int[] {1, 2, 3, 4});
        root.group("g").array("x").writeInts(new int[] {5, 6, 7, 8});
        return store;
    }

    static void record(String name, Store store) {
        MANIFEST.add(JsonObject.builder().put("name", name)
                .put("consolidated", new JsonArray(walk(Zarr.openGroup(store), "")))
                .put("per_node", new JsonArray(walk(Zarr.openGroup(store, false), "")))
                .build());
    }

    /** Every node below {@code group}, in the form gen_zarr_consolidated_fixtures.py records. */
    static List<JsonValue> walk(ZarrGroup group, String prefix) {
        List<JsonValue> out = new ArrayList<>();
        for (ZarrNode node : group.children()) {
            String path = prefix + node.name();
            JsonObject.Builder b = JsonObject.builder().put("path", path)
                    .put("kind", node.isGroup() ? "group" : "array").put("attributes", node.attributes());
            if (node instanceof ZarrArray a) {
                List<JsonValue> shape = new ArrayList<>();
                for (long d : a.shape()) {
                    shape.add(JsonNumber.of(d));
                }
                b.put("shape", new JsonArray(shape)).put("dtype", a.dataType().name());
            }
            out.add(b.build());
            if (node instanceof ZarrGroup child) {
                out.addAll(walk(child, path + "/"));
            }
        }
        return out;
    }
}
