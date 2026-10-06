package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Falcon's numcodecs filters and checksums against numcodecs 0.17 and NumPy 2 themselves: every vector in
 * {@code numcodecs_filter_vectors.txt} ({@code tools/fixtures/gen_numcodecs_filter_vectors.py}) gives a
 * configuration, the elements numcodecs encoded, what it encoded them to, and what it decodes that back
 * to (or that it refused). Falcon must produce the same bytes both ways, and refuse where numcodecs does.
 */
class NumcodecsVectorsTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final List<JsonObject> VECTORS = load();

    private static List<JsonObject> load() {
        try (InputStream in = NumcodecsVectorsTest.class.getResourceAsStream("/fixtures/numcodecs_filter_vectors.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<JsonObject> out = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    out.add(Json.parse(line.getBytes(StandardCharsets.UTF_8)).asObject());
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"delta", "fixedscaleoffset", "quantize", "bitround", "astype", "packbits", "shuffle",
        "crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3"})
    void matchesNumcodecs(String id) {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (JsonObject vector : VECTORS) {
            Optional<JsonValue> config = vector.find("config");
            if (config.isEmpty() || !config.get().asObject().get("id").asString().equals(id)) {
                continue;
            }
            checked++;
            String failure = check(vector);
            if (failure != null) {
                failures.add(failure + "\n    in " + vector.toJson().substring(0, Math.min(300, vector.toJson().length())));
            }
        }
        assertTrue(checked > 10, id + ": only " + checked + " vectors");
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + checked + " " + id + " vectors differ:\n" + String.join("\n", failures.subList(
                    0, Math.min(8, failures.size()))));
        }
    }

    /** Runs one vector; a description of the difference, or null. */
    private static String check(JsonObject vector) {
        JsonObject config = vector.get("config").asObject();
        Map<String, JsonValue> rest = new LinkedHashMap<>(config.members());
        String name = Numcodecs.PREFIX + rest.remove("id").asString();
        JsonValue input = vector.get("input");
        NumpyType type = input.isNull() ? null : NumpyType.parse(input.asString(), "input");
        BytesBytesCodec codec = Numcodecs.parse(name, new JsonObject(rest), type);

        if (!vector.get("data").isNull()) {
            byte[] data = HEX.parseHex(vector.get("data").asString());
            JsonValue encoded = vector.get("encoded");
            if (encoded.isNull()) {
                try {
                    byte[] out = codec.encode(data);
                    return "encode should fail as numcodecs does, gave " + HEX.formatHex(out);
                } catch (ZarrException expected) {
                    // as numcodecs
                }
            } else {
                byte[] want = HEX.parseHex(encoded.asString());
                byte[] got;
                try {
                    got = codec.encode(data);
                } catch (ZarrException e) {
                    return "encode failed: " + e.getMessage();
                }
                if (!java.util.Arrays.equals(want, got)) {
                    return "encode\n      want " + HEX.formatHex(want) + "\n      got  " + HEX.formatHex(got);
                }
            }
        }
        if (vector.get("encoded").isNull()) {
            return null;
        }
        byte[] encoded = HEX.parseHex(vector.get("encoded").asString());
        JsonValue decoded = vector.find("decoded").orElse(vector.get("data"));
        if (decoded instanceof JsonNull) {
            try {
                byte[] out = codec.decode(encoded, Integer.MAX_VALUE - 8);
                return "decode should fail as numcodecs does, gave " + HEX.formatHex(out);
            } catch (ZarrException expected) {
                return null;
            }
        }
        byte[] want = HEX.parseHex(decoded.asString());
        byte[] got;
        try {
            got = codec.decode(encoded, Integer.MAX_VALUE - 8);
        } catch (ZarrException e) {
            return "decode failed: " + e.getMessage();
        }
        if (!java.util.Arrays.equals(want, got)) {
            return "decode\n      want " + HEX.formatHex(want) + "\n      got  " + HEX.formatHex(got);
        }
        return null;
    }

    /** {@code np.promote_types}, the type {@code np.cumsum} accumulates a delta in, for every pair. */
    @Test
    void promotionIsNumpys() {
        int checked = 0;
        for (JsonObject vector : VECTORS) {
            if (vector.has("promote")) {
                var pair = vector.get("promote").asArray();
                NumpyType a = NumpyType.parse(pair.get(0).asString(), "a");
                NumpyType b = NumpyType.parse(pair.get(1).asString(), "b");
                assertEquals(vector.get("result").asString(), NumpyType.promote(a, b).toString(), a + " with " + b);
                checked++;
            }
        }
        assertEquals(144, checked);
    }

    /** quantize.py's power-of-two scale for each number of digits. */
    @Test
    void quantizeScaleIsNumcodecs() {
        int checked = 0;
        for (JsonObject vector : VECTORS) {
            if (vector.has("quantize_digits")) {
                int digits = vector.get("quantize_digits").asNumber().intValue();
                assertEquals(Double.parseDouble(vector.get("scale").asString()), QuantizeCodec.scale(digits),
                        "digits " + digits);
                checked++;
            }
        }
        assertEquals(61, checked);
    }
}
