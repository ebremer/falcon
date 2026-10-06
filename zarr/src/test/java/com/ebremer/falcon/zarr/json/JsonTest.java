package com.ebremer.falcon.zarr.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesEachValueType() {
        assertInstanceOf(JsonObject.class, Json.parse("{}"));
        assertInstanceOf(JsonArray.class, Json.parse("[]"));
        assertInstanceOf(JsonString.class, Json.parse("\"hi\""));
        assertInstanceOf(JsonNumber.class, Json.parse("42"));
        assertInstanceOf(JsonBool.class, Json.parse("true"));
        assertTrue(Json.parse("null").isNull());
    }

    @Test
    void compactWriteIsByteStableRoundTrip() {
        String doc = "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}";
        assertEquals(doc, Json.write(Json.parse(doc)));
    }

    @Test
    void writeStripsInsignificantWhitespace() {
        String pretty = "{  \"a\" : [ 1 , 2 ] ,\n \"b\" : true }";
        assertEquals("{\"a\":[1,2],\"b\":true}", Json.write(Json.parse(pretty)));
    }

    @Test
    void objectMemberOrderIsPreserved() {
        String doc = "{\"c\":1,\"a\":2,\"b\":3}";
        assertEquals(doc, Json.write(Json.parse(doc)));
    }

    @Test
    void numberLiteralsArePreservedExactly() {
        assertEquals("100", Json.write(Json.parse("100")));
        assertEquals("1.5", Json.write(Json.parse("1.5")));
        assertEquals("-0", Json.write(Json.parse("-0")));
        assertEquals("6.022e23", Json.write(Json.parse("6.022e23")));
        assertEquals("1E-10", Json.write(Json.parse("1E-10")));
    }

    @Test
    void numberAccessors() {
        assertEquals(100, ((JsonNumber) Json.parse("100")).intValue());
        assertEquals(100L, ((JsonNumber) Json.parse("1e2")).longValue());
        assertEquals(1.5, ((JsonNumber) Json.parse("1.5")).doubleValue());
        assertEquals(Long.MAX_VALUE, ((JsonNumber) Json.parse("9223372036854775807")).longValue());
    }

    @Test
    void uint64BeyondLongUsesBigInteger() {
        JsonNumber n = (JsonNumber) Json.parse("18446744073709551615");
        assertEquals(new BigInteger("18446744073709551615"), n.bigIntegerValue());
        assertThrows(JsonException.class, n::longValue);
    }

    @Test
    void fractionalIsNotAnInteger() {
        assertThrows(JsonException.class, () -> ((JsonNumber) Json.parse("1.5")).longValue());
    }

    @Test
    void numberFactories() {
        assertEquals("7", JsonNumber.of(7L).literal());
        assertEquals(1.5, JsonNumber.of(1.5).doubleValue());
        assertThrows(JsonException.class, () -> JsonNumber.of(Double.NaN));
        assertThrows(JsonException.class, () -> JsonNumber.of(Double.POSITIVE_INFINITY));
    }

    @Test
    void stringEscapesAreParsed() {
        assertEquals("a\"b\\c/d\b\f\n\r\te",
                ((JsonString) Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\te\"")).value());
        assertEquals("é☃", ((JsonString) Json.parse("\"\\u00e9\\u2603\"")).value());
    }

    @Test
    void stringEscapesAreWritten() {
        // Only mandatory characters are escaped; '/' and non-ASCII are emitted raw.
        assertEquals("\"a\\\"b\\\\c/d\\n\"", Json.write(new JsonString("a\"b\\c/d\n")));
        assertEquals("\"é\"", Json.write(new JsonString("é")));
        assertEquals("\"\\u0001\"", Json.write(new JsonString("")));
    }

    @Test
    void specialFloatStringsRoundTripAsStrings() {
        // Zarr encodes non-finite fill values as JSON strings, not JSON numbers.
        for (String token : new String[] {"NaN", "Infinity", "-Infinity"}) {
            String doc = "{\"fill_value\":\"" + token + "\"}";
            JsonValue parsed = Json.parse(doc);
            assertEquals(token, parsed.asObject().get("fill_value").asString());
            assertEquals(doc, Json.write(parsed));
        }
    }

    @Test
    void typedAccessorsRejectWrongType() {
        assertThrows(JsonException.class, () -> Json.parse("42").asObject());
        assertThrows(JsonException.class, () -> Json.parse("\"s\"").asArray());
        assertThrows(JsonException.class, () -> Json.parse("[]").asString());
        assertThrows(JsonException.class, () -> Json.parse("true").asNumber());
        assertThrows(JsonException.class, () -> Json.parse("{}").asBoolean());
    }

    @Test
    void objectLookup() {
        JsonObject o = Json.parse("{\"a\":1,\"b\":2}").asObject();
        assertTrue(o.has("a"));
        assertFalse(o.has("z"));
        assertEquals(2, o.get("b").asNumber().intValue());
        assertTrue(o.find("z").isEmpty());
        assertThrows(JsonException.class, () -> o.get("z"));
    }

    @Test
    void arrayAccess() {
        JsonArray a = Json.parse("[10,20,30]").asArray();
        assertEquals(3, a.size());
        assertEquals(20, a.get(1).asNumber().intValue());
    }

    @Test
    void utf8BytesAreParsed() {
        byte[] bytes = "{\"k\":\"café\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("café", Json.parse(bytes).asObject().get("k").asString());
    }

    @Test
    void prettyPrinting() {
        String expected = "{\n  \"a\": {\n    \"b\": 1\n  },\n  \"c\": [\n    1,\n    2\n  ]\n}";
        assertEquals(expected, Json.writePretty(Json.parse("{\"a\":{\"b\":1},\"c\":[1,2]}")));
        assertEquals("{}", Json.writePretty(Json.parse("{}")));
        assertEquals("[]", Json.writePretty(Json.parse("[]")));
    }

    @Test
    void builderProducesOrderedObject() {
        JsonObject o = JsonObject.builder()
                .put("zarr_format", 3)
                .put("node_type", "group")
                .put("attributes", new JsonObject(java.util.Map.of()))
                .build();
        assertEquals("{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}", Json.write(o));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(JsonException.class, () -> Json.parse(""));
        assertThrows(JsonException.class, () -> Json.parse("{"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonException.class, () -> Json.parse("\"bad\\xescape\""));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("1.5.6"));
        assertThrows(JsonException.class, () -> Json.parse("truthy"));
        assertThrows(JsonException.class, () -> Json.parse("{}extra"));
        assertThrows(JsonException.class, () -> Json.parse("{1:2}"));
    }

    @Test
    void deeplyNestedInputIsRejectedNotCrashed() {
        assertThrows(JsonException.class, () -> Json.parse("[".repeat(10_000)));
    }

    @Test
    void representativeArrayMetadataRoundTrips() {
        String doc = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[100,100],"
                + "\"data_type\":\"float64\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[10,10]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                + "\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]}";
        assertEquals(doc, Json.write(Json.parse(doc)));
    }

    // ---- P1 ---------------------------------------------------------------------------------------

    /**
     * P1 I1: Python's json.dumps writes the bare tokens NaN, Infinity, and -Infinity (zarr-python does, in
     * attributes and v2 .zattrs). They are read as numbers and written back as they came.
     */
    @Test
    void pythonsNonFiniteTokensAreReadAndWrittenBack() {
        String doc = "{\"a\":NaN,\"b\":Infinity,\"c\":-Infinity,\"d\":[NaN,1]}";
        JsonObject o = Json.parse(doc).asObject();
        assertTrue(Double.isNaN(o.get("a").asNumber().doubleValue()));
        assertEquals(Double.POSITIVE_INFINITY, o.get("b").asNumber().doubleValue());
        assertEquals(Double.NEGATIVE_INFINITY, o.get("c").asNumber().doubleValue());
        assertFalse(o.get("a").asNumber().isFinite());
        assertTrue(o.get("d").asArray().get(1).asNumber().isFinite());
        assertEquals(doc, Json.write(Json.parse(doc)));
        assertThrows(JsonException.class, () -> o.get("a").asNumber().longValue());
        assertThrows(JsonException.class, () -> o.get("b").asNumber().bigIntegerValue());
        // only the exact tokens
        for (String bad : new String[] {"nan", "NaNa", "Inf", "-Inf", "+Infinity", "-NaN", "Infinityx"}) {
            assertThrows(JsonException.class, () -> Json.parse(bad), bad);
        }
        // Falcon's own numbers stay finite: JsonNumber.of refuses non-finite values
        assertThrows(JsonException.class, () -> JsonNumber.of(Double.POSITIVE_INFINITY));
    }

    /** P1 I13: a lone surrogate was written as '?' once encoded as UTF-8; it is now a \\u escape. */
    @Test
    void aLoneSurrogateSurvivesWriteAndRead() {
        for (String text : new String[] {"a\uD800b", "\uDC00", "x\uD83D", "\uDE00\uD83D"}) {
            JsonString value = new JsonString(text);
            byte[] bytes = Json.writeBytes(value);
            assertEquals(text, Json.parse(bytes).asString(), "lone surrogate in " + Json.write(value));
        }
        // a proper pair stays raw UTF-8
        assertEquals("\"\uD83D\uDE00\"", Json.write(new JsonString("\uD83D\uDE00")));
        assertEquals("\"\\ud800\"", Json.write(new JsonString("\uD800")));
    }

    /** P1 I13: a repeated key was silently last-wins, so one document read two ways. */
    @Test
    void aRepeatedKeyIsAnError() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,\"b\":2,\"a\":3}"));
        assertTrue(e.getMessage().contains("duplicate key \"a\""), e.getMessage());
        assertThrows(JsonException.class, () -> Json.parse("{\"x\":{\"k\":1,\"k\":1}}"));
        assertEquals(2, Json.parse("{\"a\":{\"k\":1},\"b\":{\"k\":1}}").asObject().members().size());
    }

    /** P1 I13: invalid UTF-8 was replaced with U+FFFD, changing the text silently. */
    @Test
    void invalidUtf8IsAnError() {
        byte[][] bad = {
            {'"', (byte) 0xff, '"'},                  // never valid
            {'"', (byte) 0xc3, '"'},                  // truncated two-byte sequence
            {'"', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '"'}, // an encoded surrogate
            {'"', (byte) 0xc0, (byte) 0xaf, '"'},     // overlong '/'
        };
        for (byte[] bytes : bad) {
            JsonException e = assertThrows(JsonException.class, () -> Json.parse(bytes));
            assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
        }
        assertEquals("é", Json.parse(new byte[] {'"', (byte) 0xc3, (byte) 0xa9, '"'}).asString());
    }

    /** P1 I13: new JsonNumber("1.2.3") was accepted and then written as invalid JSON. */
    @Test
    void aNumberLiteralMustBeAJsonNumber() {
        for (String bad : new String[] {"1.2.3", "", "01", "+1", "1.", ".5", "1e", "0x10", "1 ", "--1", "nan"}) {
            assertThrows(JsonException.class, () -> new JsonNumber(bad), bad);
        }
        for (String good : new String[] {"0", "-0", "1.5", "-12.5e-3", "1E+9", "NaN", "Infinity", "-Infinity"}) {
            assertEquals(good, new JsonNumber(good).literal());
        }
    }

    /**
     * P1 H3: bigIntegerValue() built the whole integer before any check, so 1e20000000 took 25 s and a
     * 20 MB message. An integer of more than MAX_INTEGER_DIGITS digits is refused first, in a short message.
     */
    @Test
    void anAbsurdIntegerIsRefusedQuickly() {
        long start = System.nanoTime();
        for (String literal : new String[] {"1e20000000", "-7e20000000", "1e-20000000", "2e99999999999"}) {
            JsonException e = assertThrows(JsonException.class, () -> new JsonNumber(literal).bigIntegerValue());
            assertTrue(e.getMessage().length() < 200, e.getMessage());
            assertThrows(JsonException.class, () -> new JsonNumber(literal).longValue());
        }
        assertTrue(System.nanoTime() - start < 2_000_000_000L);
        String longLiteral = "9".repeat(5000);
        JsonException e = assertThrows(JsonException.class, () -> new JsonNumber(longLiteral).bigIntegerValue());
        assertTrue(e.getMessage().length() < 200, e.getMessage());
        assertEquals(BigInteger.TEN.pow(JsonNumber.MAX_INTEGER_DIGITS - 1),
                new JsonNumber("1e" + (JsonNumber.MAX_INTEGER_DIGITS - 1)).bigIntegerValue());
        assertEquals(BigInteger.valueOf(120), new JsonNumber("1.2e2").bigIntegerValue());
        assertEquals(0, new JsonNumber("0e-20000000").bigIntegerValue().signum());
        assertEquals(Long.MAX_VALUE, new JsonNumber("9223372036854775807").longValue());
        assertThrows(JsonException.class, () -> new JsonNumber("9223372036854775808").longValue());
    }
}
