package com.ebremer.falcon.zarr.chunk;

/**
 * Encodes chunk grid coordinates into the array-relative chunk key (the store key is that string
 * appended to the array's path). Two encodings are defined:
 *
 * <ul>
 *   <li><b>default</b> &mdash; a {@code "c"} prefix, then each coordinate preceded by the separator:
 *       {@code (1,2)} &rarr; {@code "c/1/2"} (or {@code "c.1.2"}). A rank-0 array's chunk key is
 *       {@code "c"}.</li>
 *   <li><b>v2</b> &mdash; the coordinates joined by the separator, no prefix: {@code (1,2)} &rarr;
 *       {@code "1.2"} (or {@code "1/2"}). A rank-0 array's chunk key is {@code "0"}.</li>
 * </ul>
 *
 * The separator is {@code "/"} or {@code "."} (default: {@code "/"} for the default encoding,
 * {@code "."} for v2).
 */
public final class ChunkKeyEncoding {

    /** The encoding scheme. */
    public enum Kind {
        DEFAULT,
        V2
    }

    private final Kind kind;
    private final String separator;

    private ChunkKeyEncoding(Kind kind, String separator) {
        this.kind = kind;
        this.separator = separator;
    }

    /**
     * @throws IllegalArgumentException if {@code name} is not {@code "default"}/{@code "v2"} or the
     *                                  separator is not {@code "/"}/{@code "."}
     */
    public static ChunkKeyEncoding of(String name, String separator) {
        Kind kind = switch (name) {
            case "default" -> Kind.DEFAULT;
            case "v2" -> Kind.V2;
            default -> throw new IllegalArgumentException("unknown chunk key encoding: '" + name + "'");
        };
        if (!separator.equals("/") && !separator.equals(".")) {
            throw new IllegalArgumentException("separator must be '/' or '.', was '" + separator + "'");
        }
        return new ChunkKeyEncoding(kind, separator);
    }

    /** The encoding name ({@code "default"} or {@code "v2"}). */
    public String name() {
        return kind == Kind.DEFAULT ? "default" : "v2";
    }

    /** The encoding scheme. */
    public Kind kind() {
        return kind;
    }

    /** The separator ({@code "/"} or {@code "."}). */
    public String separator() {
        return separator;
    }

    /** Encodes chunk grid coordinates into the array-relative chunk key. */
    public String encode(long[] coords) {
        return switch (kind) {
            case DEFAULT -> encodeDefault(coords);
            case V2 -> encodeV2(coords);
        };
    }

    private String encodeDefault(long[] coords) {
        StringBuilder sb = new StringBuilder("c");
        for (long c : coords) {
            sb.append(separator).append(c);
        }
        return sb.toString();
    }

    private String encodeV2(long[] coords) {
        if (coords.length == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(coords[i]);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ChunkKeyEncoding[" + name() + " separator='" + separator + "']";
    }
}
