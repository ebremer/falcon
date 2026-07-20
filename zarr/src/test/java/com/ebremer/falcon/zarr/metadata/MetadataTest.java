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

    @Test
    void ignoresUnknownFieldWithoutMustUnderstand() {
        ArrayMetadata a = array(VALID_ARRAY.replace(
                "\"fill_value\":0", "\"fill_value\":0,\"future_hint\":{\"whatever\":true}"));
        assertEquals("float64", a.dataType().name()); // parsed fine, unknown field ignored
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
    void nonRegularChunkGridIsUnsupported() {
        assertThrows(ZarrUnsupportedException.class, () -> array(VALID_ARRAY.replace(
                "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2,3]}}",
                "\"chunk_grid\":{\"name\":\"rectilinear\",\"configuration\":{}}")));
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
}
