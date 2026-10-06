package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads Zarr <b>v2</b> metadata and translates it into the v3 model, so the whole v3 read path (chunk
 * grid, codec pipeline, selections) serves v2 stores unchanged.
 *
 * <p>A v2 array is described by a {@code .zarray} document with a NumPy dtype string ({@code "<i4"},
 * {@code ">f8"}, {@code "|u1"}), a {@code compressor} object, a {@code dimension_separator}, and its
 * user attributes in a <em>separate</em> {@code .zattrs} file. The translation maps:
 *
 * <ul>
 *   <li>the dtype string to a v3 {@link com.ebremer.falcon.zarr.datatype.DataType} name plus the
 *       {@code bytes} codec's endian;</li>
 *   <li>the {@code compressor} to the matching v3 bytes&rarr;bytes codec ({@code gzip}/{@code zstd}/
 *       {@code blosc});</li>
 *   <li>the chunk grid to a {@code regular} grid, and the {@code dimension_separator} to the {@code v2}
 *       chunk key encoding (no {@code "c"} prefix), which Falcon already implements.</li>
 * </ul>
 *
 * <p>Fortran ({@code "F"}) order for rank&gt;1, non-empty v2 {@code filters}, and v2 compressors other
 * than the three above are reported as {@link ZarrUnsupportedException}. Writing v2 is out of scope.
 */
public final class V2Metadata {

    /** The v2 metadata key names. */
    public static final String ZARRAY = ".zarray";
    public static final String ZGROUP = ".zgroup";
    public static final String ZATTRS = ".zattrs";

    private V2Metadata() {
    }

    /** Translates a v2 group ({@code .zgroup} + optional {@code .zattrs}) into v3 group metadata. */
    public static GroupMetadata parseGroup(byte[] zgroup, byte[] zattrs, String key) {
        return Metadata.wrapJson(key, () -> {
            checkFormat(object(zgroup, key), key);
            JsonObject attributes = zattrs == null ? Fields.EMPTY_OBJECT : object(zattrs, key + " (.zattrs)");
            return GroupMetadata.parse(JsonObject.builder()
                    .put("zarr_format", 3).put("node_type", "group").put("attributes", attributes)
                    .build(), key);
        });
    }

    /** Translates a v2 array ({@code .zarray} + optional {@code .zattrs}) into v3 array metadata. */
    public static ArrayMetadata parseArray(byte[] zarray, byte[] zattrs, String key) {
        return Metadata.wrapJson(key, () -> translateArray(zarray, zattrs, key));
    }

    private static ArrayMetadata translateArray(byte[] zarray, byte[] zattrs, String key) {
        JsonObject meta = object(zarray, key);
        checkFormat(meta, key);

        JsonArray shape = Fields.array(Fields.require(meta, "shape", key), key + ".shape");
        JsonArray chunks = Fields.array(Fields.require(meta, "chunks", key), key + ".chunks");
        int rank = shape.size();
        if (chunks.size() != rank) {
            throw new ZarrFormatException(key + ": chunks rank does not match shape rank");
        }

        String order = meta.find("order").map(v -> Fields.string(v, key + ".order")).orElse("C");
        if (order.equals("F") && rank > 1) {
            throw new ZarrUnsupportedException(key + ": Fortran (\"F\") order is not supported");
        } else if (!order.equals("C") && !order.equals("F")) {
            throw new ZarrFormatException(key + ".order must be \"C\" or \"F\", was \"" + order + "\"");
        }

        meta.find("filters").ifPresent(v -> {
            if (v instanceof JsonArray filters && filters.size() > 0) {
                throw new ZarrUnsupportedException(key + ": v2 filters are not supported");
            }
        });

        DType dtype = parseDtype(Fields.string(Fields.require(meta, "dtype", key), key + ".dtype"), key);
        // null, as zarr-python 2 writes when none was chosen, means the default "."
        String separator = meta.find("dimension_separator").filter(v -> !v.isNull())
                .map(v -> Fields.string(v, key + ".dimension_separator")).orElse(".");

        JsonValue fillValue = translateFill(meta.find("fill_value").orElse(JsonNull.INSTANCE), dtype);
        JsonObject attributes = zattrs == null ? Fields.EMPTY_OBJECT : object(zattrs, key + " (.zattrs)");

        JsonObject v3 = JsonObject.builder()
                .put("zarr_format", 3)
                .put("node_type", "array")
                .put("shape", shape)
                .put("data_type", dtype.name)
                .put("chunk_grid", JsonObject.builder().put("name", "regular")
                        .put("configuration", JsonObject.builder().put("chunk_shape", chunks).build()).build())
                .put("chunk_key_encoding", JsonObject.builder().put("name", "v2")
                        .put("configuration", JsonObject.builder().put("separator", separator).build()).build())
                .put("fill_value", fillValue)
                .put("codecs", codecs(dtype, meta.find("compressor").orElse(JsonNull.INSTANCE), key))
                .put("attributes", attributes)
                .build();
        return ArrayMetadata.parse(v3, key);
    }

