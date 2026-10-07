package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Filter;
import com.ebremer.falcon.hdf5.Hdf5Writer;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.List;
import java.util.Locale;

/**
 * A lossless compressor both formats have, and its settings: what {@code convert} and {@code copy} carry
 * from a source array's filters or codecs to the array they write, or what {@code --compression} names.
 *
 * @param kind    the compressor
 * @param level   its level (gzip 0&ndash;9, zstd 1&ndash;22 or 0 for its default, bzip2's block size
 *                1&ndash;9, Blosc's clevel 0&ndash;9)
 * @param cname   Blosc's internal compressor, or null
 * @param shuffle Blosc's shuffle ({@code noshuffle}, {@code shuffle}, {@code bitshuffle}), or null
 */
record Compression(Kind kind, int level, String cname, String shuffle) {

    /** The compressors. */
    enum Kind {
        /** No compression. */
        NONE,
        /** Deflate: HDF5's {@code deflate}, Zarr's {@code gzip}. */
        GZIP,
        /** Zstandard. */
        ZSTD,
        /** Blosc (version 1's format). */
        BLOSC,
        /** bzip2. */
        BZ2,
        /** LZ4: HDF5's filter 32004; in Zarr v2 numcodecs' {@code lz4}, in v3 Blosc's LZ4. */
        LZ4,
        /** LZF, h5py's own: HDF5 only. */
        LZF
    }

    static final Compression NONE = new Compression(Kind.NONE, 0, null, null);
    /** Zarr's default, as zarr-python's: zstd at its default level. */
    static final Compression ZARR_DEFAULT = new Compression(Kind.ZSTD, 0, null, null);
    /** HDF5's default, one every HDF5 reader has built in: deflate at level 4. */
    static final Compression HDF5_DEFAULT = new Compression(Kind.GZIP, 4, null, null);

    /** What {@code --compression} asks for. */
    sealed interface Policy permits Auto, Keep, Fixed {

        /**
         * Parses {@code --compression}.
         *
         * @param spec {@code auto}, {@code keep}, or a compressor (see {@link Compression#parse})
         * @return the policy
         * @throws UsageException if the spec is none of them
         */
        static Policy parse(String spec) {
            return switch (spec.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> new Auto();
                case "keep" -> new Keep();
                default -> new Fixed(Compression.parse(spec));
            };
        }
    }

    /**
     * The default: the source's compressor where the target has it, and otherwise the target's default (zstd
     * for Zarr, deflate for HDF5). An HDF5 file written takes deflate whatever the source, so that every HDF5
     * reader reads it without plugins; an uncompressed source is compressed.
     */
    record Auto() implements Policy {
    }

    /**
     * The source's compressor wherever the target has it, an HDF5 file's through the plugin filters (zstd,
     * Blosc, bzip2, LZ4, LZF) too; uncompressed stays uncompressed.
     */
    record Keep() implements Policy {
    }

    /**
     * One compressor for every array.
     *
     * @param compression the compressor
     */
    record Fixed(Compression compression) implements Policy {
    }

    /**
     * Parses a compressor: {@code none}, {@code gzip[:level]} (also {@code deflate}, {@code zlib}),
     * {@code zstd[:level]}, {@code blosc[:cname[:clevel[:shuffle]]]}, {@code bz2[:level]} (also {@code bzip2}),
     * {@code lz4}, or {@code lzf}.
     *
     * @param spec the compressor
     * @return it
     * @throws UsageException if it is none of these, or a setting is out of range
     */
    static Compression parse(String spec) {
        String[] parts = spec.strip().toLowerCase(Locale.ROOT).split(":", -1);
        try {
            Compression c = switch (parts[0]) {
                case "none" -> NONE;
                case "gzip", "deflate", "zlib" -> new Compression(Kind.GZIP, level(parts, 4, 0, 9), null, null);
                case "zstd" -> new Compression(Kind.ZSTD, level(parts, 0, 0, 22), null, null);
                case "bz2", "bzip2" -> new Compression(Kind.BZ2, level(parts, 9, 1, 9), null, null);
                case "lz4" -> new Compression(Kind.LZ4, 0, null, null);
                case "lzf" -> new Compression(Kind.LZF, 0, null, null);
                case "blosc" -> {
                    String cname = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "lz4";
                    int clevel = parts.length > 2 && !parts[2].isEmpty() ? Integer.parseInt(parts[2]) : 5;
                    String shuffle = parts.length > 3 && !parts[3].isEmpty() ? parts[3] : "shuffle";
                    if (!List.of("blosclz", "lz4", "lz4hc", "zlib", "zstd").contains(cname)
                            || clevel < 0 || clevel > 9 || !List.of("noshuffle", "shuffle", "bitshuffle").contains(shuffle)
                            || parts.length > 4) {
                        throw new UsageException("--compression blosc takes blosc:cname:clevel:shuffle, cname one of "
                                + "blosclz, lz4, lz4hc, zlib, zstd, clevel 0-9, shuffle one of noshuffle, shuffle, "
                                + "bitshuffle; not '" + spec + "'");
                    }
                    yield new Compression(Kind.BLOSC, clevel, cname, shuffle);
                }
                default -> throw new UsageException("--compression is auto, keep, none, gzip[:level], zstd[:level], "
                        + "blosc[:cname[:clevel[:shuffle]]], bz2[:level], lz4, or lzf; not '" + spec + "'");
            };
            int settings = switch (c.kind) {
                case NONE, LZ4, LZF -> 0;
                case BLOSC -> 3;
                default -> 1;
            };
            if (parts.length > settings + 1) {
                throw new UsageException("--compression " + parts[0] + " takes " + (settings == 0 ? "no setting"
                        : settings == 1 ? "one setting, its level" : "at most " + settings + " settings")
                        + ", not '" + spec + "'");
            }
            return c;
        } catch (NumberFormatException e) {
            throw new UsageException("--compression '" + spec + "' has a setting that is not a number");
        }
    }

