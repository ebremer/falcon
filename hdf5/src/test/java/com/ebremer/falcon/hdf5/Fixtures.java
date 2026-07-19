package com.ebremer.falcon.hdf5;

import java.net.URISyntaxException;
import java.nio.file.Path;

/** Resolves committed test fixtures (real {@code .h5} files under {@code src/test/resources/fixtures}). */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path path(String name) {
        var url = Fixtures.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalStateException("fixture not found on test classpath: " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
