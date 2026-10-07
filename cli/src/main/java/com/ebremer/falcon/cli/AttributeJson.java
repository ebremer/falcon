package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Attribute;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Attributes between the formats: an HDF5 attribute becomes the JSON of its value (see
 * {@link Hdf5Values#json}), and a Zarr attribute becomes an HDF5 attribute of the type h5py would give its
 * value: a string a variable-length UTF-8 string, an integer an {@code int64} (a {@code uint64} past it), any
 * other number a {@code float64}, a boolean h5py's boolean, and an array of one of those, rectangular, an
 * array attribute of that shape. Anything else (an object, {@code null}, a ragged or mixed array, an empty
 * one) is kept as its JSON text, in a string attribute.
 */
final class AttributeJson {

    private AttributeJson() {
    }

    /** Where an HDF5 attribute goes: a group's or a dataset's {@code attribute} method. */
    interface Sink {
        void attribute(String name, Datatype type, long[] shape, Object values);
    }

    /**
     * {@return an HDF5 object's attributes as a JSON object, leaving out (with a warning) any that cannot be
     * read}
     *
     * @param context the command's context, for warnings
     * @param object  the object
     */
    static JsonObject fromHdf5(Context context, Hdf5Object object) {
        JsonObject.Builder json = JsonObject.builder();
        for (Attribute attribute : object.attributes()) {
            try {
                json.put(attribute.name(), Hdf5Values.json(attribute));
            } catch (RuntimeException e) {
                context.warn("left out attribute '" + attribute.name() + "' of " + object.path() + ": "
                        + Errors.describe(e));
            }
        }
        return json.build();
    }

    /**
     * Writes a Zarr node's attributes to an HDF5 object.
     *
     * @param context    the command's context, for warnings
     * @param path       the object's path, for warnings
     * @param attributes the attributes
     * @param sink       the object's writer
     */
    static void toHdf5(Context context, String path, JsonObject attributes, Sink sink) {
        for (Map.Entry<String, JsonValue> entry : attributes.members().entrySet()) {
            String name = entry.getKey();
            if (name.isEmpty()) {
                context.warn("left out an attribute of " + path + " with an empty name, which HDF5 refuses");
                continue;
            }
            try {
                write(name, entry.getValue(), sink);
            } catch (RuntimeException e) {
                context.warn("left out attribute '" + name + "' of " + path + ": " + Errors.describe(e));
            }
        }
    }

    private static void write(String name, JsonValue value, Sink sink) {
        List<Long> shape = new ArrayList<>();
        List<JsonValue> leaves = new ArrayList<>();
        if (!rectangular(value, shape, leaves, 0) || leaves.isEmpty()) {
            sink.attribute(name, Datatype.variableString(), new long[0], new String[] {Json.write(value)});
            return;
        }
        long[] dims = shape.stream().mapToLong(Long::longValue).toArray();
        int n = leaves.size();
        JsonValue first = leaves.get(0);
        if (first instanceof JsonString && leaves.stream().allMatch(JsonString.class::isInstance)) {
            String[] strings = new String[n];
            for (int i = 0; i < n; i++) {
                strings[i] = ((JsonString) leaves.get(i)).value();
            }
            sink.attribute(name, Datatype.variableString(), dims, strings);
        } else if (first instanceof JsonBool && leaves.stream().allMatch(JsonBool.class::isInstance)) {
            boolean[] booleans = new boolean[n];
            for (int i = 0; i < n; i++) {
                booleans[i] = ((JsonBool) leaves.get(i)).value();
            }
            sink.attribute(name, Datatype.bool(), dims, booleans);
        } else if (leaves.stream().allMatch(JsonNumber.class::isInstance)) {
            numbers(name, dims, leaves, sink);
        } else {
            sink.attribute(name, Datatype.variableString(), new long[0], new String[] {Json.write(value)});
        }
    }

    private static void numbers(String name, long[] dims, List<JsonValue> leaves, Sink sink) {
        int n = leaves.size();
        boolean integers = true;
        boolean signed = true; // every integer fits a long
        for (JsonValue leaf : leaves) {
            String literal = ((JsonNumber) leaf).literal();
            if (!literal.matches("-?\\d+")) {
                integers = false;
                break;
            }
            BigInteger v = new BigInteger(literal);
            if (v.bitLength() > 63) {
                signed = false;
                if (v.signum() < 0 || v.bitLength() > 64) {
                    integers = false;
                    break;
                }
            }
        }
        if (integers && signed) {
            long[] values = new long[n];
            for (int i = 0; i < n; i++) {
                values[i] = Long.parseLong(((JsonNumber) leaves.get(i)).literal());
            }
            sink.attribute(name, Datatype.int64(), dims, values);
        } else if (integers) {
            BigInteger[] values = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                values[i] = new BigInteger(((JsonNumber) leaves.get(i)).literal());
            }
            sink.attribute(name, Datatype.uint64(), dims, values);
        } else {
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = number((JsonNumber) leaves.get(i));
            }
            sink.attribute(name, Datatype.float64(), dims, values);
        }
    }

    static double number(JsonNumber number) {
        return switch (number.literal()) {
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> number.doubleValue();
        };
    }

    /**
     * Whether a value is a scalar or a rectangular nest of arrays of scalars (no arrays of length 0), and if so
     * its shape and its scalars in C order.
     */
    private static boolean rectangular(JsonValue value, List<Long> shape, List<JsonValue> leaves, int depth) {
        if (!(value instanceof JsonArray array)) {
            if (depth < shape.size() || value instanceof JsonObject || !(value instanceof JsonString
                    || value instanceof JsonNumber || value instanceof JsonBool)) {
                return false;
            }
            leaves.add(value);
            return true;
        }
        if (array.size() == 0) {
            return false;
        }
        if (depth == shape.size()) {
            if (!leaves.isEmpty()) {
                return false; // deeper here than elsewhere
            }
            shape.add((long) array.size());
        } else if (shape.get(depth) != array.size()) {
            return false;
        }
        for (JsonValue item : array.values()) {
            if (!rectangular(item, shape, leaves, depth + 1)) {
                return false;
            }
        }
        return true;
    }
}
