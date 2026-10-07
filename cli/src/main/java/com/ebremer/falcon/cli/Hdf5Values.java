package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Attribute;
import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.hdf5.Selection;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads the elements of an HDF5 dataset, selection, or attribute as {@link Values}: numbers as numbers (a
 * {@code uint64} exactly), strings and enumeration names as strings, h5py's booleans as booleans, times as
 * instants, references as the paths of what they point at, and records, sequences, and arrays as JSON.
 */
final class Hdf5Values {

    private Hdf5Values() {
    }

    /** What a dataset, a selection, and an attribute each read with: they share their readers, not a type. */
    private record Reads(Datatype type, long count, Supplier<long[]> longs, Supplier<float[]> floats,
                         Supplier<double[]> doubles, Supplier<double[]> complexDoubles, Supplier<String[]> strings,
                         Supplier<Object> natural) {
    }

    static Values of(Dataset dataset) {
        return values(new Reads(dataset.datatype(), dataset.dataspace().elementCount(), dataset::readLongs,
                dataset::readFloats, dataset::readDoubles,
                dataset::readComplexDoubles, dataset::readStrings, dataset::read));
    }

    static Values of(Selection selection) {
        return values(new Reads(selection.datatype(), selection.elementCount(), selection::readLongs, selection::readFloats,
                selection::readDoubles, selection::readComplexDoubles, selection::readStrings, selection::read));
    }

    static Values of(Attribute attribute) {
        return values(new Reads(attribute.datatype(), attribute.dataspace().elementCount(), attribute::readLongs,
                attribute::readFloats,
                attribute::readDoubles, attribute::readComplexDoubles, attribute::readStrings, attribute::read));
    }

    /**
     * {@return an attribute's value as JSON: a scalar's element, an array's elements nested by its shape, or
     * {@code null} for an attribute with no elements (a null dataspace)}
     *
     * @param attribute the attribute
     */
    static JsonValue json(Attribute attribute) {
        if (attribute.dataspace().kind() == com.ebremer.falcon.hdf5.Dataspace.Kind.NULL) {
            return JsonNull.INSTANCE;
        }
        return Values.nested(of(attribute), attribute.dataspace().dimensions());
    }

    private static Values values(Reads reads) {
        Datatype type = reads.type();
        return switch (type) {
            case Datatype.FixedPoint fp when fp.size() == 8 && !fp.signed() && fp.bitPrecision() == 64 ->
                    Values.bigIntegers((BigInteger[]) reads.natural().get());
            case Datatype.FixedPoint fp -> Values.longs(reads.longs().get());
            case Datatype.BitField b when b.bitPrecision() >= 64 -> Values.bigIntegers((BigInteger[]) reads.natural().get());
            case Datatype.BitField b -> Values.longs(reads.longs().get());
            case Datatype.FloatingPoint fp when fp.size() <= 4 -> Values.floats(reads.floats().get());
            case Datatype.FloatingPoint fp -> Values.doubles(reads.doubles().get());
            case Datatype.Enumeration e when isBool(e) -> Values.booleans(reads.longs().get());
            case Datatype.StringType s -> Values.strings(reads.strings().get());
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING ->
                    Values.strings(reads.strings().get());
            case Datatype.Enumeration e -> Values.strings(reads.strings().get());
            case Datatype.Complex c -> Values.complex(reads.complexDoubles().get(), c.base().size() <= 4);
            case Datatype.Compound c when complexParts(c) != null ->
                    Values.complex(reads.complexDoubles().get(), complexParts(c).size() <= 4);
            case Datatype.Time t -> Values.instants((Instant[]) reads.natural().get());
            case Datatype.Opaque o when NumpyTime.of(o) != null -> NumpyTime.of(o).values((byte[][]) reads.natural().get());
            default -> {
                Object natural = reads.natural().get();
                int n = Math.toIntExact(reads.count());
                JsonValue[] json = new JsonValue[n];
                for (int i = 0; i < n; i++) {
                    json[i] = element(type, natural, i);
                }
                yield Values.of(json);
            }
        };
    }

    /** {@return whether an enumeration is h5py's boolean: {@code FALSE = 0} and {@code TRUE = 1}} */
    static boolean isBool(Datatype.Enumeration e) {
        return e.members().size() == 2 && e.members().get(0).name().equals("FALSE") && e.members().get(0).value() == 0
                && e.members().get(1).name().equals("TRUE") && e.members().get(1).value() == 1;
    }

    /**
     * {@return the floating-point type of h5py's complex numbers, a compound of two floats {@code r} and
     * {@code i} of one type, the imaginary part right after the real; or null for any other compound}
     *
     * @param c the compound
     */
    static Datatype.FloatingPoint complexParts(Datatype.Compound c) {
        if (c.members().size() == 2 && c.members().get(0).name().equals("r") && c.members().get(1).name().equals("i")
                && c.members().get(0).type() instanceof Datatype.FloatingPoint re && re.equals(c.members().get(1).type())
                && c.members().get(0).offset() == 0 && c.members().get(1).offset() == re.size()
                && c.size() == 2 * re.size()) {
            return re;
        }
        return null;
    }