    private record DType(String name, String endian) {
    }

    private static DType parseDtype(String dtype, String key) {
        if (dtype.length() < 2) {
            throw new ZarrFormatException(key + ": unrecognized v2 dtype \"" + dtype + "\"");
        }
        char byteOrder = dtype.charAt(0);
        String endian = switch (byteOrder) {
            case '<' -> "little";
            case '>' -> "big";
            case '|', '=' -> null; // not applicable (single byte) or native
            default -> throw new ZarrFormatException(key + ": unknown byte order in dtype \"" + dtype + "\"");
        };
        String rest = dtype.substring(1);
        String name = switch (rest) {
            case "b1" -> "bool";
            case "i1" -> "int8";
            case "i2" -> "int16";
            case "i4" -> "int32";
            case "i8" -> "int64";
            case "u1" -> "uint8";
            case "u2" -> "uint16";
            case "u4" -> "uint32";
            case "u8" -> "uint64";
            case "f2" -> "float16";
            case "f4" -> "float32";
            case "f8" -> "float64";
            case "c8" -> "complex64";
            case "c16" -> "complex128";
            default -> throw new ZarrUnsupportedException(key + ": unsupported v2 dtype \"" + dtype + "\"");
        };
        return new DType(name, endian);
    }

    private static JsonArray codecs(DType dtype, JsonValue compressor, String key) {
        List<JsonValue> codecs = new ArrayList<>();
        codecs.add(bytesCodec(dtype));
        if (!(compressor instanceof JsonNull)) {
            JsonObject c = Fields.object(compressor, key + ".compressor");
            String id = Fields.string(Fields.require(c, "id", key + ".compressor"), key + ".compressor.id");
            codecs.add(switch (id) {
                case "gzip" -> named("gzip", JsonObject.builder()
                        .put("level", c.find("level")
                                .map(v -> Fields.integer(v, key + ".compressor.level")).orElse(5L))
                        .build());
                case "zstd" -> named("zstd", null);
                case "blosc" -> named("blosc", null); // blosc self-describes in its own header
                default -> throw new ZarrUnsupportedException(
                        key + ": v2 compressor \"" + id + "\" is not supported (use gzip, zstd, or blosc)");
            });
        }
        return new JsonArray(codecs);
    }

    private static JsonValue bytesCodec(DType dtype) {
        if (dtype.endian == null) {
            return named("bytes", null); // single-byte element: byte order is meaningless
        }
        return named("bytes", JsonObject.builder().put("endian", dtype.endian).build());
    }

    private static JsonValue named(String name, JsonObject configuration) {
        JsonObject.Builder b = JsonObject.builder().put("name", name);
        if (configuration != null) {
            b.put("configuration", configuration);
        }
        return b.build();
    }

    /**
     * Maps a v2 fill value onto what the v3 data type expects. A v2 {@code null} means no fill value was
     * set, and unwritten chunks read as zeros, as zarr-python reads them: {@code false} for bool,
     * {@code [0.0, 0.0]} for a complex type, else {@code 0}. A bool accepts 0/1.
     */
    private static JsonValue translateFill(JsonValue fill, DType dtype) {
        boolean isBool = dtype.name.equals("bool");
        if (fill instanceof JsonNull) {
            if (dtype.name.startsWith("complex")) {
                return JsonArray.of(JsonNumber.of(0.0), JsonNumber.of(0.0));
            }
            return isBool ? JsonBool.FALSE : JsonNumber.of(0);
        }
        if (isBool && fill instanceof JsonNumber n) {
            return JsonBool.of(n.doubleValue() != 0);
        }
        return fill;
    }

    private static void checkFormat(JsonObject meta, String key) {
        long format = Fields.integer(Fields.require(meta, "zarr_format", key), key + ".zarr_format");
        if (format != 2) {
            throw new ZarrFormatException(key + ": expected zarr_format 2, was " + format);
        }
    }

    private static JsonObject object(byte[] json, String key) {
        try {
            return Fields.object(Json.parse(json), key);
        } catch (JsonException e) {
            throw new ZarrFormatException("malformed JSON in '" + key + "': " + e.getMessage(), e);
        }
    }
}