    private static int level(String[] parts, int otherwise, int min, int max) {
        if (parts.length < 2 || parts[1].isEmpty()) {
            return otherwise;
        }
        int level = Integer.parseInt(parts[1]);
        if (level < min || level > max) {
            throw new UsageException("--compression " + parts[0] + " level must be " + min + " to " + max + ", not "
                    + level);
        }
        return level;
    }

    /**
     * {@return the compressor an HDF5 dataset's filters hold: the first that both formats have, {@link #NONE}
     * if it has none, or null if it is compressed by a filter only HDF5 has (szip, n-bit, scale-offset, ZFP,
     * SZ)}
     *
     * @param filters the dataset's filter pipeline
     */
    static Compression fromHdf5(List<Filter> filters) {
        boolean other = false;
        for (Filter f : filters) {
            int[] cd = f.clientData();
            switch (f.id()) {
                case Filter.DEFLATE -> {
                    return new Compression(Kind.GZIP, cd.length > 0 ? Math.min(9, Math.max(0, cd[0])) : 4, null, null);
                }
                case Filter.ZSTD -> {
                    return new Compression(Kind.ZSTD, cd.length > 0 ? Math.min(22, Math.max(0, cd[0])) : 0, null, null);
                }
                case Filter.BZIP2 -> {
                    return new Compression(Kind.BZ2, cd.length > 0 ? Math.min(9, Math.max(1, cd[0])) : 9, null, null);
                }
                case Filter.LZ4 -> {
                    return new Compression(Kind.LZ4, 0, null, null);
                }
                case Filter.LZF -> {
                    return new Compression(Kind.LZF, 0, null, null);
                }
                case Filter.BLOSC, Filter.BLOSC2 -> {
                    String cname = cd.length > 6 ? Describe.bloscName(cd[6]) : "lz4";
                    int clevel = cd.length > 4 ? Math.min(9, Math.max(0, cd[4])) : 5;
                    String shuffle = cd.length > 5 ? switch (cd[5]) {
                        case 0 -> "noshuffle";
                        case 2 -> "bitshuffle";
                        default -> "shuffle";
                    } : "shuffle";
                    return new Compression(Kind.BLOSC, clevel, bloscCname(cname), shuffle);
                }
                case Filter.BITSHUFFLE -> {
                    if (cd.length > 4 && (cd[4] == 2 || cd[4] == 3)) {
                        return new Compression(Kind.BLOSC, 5, cd[4] == 2 ? "lz4" : "zstd", "bitshuffle");
                    }
                }
                case Filter.SHUFFLE, Filter.FLETCHER32 -> {
                    // no compression of their own
                }
                default -> other = true;
            }
        }
        return other ? null : NONE;
    }

    /** {@return a Blosc compressor both formats' readers have: snappy, which numcodecs' c-blosc lacks, as lz4} */
    private static String bloscCname(String cname) {
        return List.of("blosclz", "lz4", "lz4hc", "zlib", "zstd").contains(cname) ? cname : "lz4";
    }

