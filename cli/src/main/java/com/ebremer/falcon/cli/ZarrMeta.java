package com.ebremer.falcon.cli;

import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Zarr node's metadata document as stored ({@code zarr.json}, or a v2 {@code .zarray}), for what the
 * model does not expose: the byte order of the elements (the {@code bytes} codec's {@code endian}, or the v2
 * dtype's {@code <} or {@code >}) and each codec's configuration.
 *
 * @param format   the Zarr format, 2 or 3
 * @param document the metadata document, or an empty object if it could not be read
 */
record ZarrMeta(int format, JsonObject document) {

    /**
     * Reads a node's metadata document from its store.
     *
     * @param store the store
     * @param node  the node
     * @return its metadata; an empty document if none is stored at the node's own key (it was found in
     *         consolidated metadata, say)
     */
    static ZarrMeta of(Store store, ZarrNode node) {
        String prefix = node.path().isEmpty() ? "" : node.path() + "/";
        String key = prefix + (node.zarrFormat() == 3 ? "zarr.json" : node.isGroup() ? ".zgroup" : ".zarray");
        JsonObject document = new JsonObject(java.util.Map.of());
        try {
            Optional<byte[]> bytes = store.get(key);
            if (bytes.isPresent() && Json.parse(bytes.get()) instanceof JsonObject object) {
                document = object;
            }
        } catch (RuntimeException e) {
            // left empty: the model's own view still describes the node
        }
        return new ZarrMeta(node.zarrFormat(), document);
    }

    /** {@return the order of the elements' bytes, little-endian when nothing says otherwise} */
    ByteOrder byteOrder() {
        if (format == 2) {
            return v2Order(document.find("dtype").orElse(null));
        }
        String endian = findEndian(document.find("codecs").orElse(null));
        return "big".equals(endian) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    private static ByteOrder v2Order(JsonValue dtype) {
        if (dtype instanceof JsonString s && !s.value().isEmpty()) {
            return s.value().charAt(0) == '>' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        }
        if (dtype instanceof JsonArray fields) { // a structured dtype: its first field with an order
            for (JsonValue field : fields.values()) {
                if (field instanceof JsonArray f && f.size() >= 2) {
                    JsonValue t = f.get(1);
                    if (t instanceof JsonString s && !s.value().isEmpty() && s.value().charAt(0) != '|') {
                        return v2Order(t);
                    }
                    if (t instanceof JsonArray) {
                        return v2Order(t);
                    }
                }
            }
        }
        return ByteOrder.LITTLE_ENDIAN;
    }

    private static String findEndian(JsonValue codecs) {
        if (!(codecs instanceof JsonArray list)) {
            return null;
        }
        for (JsonValue codec : list.values()) {
            if (codec instanceof JsonObject c) {
                String name = name(c);
                JsonObject configuration = configuration(c);
                if (name.equals("bytes")) {
                    return configuration.find("endian").filter(JsonString.class::isInstance)
                            .map(v -> ((JsonString) v).value()).orElse(null);
                }
                if (name.equals("sharding_indexed")) {
                    return findEndian(configuration.find("codecs").orElse(null));
                }
            }
        }
        return null;
    }

    /**
     * {@return the codecs, each a {@code {name, configuration}} object: a v3 array's as listed, a v2 array's its
     * filters then its compressor (each named by its numcodecs {@code id}); with a sharded array's own codecs
     * after the {@code sharding_indexed} codec}
     */
    List<Codec> codecs() {
        List<Codec> out = new ArrayList<>();
        if (format == 2) {
            document.find("filters").filter(JsonArray.class::isInstance).ifPresent(filters -> {
                for (JsonValue f : ((JsonArray) filters).values()) {
                    if (f instanceof JsonObject o) {
                        out.add(new Codec(o.find("id").map(ZarrMeta::text).orElse("?"), o, 0));
                    }
                }
            });
            document.find("compressor").filter(JsonObject.class::isInstance)
                    .ifPresent(c -> out.add(new Codec(((JsonObject) c).find("id").map(ZarrMeta::text).orElse("?"),
                            (JsonObject) c, 0)));
            return out;
        }
        collect(document.find("codecs").orElse(null), 0, out);
        return out;
    }

    private static void collect(JsonValue codecs, int depth, List<Codec> out) {
        if (!(codecs instanceof JsonArray list)) {
            return;
        }
        for (JsonValue codec : list.values()) {
            if (codec instanceof JsonString s) {
                out.add(new Codec(s.value(), new JsonObject(java.util.Map.of()), depth));
            } else if (codec instanceof JsonObject c) {
                out.add(new Codec(name(c), configuration(c), depth));
                if (name(c).equals("sharding_indexed")) {
                    collect(configuration(c).find("codecs").orElse(null), depth + 1, out);
                }
            }
        }
    }

    private static String name(JsonObject codec) {
        return codec.find("name").map(ZarrMeta::text).orElse("?");
    }

    private static JsonObject configuration(JsonObject codec) {
        return codec.find("configuration").filter(JsonObject.class::isInstance).map(JsonObject.class::cast)
                .orElse(new JsonObject(java.util.Map.of()));
    }

    private static String text(JsonValue value) {
        return value instanceof JsonString s ? s.value() : value.toJson();
    }

    /**
     * One codec.
     *
     * @param name          its name: a v3 codec name, or a numcodecs id in Zarr v2
     * @param configuration its configuration (in v2 the whole numcodecs object, {@code id} included)
     * @param depth         0 for the array's own codecs, 1 for those inside a shard, and so on
     */
    record Codec(String name, JsonObject configuration, int depth) {

        /** {@return the codec and its configuration, such as {@code zstd(level=0, checksum=false)}} */
        String describe() {
            StringBuilder s = new StringBuilder(name);
            List<String> parts = new ArrayList<>();
            configuration.members().forEach((key, value) -> {
                if (!key.equals("id") && !(name.equals("sharding_indexed") && key.equals("codecs"))) {
                    parts.add(key + "=" + text(value));
                }
            });
            if (!parts.isEmpty()) {
                s.append('(').append(String.join(", ", parts)).append(')');
            }
            return s.toString();
        }

        /**
         * {@return an integer setting, or {@code otherwise}}
         *
         * @param key       the setting's name
         * @param otherwise the value when it is absent or not an integer
         */
        int integer(String key, int otherwise) {
            return configuration.find(key).filter(com.ebremer.falcon.zarr.json.JsonNumber.class::isInstance)
                    .map(v -> {
                        try {
                            return ((com.ebremer.falcon.zarr.json.JsonNumber) v).intValue();
                        } catch (RuntimeException e) {
                            return otherwise;
                        }
                    }).orElse(otherwise);
        }

        /**
         * {@return a number setting, or {@code otherwise}}
         *
         * @param key       the setting's name
         * @param otherwise the value when it is absent or not a number
         */
        double number(String key, double otherwise) {
            return configuration.find(key).filter(com.ebremer.falcon.zarr.json.JsonNumber.class::isInstance)
                    .map(v -> ((com.ebremer.falcon.zarr.json.JsonNumber) v).doubleValue()).orElse(otherwise);
        }

        /**
         * {@return a setting as text: a string's value, else its JSON; or {@code otherwise}}
         *
         * @param key       the setting's name
         * @param otherwise the value when it is absent
         */
        String string(String key, String otherwise) {
            return configuration.find(key).map(ZarrMeta::text).orElse(otherwise);
        }
    }
}
