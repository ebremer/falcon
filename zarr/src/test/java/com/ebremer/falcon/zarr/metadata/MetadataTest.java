package com.ebremer.falcon.zarr.metadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetadataTest {

    private static final String VALID_ARRAY = """
            {"zarr_format":3,"node_type":"array","shape":[4,6],"data_type":"float64",
             "chunk_grid":{"name":"regular","configuration":{"chunk_shape":[2,3]}},
             "chunk_key_encoding":{"name":"default","configuration":{"separator":"/"}},
             "fill_value":0,
             "codecs":[{"name":"bytes","configuration":{"endian":"little"}}]}""";

    private static NodeMetadata parse(String json) {
        return Metadata.parse(json.getBytes(StandardCharsets.UTF_8), "test/zarr.json");
    }

    private static ArrayMetadata array(String json) {
        return (ArrayMetadata) parse(json);
    }

    // ---- groups -----------------------------------------------------------------------------------

    @Test
    void parsesGroupWithAttributes() {
        GroupMetadata g = (GroupMetadata) parse(
                "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{\"title\":\"root\"}}");
        assertEquals(NodeType.GROUP, g.nodeType());
        assertEquals("root", g.attributes().get("title").asString());
    }

    @Test
    void parsesGroupWithoutAttributes() {
        GroupMetadata g = (GroupMetadata) parse("{\"zarr_format\":3,\"node_type\":\"group\"}");
        assertTrue(g.attributes().members().isEmpty());
    }

    // ---- arrays -----------------------------------------------------------------------------------

    @Test
    void parsesFullArrayMetadata() {
        ArrayMetadata a = array(VALID_ARRAY);
        assertEquals(NodeType.ARRAY, a.nodeType());
        assertArrayEquals(new long[] {4, 6}, a.shape());
        assertEquals(2, a.rank());
        assertEquals("float64", a.dataType().name());
        assertArrayEquals(new long[] {2, 3}, a.chunkShape());
        assertArrayEquals(new long[] {2, 2}, a.grid().gridShape()); // ceil([4,6]/[2,3])
        assertEquals("default", a.chunkKeyEncoding().name());
        assertEquals("/", a.separator());
        assertEquals(0, a.fillValue().asNumber().intValue());
        assertEquals(List.of("bytes"), a.codecNames());
        assertTrue(a.attributes().members().isEmpty());
        assertTrue(a.dimensionNames().isEmpty());
    }

    @Test
    void defaultEncodingSeparatorDefaultsToSlash() {
        ArrayMetadata a = array(VALID_ARRAY.replace(
                "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}}",
                "\"chunk_key_encoding\":{\"name\":\"default\"}"));
        assertEquals("/", a.separator());
    }

    @Test
    void v2EncodingSeparatorDefaultsToDot() {
        ArrayMetadata a = array(VALID_ARRAY.replace(
                "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}}",
                "\"chunk_key_encoding\":{\"name\":\"v2\"}"));
        assertEquals("v2", a.chunkKeyEncoding().name());
        assertEquals(".", a.separator());
    }

    @Test
    void fillValueIsKeptRaw() {
        ArrayMetadata a = array(VALID_ARRAY.replace("\"fill_value\":0", "\"fill_value\":\"NaN\""));
        assertInstanceOf(com.ebremer.falcon.zarr.json.JsonString.class, a.fillValue());
        assertEquals("NaN", a.fillValue().asString());
    }

    @Test
    void parsesAttributesAndDimensionNames() {
        ArrayMetadata a = array(VALID_ARRAY.replace(
                "\"fill_value\":0",
                "\"fill_value\":0,\"attributes\":{\"units\":\"K\"},\"dimension_names\":[\"y\",null]"));
        assertEquals("K", a.attributes().get("units").asString());
        List<String> dims = a.dimensionNames().orElseThrow();
        assertEquals("y", dims.get(0));
        assertNull(dims.get(1));
    }

    @Test
    void multipleCodecsKeepPipelineOrder() {
        ArrayMetadata a = array(VALID_ARRAY.replace(
                "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]",
                "\"codecs\":[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},"
                        + "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                        + "{\"name\":\"gzip\",\"configuration\":{\"level\":5}}]"));
        assertEquals(List.of("transpose", "bytes", "gzip"), a.codecNames());
    }

    /**
     * P1 I10: the v3 specification's default is that an unknown member must be understood. Only an object
     * that says {@code "must_understand": false} may be ignored; this test used to assert the opposite.
     */
    @Test
    void anUnknownFieldIsIgnoredOnlyWithMustUnderstandFalse() {
        ArrayMetadata a = array(VALID_ARRAY.replace(
                "\"fill_value\":0", "\"fill_value\":0,\"future_hint\":{\"must_understand\":false,\"x\":1}"));
        assertEquals("float64", a.dataType().name()); // parsed fine, unknown field ignored
        for (String field : new String[] {"{\"whatever\":true}", "1", "\"text\"", "null", "[]",
                "{\"must_understand\":\"false\"}"}) {
            ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class, () -> array(
                    VALID_ARRAY.replace("\"fill_value\":0", "\"fill_value\":0,\"future_hint\":" + field)), field);
            assertTrue(e.getMessage().contains("future_hint"), e.getMessage());
        }
        assertThrows(ZarrUnsupportedException.class, () -> parse(
                "{\"zarr_format\":3,\"node_type\":\"group\",\"future_hint\":{\"whatever\":true}}"));
    }

    /** P1 I10: zarr-python writes consolidated metadata into a group's zarr.json; Falcon may ignore it. */
    @Test
    void consolidatedMetadataInAGroupIsIgnored() {
        for (String consolidated : new String[] {"null",
                "{\"kind\":\"inline\",\"must_understand\":false,\"metadata\":{}}"}) {
            GroupMetadata g = (GroupMetadata) parse("{\"attributes\":{},\"zarr_format\":3,"
                    + "\"consolidated_metadata\":" + consolidated + ",\"node_type\":\"group\"}");
            assertEquals(NodeType.GROUP, g.nodeType());
        }
    }

    // ---- errors -----------------------------------------------------------------------------------

    @Test
    void rejectsUnknownNodeType() {
        assertThrows(ZarrFormatException.class,
                () -> parse("{\"zarr_format\":3,\"node_type\":\"table\"}"));
    }

    @Test
    void rejectsMissingNodeType() {
        assertThrows(ZarrFormatException.class, () -> parse("{\"zarr_format\":3}"));
    }

    @Test
    void zarrFormat2IsUnsupportedNotMalformed() {
        assertThrows(ZarrUnsupportedException.class,
                () -> parse("{\"zarr_format\":2,\"node_type\":\"group\"}"));
    }

    @Test
    void otherZarrFormatIsMalformed() {
        assertThrows(ZarrFormatException.class,
                () -> parse("{\"zarr_format\":9,\"node_type\":\"group\"}"));
    }

    @Test
    void rejectsMissingRequiredArrayField() {
        assertThrows(ZarrFormatException.class,
                () -> array(VALID_ARRAY.replace(",\"data_type\":\"float64\"", "")));
    }

    @Test
    void rejectsChunkRankMismatch() {
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> array(VALID_ARRAY.replace("\"chunk_shape\":[2,3]", "\"chunk_shape\":[2]")));
        assertTrue(e.getMessage().contains("rank"));
    }

    @Test
    void rejectsNegativeShape() {
        assertThrows(ZarrFormatException.class,
                () -> array(VALID_ARRAY.replace("\"shape\":[4,6]", "\"shape\":[4,-6]")));
    }

    @Test
    void rejectsZeroChunkShape() {
        assertThrows(ZarrFormatException.class,
                () -> array(VALID_ARRAY.replace("\"chunk_shape\":[2,3]", "\"chunk_shape\":[2,0]")));
    }

    @Test
    void rejectsNonIntegerShape() {
        assertThrows(ZarrFormatException.class,
                () -> array(VALID_ARRAY.replace("\"shape\":[4,6]", "\"shape\":[4,6.5]")));
    }

    @Test
    void unknownChunkGridIsUnsupported() {
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace(
                "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2,3]}}",
                "\"chunk_grid\":{\"name\":\"variable\",\"configuration\":{}}")));
    }

    @Test
    void objectDataTypeIsUnsupported() {
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace(
                "\"data_type\":\"float64\"", "\"data_type\":{\"name\":\"datetime64\"}")));
    }

    @Test
    void unknownChunkKeyEncodingIsUnsupported() {
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace(
                "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}}",
                "\"chunk_key_encoding\":{\"name\":\"custom\"}")));
    }

    @Test
    void badSeparatorIsMalformed() {
        assertThrows(ZarrFormatException.class, () -> array(VALID_ARRAY.replace(
                "\"separator\":\"/\"", "\"separator\":\"-\"")));
    }

    @Test
    void nonEmptyStorageTransformersUnsupported() {
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace(
                "\"fill_value\":0",
                "\"fill_value\":0,\"storage_transformers\":[{\"name\":\"x\"}]")));
    }

    @Test
    void mustUnderstandUnknownFieldIsUnsupported() {
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace(
                "\"fill_value\":0",
                "\"fill_value\":0,\"weird\":{\"must_understand\":true}")));
    }

    @Test
    void rejectsFillValueIncompatibleWithDataType() {
        // float64 array given a boolean fill value
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> array(VALID_ARRAY.replace("\"fill_value\":0", "\"fill_value\":true")));
        assertTrue(e.getMessage().contains("fill_value"));
        // int16 fill out of range
        assertThrows(ZarrFormatException.class, () -> array(VALID_ARRAY
                .replace("\"data_type\":\"float64\"", "\"data_type\":\"int16\"")
                .replace("\"fill_value\":0", "\"fill_value\":100000")));
    }

    @Test
    void pipelineDecodesAChunk() {
        ArrayMetadata a = array(VALID_ARRAY); // float64, chunk [2,3] = 6 elements, bytes little
        ChunkPipeline pipeline = a.pipeline();
        assertEquals(ByteOrder.LITTLE_ENDIAN, pipeline.elementOrder());

        ByteBuffer buf = ByteBuffer.allocate(6 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : new double[] {1, 2, 3, 4, 5, 6}) {
            buf.putDouble(v);
        }
        byte[] decoded = pipeline.decode(buf.array());
        ByteBuffer out = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : new double[] {1, 2, 3, 4, 5, 6}) {
            assertEquals(v, out.getDouble());
        }
    }

    @Test
    void unsupportedCodecIsDeferredToPipelineBuild() {
        // Parsing succeeds (the node can be described); the failure surfaces on data access.
        ArrayMetadata unknown = array(VALID_ARRAY.replace(
                "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]",
                "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"pcodec\"}]"));
        assertEquals(List.of("bytes", "pcodec"), unknown.codecNames());
        assertThrows(ZarrUnsupportedException.class, unknown::pipeline);

        ArrayMetadata empty = array(VALID_ARRAY.replace(
                "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]", "\"codecs\":[]"));
        assertThrows(ZarrFormatException.class, empty::pipeline);
    }

    @Test
    void unknownDataTypeIsUnsupported() {
        assertThrows(ZarrUnsupportedException.class,
                () -> array(VALID_ARRAY.replace("\"data_type\":\"float64\"", "\"data_type\":\"float128\"")));
    }

    @Test
    void malformedJsonIsFormatError() {
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> parse("{\"zarr_format\":3,"));
        assertTrue(e.getMessage().contains("test/zarr.json"));
    }

    // ---- P1: forms the specification allows, v2 translation, and bounds ------------------------------

    /** P1 I5: a codec, chunk key encoding, or data type with no configuration may be just its name. */
    @Test
    void shorthandNamesAreAccepted() {
        ArrayMetadata a = array(VALID_ARRAY
                .replace("\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]",
                        "\"codecs\":[\"bytes\",{\"name\":\"gzip\",\"configuration\":{\"level\":1}},\"crc32c\"]")
                .replace("\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}}",
                        "\"chunk_key_encoding\":\"default\"")
                .replace("\"data_type\":\"float64\"", "\"data_type\":{\"name\":\"float64\"}"));
        assertEquals(List.of("bytes", "gzip", "crc32c"), a.codecNames());
        assertEquals("/", a.separator());
        assertEquals("float64", a.dataType().name());
        byte[] chunk = new byte[6 * 8];
        chunk[8] = 1;
        assertArrayEquals(chunk, a.pipeline().decode(a.pipeline().encode(chunk, new byte[8])));

        // inside a sharding codec too
        ArrayMetadata sharded = array(VALID_ARRAY.replace(
                "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]",
                "\"codecs\":[{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[1,3],"
                        + "\"codecs\":[\"bytes\"],\"index_codecs\":[\"bytes\",\"crc32c\"]}}]"));
        assertArrayEquals(chunk, sharded.pipeline().decode(sharded.pipeline().encode(chunk, new byte[8])));
    }

    /**
     * P1 I5: an object data type with a configuration is an extension, unsupported unless it is one of the
     * extension types zarr-python writes (P2 F14), such as numpy.datetime64.
     */
    @Test
    void anObjectDataTypeWithAConfigurationIsUnsupportedUnlessFalconImplementsIt() {
        assertEquals(DataType.datetime64("s", 1), array(VALID_ARRAY.replace("\"data_type\":\"float64\"",
                "\"data_type\":{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"s\",\"scale_factor\":1}}"))
                .dataType());
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace("\"data_type\":\"float64\"",
                "\"data_type\":{\"name\":\"float64\",\"configuration\":{\"x\":1}}")));
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace("\"data_type\":\"float64\"",
                "\"data_type\":{\"name\":\"bfloat16\",\"configuration\":{\"x\":1}}")));
        ZarrFormatException bad = assertThrows(ZarrFormatException.class, () -> array(VALID_ARRAY.replace(
                "\"data_type\":\"float64\"",
                "\"data_type\":{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"x\",\"scale_factor\":1}}")));
        assertTrue(bad.getMessage().contains("data_type"), bad.getMessage());
        assertThrows(ZarrFormatException.class, () -> array(VALID_ARRAY.replace("\"data_type\":\"float64\"",
                "\"data_type\":7")));
    }

    /** zarr-python reads "dimension_names": null as no names; so does Falcon. */
    @Test
    void nullDimensionNamesMeanNone() {
        ArrayMetadata a = array(VALID_ARRAY.replace("\"fill_value\":0", "\"fill_value\":0,\"dimension_names\":null"));
        assertTrue(a.dimensionNames().isEmpty());
    }

    /** No JsonException escapes metadata parsing raw: a member of the wrong type is a format error. */
    @Test
    void wrongJsonTypesAreFormatErrors() {
        assertThrows(ZarrFormatException.class, () -> parse("{\"zarr_format\":3.5,\"node_type\":\"group\"}"));
        assertThrows(ZarrFormatException.class, () -> parse("{\"zarr_format\":\"3\",\"node_type\":\"group\"}"));
        assertThrows(ZarrFormatException.class, () -> array(VALID_ARRAY.replace(
                "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]", "\"codecs\":[7]")));
        // P1 I13: a repeated key, or bytes that are not UTF-8, are malformed metadata
        assertThrows(ZarrFormatException.class,
                () -> parse("{\"zarr_format\":3,\"node_type\":\"group\",\"node_type\":\"array\"}"));
        byte[] latin1 = "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{\"t\":\"é\"}}"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(ZarrFormatException.class, () -> Metadata.parse(latin1, "test/zarr.json"));
    }

    /**
     * P1 H3: {@code "fill_value": 1e20000000}, a few bytes of JSON, was expanded into a 20-million-digit
     * integer: 25.7 s to open, and a 20 MB exception message.
     */
    @Test
    void anAbsurdExponentFailsFastWithAShortMessage() {
        for (String fill : new String[] {"1e20000000", "-1e20000000", "1e-20000000", "1e99999999999", "0e-20000000"}) {
            String json = VALID_ARRAY.replace("\"data_type\":\"float64\"", "\"data_type\":\"int32\"")
                    .replace("\"fill_value\":0", "\"fill_value\":" + fill);
            long start = System.nanoTime();
            if (fill.startsWith("0e")) {
                assertEquals(0, array(json).fillValue().asNumber().bigIntegerValue().signum());
            } else {
                ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> array(json), fill);
                assertTrue(e.getMessage().length() < 300, e.getMessage());
            }
            assertTrue(System.nanoTime() - start < 2_000_000_000L, fill + " took too long");
        }
        // a float type takes it as the double it rounds to
        assertTrue(Double.isInfinite(array(VALID_ARRAY.replace("\"fill_value\":0", "\"fill_value\":1e20000000"))
                .fillValue().asNumber().doubleValue()));
    }

    // ---- P1 I6: v2 translation ---------------------------------------------------------------------------

    private static final String V2_ARRAY = """
            {"zarr_format":2,"shape":[2,2],"chunks":[1,2],"dtype":"<i4","fill_value":0,"order":"C",
             "filters":null,"compressor":null,"dimension_separator":"/"}""";

    private static ArrayMetadata v2(String zarray) {
        return V2Metadata.parseArray(zarray.getBytes(StandardCharsets.UTF_8), null, "a/.zarray");
    }

    /** P1 I6: "dimension_separator": null (zarr-python 2 wrote it) is the default ".", not an error. */
    @Test
    void v2NullDimensionSeparatorIsTheDefault() {
        assertEquals(".", v2(V2_ARRAY.replace("\"dimension_separator\":\"/\"", "\"dimension_separator\":null"))
                .separator());
        assertEquals(".", v2(V2_ARRAY.replace(",\"dimension_separator\":\"/\"", "")).separator());
        assertEquals("/", v2(V2_ARRAY).separator());
    }

    /** P1 I6: a non-numeric gzip level leaked a raw JsonException, not a ZarrException. */
    @Test
    void v2NonNumericGzipLevelIsAFormatError() {
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> v2(V2_ARRAY.replace(
                "\"compressor\":null", "\"compressor\":{\"id\":\"gzip\",\"level\":\"high\"}")));
        assertTrue(e.getMessage().contains("level"), e.getMessage());
        assertEquals(List.of("bytes", "gzip"), v2(V2_ARRAY.replace(
                "\"compressor\":null", "\"compressor\":{\"id\":\"gzip\",\"level\":3}")).codecNames());
    }

    /**
     * P1 I6: a complex dtype with "fill_value": null was translated to 0, which then failed the
     * two-element check. A v2 null fill means unwritten chunks read as zeros, as zarr-python reads them.
     */
    @Test
    void v2ComplexNullFillIsZero() {
        for (String dtype : new String[] {"<c8", "<c16", ">c16"}) {
            ArrayMetadata a = v2(V2_ARRAY.replace("\"<i4\"", "\"" + dtype + "\"").replace("\"fill_value\":0",
                    "\"fill_value\":null"));
            assertArrayEquals(new byte[a.dataType().byteCount()], a.fillValueBytes(ByteOrder.LITTLE_ENDIAN), dtype);
        }
        ArrayMetadata pair = v2(V2_ARRAY.replace("\"<i4\"", "\"<c8\"").replace("\"fill_value\":0",
                "\"fill_value\":[1.0,2.0]"));
        ByteBuffer fill = ByteBuffer.wrap(pair.fillValueBytes(ByteOrder.LITTLE_ENDIAN)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.0f, fill.getFloat());
        assertEquals(2.0f, fill.getFloat());
    }

    /** P1 I1 + I6: zarr-python writes bare NaN and Infinity into .zattrs and zarr.json attributes. */
    @Test
    void attributesMayHoldNaNAndInfinity() {
        ArrayMetadata a = V2Metadata.parseArray(V2_ARRAY.getBytes(StandardCharsets.UTF_8),
                "{\"_FillValue\": NaN, \"valid_max\": Infinity, \"valid_min\": -Infinity}"
                        .getBytes(StandardCharsets.UTF_8), "a/.zarray");
        assertTrue(Double.isNaN(a.attributes().get("_FillValue").asNumber().doubleValue()));
        assertEquals(Double.POSITIVE_INFINITY, a.attributes().get("valid_max").asNumber().doubleValue());
        assertEquals(Double.NEGATIVE_INFINITY, a.attributes().get("valid_min").asNumber().doubleValue());
        GroupMetadata g = (GroupMetadata) parse(
                "{\"attributes\":{\"title\":\"t\",\"nan\":NaN},\"zarr_format\":3,\"node_type\":\"group\"}");
        assertTrue(Double.isNaN(g.attributes().get("nan").asNumber().doubleValue()));
        // a v3 float fill written as a bare token decodes too
        ArrayMetadata f = array(VALID_ARRAY.replace("\"fill_value\":0", "\"fill_value\":NaN"));
        assertTrue(Double.isNaN(ByteBuffer.wrap(f.fillValueBytes(ByteOrder.LITTLE_ENDIAN))
                .order(ByteOrder.LITTLE_ENDIAN).getDouble()));
    }
}
