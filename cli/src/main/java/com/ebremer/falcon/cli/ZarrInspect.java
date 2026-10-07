package com.ebremer.falcon.cli;

import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.Store;
import java.io.PrintStream;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code ls} and {@code info} of a Zarr store. */
final class ZarrInspect {

    private final PrintStream out;

    ZarrInspect(Context context) {
        this.out = context.out;
    }

    /**
     * {@return the node at {@code path}, answered from consolidated metadata where the store has some}
     *
     * @param store the store
     * @param path  the node's path, {@code /} or empty for the root
     */
    static ZarrNode resolve(Store store, String path) {
        String p = path.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return Zarr.open(store, p);
    }

    /** {@return a node's path as {@code ls} shows it: {@code /} for the root, else {@code /a/b}} */
    static String display(ZarrNode node) {
        return "/" + node.path();
    }

    // ---- ls -----------------------------------------------------------------------------------------------

    void ls(Store store, String path, boolean recursive) {
        ZarrNode node = resolve(store, path);
        List<String[]> rows = new ArrayList<>();
        if (node instanceof ZarrGroup group) {
            if (recursive) {
                rows.add(row(display(group), group));
                walk(group, rows);
            } else {
                for (String name : group.childNames()) {
                    rows.add(childRow(group, name, name));
                }
            }
        } else {
            rows.add(row(display(node), node));
        }
        Table.print(out, rows);
    }

    private void walk(ZarrGroup group, List<String[]> rows) {
        for (String name : group.childNames()) {
            String path = group.path().isEmpty() ? "/" + name : "/" + group.path() + "/" + name;
            rows.add(childRow(group, name, path));
            try {
                if (group.child(name).orElse(null) instanceof ZarrGroup child) {
                    walk(child, rows);
                }
            } catch (RuntimeException e) {
                // the row already says what is wrong with it
            }
        }
    }

    private static String[] childRow(ZarrGroup group, String name, String shown) {
        try {
            ZarrNode child = group.child(name).orElse(null);
            return child == null ? new String[] {shown, "(nothing)"} : row(shown, child);
        } catch (RuntimeException e) {
            return new String[] {shown, "error: " + Errors.describe(e)};
        }
    }

    private static String[] row(String name, ZarrNode node) {
        if (node instanceof ZarrArray array) {
            return new String[] {name, "array", Describe.shape(array.shape()), Describe.type(array.dataType(), null),
                    chunks(array), String.join(", ", array.codecNames())};
        }
        return OmeInspect.kind(node).map(kind -> new String[] {name, "group", "v" + node.zarrFormat(), kind})
                .orElse(new String[] {name, "group", "v" + node.zarrFormat()});
    }

    private static String chunks(ZarrArray array) {
        if (array.isRectilinear()) {
            return "rectilinear chunks";
        }
        if (array.rank() == 0) {
            return "";
        }
        long[] inner = array.innerChunkShape();
        if (!java.util.Arrays.equals(inner, array.chunkShape())) {
            return "shards " + Describe.shape(array.chunkShape()) + " of " + Describe.shape(inner);
        }
        return "chunks " + Describe.shape(array.chunkShape());
    }

    // ---- info ---------------------------------------------------------------------------------------------

    void info(Store store, String path) {
        ZarrNode node = resolve(store, path);
        out.println(display(node));
        List<String[]> rows = new ArrayList<>();
        ZarrMeta meta = ZarrMeta.of(store, node);
        if (node instanceof ZarrArray array) {
            ByteOrder order = meta.byteOrder();
            rows.add(Hdf5Inspect.field("object", "array (Zarr v" + array.zarrFormat() + ")"));
            rows.add(Hdf5Inspect.field("shape", Describe.shape(array.shape())));
            rows.add(Hdf5Inspect.field("type", Describe.type(array.dataType(), order)));
            if (array.isRectilinear()) {
                long[][] sizes = array.chunkSizes();
                for (int d = 0; d < sizes.length; d++) {
                    rows.add(Hdf5Inspect.field(d == 0 ? "chunks" : "", "dimension " + d + ": "
                            + Table.clip(java.util.Arrays.toString(sizes[d]), 80)));
                }
            } else {
                rows.add(Hdf5Inspect.field("chunks", chunks(array).replaceFirst("^chunks ", "") + ", "
                        + array.chunkCount() + (array.chunkCount() == 1 ? " chunk" : " in all")));
            }
            List<ZarrMeta.Codec> codecs = meta.codecs();
            if (codecs.isEmpty()) {
                rows.add(Hdf5Inspect.field("codecs", String.join(", ", array.codecNames())));
            }
            for (int i = 0; i < codecs.size(); i++) {
                ZarrMeta.Codec codec = codecs.get(i);
                // a shard's own codecs are indented under sharding_indexed
                rows.add(Hdf5Inspect.field(i == 0 ? "codecs" : "",
                        "  ".repeat(codec.depth()) + Table.clip(codec.describe(), 120)));
            }
            rows.add(Hdf5Inspect.field("fill value", array.fillValue().toJson()));
            array.dimensionNames().ifPresent(names -> rows.add(Hdf5Inspect.field("dimensions",
                    String.join(", ", names.stream().map(n -> n == null ? "-" : n).toList()))));
            rows.add(Hdf5Inspect.field("chunk keys", array.chunkKeyEncoding() + " (separator '" + array.separator() + "')"));
        } else {
            ZarrGroup group = node.asGroup();
            rows.add(Hdf5Inspect.field("object", "group (Zarr v" + group.zarrFormat() + ")"));
            String members;
            try {
                members = Integer.toString(group.childNames().size());
            } catch (UnsupportedOperationException e) { // a store that cannot list, such as HTTP without listing
                members = "unknown (the store cannot list its keys)";
            }
            rows.add(Hdf5Inspect.field("members", members));
            rows.add(Hdf5Inspect.field("consolidated", group.isConsolidated() ? "yes" : "no"));
            rows.addAll(OmeInspect.rows(group));
        }
        Map<String, JsonValue> attributes = node.attributes().members();
        rows.add(Hdf5Inspect.field("attributes", Integer.toString(attributes.size())));
        Table.print(out, rows);
        List<String[]> attributeRows = new ArrayList<>();
        attributes.forEach((name, value) ->
                attributeRows.add(new String[] {"  " + name, "=", Table.clip(value.toJson(), 100)}));
        Table.print(out, attributeRows);
    }
}
