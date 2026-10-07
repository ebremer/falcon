package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.Hdf5Writer;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code conformance}: the command line the Zarr conformance tests call, {@code --array_path=<array>}, and the
 * HDF5 manifest Falcon's HDF5 conformance harness compares with h5py's, {@code --hdf5=<file>}.
 */
class ConformanceTest {

    @TempDir
    Path dir;

    @Test
    void printsEveryValueOfTheArrayAtArrayPath() throws IOException {
        String h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
        for (String format : new String[] {"2", "3"}) {
            Path zarr = dir.resolve("v" + format + ".zarr");
            Cli.run("convert", "-q", "--zarr-format", format, h5, zarr.toString()).ok();
            String array = zarr.resolve("run").resolve("temperature").toString();
            String values = Cli.ok("dump", zarr.toString(), "run/temperature");
            assertEquals(values, Cli.ok("conformance", "--array_path=" + array));
            assertEquals(values, Cli.ok("conformance", "--array_path", array));
            assertEquals(Cli.ok("dump", zarr.toString(), "flags"),
                    Cli.ok("conformance", "--array_path=" + zarr.resolve("flags")));
        }
    }

    /**
     * {@code --hdf5}: every path in link-name order, depth first; the second link to an object as {@code hard},
     * soft and external links unfollowed; and only ASCII, whatever the console's encoding.
     */
    @Test
    void printsAJsonManifestOfEverythingInAnHdf5File() throws IOException {
        Path file = dir.resolve("links.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.root().stringAttribute("place", "café");
            w.group("g").createDataset("d", Datatype.int32(), 3).write(new int[] {1, 2, 3});
            w.hardLink("alias", "/g");
            w.softLink("soft", "/g/d");
            w.externalLink("ext", "other.h5", "/x");
        }
        String out = Cli.ok("conformance", "--hdf5=" + file);
        assertTrue(out.chars().allMatch(c -> c < 0x7f), out);
        JsonObject objects = Json.parse(out).asObject().get("objects").asObject();
        assertEquals(List.of("/", "/alias", "/alias/d", "/ext", "/g", "/soft"), List.copyOf(objects.members().keySet()));
        JsonObject place = objects.get("/").asObject().get("attributes").asObject().get("place").asObject();
        assertEquals("string", place.get("type").asString());
        assertEquals("café", place.get("value").asString());
        JsonObject d = objects.get("/alias/d").asObject();
        assertEquals("dataset", d.get("kind").asString());
        assertEquals("[3]", Json.write(d.get("shape")));
        assertEquals("integer", d.get("type").asString());
        assertEquals("[1,2,3]", Json.write(d.get("values")));
        assertEquals("{\"kind\":\"hard\",\"same_as\":\"/alias\"}", Json.write(objects.get("/g")));
        assertEquals("{\"kind\":\"soft\",\"target\":\"/g/d\"}", Json.write(objects.get("/soft")));
        assertEquals("{\"kind\":\"external\",\"file\":\"other.h5\",\"target\":\"/x\"}", Json.write(objects.get("/ext")));
    }

    @Test
    void anHdf5FileThatDoesNotOpenIsAnErrorManifestAndStatus1() {
        Cli.Result missing = Cli.run("conformance", "--hdf5=" + dir.resolve("absent.h5"));
        assertEquals(1, missing.status());
        assertTrue(Json.parse(missing.out()).asObject().get("error").asString().contains("absent.h5"), missing.out());
        assertEquals(2, Cli.run("conformance", "--hdf5=a.h5", "--array_path=b.zarr").status());
    }

    @Test
    void failsWithANonZeroStatusUnlessItReadsAnArray() throws IOException {
        String h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
        Path zarr = dir.resolve("sample.zarr");
        Cli.run("convert", "-q", h5, zarr.toString()).ok();

        Cli.Result missing = Cli.run("conformance", "--array_path=" + dir.resolve("absent.zarr"));
        assertEquals(1, missing.status());
        assertTrue(missing.err().startsWith("falcon conformance: "), missing.err());
        Cli.Result group = Cli.run("conformance", "--array_path=" + zarr);
        assertEquals(2, group.status());
        assertTrue(group.err().contains("is a group"), group.err());
        assertEquals(2, Cli.run("conformance").status());
    }
}
