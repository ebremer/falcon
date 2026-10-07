package com.ebremer.falcon.cli;

import com.ebremer.falcon.zarr.Selection;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;

/**
 * Reads the elements of a Zarr selection as {@link Values}, by data type: unsigned integers exactly,
 * float32 as float32, times as numpy prints them, byte strings as {@code b"..."}, and structs as JSON objects.
 */
final class ZarrValues {

    private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

    private ZarrValues() {
    }

    static Values of(Selection selection, DataType type) {
        return switch (type.kind()) {
            case BOOL -> Values.booleans(selection.readLongs());
            case INT -> Values.longs(selection.readLongs());
            case UINT -> Values.unsignedLongs(selection.readUnsignedLongs());
            case FLOAT -> type.byteCount() == 8 ? Values.doubles(selection.readDoubles())
                    : Values.floats(selection.readFloats());
            case COMPLEX -> Values.complex(selection.readComplex(), type.byteCount() == 8);
            case STRING, FIXED_STRING -> Values.strings(selection.readStrings());
            case BYTES, FIXED_BYTES -> Values.bytes(selection.readByteArrays(), true);
            case RAW, RAW_BYTES -> Values.bytes(selection.readByteArrays(), false);
            case DATETIME, TIMEDELTA -> Values.times(selection.readLongs(), type.unit(), type.scaleFactor(),
                    type.kind() == com.ebremer.falcon.zarr.datatype.DataTypeKind.TIMEDELTA);
            case STRUCT -> {
                byte[][] elements = selection.readByteArrays();
                JsonValue[] json = new JsonValue[elements.length];
                for (int i = 0; i < elements.length; i++) {
                    json[i] = struct(elements[i], 0, type);
                }
                yield Values.of(json);
            }
        };
    }

    /**
     * {@return a struct element, little-endian as {@code readByteArrays} gives it, as a JSON object}
     *
     * @param element the element's bytes
     * @param offset  where the struct starts in them
     * @param type    the struct's data type
     */
    static JsonObject struct(byte[] element, int offset, DataType type) {
        JsonObject.Builder object = JsonObject.builder();
        for (DataType.Field field : type.fields()) {
            object.put(field.name(), field(element, offset + type.fieldOffset(field.name()), field.type()));
        }
        return object.build();
    }

    private static JsonValue field(byte[] element, int at, DataType type) {
        ByteBuffer b = ByteBuffer.wrap(element).order(ByteOrder.LITTLE_ENDIAN);
        int n = type.byteCount();
        return switch (type.kind()) {
            case BOOL -> JsonBool.of(element[at] != 0);
            case INT -> JsonNumber.of(switch (n) {
                case 1 -> element[at];
                case 2 -> b.getShort(at);
                case 4 -> b.getInt(at);
                default -> b.getLong(at);
            });
            case UINT -> switch (n) {
                case 1 -> JsonNumber.of(element[at] & 0xff);
                case 2 -> JsonNumber.of(b.getShort(at) & 0xffff);
                case 4 -> JsonNumber.of(b.getInt(at) & 0xffffffffL);
                default -> JsonNumber.of(new BigInteger(Long.toUnsignedString(b.getLong(at))));
            };
            case FLOAT -> switch (n) {
                case 2 -> Values.number(Float.float16ToFloat(b.getShort(at)));
                case 4 -> Values.number(b.getFloat(at));
                default -> Values.number(b.getDouble(at));
            };
            case COMPLEX -> n == 8 ? JsonArray.of(Values.number(b.getFloat(at)), Values.number(b.getFloat(at + 4)))
                    : JsonArray.of(Values.number(b.getDouble(at)), Values.number(b.getDouble(at + 8)));
            case DATETIME -> new JsonString(Values.time(b.getLong(at), type.unit(), type.scaleFactor(), false));
            case TIMEDELTA -> b.getLong(at) == Long.MIN_VALUE ? new JsonString("NaT") : JsonNumber.of(b.getLong(at));
            case FIXED_STRING -> new JsonString(stripNuls(new String(element, at, n, UTF_32LE)));
            case FIXED_BYTES -> {
                int end = at + n;
                while (end > at && element[end - 1] == 0) {
                    end--;
                }
                yield Values.textOrBase64(Arrays.copyOfRange(element, at, end));
            }
            case STRUCT -> struct(element, at, type);
            default -> new JsonString(Base64.getEncoder().encodeToString(Arrays.copyOfRange(element, at, at + n)));
        };
    }

    private static String stripNuls(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) {
            end--;
        }
        return s.substring(0, end);
    }
}
