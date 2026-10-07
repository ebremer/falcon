package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Attribute;
import com.ebremer.falcon.hdf5.CommittedDatatype;
import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.Filter;
import com.ebremer.falcon.hdf5.Group;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.hdf5.Link;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What {@code falcon conformance --hdf5} prints: a JSON manifest of everything an HDF5 file holds, which
 * Falcon's HDF5 conformance harness ({@code tools/conformance/run_hdf5_conformance.py}) compares with what
 * h5py reads from the same file.
 *
 * <p>The manifest's {@code objects} map every path reachable from the root group to what its link leads to:
 * <ul>
 *   <li>a group, a dataset, or a committed datatype, with its attributes; a dataset with its shape, type
 *       class and size, layout, chunks, filter ids, and values, as {@code dump -f json} prints them;</li>
 *   <li>{@code hard}, for an object an earlier path already reached ({@code same_as} that path);</li>
 *   <li>{@code soft}, {@code external}, or {@code user}, for those links, which it does not follow.</li>
 * </ul>
 * Links are visited in the byte order of their UTF-8 names, depth first, as h5py lists them. Whatever cannot
 * be read is recorded as an {@code error} (or a {@code key_error}): the exception's simple class name and
 * message, so the harness tells a typed failure from a bug. A dataset of more than {@value #MAX_ELEMENTS}
 * elements is read in part: the first {@value #CAP_LAST} indexes of its last dimension and the first
 * {@value #CAP} of the others ({@code truncated}).
 */
final class Hdf5Manifest {

    /** Datasets with more elements than this are read in part. */
    static final long MAX_ELEMENTS = 65_536;
    /** How much of each dimension but the last a dataset read in part reads. */
    static final long CAP = 16;
    /** How much of the last dimension a dataset read in part reads. */
    static final long CAP_LAST = 4096;

    private final Map<String, JsonValue> objects = new LinkedHashMap<>();
    private final Map<Long, String> seen = new HashMap<>();

    private Hdf5Manifest() {
    }

    /**
     * {@return the manifest of an open file, as one line of JSON in ASCII: every other character escaped, so
     * the line reads the same whatever the console's encoding}
     *
     * @param file the file
     */
    static String of(Hdf5File file) {
        Hdf5Manifest manifest = new Hdf5Manifest();
        Group root = file.root();
        manifest.seen.put(root.objectHeaderAddress(), "/");
        manifest.visit(root, "/");
        return ascii(Json.write(JsonObject.builder().put("objects", new JsonObject(manifest.objects)).build()));
    }

    /**
     * {@return the manifest of a file that could not be opened: its error alone}
     *
     * @param failure why it could not be opened
     */
    static String failure(Throwable failure) {
        return ascii(Json.write(JsonObject.builder().put("error", message(failure)).build()));
    }

    private void visit(Hdf5Object object, String path) {
        objects.put(path, JsonNull.INSTANCE); // keeps the object ahead of its members
        JsonObject.Builder entry = JsonObject.builder();
        switch (object) {
            case Group _ -> entry.put("kind", "group");
            case Dataset dataset -> {
                entry.put("kind", "dataset");
                dataset(dataset, entry);
            }
            case CommittedDatatype type -> {
                entry.put("kind", "datatype");
                attempt(entry, "type", () -> new JsonString(typeClass(type.datatype())));
            }
            default -> entry.put("kind", "unknown");
        }
        attributes(object, entry);
        if (object instanceof Group group) {
            members(group, path, entry);
        }
        objects.put(path, entry.build());
    }

    private void members(Group group, String path, JsonObject.Builder entry) {
        List<Link> links;
        try {
            links = new ArrayList<>(group.links());
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            entry.put("links_error", message(e));
            return;
        }
        links.sort((a, b) -> Arrays.compareUnsigned(utf8(a.name()), utf8(b.name())));
        for (Link link : links) {
            String member = (path.equals("/") ? "" : path) + "/" + link.name();
            switch (link) {
                case Link.Hard hard -> {
                    String first = seen.putIfAbsent(hard.objectHeaderAddress(), member);
                    if (first != null) {
                        objects.put(member, JsonObject.builder().put("kind", "hard").put("same_as", first).build());
                        continue;
                    }
                    try {
                        Hdf5Object child = group.child(link.name()).orElseThrow(
                                () -> new IllegalStateException("the hard link reaches nothing"));
                        visit(child, member);
                    } catch (Exception | StackOverflowError | OutOfMemoryError e) {
                        objects.put(member, JsonObject.builder().put("kind", "hard").put("error", message(e)).build());
                    }
                }
                case Link.Soft soft -> objects.put(member, JsonObject.builder().put("kind", "soft")
                        .put("target", soft.targetPath()).build());
                case Link.External external -> objects.put(member, JsonObject.builder().put("kind", "external")
                        .put("file", external.fileName()).put("target", external.objectPath()).build());
                case Link.UserDefined user -> objects.put(member, JsonObject.builder().put("kind", "user")
                        .put("type", user.type()).build());
            }
        }
    }

    private static void dataset(Dataset dataset, JsonObject.Builder entry) {
        Dataspace space;
        Datatype type;
        try {
            space = dataset.dataspace();
            type = dataset.datatype();
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            entry.put("error", message(e));
            return;
        }
        long[] dims = space.kind() == Dataspace.Kind.NULL ? null : space.dimensions();
        entry.put("shape", dims == null ? JsonNull.INSTANCE : longs(dims));
        entry.put("type", typeClass(type)).put("size", type.size());
        attempt(entry, "layout", () -> new JsonString(dataset.layout().name().toLowerCase()));
        attempt(entry, "chunks", () -> dataset.chunkShape().<JsonValue>map(Hdf5Manifest::longs).orElse(JsonNull.INSTANCE));
        attempt(entry, "filters", () -> {
            List<JsonValue> ids = new ArrayList<>();
            for (Filter filter : dataset.filters()) {
                ids.add(JsonNumber.of(filter.id()));
            }
            return new JsonArray(ids);
        });
        if (dims == null) {
            return;
        }
        long count = space.elementCount();
        if (count > MAX_ELEMENTS) {
            long[] part = new long[dims.length];
            for (int d = 0; d < dims.length; d++) {
                part[d] = Math.min(dims[d], d == dims.length - 1 ? CAP_LAST : CAP);
            }
            entry.put("truncated", true);
            attempt(entry, "values", () -> Values.nested(Hdf5Values.of(dataset.select(new long[dims.length], part)), part));
        } else {
            attempt(entry, "values", () -> Values.nested(Hdf5Values.of(dataset), dims));
        }
    }

    private static void attributes(Hdf5Object object, JsonObject.Builder entry) {
        List<Attribute> attributes;
        try {
            attributes = new ArrayList<>(object.attributes());
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            entry.put("attributes_error", message(e));
            return;
        }
        attributes.sort((a, b) -> Arrays.compareUnsigned(utf8(a.name()), utf8(b.name())));
        JsonObject.Builder all = JsonObject.builder();
        for (Attribute attribute : attributes) {
            JsonObject.Builder one = JsonObject.builder();
            try {
                Dataspace space = attribute.dataspace();
                Datatype type = attribute.datatype();
                one.put("shape", space.kind() == Dataspace.Kind.NULL ? JsonNull.INSTANCE : longs(space.dimensions()));
                one.put("type", typeClass(type)).put("size", type.size());
                attempt(one, "value", () -> Hdf5Values.json(attribute));
            } catch (Exception | StackOverflowError | OutOfMemoryError e) {
                one.put("error", message(e));
            }
            all.put(attribute.name(), one.build());
        }
        entry.put("attributes", all.build());
    }

    /** Puts {@code key}'s value, or, if it cannot be read, why not as {@code key_error}. */
    private static void attempt(JsonObject.Builder entry, String key, Supplier<JsonValue> value) {
        try {
            entry.put(key, value.get());
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            entry.put(key + "_error", message(e));
        }
    }

    /** {@return a datatype's class as libhdf5 names it, lower case: a variable-length string is a string} */
    static String typeClass(Datatype type) {
        return switch (type) {
            case Datatype.FixedPoint _ -> "integer";
            case Datatype.FloatingPoint _ -> "float";
            case Datatype.Time _ -> "time";
            case Datatype.StringType _ -> "string";
            case Datatype.BitField _ -> "bitfield";
            case Datatype.Opaque _ -> "opaque";
            case Datatype.Compound _ -> "compound";
            case Datatype.Reference _ -> "reference";
            case Datatype.Enumeration _ -> "enum";
            case Datatype.VariableLength t when t.kind() == Datatype.VlenKind.STRING -> "string";
            case Datatype.VariableLength _ -> "vlen";
            case Datatype.Array _ -> "array";
            case Datatype.Complex _ -> "complex";
        };
    }

    private static JsonArray longs(long[] values) {
        List<JsonValue> items = new ArrayList<>(values.length);
        for (long v : values) {
            items.add(JsonNumber.of(v));
        }
        return new JsonArray(items);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String message(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** JSON text with every character outside printable ASCII escaped, which only strings can hold. */
    private static String ascii(String json) {
        StringBuilder s = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x7f) {
                s.append(c);
            } else {
                s.append(String.format("\\u%04x", (int) c));
            }
        }
        return s.toString();
    }
}
