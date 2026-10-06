package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Falcon's {@code cast_value} against cast-value-rs 0.4.2 itself, the Rust backend zarr-python's
 * {@code CastValue} calls: every vector in {@code cast_value_vectors.txt}
 * ({@code tools/fixtures/gen_cast_value_vectors.py}) gives an input element, a configuration (source and target
 * type, rounding, out_of_range, with or without a scalar_map), and the bits cast-value-rs produced or the error
 * it raised. Falcon must produce the same bits, and refuse the same elements with the same kind of error, over
 * every pair of the eleven types, both through the codec as an array's metadata configures it.
 */
class CastValueVectorsTest {

    private static final Map<String, String> ROUNDING = Map.of("ne", "nearest-even", "tz", "towards-zero",
            "tp", "towards-positive", "tn", "towards-negative", "na", "nearest-away");
    private static final Map<String, String> OUT_OF_RANGE = Map.of("c", "clamp", "w", "wrap");

    private static List<String> lines() {
        try (InputStream in = CastValueVectorsTest.class.getResourceAsStream("/fixtures/cast_value_vectors.txt")) {
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void matchesCastValueRs() {
        Map<String, long[]> inputs = new HashMap<>();
        Map<String, String> maps = new HashMap<>();
        List<String> failures = new ArrayList<>();
        int vectors = 0;
        int errors = 0;
        int configurations = 0;
        for (String line : lines()) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(" ");
            switch (f[0]) {
                case "I" -> {
                    long[] bits = new long[f.length - 2];
                    for (int i = 0; i < bits.length; i++) {
                        bits[i] = Long.parseUnsignedLong(f[i + 2], 16);
                    }
                    inputs.put(f[1], bits);
                }
                case "M" -> maps.put(f[1] + " " + f[2], line.substring(line.indexOf('[')));
                default -> {
                    configurations++;
                    long[] in = inputs.get(f[1]);
                    assertEquals(in.length, f.length - 6, "outputs on " + line.substring(0, 40));
                    Case c = new Case(f[1], f[2], ROUNDING.get(f[3]), OUT_OF_RANGE.get(f[4]),
                            f[5].equals("1") ? maps.get(f[1] + " " + f[2]) : null);
                    for (int i = 0; i < in.length; i++) {
                        vectors++;
                        String want = f[i + 6];
                        errors += want.startsWith("!") ? 1 : 0;
                        String got = c.cast(in[i]);
                        if (!want.equals(got) && failures.size() < 40) {
                            failures.add(String.join(" ", f[1], f[2], f[3], f[4], f[5]) + ": input "
                                    + Long.toHexString(in[i]) + " gave " + got + ", cast-value-rs " + want);
                        }
                        if (!want.equals(got) && failures.size() >= 40) {
                            failures.set(39, "... and more");
                        }
                    }
                    c.checkWholeArray(in, f);
                }
            }
        }
        assertTrue(configurations > 3000 && vectors > 400_000 && errors > 10_000,
                configurations + " configurations, " + vectors + " vectors, " + errors + " errors");
        if (!failures.isEmpty()) {
            fail(failures.size() + " vectors differ (of " + vectors + "):\n" + String.join("\n", failures));
        }
    }

    /**
     * One configuration, run through a {@link CastValueCodec} the way an array would configure it: encoding
     * from the source type, or, for {@code wrap} into a float (which only an integer {@code data_type}
     * permits), decoding an integer {@code data_type} back to a float array.
     */
    private static final class Case {

        final DataType from;
        final DataType to;
        final CastValueCodec codec;
        final boolean encode;

        Case(String src, String dst, String rounding, String outOfRange, String map) {
            this.from = DataType.of(src);
            this.to = DataType.of(dst);
            this.encode = !"wrap".equals(outOfRange) || to.kind() != DataTypeKind.FLOAT;
            StringBuilder json = new StringBuilder("{\"data_type\":\"" + (encode ? dst : src) + "\",\"rounding\":\""
                    + rounding + "\"");
            if (outOfRange != null) {
                json.append(",\"out_of_range\":\"").append(outOfRange).append('"');
            }
            if (map != null) {
                json.append(",\"scalar_map\":{\"").append(encode ? "encode" : "decode").append("\":").append(map)
                        .append('}');
            }
            JsonObject config = Json.parse(json.append('}').toString()).asObject();
            this.codec = CastValueCodec.parse(config, encode ? from : to);
        }

        /** The result's bits in hex, or !n / !r for cast-value-rs's NanOrInf / OutOfRange errors. */
        String cast(long bits) {
            try {
                byte[] out = run(element(bits));
                return Long.toHexString(read(out, 0, to.byteCount()));
            } catch (ValueCast.CastException e) {
                return e.nanOrInfinity ? "!n" : "!r";
            }
        }

        byte[] element(long bits) {
            byte[] b = new byte[from.byteCount()];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (bits >>> (8 * i));
            }
            return b;
        }

        byte[] run(byte[] data) {
            ArrayValue in = new ArrayValue(data, new int[] {data.length / from.byteCount()});
            return encode ? codec.encode(in, from, ByteOrder.LITTLE_ENDIAN).data
                    : codec.decode(in, to, ByteOrder.LITTLE_ENDIAN).data;
        }

        /** Where no element fails, the whole array at once, little- and big-endian, gives every result. */
        void checkWholeArray(long[] in, String[] f) {
            for (int i = 0; i < in.length; i++) {
                if (f[i + 6].startsWith("!")) {
                    return;
                }
            }
            for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                ByteBuffer b = ByteBuffer.allocate(in.length * from.byteCount()).order(order);
                for (long bits : in) {
                    switch (from.byteCount()) {
                        case 1 -> b.put((byte) bits);
                        case 2 -> b.putShort((short) bits);
                        case 4 -> b.putInt((int) bits);
                        default -> b.putLong(bits);
                    }
                }
                ArrayValue array = new ArrayValue(b.array(), new int[] {in.length});
                byte[] out = encode ? codec.encode(array, from, order).data : codec.decode(array, to, order).data;
                ByteBuffer o = ByteBuffer.wrap(out).order(order);
                for (int i = 0; i < in.length; i++) {
                    long got = switch (to.byteCount()) {
                        case 1 -> o.get(i) & 0xffL;
                        case 2 -> o.getShort(2 * i) & 0xffffL;
                        case 4 -> o.getInt(4 * i) & 0xffffffffL;
                        default -> o.getLong(8 * i);
                    };
                    assertEquals(f[i + 6], Long.toHexString(got), String.join(" ", f[1], f[2], f[3], f[4], f[5])
                            + " " + order + " element " + i);
                }
            }
        }

        static long read(byte[] b, int off, int n) {
            long v = 0;
            for (int i = n - 1; i >= 0; i--) {
                v = (v << 8) | (b[off + i] & 0xffL);
            }
            return v;
        }
    }
}
