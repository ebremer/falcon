package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * The {@code gzip} bytes&rarr;bytes codec. Chunks are stored in the gzip container format (RFC&nbsp;1952);
 * decoding inflates them with {@link GZIPInputStream}. The {@code level} configuration only affects
 * encoding, so it is ignored here.
 */
final class GzipCodec implements BytesBytesCodec {

    static GzipCodec parse(JsonObject configuration) {
        return new GzipCodec();
    }

    @Override
    public String name() {
        return "gzip";
    }

    @Override
    public byte[] decode(byte[] input) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(input))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ZarrFormatException("gzip decode failed: " + e.getMessage(), e);
        }
    }
}
