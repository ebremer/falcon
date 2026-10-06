package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Zarr v3 array zarr-python 3 wrote with numcodecs' byte codecs ({@code numcodecs.shuffle} and every
 * checksum; {@code tools/fixtures/gen_numcodecs_filter_vectors.py}): Falcon reads its values, and writing
 * them again stores the very chunk bytes zarr-python stored.
 */
class NumcodecsFixtureTest {

    private static Path fixture(String name) {
        try {
            return Path.of(NumcodecsFixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int[] expectedValues() throws IOException {
        JsonObject expected = Json.parse(Files.readAllBytes(fixture("numcodecs_v3_bytes.expected.json"))).asObject();
        return expected.get("values").asArray().values().stream().mapToInt(v -> v.asNumber().intValue()).toArray();
    }

    @Test
    void readsZarrPythonsByteCodecs() throws IOException {
        ZarrArray array = Zarr.open(fixture("numcodecs_v3_bytes")).asArray();
        assertEquals(List.of("bytes", "numcodecs.shuffle", "numcodecs.crc32", "numcodecs.adler32",
                "numcodecs.fletcher32", "numcodecs.jenkins_lookup3", "numcodecs.crc32c"), array.codecNames());
        assertArrayEquals(expectedValues(), array.readInts());
    }

    @Test
    void writesTheChunksZarrPythonWrote() throws IOException {
        Path root = fixture("numcodecs_v3_bytes");
        MemoryStore store = new MemoryStore();
        store.set("zarr.json", Files.readAllBytes(root.resolve("zarr.json")));
        Zarr.openArray(store).writeInts(expectedValues());
        for (String key : List.of("c/0/0", "c/1/0")) {
            assertArrayEquals(Files.readAllBytes(root.resolve(key)), store.get(key).orElseThrow(), key);
        }
    }
}