    /**
     * {@return the compressor among a Zarr array's codecs (a sharded array's inner ones included), {@link #NONE}
     * if it has none, or null if it is compressed by a codec HDF5 lacks (zfpy, say)}
     *
     * @param codecs the array's codecs
     */
    static Compression fromZarr(List<ZarrMeta.Codec> codecs) {
        boolean other = false;
        for (ZarrMeta.Codec c : codecs) {
            String name = c.name().startsWith("numcodecs.") ? c.name().substring("numcodecs.".length()) : c.name();
            switch (name) {
                case "gzip", "zlib" -> {
                    return new Compression(Kind.GZIP, Math.min(9, Math.max(0, c.integer("level", 1))), null, null);
                }
                case "zstd" -> {
                    return new Compression(Kind.ZSTD, Math.min(22, Math.max(0, c.integer("level", 0))), null, null);
                }
                case "bz2" -> {
                    return new Compression(Kind.BZ2, Math.min(9, Math.max(1, c.integer("level", 1))), null, null);
                }
                case "lz4" -> {
                    return new Compression(Kind.LZ4, 0, null, null);
                }
                case "blosc" -> {
                    String shuffle = c.string("shuffle", "shuffle");
                    shuffle = switch (shuffle) {
                        case "0", "noshuffle" -> "noshuffle";
                        case "2", "bitshuffle" -> "bitshuffle";
                        default -> "shuffle";
                    };
                    return new Compression(Kind.BLOSC, Math.min(9, Math.max(0, c.integer("clevel", 5))),
                            bloscCname(c.string("cname", "lz4")), shuffle);
                }
                case "zfpy", "lzma", "pcodec", "quantize", "bitround" -> other = true;
                default -> {
                    // array-to-array and bytes codecs, filters, and checksums: no compression
                }
            }
        }
        return other ? null : NONE;
    }

    /**
     * {@return the compressor a policy chooses for one array}
     *
     * @param policy     what {@code --compression} asked for
     * @param source     the source array's compressor: {@link #NONE}, or null for one the target lacks
     * @param zarrTarget whether the array is written to Zarr (else HDF5)
     */
    static Compression choose(Policy policy, Compression source, boolean zarrTarget) {
        Compression fallback = zarrTarget ? ZARR_DEFAULT : HDF5_DEFAULT;
        return switch (policy) {
            case Fixed f -> zarrTarget && f.compression().kind() == Kind.LZF ? fallback : f.compression();
            case Keep k -> source == null || (zarrTarget && source.kind == Kind.LZF) ? fallback : source;
            case Auto a -> {
                if (zarrTarget) {
                    yield source == null || source.kind == Kind.NONE || source.kind == Kind.LZF ? fallback : source;
                }
                yield source != null && source.kind == Kind.GZIP ? source : fallback;
            }
        };
    }

    /**
     * Sets this compressor on a Zarr array.
     *
     * @param builder the array's spec
     * @param format  the Zarr format it is written in
     */
    void applyTo(ArraySpec.Builder builder, int format) {
        switch (kind) {
            case NONE, LZF -> { }
            case GZIP -> builder.gzip(level);
            case ZSTD -> {
                if (level == 0) {
                    builder.zstd();
                } else {
                    builder.zstd(level);
                }
            }
            case BLOSC -> builder.blosc(cname, level, shuffle);
            case BZ2 -> builder.bz2(level);
            case LZ4 -> {
                if (format == 2) {
                    builder.compressor(JsonObject.builder().put("id", "lz4").put("acceleration", 1).build());
                } else {
                    builder.blosc("lz4", 5, "shuffle");
                }
            }
        }
    }

    /**
     * Sets this compressor on an HDF5 dataset, which must be chunked.
     *
     * @param dataset the dataset's writer
     */
    void applyTo(Hdf5Writer.DatasetWriter dataset) {
        switch (kind) {
            case NONE -> { }
            case GZIP -> dataset.deflate(level);
            case ZSTD -> {
                if (level == 0) {
                    dataset.zstd();
                } else {
                    dataset.zstd(level);
                }
            }
            case BLOSC -> dataset.blosc(cname, level, shuffle);
            case BZ2 -> dataset.bzip2(level);
            case LZ4 -> dataset.lz4();
            case LZF -> dataset.lzf();
        }
    }

    /** {@return the compressor as {@code --compression} would name it, such as {@code gzip:4}} */
    String describe() {
        return switch (kind) {
            case NONE -> "none";
            case GZIP -> "gzip:" + level;
            case ZSTD -> level == 0 ? "zstd" : "zstd:" + level;
            case BLOSC -> "blosc:" + cname + ":" + level + ":" + shuffle;
            case BZ2 -> "bz2:" + level;
            case LZ4 -> "lz4";
            case LZF -> "lzf";
        };
    }
}
