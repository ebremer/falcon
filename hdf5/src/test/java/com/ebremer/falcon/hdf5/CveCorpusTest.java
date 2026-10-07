package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The HDF Group's CVE test files (<a href="https://github.com/HDFGroup/cve_hdf5">HDFGroup/cve_hdf5</a>): the
 * malformed files behind each CVE filed against the HDF5 library, and the fuzzer finds beside them. Falcon must
 * read each one as {@link RobustnessTest} reads its mutations, memory-mapped and through a {@link RangeReader}:
 * every object, attribute, and value, failing (if it fails) with a typed {@link HdfException} or IOException,
 * within a minute, under the {@code fuzz} execution's small stack and heap. A file may also read without
 * failing: some CVEs are in libhdf5's tools, not in the format.
 *
 * <p>The files are not Falcon's: {@code tools/conformance/run_hdf5_cve.sh} fetches them at a pinned commit and
 * runs this test on them, as {@code -Dfalcon.cve.dir=<checkout>}. Without that property, or outside the
 * {@code fuzz} execution (which sets {@code falcon.limits}), it is skipped.
 */
class CveCorpusTest {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    @TestFactory
    Stream<DynamicTest> everyFileFailsTypedOrReads() throws IOException {
        String dir = System.getProperty("falcon.cve.dir");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(),
                "the CVE corpus; run tools/conformance/run_hdf5_cve.sh (-Dfalcon.cve.dir=<checkout>)");
        Assumptions.assumeTrue(System.getProperty("falcon.limits") != null,
                "runs in the fuzz execution, under its small stack and heap");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(dir))) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String parent = p.getParent().getFileName().toString();
                        String name = p.getFileName().toString();
                        return (parent.equals("cvefiles") || parent.equals("fuzzerfiles"))
                                && !name.endsWith(".c") && !name.endsWith(".md");
                    })
                    .sorted()
                    .toList();
        }
        Assumptions.assumeFalse(files.isEmpty(), "no cvefiles or fuzzerfiles under " + dir);
        return files.stream().map(file -> DynamicTest.dynamicTest(
                file.getParent().getFileName() + "/" + file.getFileName(),
                () -> {
                    byte[] bytes = Files.readAllBytes(file);
                    assertTimeoutPreemptively(LIMIT, () -> RobustnessTest.assertTypedFailure(bytes, false),
                            "memory-mapped");
                    assertTimeoutPreemptively(LIMIT, () -> RobustnessTest.assertTypedFailure(bytes, true),
                            "through a RangeReader");
                }));
    }
}
