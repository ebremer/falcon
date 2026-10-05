package com.ebremer.falcon.core.compress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the space-separated reference vectors under {@code /fixtures}. */
public final class Vectors {

    private Vectors() {
    }

    /** The fields of each non-comment line of {@code resource}. */
    public static List<String[]> read(String resource) {
        var url = Vectors.class.getResource("/fixtures/" + resource);
        try {
            List<String[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(line.split(" "));
                }
            }
            return out;
        } catch (IOException | URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public static byte[] hex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
