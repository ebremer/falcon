package com.ebremer.falcon.zarr.metadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataType.Field;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2 F4: the translation of Zarr v2 metadata into the v3 model ({@link V2Metadata#translate}), rule by
 * rule: dtypes, fill values, Fortran order, filters, and compressors, with what zarr-python 3.4 reads for
 * the same {@code .zarray} as the reference (see {@code gen_zarr_v2_ext_fixtures.py} for whole arrays).
 */
class V2MetadataTest {

    /** A one-dimensional array of {@code dtype}; {@code extra} members replace the defaults. */
    private static String zarray(String dtype, String extra) {
        String base = "{\"zarr_format\":2,\"shape\":[4],\"chunks\":[2],\"dtype\":" + dtype + ",\"fill_value\":null,"
                + "\"order\":\"C\",\"filters\":null,\"compressor\":null,\"dimension_separator\":\".\"}";
        if (extra.isEmpty()) {
            return base;
        }
        JsonObject.Builder b = JsonObject.builder();
        JsonObject changes = Json.parse("{" + extra + "}").asObject();
        Json.parse(base).asObject().members().forEach((k, v) -> b.put(k, changes.find(k).orElse(v)));
        return b.build().toJson();
    }

    /** The translated v3 document, as JSON text without spaces. */
    private static JsonObject v3(String zarray) {
        return V2Metadata.translate(Json.parse(zarray).asObject(), null, "a/.zarray");
    }

    private static ArrayMetadata parse(String zarray) {
        return V2Metadata.parseArray(Json.parse(zarray), null, "a/.zarray");
    }

    private static String codecs(String zarray) {
        return v3(zarray).get("codecs").toJson();
    }

    private static String fill(String zarray) {
        return v3(zarray).get("fill_value").toJson();
    }

    // ---- dtypes ------------------------------------------------------------------------------------

    @Test
    void eachDtypeIsAV3DataTypeWithItsByteOrderInTheBytesCodec() {
        record Row(String dtype, DataType type, String bytes) {
        }
        String le = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}";
        String be = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}";
        String none = "{\"name\":\"bytes\"}";
        for (Row r : List.of(
                new Row("\"<i4\"", DataType.INT32, le), new Row("\">f8\"", DataType.FLOAT64, be),
                new Row("\"|u1\"", DataType.UINT8, none), new Row("\"|b1\"", DataType.BOOL, none),
                new Row("\"<U5\"", DataType.fixedLengthUtf32(5), le), new Row("\">U1\"", DataType.fixedLengthUtf32(1), be),
                new Row("\"|S7\"", DataType.nullTerminatedBytes(7), none), new Row("\"|V3\"", DataType.rawBytes(3), none),
                new Row("\"<M8[ns]\"", DataType.datetime64("ns", 1), le),
                new Row("\">M8[10s]\"", DataType.datetime64("s", 10), be),
                new Row("\"<m8[25us]\"", DataType.timedelta64("us", 25), le),
                new Row("\"<m8[μs]\"", DataType.timedelta64("μs", 1), le),
                new Row("\"<M8\"", DataType.datetime64("generic", 1), le),
                new Row("[[\"a\",\"<i4\"],[\"b\",\"|S2\"]]",
                        DataType.struct(new Field("a", DataType.INT32), new Field("b", DataType.nullTerminatedBytes(2))), le),
                new Row("[[\"a\",\">i2\"],[\"p\",[[\"x\",\">f4\"],[\"y\",\"|u1\"]]]]", DataType.struct(
                        new Field("a", DataType.INT16), new Field("p", DataType.struct(
                                new Field("x", DataType.FLOAT32), new Field("y", DataType.UINT8)))), be),
                new Row("[[\"a\",\"|u1\"],[\"b\",\"|S2\"]]",
                        DataType.struct(new Field("a", DataType.UINT8), new Field("b", DataType.nullTerminatedBytes(2))), none))) {
            ArrayMetadata a = parse(zarray(r.dtype, ""));
            assertEquals(r.type, a.dataType(), r.dtype);
            assertEquals("[" + r.bytes + "]", codecs(zarray(r.dtype, "")), r.dtype);
        }
    }

    @Test
    void objectArraysTakeTheirTypeFromTheirObjectCodec() {
        String utf8 = zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-utf8\"}]");
        assertEquals(DataType.STRING, parse(utf8).dataType());
        assertEquals("[{\"name\":\"vlen-utf8\"}]", codecs(utf8));
        String bytes = zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-bytes\"},{\"id\":\"crc32\"}]");
        assertEquals(DataType.BYTES, parse(bytes).dataType());
        assertEquals("[{\"name\":\"vlen-bytes\"},{\"name\":\"numcodecs.crc32\",\"configuration\":{}}]", codecs(bytes));

        for (String id : List.of("vlen-array", "json2", "msgpack2", "pickle")) {
            ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class,
                    () -> v3(zarray("\"|O\"", "\"filters\":[{\"id\":\"" + id + "\"}]")));
            assertTrue(e.getMessage().contains("object codec '" + id + "'"), e.getMessage());
        }
        ZarrUnsupportedException cat = assertThrows(ZarrUnsupportedException.class, () -> v3(zarray("\"|O\"",
                "\"filters\":[{\"id\":\"categorize\",\"labels\":[\"a\"],\"dtype\":\"|O\",\"astype\":\"|u1\"}]")));
        assertTrue(cat.getMessage().contains("'categorize'"), cat.getMessage());
        assertThrows(ZarrFormatException.class, () -> v3(zarray("\"|O\"", "")));       // no object codec
        assertThrows(ZarrFormatException.class, () -> v3(zarray("\"|O\"", "\"filters\":[]")));
        // an object codec anywhere else is a mistake
        assertThrows(ZarrFormatException.class, () -> v3(zarray("\"<U3\"", "\"filters\":[{\"id\":\"vlen-utf8\"}]")));
        assertThrows(ZarrFormatException.class,
                () -> v3(zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-utf8\"},{\"id\":\"vlen-utf8\"}]")));
    }

    @Test
    void dtypesFalconCannotReadAreRefused() {
        for (String dtype : List.of("\"<i16\"", "\"<f16\"", "\"|U0\"", "\"|S0\"", "\"<c32\"", "\"<x4\"",
                "[[\"a\",\"<i4\",[2]]]", "[[\"a\",\">i4\"],[\"b\",\"<f8\"]]", "[[\"o\",\"|O\"]]")) {
            assertThrows(ZarrUnsupportedException.class, () -> v3(zarray(dtype, "")), dtype);
        }
        // the structured dtype's message says which rule it breaks
        assertTrue(assertThrows(ZarrUnsupportedException.class, () -> v3(zarray("[[\"a\",\">i4\"],[\"b\",\"<f8\"]]", "")))
                .getMessage().contains("mixes little- and big-endian"));
        assertTrue(assertThrows(ZarrUnsupportedException.class, () -> v3(zarray("[[\"a\",\"<i4\",[2]]]", "")))
                .getMessage().contains("subarray"));
        for (String dtype : List.of("\"i4\"", "\"<\"", "\"<M8[sec]\"", "\"<M8[0s]\"", "\"<M8(s)\"", "[]",
                "[[\"a\"]]", "[[\"\",\"<i4\"]]", "[[\"a\",\"<i4\"],[\"a\",\"<i4\"]]", "4")) {
            assertThrows(ZarrFormatException.class, () -> v3(zarray(dtype, "")), dtype);
        }
    }

    // ---- fill values --------------------------------------------------------------------------------

    /** zarr-python 3.4 reads a null fill as the type's default scalar; Falcon reads the same element. */
    @Test
    void aNullFillIsTheTypesDefault() {
        assertEquals("\"\"", fill(zarray("\"<U3\"", "")));
        assertEquals("\"\"", fill(zarray("\"|S3\"", "")));
        assertEquals("\"AAAA\"", fill(zarray("\"|V3\"", "")));
        assertEquals("-9223372036854775808", fill(zarray("\"<M8[s]\"", "")));
        assertEquals("-9223372036854775808", fill(zarray("\"<m8[s]\"", "")));
        assertEquals("{\"a\":0,\"b\":0.0}", fill(zarray("[[\"a\",\"<i4\"],[\"b\",\"<f8\"]]", "")));
        assertEquals("\"\"", fill(zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-utf8\"}]")));
        assertEquals("\"\"", fill(zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-bytes\"}]")));
        assertEquals("false", fill(zarray("\"|b1\"", "")));
        assertEquals("0", fill(zarray("\"<f4\"", "")));
    }

    @Test
    void fillValuesAreReadInEachTypesV2Form() {
        assertEquals("\"hé\"", fill(zarray("\"<U3\"", "").replace("\"fill_value\":null", "\"fill_value\":\"hé\"")));
        assertEquals("\"cQA=\"", fill(zarray("\"|S3\"", "").replace("null,\"order", "\"cQA=\",\"order")));
        assertEquals("5", fill(zarray("\"<M8[s]\"", "").replace("null,\"order", "5,\"order")));
        assertEquals("\"NaT\"", fill(zarray("\"<M8[s]\"", "").replace("null,\"order", "\"NaT\",\"order")));
        // zarr-python 2 wrote numbers for string fills, and zarr-python 3 reads them as their text
        String utf8 = zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-utf8\"}]");
        assertEquals("\"0\"", fill(utf8.replace("null,\"order", "0,\"order")));
        assertEquals("\"1.5\"", fill(utf8.replace("null,\"order", "1.5,\"order")));
        assertEquals("\"True\"", fill(utf8.replace("null,\"order", "true,\"order")));
        // zarr-python 2's default 0 for byte strings is no bytes (zarr-python 3 refuses it); base64 otherwise
        String bytes = zarray("\"|O\"", "\"filters\":[{\"id\":\"vlen-bytes\"}]");
        assertEquals("\"\"", fill(bytes.replace("null,\"order", "0,\"order")));
        assertEquals("\"eHk=\"", fill(bytes.replace("null,\"order", "\"eHk=\",\"order")));
        assertThrows(ZarrFormatException.class, () -> parse(bytes.replace("null,\"order", "1,\"order")));
        // as zarr-python, a number is no fill for U, S, or V
        for (String dtype : List.of("\"<U3\"", "\"|S3\"", "\"|V3\"")) {
            assertThrows(ZarrFormatException.class, () -> parse(zarray(dtype, "").replace("null,\"order", "0,\"order")));
        }
    }

    /**
     * A structured fill is base64 of the element as numpy stores it, in the dtype's byte order; it becomes
     * the v3 object form so that the {@code bytes} codec's order does not reinterpret it.
     */
    @Test
    void aStructuredFillIsTheElementInTheDtypesByteOrder() {
        String be = zarray("[[\"a\",\">i4\"],[\"b\",\">f8\"]]", "")
                .replace("null,\"order", "\"AAAABz/4AAAAAAAA\",\"order"); // zarr-python's (7, 1.5)
        assertEquals("{\"a\":7,\"b\":1.5}", fill(be));
        ArrayMetadata a = parse(be);
        assertArrayEquals(new byte[] {0, 0, 0, 7, 0x3f, (byte) 0xf8, 0, 0, 0, 0, 0, 0}, a.fillValueBytes(ByteOrder.BIG_ENDIAN));
        String le = zarray("[[\"a\",\"<i4\"],[\"b\",\"<f8\"]]", "")
                .replace("null,\"order", "\"BwAAAAAAAAAAAPg/\",\"order");
        assertEquals("{\"a\":7,\"b\":1.5}", fill(le));
        // zarr-python 3's dict form passes through; a wrong length or bad base64 is malformed
        assertEquals("{\"a\":3}", fill(zarray("[[\"a\",\"<i4\"]]", "").replace("null,\"order", "{\"a\":3},\"order")));
        assertThrows(ZarrFormatException.class, () -> v3(le.replace("BwAAAAAAAAAAAPg/", "AAAA")));
        assertThrows(ZarrFormatException.class, () -> v3(le.replace("BwAAAAAAAAAAAPg/", "!!")));
    }

    // ---- order --------------------------------------------------------------------------------------

    @Test
    void fortranOrderIsATransposeReversingTheAxes() {
        String f2 = "{\"zarr_format\":2,\"shape\":[4,6],\"chunks\":[3,4],\"dtype\":\"<i4\",\"fill_value\":0,"
                + "\"order\":\"F\",\"filters\":null,\"compressor\":null}";
        assertEquals("[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},"
                + "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]", codecs(f2));
        String f3 = f2.replace("[4,6]", "[2,3,4]").replace("[3,4]", "[1,2,3]");
        assertTrue(codecs(f3).startsWith("[{\"name\":\"transpose\",\"configuration\":{\"order\":[2,1,0]}}"));
        String fStr = f2.replace("\"<i4\"", "\"|O\"").replace("\"fill_value\":0", "\"fill_value\":\"\"")
                .replace("\"filters\":null", "\"filters\":[{\"id\":\"vlen-utf8\"}]");
        assertEquals("[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},{\"name\":\"vlen-utf8\"}]",
                codecs(fStr));
        // one dimension, or none, is the same in either order
        assertEquals("[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]",
                codecs(zarray("\"<i4\"", "").replace("\"C\"", "\"F\"")));
        assertEquals("[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]",
                codecs(f2.replace("[4,6]", "[]").replace("[3,4]", "[]")));
        assertThrows(ZarrFormatException.class, () -> v3(f2.replace("\"F\"", "\"K\"")));
    }

    // ---- filters and compressors ----------------------------------------------------------------------

    @Test
    void filtersAndOtherCompressorsAreNumcodecsCodecsInOrder() {
        String z = zarray("\"<i4\"", "\"filters\":[{\"id\":\"delta\",\"dtype\":\"<i4\",\"astype\":\"<i2\"},"
                + "{\"id\":\"jenkins_lookup3\",\"initval\":7,\"prefix\":null}],"
                + "\"compressor\":{\"id\":\"zlib\",\"level\":6}").replace("\"filters\":null,\"compressor\":null,", "");
        assertEquals("[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                + "{\"name\":\"numcodecs.delta\",\"configuration\":{\"dtype\":\"<i4\",\"astype\":\"<i2\"}},"
                + "{\"name\":\"numcodecs.jenkins_lookup3\",\"configuration\":{\"initval\":7,\"prefix\":null}},"
                + "{\"name\":\"numcodecs.zlib\",\"configuration\":{\"level\":6}}]", codecs(z));
        assertEquals(List.of("bytes", "numcodecs.delta", "numcodecs.jenkins_lookup3", "numcodecs.zlib"),
                parse(z).codecNames());
        for (String id : List.of("delta", "fixedscaleoffset", "quantize", "bitround", "astype", "packbits", "shuffle",
                "crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3", "zlib", "lz4", "bz2", "zfpy")) {
            assertEquals("numcodecs." + id, parse(compressed("{\"id\":\"" + id + "\"}")).codecNames().get(1), id);
        }
    }

    @Test
    void bz2AndZfpyKeepNumcodecsSettings() {
        assertEquals("{\"name\":\"numcodecs.bz2\",\"configuration\":{\"level\":9}}",
                compressorOf(compressed("{\"id\":\"bz2\",\"level\":9}")));
        assertEquals("{\"name\":\"numcodecs.bz2\",\"configuration\":{}}", compressorOf(compressed("{\"id\":\"bz2\"}")));
        // zfpy stays after the bytes codec, as a v2 compressor: zarr-python 3 hands it the chunk's elements there
        String zfpy = compressed("{\"id\":\"zfpy\",\"mode\":2,\"compression_kwargs\":{\"rate\":8},\"tolerance\":-1,"
                + "\"rate\":8,\"precision\":-1}").replace("\"<i4\"", "\"<f8\"");
        assertEquals("[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"numcodecs.zfpy\","
                + "\"configuration\":{\"mode\":2,\"compression_kwargs\":{\"rate\":8},\"tolerance\":-1,\"rate\":8,"
                + "\"precision\":-1}}]", codecs(zfpy));
        // and among the filters, as numcodecs allows any codec there
        String filtered = zarray("\"<i4\"", "").replace("\"filters\":null", "\"filters\":[{\"id\":\"bz2\",\"level\":3}]")
                .replace("\"compressor\":null", "\"compressor\":{\"id\":\"zlib\",\"level\":1}");
        assertEquals(List.of("bytes", "numcodecs.bz2", "numcodecs.zlib"), parse(filtered).codecNames());
    }

    @Test
    void codecsFalconCannotReadAreRefusedByName() {
        for (String id : List.of("lzma", "pcodec", "base64", "categorize", "zfp", "bz")) {
            ZarrUnsupportedException c = assertThrows(ZarrUnsupportedException.class,
                    () -> v3(compressed("{\"id\":\"" + id + "\"}")));
            assertTrue(c.getMessage().contains("compressor '" + id + "'"), c.getMessage());
            ZarrUnsupportedException f = assertThrows(ZarrUnsupportedException.class,
                    () -> v3(zarray("\"<i4\"", "").replace("\"filters\":null", "\"filters\":[{\"id\":\"" + id + "\"}]")));
            assertTrue(f.getMessage().contains("filter '" + id + "'"), f.getMessage());
        }
        assertThrows(ZarrUnsupportedException.class, () -> v3(compressed("{\"id\":\"vlen-utf8\"}")));
        assertThrows(ZarrFormatException.class, () -> v3(compressed("{\"level\":1}")));      // no id
        assertThrows(ZarrFormatException.class, () -> v3(compressed("\"gzip\"")));          // not an object
        assertThrows(ZarrFormatException.class,
                () -> v3(zarray("\"<i4\"", "").replace("\"filters\":null", "\"filters\":{\"id\":\"delta\"}")));
    }

    private static String compressed(String compressor) {
        return zarray("\"<i4\"", "").replace("\"compressor\":null", "\"compressor\":" + compressor);
    }

    @Test
    void gzipAndZstdTakeNumcodecsSettingsAndDefaults() {
        assertEquals("{\"name\":\"gzip\",\"configuration\":{\"level\":1}}", compressorOf(compressed("{\"id\":\"gzip\"}")));
        assertEquals("{\"name\":\"gzip\",\"configuration\":{\"level\":9}}",
                compressorOf(compressed("{\"id\":\"gzip\",\"level\":9}")));
        assertEquals("{\"name\":\"zstd\",\"configuration\":{\"level\":0,\"checksum\":false}}",
                compressorOf(compressed("{\"id\":\"zstd\"}")));
        assertEquals("{\"name\":\"zstd\",\"configuration\":{\"level\":-3,\"checksum\":true}}",
                compressorOf(compressed("{\"id\":\"zstd\",\"level\":-3,\"checksum\":true}")));
        assertThrows(ZarrFormatException.class, () -> v3(compressed("{\"id\":\"zstd\",\"checksum\":1}")));
        assertThrows(ZarrFormatException.class, () -> v3(compressed("{\"id\":\"zstd\",\"level\":1.5}")));
    }

    /**
     * Blosc gets numcodecs' configuration and the type size c-blosc is handed: the element size of the
     * buffer after the filters, as the headers of zarr-python's v2 chunks show (delta to int16: 2; a
     * fixedscaleoffset to uint8, packbits, shuffle, a checksum, an object codec: 1; U3: 12).
     */
    @Test
    void bloscTakesTheTypeSizeNumcodecsHandsIt() {
        record Row(String dtype, String filters, String compressor, String config) {
        }
        String lz4auto = "{\"id\":\"blosc\",\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":-1,\"blocksize\":0}";
        for (Row r : List.of(
                new Row("\"<i4\"", "null", lz4auto, "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":4"),
                new Row("\"|u1\"", "null", lz4auto, "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"bitshuffle\",\"typesize\":1"),
                new Row("\"<i4\"", "[{\"id\":\"delta\",\"dtype\":\"<i4\",\"astype\":\"<i2\"}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":2"),
                new Row("\"<f8\"", "[{\"id\":\"fixedscaleoffset\",\"scale\":10,\"offset\":0,\"dtype\":\"<f8\",\"astype\":\"|u1\"}]",
                        lz4auto, "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"bitshuffle\",\"typesize\":1"),
                new Row("\"<f8\"", "[{\"id\":\"quantize\",\"digits\":2,\"dtype\":\"<f8\",\"astype\":\"<f4\"}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":4"),
                new Row("\"<f8\"", "[{\"id\":\"astype\",\"encode_dtype\":\"<f4\",\"decode_dtype\":\"<f8\"}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":4"),
                new Row("\"<f4\"", "[{\"id\":\"bitround\",\"keepbits\":5}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":4"),
                new Row("\"|b1\"", "[{\"id\":\"packbits\"}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"bitshuffle\",\"typesize\":1"),
                new Row("\"<i4\"", "[{\"id\":\"shuffle\",\"elementsize\":4}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"bitshuffle\",\"typesize\":1"),
                new Row("\"<i4\"", "[{\"id\":\"crc32\"}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"bitshuffle\",\"typesize\":1"),
                new Row("\"<U3\"", "null", lz4auto, "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":12"),
                new Row("\"|S3\"", "null", lz4auto, "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":3"),
                new Row("\"<M8[s]\"", "null", lz4auto, "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":8"),
                new Row("[[\"a\",\"<i4\"],[\"b\",\"<f8\"]]", "null", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":12"),
                new Row("\"|O\"", "[{\"id\":\"vlen-utf8\"}]", lz4auto,
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"bitshuffle\",\"typesize\":1"),
                new Row("\"<f8\"", "null", "{\"id\":\"blosc\"}",
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":8"),
                new Row("\"<f8\"", "null", "{\"id\":\"blosc\",\"cname\":\"zstd\",\"clevel\":9,\"shuffle\":2,\"blocksize\":4096}",
                        "\"cname\":\"zstd\",\"clevel\":9,\"shuffle\":\"bitshuffle\",\"typesize\":8"),
                new Row("\"<f8\"", "null", "{\"id\":\"blosc\",\"shuffle\":0,\"typesize\":2}",
                        "\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"noshuffle\",\"typesize\":2"))) {
            String z = zarray(r.dtype, "").replace("\"filters\":null", "\"filters\":" + r.filters)
                    .replace("\"compressor\":null", "\"compressor\":" + r.compressor);
            String blocksize = r.compressor.contains("4096") ? "4096" : "0";
            assertEquals("{\"name\":\"blosc\",\"configuration\":{" + r.config + ",\"blocksize\":" + blocksize + "}}",
                    compressorOf(z), r.dtype + " " + r.filters);
            if (r.filters.equals("null")) {
                parse(z).checkPipelines(); // the v3 blosc codec takes the configuration
            }
        }
        assertThrows(ZarrFormatException.class, () -> v3(compressed("{\"id\":\"blosc\",\"shuffle\":3}")));
        assertThrows(ZarrFormatException.class, () -> v3(compressed("{\"id\":\"blosc\",\"clevel\":\"high\"}")));
        // a filter whose output dtype cannot be read leaves blosc's type size unknown
        assertThrows(ZarrFormatException.class, () -> v3(zarray("\"<i4\"", "")
                .replace("\"filters\":null", "\"filters\":[{\"id\":\"delta\",\"dtype\":\"<i4\",\"astype\":\"int16\"}]")
                .replace("\"compressor\":null", "\"compressor\":" + lz4auto)));
        // a bad cname is the blosc codec's to refuse, when the pipeline is built
        ArrayMetadata bad = parse(compressed("{\"id\":\"blosc\",\"cname\":\"brotli\"}"));
        assertThrows(ZarrException.class, bad::checkPipelines);
    }

    private static String compressorOf(String zarray) {
        List<JsonValue> codecs = v3(zarray).get("codecs").asArray().values();
        return codecs.get(codecs.size() - 1).toJson();
    }
}
