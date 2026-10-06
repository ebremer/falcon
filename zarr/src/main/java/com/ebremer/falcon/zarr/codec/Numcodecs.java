package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The numcodecs filters and checksums Falcon runs, by their zarr-python 3 names ({@code numcodecs.<id>}).
 * A Zarr v2 array's {@code filters} reach the codec pipeline under these names, each with its numcodecs
 * configuration (less the {@code id}), after the array&rarr;bytes codec, in the order numcodecs applies
 * them; decoding undoes them in reverse.
 *
 * <ul>
 *   <li><b>Element filters</b> ({@code delta}, {@code fixedscaleoffset}, {@code quantize},
 *       {@code bitround}, {@code astype}, {@code packbits}) work on the elements of the bytes before them:
 *       numcodecs' arithmetic, NumPy's casts, and NumPy 2's promotion rules, byte for byte both ways. In
 *       Zarr v3 metadata zarr-python puts these before the array&rarr;bytes codec, as array&rarr;array
 *       codecs, which Falcon does not run there.</li>
 *   <li><b>Byte codecs</b> ({@code shuffle} and the checksums {@code crc32}, {@code crc32c},
 *       {@code adler32}, {@code fletcher32}, {@code jenkins_lookup3}) work on bytes; zarr-python 3 writes
 *       them as bytes&rarr;bytes codecs in Zarr v3 metadata too, where they work as well.</li>
 * </ul>
 *
 * <p>A filter's input elements are what the stage before gives: the {@code bytes} codec's data type and
 * byte order, or the elements a filter before encodes to ({@code bitround} needs to know them, a float
 * type), or plain bytes ({@code |u1}) after any other codec.
 */
final class Numcodecs {

    /** The prefix zarr-python 3 gives numcodecs' codecs in Zarr v3 metadata. */
    static final String PREFIX = "numcodecs.";

    /** The element filters: zarr-python 3's array&rarr;array numcodecs codecs. */
    private static final Set<String> ELEMENT_FILTERS =
            Set.of("delta", "fixedscaleoffset", "quantize", "bitround", "astype", "packbits");
    /** The byte codecs: zarr-python 3's bytes&rarr;bytes numcodecs codecs that Falcon has here. */
    private static final Set<String> BYTE_CODECS =
            Set.of("shuffle", "crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3");

    private Numcodecs() {
    }

    /** Whether {@code name} is one of the numcodecs codecs this class builds. */
    static boolean handles(String name) {
        if (!name.startsWith(PREFIX)) {
            return false;
        }
        String id = name.substring(PREFIX.length());
        return ELEMENT_FILTERS.contains(id) || BYTE_CODECS.contains(id);
    }

    /**
     * The error for a numcodecs codec placed before the array&rarr;bytes codec: an element filter is
     * unsupported there (zarr-python 3's Zarr v3 placement), a byte codec malformed.
     */
    static RuntimeException beforeArrayBytes(String name) {
        if (ELEMENT_FILTERS.contains(name.substring(PREFIX.length()))) {
            return new ZarrUnsupportedException("'" + name + "' as an array->array codec (before the array->bytes"
                    + " codec, as Zarr v3 metadata places it) is not supported; Falcon runs it among a Zarr v2"
                    + " array's filters");
        }
        return new ZarrFormatException("bytes->bytes codec '" + name + "' appears before the array->bytes codec");
    }

    /**
     * The elements a codec appended after {@code byteCodecs} receives when encoding: those of the last
     * numcodecs filter, plain bytes after any other bytes&rarr;bytes codec, and otherwise the array&rarr;bytes
     * codec's ({@code bytes}: the data type in its byte order; anything else: plain bytes).
     *
     * @return the elements' type, or {@code null} if they are not NumPy numbers
     */
    static NumpyType elementType(DataType dataType, ArrayBytesCodec bytesCodec, List<BytesBytesCodec> byteCodecs) {
        if (!byteCodecs.isEmpty()) {
            return byteCodecs.getLast() instanceof NumcodecsCodec n ? n.encodedType() : NumpyType.U1;
        }
        return bytesCodec instanceof BytesCodec b ? NumpyType.of(dataType, b.elementByteOrder()) : NumpyType.U1;
    }

