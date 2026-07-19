package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Parsers for the two object modification-time messages, returning seconds since the Unix epoch (UTC).
 *
 * <ul>
 *   <li><b>Type 18</b> (current) &mdash; {@code version(1) · reserved(3) · seconds since epoch(4)}.</li>
 *   <li><b>Type 14</b> (old, spec section IV.A.2.p) &mdash; a 14-character ASCII timestamp
 *       {@code "YYYYMMDDHHMMSS"} in UTC, followed by 2 reserved bytes. Modern HDF5 always writes type
 *       18 instead, so this is only met in files from very old libraries.</li>
 * </ul>
 */
public final class ObjectModificationTimeMessage {

    private ObjectModificationTimeMessage() {
    }

    /** Seconds since the epoch from a current (type 18) modification-time message body. */
    public static long epochSeconds(HdfBuffer body) {
        return body.getUnsignedInt(4); // version(1), reserved(3), then the time
    }

    /** Seconds since the epoch from an old (type 14) modification-time message body. */
    public static long epochSecondsOld(HdfBuffer body) {
        String s = new String(body.getBytes(0, 14), StandardCharsets.US_ASCII);
        try {
            LocalDateTime time = LocalDateTime.of(
                    Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)),
                    Integer.parseInt(s.substring(6, 8)), Integer.parseInt(s.substring(8, 10)),
                    Integer.parseInt(s.substring(10, 12)), Integer.parseInt(s.substring(12, 14)));
            return time.toEpochSecond(ZoneOffset.UTC);
        } catch (NumberFormatException | DateTimeException e) {
            throw new com.ebremer.falcon.hdf5.HdfFormatException("malformed old modification time '" + s + "'", e);
        }
    }
}
