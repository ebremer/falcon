package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code conformance}: the command line the Zarr conformance tests call, {@code --array_path=<array>}. */
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
