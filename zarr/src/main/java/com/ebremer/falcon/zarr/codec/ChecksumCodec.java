package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.checksum.Fletcher32;
import com.ebremer.falcon.core.checksum.Lookup3;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * numcodecs' 32-bit checksum codecs: the data with a little-endian checksum of it before or after.
 * Decoding verifies it and strips it; a mismatch is a {@link ZarrFormatException}.
 *
 * <ul>
 *   <li>{@code crc32} (zlib's CRC-32), {@code adler32} (zlib's Adler-32), and {@code crc32c}
 *       (Castagnoli): the configuration's {@code location}, {@code "start"} or {@code "end"}, places the
 *       checksum; numcodecs' defaults are the start for {@code crc32} and {@code adler32} and the end for
 *       {@code crc32c}.</li>
 *   <li>{@code fletcher32}: HDF5's Fletcher-32 (big-endian 16-bit words), at the end. numcodecs cannot
 *       encode an empty buffer with it, and neither does Falcon.</li>
 *   <li>{@code jenkins_lookup3}: HDF5's lookup3 hash seeded with the configuration's {@code initval}
 *       (default 0), at the end. A {@code prefix} hashed before the data is not supported: numcodecs takes
 *       it as raw bytes, which its JSON configuration cannot carry.</li>
 * </ul>
 */
final class ChecksumCodec extends NumcodecsCodec {

    private enum Algorithm { CRC32, CRC32C, ADLER32, FLETCHER32, LOOKUP3 }

    private final Algorithm algorithm;
    private final boolean atStart;
    private final int initval;

    private ChecksumCodec(String id, Algorithm algorithm, boolean atStart, int initval) {
        super(id);
        this.algorithm = algorithm;
        this.atStart = atStart;
        this.initval = initval;
    }

    static ChecksumCodec parse(String id, JsonObject configuration) {
        return switch (id) {
            case "crc32" -> new ChecksumCodec(id, Algorithm.CRC32, atStart(configuration, id, true), 0);
            case "adler32" -> new ChecksumCodec(id, Algorithm.ADLER32, atStart(configuration, id, true), 0);
            case "crc32c" -> new ChecksumCodec(id, Algorithm.CRC32C, atStart(configuration, id, false), 0);
            case "fletcher32" -> new ChecksumCodec(id, Algorithm.FLETCHER32, false, 0);
            case "jenkins_lookup3" -> {
                if (configuration.find("prefix").filter(v -> !v.isNull()).isPresent()) {
                    throw new ZarrUnsupportedException("numcodecs.jenkins_lookup3: a prefix is not supported");
                }
                long initval = Numcodecs.integer(configuration, "initval", 0);
                if (initval < 0 || initval > 0xffffffffL) {
                    throw new ZarrFormatException("numcodecs.jenkins_lookup3: initval " + initval
                            + " is not a 32-bit unsigned integer");
                }
                yield new ChecksumCodec(id, Algorithm.LOOKUP3, false, (int) initval);
            }
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static boolean atStart(JsonObject configuration, String id, boolean fallback) {
        String location = configuration.find("location").filter(v -> !v.isNull()).map(JsonValue::asString)
                .orElse(fallback ? "start" : "end");
        return switch (location) {
            case "start" -> true;
            case "end" -> false;
            default -> throw new ZarrFormatException("numcodecs." + id + ": location must be 'start' or 'end', was '"
                    + location + "'");
        };
    }

    @Override
    NumpyType encodedType() {
        return NumpyType.U1;
    }

    @Override
    public long encodedSize(long decodedSize) {
        return decodedSize + 4;
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return decodedSize > Long.MAX_VALUE - 4 ? Long.MAX_VALUE : decodedSize + 4;
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        if (input.length < 4) {
            throw new ZarrFormatException(name() + ": chunk is shorter than the 4-byte checksum");
        }
        int length = input.length - 4;
        if (length > maxSize) {
            throw new ZarrFormatException(name() + ": chunk holds " + length + " bytes, more than " + maxSize);
        }
        int data = atStart ? 4 : 0;
        int at = atStart ? 0 : length;
        int stored = (input[at] & 0xff) | (input[at + 1] & 0xff) << 8 | (input[at + 2] & 0xff) << 16
                | (input[at + 3] & 0xff) << 24;
        int computed = checksum(input, data, length);
        if (computed != stored) {
            throw new ZarrFormatException(String.format("%s mismatch: computed 0x%08x, stored 0x%08x",
                    name(), computed, stored));
        }
        return Arrays.copyOfRange(input, data, data + length);
    }

    @Override
    public byte[] encode(byte[] input) {
        if (algorithm == Algorithm.FLETCHER32 && input.length == 0) {
            throw new ZarrFormatException(name() + ": numcodecs cannot checksum an empty buffer");
        }
        byte[] out = allocate(input.length + 4L, MAX_ARRAY);
        int data = atStart ? 4 : 0;
        int at = atStart ? 0 : input.length;
        System.arraycopy(input, 0, out, data, input.length);
        int value = checksum(input, 0, input.length);
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
        out[at + 2] = (byte) (value >>> 16);
        out[at + 3] = (byte) (value >>> 24);
        return out;
    }

    private int checksum(byte[] data, int offset, int length) {
        return switch (algorithm) {
            case CRC32 -> zip(new CRC32(), data, offset, length);
            case CRC32C -> zip(new CRC32C(), data, offset, length);
            case ADLER32 -> zip(new Adler32(), data, offset, length);
            case FLETCHER32 -> Fletcher32.checksum(data, offset, length);
            case LOOKUP3 -> Lookup3.hashLittle(data, offset, length, initval);
        };
    }

    private static int zip(Checksum checksum, byte[] data, int offset, int length) {
        checksum.update(data, offset, length);
        return (int) checksum.getValue();
    }
}
