package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.codec.ValueCast.Entry;
import com.ebremer.falcon.zarr.codec.ValueCast.Num;
import com.ebremer.falcon.zarr.codec.ValueCast.OutOfRange;
import com.ebremer.falcon.zarr.codec.ValueCast.Rounding;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code cast_value} array&rarr;array codec (zarr-extensions {@code codecs/cast_value}): each element is
 * converted <em>by value</em> to the configured {@code data_type} when encoding and back to the array's data
 * type when decoding, so the codecs after it see the new type (the {@code bytes} codec's element size, a
 * shard's sub-chunks, Blosc's type size). The conversion is {@link ValueCast}'s, cast-value-rs's bit for bit.
 *
 * <p>The configuration is {@code data_type} (required), {@code rounding} ({@code nearest-even} by default,
 * {@code towards-zero}, {@code towards-positive}, {@code towards-negative}, {@code nearest-away}),
 * {@code out_of_range} (absent: an error; {@code clamp}; {@code wrap}, for an integer {@code data_type} only),
 * and {@code scalar_map}: {@code {"encode": [[in, out], ...], "decode": [[in, out], ...]}}, each side in the
 * Zarr v3 fill value encoding of its type (the array's type and {@code data_type} when encoding, the other way
 * round when decoding), applied before any other rule, the first entry for a value winning. Any other key
 * makes the metadata invalid, as the specification requires. Both data types must be integers or floats:
 * {@code int8} to {@code uint64}, {@code float16}, {@code float32}, {@code float64}; the specification's other
 * types ({@code int2}, {@code int4}, {@code uint2}, {@code uint4}, {@code bfloat16}, the float8, float6 and
 * float4 types) are types Falcon does not model and are refused by name.
 *
 * <p>Where zarr-python 3.4 is looser than the specification, Falcon follows the specification: a repeated
 * scalar_map key takes its first value (zarr-python's dict, its last), and a float written as a hex string
 * ({@code "0x7fc00001"}) is read by its bits (zarr-python cannot convert it). Like zarr-python, Falcon ignores
 * scalar_map members other than {@code encode} and {@code decode}, and reads a {@code null}
 * {@code out_of_range} or {@code scalar_map} as absent.
 */
final class CastValueCodec implements ArrayArrayCodec {

    private static final Set<String> KEYS = Set.of("data_type", "rounding", "out_of_range", "scalar_map");

    /** The data types the specification lets cast_value convert that Falcon does not implement. */
    private static final Set<String> UNIMPLEMENTED = Set.of("int2", "int4", "uint2", "uint4", "bfloat16",
            "float4_e2m1fn", "float6_e2m3fn", "float6_e3m2fn", "float8_e3m4", "float8_e4m3", "float8_e4m3b11fnuz",
            "float8_e4m3fnuz", "float8_e5m2", "float8_e5m2fnuz", "float8_e8m0fnu");

    private final DataType source;
    private final DataType target;
    private final ValueCast encoder;
    private final ValueCast decoder;

    private CastValueCodec(DataType source, DataType target, ValueCast encoder, ValueCast decoder) {
        this.source = source;
        this.target = target;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    /**
     * Parses the configuration of a {@code cast_value} codec whose input elements are {@code source} (the
     * array's data type, or what an earlier {@code cast_value} turned it into).
     *
     * @throws ZarrFormatException      if the configuration is invalid
     * @throws ZarrUnsupportedException if a data type is one the specification permits but Falcon does not
     *                                  implement
     */
    static CastValueCodec parse(JsonObject configuration, DataType source) {
        for (String key : configuration.members().keySet()) {
            if (!KEYS.contains(key)) {
                throw new ZarrFormatException("unexpected \"" + key + "\" in the cast_value configuration");
            }
        }
        DataType target = dataType(configuration.find("data_type").orElseThrow(
                () -> new ZarrFormatException("the cast_value configuration needs a \"data_type\"")));
        Num to = Num.of(target);
        if (to == null) {
            throw new ZarrFormatException("cast_value casts to integer and floating-point data types only, not '"
                    + target.name() + "'");
        }
        Num from = Num.of(source);
        if (from == null) {
            throw new ZarrFormatException("cast_value casts integer and floating-point elements only, not '"
                    + source.name() + "' ones");
        }

        Rounding rounding = Rounding.NEAREST_EVEN;
        JsonValue r = configuration.find("rounding").orElse(null);
        if (r != null) {
            rounding = r instanceof JsonString s ? Rounding.of(s.value()) : null;
            if (rounding == null) {
                throw new ZarrFormatException("cast_value rounding must be nearest-even, towards-zero,"
                        + " towards-positive, towards-negative, or nearest-away, was " + r.toJson());
            }
        }
        OutOfRange outOfRange = null;
        JsonValue o = configuration.find("out_of_range").orElse(null);
        if (o != null && !o.isNull()) {
            outOfRange = o instanceof JsonString s ? OutOfRange.of(s.value()) : null;
            if (outOfRange == null) {
                throw new ZarrFormatException("cast_value out_of_range must be clamp or wrap, was " + o.toJson());
            }
            if (outOfRange == OutOfRange.WRAP && to.isFloat) {
                throw new ZarrFormatException("cast_value out_of_range 'wrap' needs an integer data_type, not '"
                        + target.name() + "'");
            }
        }

        List<Entry> encode = List.of();
        List<Entry> decode = List.of();
        JsonValue map = configuration.find("scalar_map").orElse(null);
        if (map != null && !map.isNull()) {
            if (!(map instanceof JsonObject m)) {
                throw new ZarrFormatException("cast_value scalar_map must be an object, was " + map.typeName());
            }
            encode = entries(m, "encode", source, from, target, to);
            decode = entries(m, "decode", target, to, source, from);
        }
        return new CastValueCodec(source, target, new ValueCast(from, to, rounding, outOfRange, encode),
                new ValueCast(to, from, rounding, outOfRange, decode));
    }

    /** The {@code data_type}: refused by name if the specification permits it but Falcon does not have it. */
    private static DataType dataType(JsonValue v) {
        String name = v instanceof JsonString s ? s.value()
                : v instanceof JsonObject o && o.find("name").orElse(null) instanceof JsonString s ? s.value() : null;
        if (name != null && UNIMPLEMENTED.contains(name)) {
            throw new ZarrUnsupportedException("cast_value to '" + name + "' is not supported: Falcon does not"
                    + " implement that data type");
        }
        try {
            return DataType.fromJson(v);
        } catch (ZarrUnsupportedException e) {
            throw new ZarrUnsupportedException("cast_value data_type: " + e.getMessage());
        } catch (ZarrFormatException e) {
            throw new ZarrFormatException("cast_value data_type: " + e.getMessage(), e);
        }
    }

    /** One side of the scalar_map: pairs of a key in {@code keyType} and a value in {@code valueType}. */
    private static List<Entry> entries(JsonObject map, String side, DataType keyType, Num keyNum,
                                       DataType valueType, Num valueNum) {
        JsonValue v = map.find(side).orElse(null);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!(v instanceof JsonArray pairs)) {
            throw new ZarrFormatException("cast_value scalar_map " + side + " must be an array of [input, output]"
                    + " pairs, was " + v.typeName());
        }
        List<Entry> out = new ArrayList<>(pairs.size());
        for (JsonValue p : pairs.values()) {
            if (!(p instanceof JsonArray pair) || pair.size() != 2) {
                throw new ZarrFormatException("cast_value scalar_map " + side + " entry " + p.toJson()
                        + " is not an [input, output] pair");
            }
            out.add(new Entry(scalar(pair.get(0), keyType, keyNum, side + " key"),
                    scalar(pair.get(1), valueType, valueNum, side + " value")));
        }
        return out;
    }

    /**
     * A scalar_map scalar of {@code type}, carried as {@link ValueCast} carries values. A float given as a number
     * or {@code "NaN"}/{@code "Infinity"}/{@code "-Infinity"} is converted as cast-value-rs converts the Python
     * float zarr-python hands it: to float32 by rounding the double, to float16 through float32. A hex string
     * gives the bits exactly.
     */
    private static long scalar(JsonValue v, DataType type, Num num, String what) {
        try {
            boolean hex = v instanceof JsonString s && (s.value().startsWith("0x") || s.value().startsWith("0X"));
            if (num.isFloat && !hex) {
                double d;
                if (v instanceof JsonNumber n) {
                    d = n.doubleValue();
                } else if (v instanceof JsonString s) {
                    d = switch (s.value()) {
                        case "NaN" -> Double.NaN;
                        case "Infinity" -> Double.POSITIVE_INFINITY;
                        case "-Infinity" -> Double.NEGATIVE_INFINITY;
                        default -> throw new ZarrFormatException("not a float: " + v.toJson());
                    };
                } else {
                    throw new ZarrFormatException("not a float: " + v.typeName());
                }
                return switch (num) {
                    case FLOAT64 -> Double.doubleToRawLongBits(d);
                    case FLOAT32 -> Float.floatToRawIntBits((float) d) & 0xffffffffL;
                    default -> (Double.isNaN(d) ? ValueCast.nan32to16(Float.floatToRawIntBits((float) d))
                            : Float.floatToFloat16((float) d)) & 0xffffL;
                };
            }
            byte[] element = type.decodeFillValue(v, ByteOrder.LITTLE_ENDIAN); // integers range-checked
            ByteBuffer b = ByteBuffer.wrap(element).order(ByteOrder.LITTLE_ENDIAN);
            return switch (num) {
                case INT8 -> b.get(0);
                case UINT8 -> b.get(0) & 0xffL;
                case INT16 -> b.getShort(0);
                case UINT16, FLOAT16 -> b.getShort(0) & 0xffffL;
                case INT32 -> b.getInt(0);
                case UINT32, FLOAT32 -> b.getInt(0) & 0xffffffffL;
                default -> b.getLong(0);
            };
        } catch (ZarrFormatException e) {
            throw new ZarrFormatException("cast_value scalar_map " + what + " " + v.toJson()
                    + " is not a valid " + type.name() + " value: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "cast_value";
    }

    @Override
    public DataType encodedType(DataType decodedType) {
        return target;
    }

    @Override
    public boolean keepsLayout() {
        return true;
    }

    @Override
    public int[] encodedShape(int[] inputShape) {
        return inputShape;
    }

    @Override
    public int[] decodedShape(int[] encodedShape) {
        return encodedShape;
    }

    @Override
    public ArrayValue encode(ArrayValue input, DataType decodedType, ByteOrder order) {
        return new ArrayValue(convert(encoder, input.data, source, order), input.shape);
    }

    @Override
    public ArrayValue decode(ArrayValue input, DataType decodedType, ByteOrder order) {
        return new ArrayValue(convert(decoder, input.data, target, order), input.shape);
    }

    @Override
    public byte[] encodeFill(byte[] fillElement, DataType decodedType, ByteOrder order) {
        return convert(encoder, fillElement, source, order);
    }

    @Override
    public byte[] decodeFill(byte[] fillElement, DataType decodedType, ByteOrder order) {
        return convert(decoder, fillElement, target, order);
    }

    private static byte[] convert(ValueCast cast, byte[] data, DataType from, ByteOrder order) {
        if (data.length % from.byteCount() != 0) {
            throw new ZarrFormatException("cast_value: " + data.length + " bytes are not a whole number of "
                    + from.name() + " elements");
        }
        int count = data.length / from.byteCount();
        if ((long) count * cast.target().size > Integer.MAX_VALUE - 8) {
            throw new ZarrFormatException("cast_value: " + count + " elements of " + cast.target().typeName()
                    + " are larger than the 2 GB a single buffer holds");
        }
        return cast.convert(data, count, order);
    }

    /** Never called: the pipeline passes the element types and byte order a value conversion needs. */
    @Override
    public ArrayValue encode(ArrayValue input, int elementSize) {
        throw new IllegalStateException("cast_value converts values: encode it with its data type and byte order");
    }

    /** Never called: the pipeline passes the element types and byte order a value conversion needs. */
    @Override
    public ArrayValue decode(ArrayValue input, int elementSize) {
        throw new IllegalStateException("cast_value converts values: decode it with its data type and byte order");
    }

    /** Never called: {@link #parse} refuses variable-length elements. */
    @Override
    public Object[] decodeObjects(Object[] input, int[] encodedShape) {
        throw new IllegalStateException("cast_value has no variable-length elements");
    }

    /** Never called: {@link #parse} refuses variable-length elements. */
    @Override
    public Object[] encodeObjects(Object[] input, int[] shape) {
        throw new IllegalStateException("cast_value has no variable-length elements");
    }
}
