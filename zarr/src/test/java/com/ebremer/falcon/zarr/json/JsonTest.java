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
}
