package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The {@code gzip} bytes&rarr;bytes codec. Chunks are stored in the gzip container format (RFC&nbsp;1952),
 * handled by {@code java.util.zip}. The {@code level} configuration (default 5) sets the deflate level
 * used when encoding.
 */
final class GzipCodec implements BytesBytesCodec {

    private static final int DEFAULT_LEVEL = 5;

    private final int level;

    private GzipCodec(int level) {
        this.level = level;
    }

    static GzipCodec parse(JsonObject configuration) {
        int level = configuration.find("level")
                .map(v -> v.asNumber().intValue())
                .orElse(DEFAULT_LEVEL);
        if (level < 0 || level > 9) {
            throw new ZarrFormatException("gzip level must be 0..9, was " + level);
        }
        return new GzipCodec(level);
    }

    @Override
    public String name() {
        return "gzip";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new ZarrUnsupportedException("gzip has no fixed encoded size, so it cannot encode a shard index");
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return BytesBytesCodec.compressorBound(decodedSize);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(input))) {
            byte[] out = in.readNBytes(maxSize); // allocates as it reads, not maxSize up front
            if (in.read() >= 0) {
                throw new ZarrFormatException("gzip chunk decodes to more than the " + maxSize + " bytes it may hold");
            }
            return out;
        } catch (IOException e) {
            throw new ZarrFormatException("gzip decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int compression = level;
        try (GZIPOutputStream out = new GZIPOutputStream(bos) {
            {
                def.setLevel(compression);
            }
        }) {
            out.write(input);
        } catch (IOException e) {
            throw new ZarrException("gzip encode failed: " + e.getMessage(), e);
        }
        return bos.toByteArray();
    }
}
