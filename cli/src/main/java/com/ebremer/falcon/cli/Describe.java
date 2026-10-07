package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.Filter;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** How {@code ls} and {@code info} describe shapes, datatypes, filters, and sizes. */
final class Describe {

    private Describe() {
    }

    /** {@return a shape as numpy writes one: {@code (100, 200)}, {@code (5,)}, or {@code ()} for a scalar} */
    static String shape(long[] dims) {
        if (dims.length == 1) {
            return "(" + dims[0] + ",)";
        }
        StringBuilder s = new StringBuilder("(");
        for (int d = 0; d < dims.length; d++) {
            s.append(d == 0 ? "" : ", ").append(dims[d] == Dataspace.UNLIMITED ? "unlimited" : Long.toString(dims[d]));
        }
        return s.append(')').toString();
    }

    /** {@return a byte count, with its size in KiB, MiB, ... when it is that large} */
    static String bytes(long n) {
        String exact = String.format("%,d bytes", n);
        if (n < 1024) {
            return exact;
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double v = n;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return exact + String.format(" (%.1f %s)", v, units[u]);
    }

    // ---- HDF5 ---------------------------------------------------------------------------------------------

    /** {@return an HDF5 datatype as numpy would name it where it can: {@code float32}, {@code string(10, ascii)}} */
    static String type(Datatype type) {
        return switch (type) {
            case Datatype.FixedPoint fp -> (fp.signed() ? "int" : "uint") + 8 * fp.size()
                    + precision(fp.size(), fp.bitOffset(), fp.bitPrecision()) + order(fp.size(), fp.byteOrder());
            case Datatype.FloatingPoint fp -> floatName(fp) + order(fp.size(), fp.byteOrder());
            case Datatype.Time t -> "time" + 8 * t.size() + " (Unix seconds)" + order(t.size(), t.byteOrder());
            case Datatype.StringType s -> "string(" + s.size() + " bytes, " + charset(s.characterSet()) + ")";
            case Datatype.BitField b -> "bitfield" + 8 * b.size() + precision(b.size(), b.bitOffset(), b.bitPrecision())
                    + order(b.size(), b.byteOrder());
            case Datatype.Opaque o when NumpyTime.of(o) != null -> numpyTime(NumpyTime.of(o)) + " (h5py's opaque)";
            case Datatype.Opaque o -> "opaque(" + o.size() + " bytes"
                    + (o.tag().isEmpty() ? "" : ", \"" + o.tag() + "\"") + ")";
            case Datatype.Compound c when Hdf5Values.complexParts(c) != null ->
                    "complex" + 8 * c.size() + " (compound of r, i)"
                            + order(c.size() / 2, Hdf5Values.complexParts(c).byteOrder());
            case Datatype.Compound c -> "compound {" + c.members().stream()
                    .map(m -> m.name() + ": " + type(m.type())).collect(Collectors.joining(", ")) + "}";
            case Datatype.Reference r -> switch (r.kind()) {
                case OBJECT, REVISED_OBJECT -> "object reference";
                case DATASET_REGION, REVISED_DATASET_REGION -> "region reference";
                case REVISED_ATTRIBUTE -> "attribute reference";
                case OTHER -> "reference";
            };
            case Datatype.Enumeration e when Hdf5Values.isBool(e) -> "bool (enum)";
            case Datatype.Enumeration e -> "enum(" + type(e.base()) + ") {" + enumMembers(e) + "}";
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING ->
                    "vlen string (" + charset(v.characterSet()) + ")";
            case Datatype.VariableLength v -> "vlen " + type(v.base());
            case Datatype.Array a -> type(a.base()) + java.util.Arrays.toString(a.dimensions());
            case Datatype.Complex c -> "complex" + 8 * c.size() + order(c.base().size(), byteOrder(c.base()));
        };
    }

    private static String numpyTime(NumpyTime t) {
        return (t.delta() ? "timedelta64" : "datetime64") + (t.unit().equals("generic") ? ""
                : "[" + (t.scale() == 1 ? "" : t.scale()) + t.unit() + "]") + order(8, t.order());
    }

    private static String enumMembers(Datatype.Enumeration e) {
        List<String> names = new ArrayList<>();
        for (Datatype.Enumeration.Member m : e.members()) {
            if (names.size() == 6) {
                names.add("... " + (e.members().size() - 6) + " more");
                break;
            }
            names.add(m.name() + "=" + m.value());
        }
        return String.join(", ", names);
    }

    private static ByteOrder byteOrder(Datatype type) {
        return type instanceof Datatype.FloatingPoint f ? f.byteOrder() : ByteOrder.LITTLE_ENDIAN;
    }

    private static String floatName(Datatype.FloatingPoint fp) {
        if (fp.vaxOrder()) {
            return "VAX float" + 8 * fp.size();
        }
        if (standardFloat(fp)) {
            return "float" + 8 * fp.size();
        }
        if (fp.size() == 2 && fp.exponentSize() == 8 && fp.mantissaSize() == 7) {
            return "bfloat16";
        }
        return "float" + fp.bitPrecision() + " (" + fp.exponentSize() + "-bit exponent, " + fp.mantissaSize()
                + "-bit mantissa, in " + fp.size() + " bytes)";
    }

    /** {@return whether a float type is IEEE 754 binary16, binary32, or binary64, in either byte order} */
    static boolean standardFloat(Datatype.FloatingPoint fp) {
        Datatype.FloatingPoint ieee = switch (fp.size()) {
            case 2 -> Datatype.float16();
            case 4 -> Datatype.float32();
            case 8 -> Datatype.float64();
            default -> null;
        };
        return ieee != null && !fp.vaxOrder() && ieee.withByteOrder(fp.byteOrder()).equals(fp);
    }

    /** {@return whether an integer type uses every bit of 1, 2, 4, or 8 bytes} */
    static boolean standardInteger(Datatype.FixedPoint fp) {
        return (fp.size() == 1 || fp.size() == 2 || fp.size() == 4 || fp.size() == 8)
                && fp.bitOffset() == 0 && fp.bitPrecision() == 8 * fp.size();
    }

    private static String precision(int size, int offset, int precision) {
        if (offset == 0 && precision == 8 * size) {
            return "";
        }
        return " (" + precision + " bits" + (offset == 0 ? "" : " from bit " + offset) + ")";
    }

    private static String order(int size, ByteOrder order) {
        return size > 1 && order == ByteOrder.BIG_ENDIAN ? ", big-endian" : "";
    }

    private static String charset(Datatype.CharacterSet set) {
        return set == Datatype.CharacterSet.UTF8 ? "utf-8" : set == Datatype.CharacterSet.ASCII ? "ascii" : "?";
    }

    /** {@return a filter pipeline, such as {@code shuffle, deflate(level=4)}, or {@code none}} */
    static String filters(List<Filter> filters) {
        if (filters.isEmpty()) {
            return "none";
        }
        return filters.stream().map(Describe::filter).collect(Collectors.joining(", "));
    }

    /** {@return one filter and the settings its client data hold} */
    static String filter(Filter f) {
        int[] cd = f.clientData();
        String name = switch (f.id()) {
            case Filter.DEFLATE -> "deflate";
            case Filter.SHUFFLE -> "shuffle";
            case Filter.FLETCHER32 -> "fletcher32";
            case Filter.SZIP -> "szip";
            case Filter.NBIT -> "nbit";
            case Filter.SCALEOFFSET -> "scaleoffset";
            case Filter.BZIP2 -> "bzip2";
            case Filter.LZF -> "lzf";
            case Filter.BLOSC -> "blosc";
            case Filter.LZ4 -> "lz4";
            case Filter.BITSHUFFLE -> "bitshuffle";
            case Filter.ZFP -> "zfp";
            case Filter.ZSTD -> "zstd";
            case Filter.SZ -> "sz";
            case Filter.BLOSC2 -> "blosc2";
            default -> f.name() == null || f.name().isEmpty() ? "filter " + f.id() : f.name() + " (" + f.id() + ")";
        };
        String settings = switch (f.id()) {
            case Filter.DEFLATE, Filter.ZSTD -> cd.length > 0 ? "level=" + cd[0] : "";
            case Filter.BZIP2 -> cd.length > 0 ? "block size=" + cd[0] : "";
            case Filter.SZIP -> cd.length > 1 ? ((cd[0] & 32) != 0 ? "nearest neighbour" : "entropy coding")
                    + ", " + cd[1] + " pixels per block" : "";
            case Filter.BLOSC, Filter.BLOSC2 -> cd.length > 6 ? bloscName(cd[6]) + ", level=" + cd[4] + ", "
                    + shuffleName(cd[5], f.id() == Filter.BLOSC2) : "";
            case Filter.BITSHUFFLE -> cd.length > 4 ? (cd[4] == 2 ? "lz4" : cd[4] == 3 ? "zstd" : "no compression") : "";
            default -> "";
        };
        return name + (settings.isEmpty() ? "" : "(" + settings + ")");
    }

    /** {@return c-blosc's compressor of a code: 0 blosclz, 1 lz4, 2 lz4hc, 3 snappy, 4 zlib, 5 zstd} */
    static String bloscName(int code) {
        return switch (code) {
            case 0 -> "blosclz";
            case 1 -> "lz4";
            case 2 -> "lz4hc";
            case 3 -> "snappy";
            case 4 -> "zlib";
            case 5 -> "zstd";
            default -> "compressor " + code;
        };
    }

    /** {@return a Blosc shuffle by its code, in Blosc 1's names (Blosc2's filter 3 is its delta)} */
    static String shuffleName(int code, boolean blosc2) {
        return switch (code) {
            case 0 -> "noshuffle";
            case 1 -> "shuffle";
            case 2 -> "bitshuffle";
            case 3 -> blosc2 ? "delta" : "filter 3";
            default -> "filter " + code;
        };
    }

    // ---- Zarr ---------------------------------------------------------------------------------------------

    /** {@return a Zarr data type, such as {@code float32}, {@code datetime64[10s]}, or {@code struct {x: int16}}} */
    static String type(DataType type, ByteOrder order) {
        String name = switch (type.kind()) {
            case DATETIME, TIMEDELTA -> (type.kind() == com.ebremer.falcon.zarr.datatype.DataTypeKind.DATETIME
                    ? "datetime64" : "timedelta64") + (type.unit().equals("generic") ? ""
                    : "[" + (type.scaleFactor() == 1 ? "" : type.scaleFactor()) + type.unit() + "]");
            case FIXED_STRING -> "fixed_length_utf32[" + type.byteCount() / 4 + "]";
            case FIXED_BYTES -> "null_terminated_bytes[" + type.byteCount() + "]";
            case RAW_BYTES -> "raw_bytes[" + type.byteCount() + "]";
            case STRUCT -> "struct {" + type.fields().stream().map(f -> f.name() + ": " + type(f.type(), order))
                    .collect(Collectors.joining(", ")) + "}";
            default -> type.name();
        };
        boolean ordered = type.hasByteOrder() && type.byteCount() > 1
                && type.kind() != com.ebremer.falcon.zarr.datatype.DataTypeKind.STRUCT;
        return name + (ordered && order == ByteOrder.BIG_ENDIAN ? ", big-endian" : "");
    }
}