    /**
     * Builds the codec {@code name} ({@link #handles} must be true) from its numcodecs configuration.
     *
     * @param input the type of the elements it is given when encoding (see {@link #elementType}), or
     *              {@code null}
     * @throws ZarrFormatException      if the configuration is malformed
     * @throws ZarrUnsupportedException if it names a setting Falcon does not implement
     */
    static BytesBytesCodec parse(String name, JsonObject configuration, NumpyType input) {
        String id = name.substring(PREFIX.length());
        return switch (id) {
            case "delta" -> DeltaCodec.parse(configuration);
            case "fixedscaleoffset" -> FixedScaleOffsetCodec.parse(configuration);
            case "quantize" -> QuantizeCodec.parse(configuration);
            case "bitround" -> BitRoundCodec.parse(configuration, input);
            case "astype" -> AsTypeCodec.parse(configuration);
            case "packbits" -> new PackBitsCodec();
            case "shuffle" -> ShuffleCodec.parse(configuration);
            case "crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3" ->
                    ChecksumCodec.parse(id, configuration);
            default -> throw new ZarrUnsupportedException("unknown codec: '" + name + "'");
        };
    }

    // ---- configuration helpers ---------------------------------------------------------------------

    /** A configuration's dtype string, parsed, or {@code null} if it is absent or JSON {@code null}. */
    static NumpyType dtype(JsonObject configuration, String key, String codec) {
        Optional<JsonValue> value = configuration.find(key).filter(v -> !v.isNull());
        return value.map(v -> NumpyType.parse(v.asString(), PREFIX + codec + " " + key)).orElse(null);
    }

    /** A configuration's required dtype string, parsed. */
    static NumpyType requireDtype(JsonObject configuration, String key, String codec) {
        NumpyType type = dtype(configuration, key, codec);
        if (type == null) {
            throw new ZarrFormatException(PREFIX + codec + " configuration needs '" + key + "'");
        }
        return type;
    }

    /** A configuration's integer, or {@code fallback} if it is absent. */
    static long integer(JsonObject configuration, String key, long fallback) {
        return configuration.find(key).filter(v -> !v.isNull()).map(v -> v.asNumber().longValue()).orElse(fallback);
    }

    /**
     * A Python number from a configuration, as numcodecs holds it after {@code json.loads}: an {@code int}
     * when the literal has no fraction or exponent, else a {@code float}. NumPy 2 combines the two
     * differently with an array (NEP&nbsp;50), so the distinction matters.
     *
     * @param integral whether it is a Python {@code int}
     * @param integer  the value of an {@code int}
     * @param real     the value as a double (an {@code int} rounded once)
     */
    record Scalar(boolean integral, long integer, double real) {

        static Scalar of(JsonObject configuration, String key, String codec) {
            JsonValue value = configuration.find(key).orElseThrow(() ->
                    new ZarrFormatException(PREFIX + codec + " configuration needs '" + key + "'"));
            JsonNumber number = value.asNumber();
            String literal = number.literal();
            boolean integral = number.isFinite() && literal.indexOf('.') < 0 && literal.indexOf('e') < 0
                    && literal.indexOf('E') < 0;
            if (!integral) {
                return new Scalar(false, 0, number.doubleValue());
            }
            long integer;
            try {
                integer = number.longValue();
            } catch (RuntimeException e) {
                throw new ZarrUnsupportedException(PREFIX + codec + " " + key + " " + literal
                        + " is beyond a 64-bit integer");
            }
            return new Scalar(true, integer, (double) integer);
        }

        /**
         * The type NumPy 2 gives {@code array op this} for an array of {@code type}: the array's, except that
         * an integer array with a Python {@code float} gives {@code float64}.
         */
        NumpyType resultWith(NumpyType type) {
            return type.isInteger() && !integral ? NumpyType.float64() : type;
        }

        /**
         * This scalar converted to {@code type}, as NumPy converts a Python scalar for an operation with an
         * array of that type: a float rounded once, an {@code int} that must fit an integer type.
         *
         * @throws ZarrFormatException if an {@code int} is out of the integer type's range (NumPy raises
         *                             {@code OverflowError})
         */
        long in(NumpyType type, String what) {
            if (type.isFloat()) {
                return type.fromDouble(real);
            }
            if (!integral || !type.holds(integer, new NumpyType('i', 8, type.order()))) {
                throw new ZarrFormatException(what + " " + (integral ? Long.toString(integer) : Double.toString(real))
                        + " is out of bounds for '" + type + "'");
            }
            return type.wrap(integer);
        }
    }
}