    /**
     * {@return element {@code i} of a natural-value column as JSON}
     *
     * @param type   the elements' datatype
     * @param column what {@code read()} gives for them
     * @param i      the element's index
     */
    static JsonValue element(Datatype type, Object column, int i) {
        return switch (type) {
            case Datatype.Compound c when complexParts(c) != null && column instanceof Map<?, ?> parts ->
                    JsonArray.of(element(complexParts(c), parts.get("r"), i), element(complexParts(c), parts.get("i"), i));
            case Datatype.Compound c when column instanceof Map<?, ?> members -> {
                JsonObject.Builder object = JsonObject.builder();
                for (Datatype.Compound.Member member : c.members()) {
                    object.put(member.name(), element(member.type(), members.get(member.name()), i));
                }
                yield object.build();
            }
            case Datatype.Array array -> {
                int n = array.elementCount();
                yield block(array.base(), column, i * n, array.dimensions(), 0);
            }
            case Datatype.Enumeration e when column instanceof String[] names -> {
                String name = names[i];
                if (isBool(e) && name != null) {
                    yield JsonBool.of(name.equals("TRUE"));
                }
                yield name == null ? JsonNull.INSTANCE : new JsonString(name);
            }
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.SEQUENCE -> {
                Object row = Array.get(column, i);
                if (row == null) {
                    yield JsonNull.INSTANCE;
                }
                int length = count(v.base(), row);
                List<JsonValue> items = new ArrayList<>(length);
                for (int j = 0; j < length; j++) {
                    items.add(element(v.base(), row, j));
                }
                yield new JsonArray(items);
            }
            case Datatype.Complex c when column instanceof double[] pairs -> {
                boolean single = c.base().size() <= 4;
                yield JsonArray.of(single ? Values.number((float) pairs[2 * i]) : Values.number(pairs[2 * i]),
                        single ? Values.number((float) pairs[2 * i + 1]) : Values.number(pairs[2 * i + 1]));
            }
            case Datatype.Opaque o when NumpyTime.of(o) != null && column instanceof byte[][] b ->
                    NumpyTime.of(o).values(new byte[][] {b[i]}).json(0);
            case Datatype.FloatingPoint f when column instanceof double[] d ->
                    f.size() <= 4 ? Values.number((float) d[i]) : Values.number(d[i]);
            default -> scalar(column, i);
        };
    }

    /**
     * {@return how many elements of {@code type} a natural-value column holds: a compound's column is a map of
     * its members' columns, an array type's holds each element's values one after another, and a complex
     * number's holds each element's two parts}
     *
     * @param type   the elements' datatype
     * @param column what {@code read()} gives for them
     */
    static int count(Datatype type, Object column) {
        return switch (type) {
            case Datatype.Compound c when column instanceof Map<?, ?> members -> c.members().isEmpty() ? 0
                    : count(c.members().getFirst().type(), members.get(c.members().getFirst().name()));
            case Datatype.Array array -> count(array.base(), column) / array.elementCount();
            case Datatype.Complex c when column instanceof double[] pairs -> pairs.length / 2;
            default -> Array.getLength(column);
        };
    }

    /** The elements of one array-type element, nested by its dimensions. */
    private static JsonValue block(Datatype base, Object column, int offset, int[] dims, int dim) {
        int stride = 1;
        for (int d = dim + 1; d < dims.length; d++) {
            stride *= dims[d];
        }
        List<JsonValue> items = new ArrayList<>(dims[dim]);
        for (int j = 0; j < dims[dim]; j++) {
            int at = offset + j * stride;
            items.add(dim == dims.length - 1 ? element(base, column, at) : block(base, column, at, dims, dim + 1));
        }
        return new JsonArray(items);
    }

    private static JsonValue scalar(Object column, int i) {
        return switch (column) {
            case int[] v -> JsonNumber.of(v[i]);
            case long[] v -> JsonNumber.of(v[i]);
            case short[] v -> JsonNumber.of(v[i]);
            case byte[] v -> JsonNumber.of(v[i]);
            case double[] v -> Values.number(v[i]);
            case float[] v -> Values.number(v[i]);
            case BigInteger[] v -> v[i] == null ? JsonNull.INSTANCE : JsonNumber.of(v[i]);
            case String[] v -> v[i] == null ? JsonNull.INSTANCE : new JsonString(v[i]);
            case byte[][] v -> v[i] == null ? JsonNull.INSTANCE : new JsonString(Base64.getEncoder().encodeToString(v[i]));
            case Instant[] v -> v[i] == null ? JsonNull.INSTANCE : new JsonString(v[i].toString());
            case Hdf5Object[] v -> v[i] == null ? JsonNull.INSTANCE : new JsonString(path(v[i]));
            case Selection[] v -> v[i] == null ? JsonNull.INSTANCE : new JsonString(describe(v[i]));
            case Object[] v -> v[i] == null ? JsonNull.INSTANCE : new JsonString(String.valueOf(v[i]));
            default -> new JsonString(String.valueOf(Array.get(column, i)));
        };
    }

    private static String path(Hdf5Object object) {
        String path = object.path();
        return path.isEmpty() ? "(object at address " + object.objectHeaderAddress() + ")" : path;
    }

    /** A region reference: its dataset's path, and the region as a box or a count of elements. */
    static String describe(Selection selection) {
        try {
            String path = path(selection.dataset());
            if (selection.isRectangular()) {
                long[] offset = selection.offset();
                long[] shape = selection.shape();
                StringBuilder s = new StringBuilder(path).append('[');
                for (int d = 0; d < offset.length; d++) {
                    s.append(d == 0 ? "" : ", ").append(offset[d]).append(':').append(offset[d] + shape[d]);
                }
                return s.append(']').toString();
            }
            return path + " (" + selection.elementCount() + " elements)";
        } catch (RuntimeException e) {
            return "(unresolvable region: " + e.getMessage() + ")";
        }
    }
}
