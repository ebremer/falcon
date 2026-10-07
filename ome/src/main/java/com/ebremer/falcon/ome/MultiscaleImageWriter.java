package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.Dataset;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Writes OME-Zarr multiscale images: the full-resolution pixels from a {@link PixelSource}, each smaller
 * level made from the one before ({@link Downsampling}), and the metadata that describes them, as the
 * chosen version lays it out. Label images are written the same way, with as many levels as their image.
 *
 * <pre>{@code
 * MultiscaleImageWriter writer = MultiscaleImageWriter.builder(OmeVersion.V0_5,
 *         List.of(Axis.channel("c"), Axis.space("y", "micrometer"), Axis.space("x", "micrometer")))
 *     .pixelSize(1, 0.25, 0.25)
 *     .build();
 * MultiscaleImage image = writer.write(FileSystemStore.open(Path.of("slide.ome.zarr")), PixelSource.of(array));
 * }</pre>
 *
 * <p>By default the last two spatial axes (y and x) are halved at each level, until the largest is no more
 * than 256 pixels; each pixel is the {@linkplain Downsampling#MEAN mean} of its 2&times;2 block. Each level's
 * {@code scale} is its pixel size, and its {@code translation} the position of its first pixel's center, so
 * that the levels line up. The levels are chunked 512 by 512 in y and x (1 in the other dimensions) and
 * compressed with zstd, and are named {@code 0}, {@code 1}, ... The group's metadata is written last, so a
 * write that fails part way leaves no image a reader would take for complete.
 *
 * <p>Blocks of each level are made in parallel. A writer is immutable, and safe to use from several threads.
 */
public final class MultiscaleImageWriter {

    /** The largest a block of pixels made at once may be, in bytes. */
    private static final long BLOCK_BYTES = 8L << 20;

    private final OmeVersion version;
    private final List<Axis> axes;
    private final String name;
    private final double[] pixelSize;
    private final double[] origin;
    private final Integer levels;
    private final long smallest;
    private final boolean[] downsampled;
    private final int factor;
    private final Downsampling method;
    private final long[] chunks;
    private final long[] shards;
    private final Consumer<ArraySpec.Builder> codec;
    private final Omero omero;
    private final ImageLabel imageLabel;
    private final int threads;
    private final Consumer<long[]> progress;

    private MultiscaleImageWriter(Builder b) {
        this.version = b.version;
        this.axes = b.axes;
        this.name = b.name;
        this.pixelSize = b.pixelSize;
        this.origin = b.origin;
        this.levels = b.levels;
        this.smallest = b.smallest;
        this.downsampled = b.downsampled;
        this.factor = b.factor;
        this.method = b.method;
        this.chunks = b.chunks;
        this.shards = b.shards;
        this.codec = b.codec;
        this.omero = b.omero;
        this.imageLabel = b.imageLabel;
        this.threads = b.threads;
        this.progress = b.progress;
    }

    /**
     * A builder for a writer of images of a version with these axes.
     *
     * @param version the OME-Zarr version to write
     * @param axes    the images' axes, in the order of their dimensions: 2 to 5 of them, ordered time,
     *                channel, then 2 or 3 space axes
     * @return the builder
     * @throws IllegalArgumentException if the axes are not as an OME-Zarr image's must be
     */
    public static Builder builder(OmeVersion version, List<Axis> axes) {
        return new Builder(version, axes);
    }

    /** {@return the version written} */
    public OmeVersion version() {
        return version;
    }

    /** {@return the axes} */
    public List<Axis> axes() {
        return axes;
    }

    /**
     * The levels this writer would make of an image of a shape.
     *
     * @param shape the full-resolution image's shape
     * @return each level's shape, from the largest
     * @throws IllegalArgumentException if the shape does not have one extent per axis
     */
    public List<long[]> levelShapes(long[] shape) {
        if (shape.length != axes.size()) {
            throw new IllegalArgumentException("a " + shape.length + "-dimensional image for " + axes.size() + " axes");
        }
        List<long[]> shapes = new ArrayList<>();
        shapes.add(shape.clone());
        int[] f = factors();
        while (levels == null ? exceeds(shapes.getLast()) : shapes.size() < levels) {
            long[] last = shapes.getLast();
            long[] next = new long[last.length];
            boolean changed = false;
            for (int d = 0; d < last.length; d++) {
                next[d] = (last[d] + f[d] - 1) / f[d];
                changed |= next[d] != last[d];
            }
            if (!changed) {
                break;
            }
            shapes.add(next);
        }
        return shapes;
    }

    private boolean exceeds(long[] shape) {
        for (int d = 0; d < shape.length; d++) {
            if (downsampled[d] && shape[d] > smallest) {
                return true;
            }
        }
        return false;
    }

    private int[] factors() {
        int[] f = new int[axes.size()];
        for (int d = 0; d < f.length; d++) {
            f[d] = downsampled[d] ? factor : 1;
        }
        return f;
    }

    /**
     * Writes an image as the root group of a store.
     *
     * @param store  an empty store
     * @param source the full-resolution pixels
     * @return the image written
     * @throws IllegalArgumentException      if the store already holds a root node, or the source's shape or
     *                                       data type does not suit the writer
     * @throws UnsupportedOperationException if the store is read-only
     */
    public MultiscaleImage write(Store store, PixelSource source) {
        return write(store, source, false);
    }

    /**
     * Writes an image as the root group of a store, replacing what the store holds if asked to.
     *
     * @param store     the store
     * @param source    the full-resolution pixels
     * @param overwrite whether to delete everything in the store first
     * @return the image written
     * @throws IllegalArgumentException      if the store already holds a root node and {@code overwrite} is
     *                                       false, or the source's shape or data type does not suit the writer
     * @throws UnsupportedOperationException if the store is read-only
     */
    public MultiscaleImage write(Store store, PixelSource source, boolean overwrite) {
        check(source);
        ZarrGroup group = Zarr.createGroup(store, new JsonObject(Map.of()), overwrite, version.zarrFormat());
        return writeImage(group, source, imageLabel, method, null);
    }

    /**
     * Writes an image as a child group.
     *
     * @param parent the group to write it in (of the version's Zarr format)
     * @param name   the image's group's name
     * @param source the full-resolution pixels
     * @return the image written
     * @throws IllegalArgumentException      if the name is taken, or the source's shape or data type does not
     *                                       suit the writer
     * @throws UnsupportedOperationException if the store is read-only
     */
    public MultiscaleImage write(ZarrGroup parent, String name, PixelSource source) {
        requireFormat(parent);
        check(source);
        ZarrGroup group = parent.createGroup(name, new JsonObject(Map.of()));
        return writeImage(group, source, imageLabel, method, null);
    }

    /**
     * Writes a label image of an image: its integer labels, with as many levels as the image and of the same
     * sizes, placed in the image's {@code labels} group (which is made, or added to). The label image takes the
     * image's axes and its levels the image's transformations; this writer's chunks and codecs apply. Each level is made from the one before with this writer's
     * downsampling method, but for {@link Downsampling#MEAN}, which labels are not averaged with:
     * {@link Downsampling#NEAREST} then.
     *
     * @param image  the image labelled
     * @param name   the label image's name in the {@code labels} group
     * @param labels the labels, the shape of the image's first level (or 1 in a dimension they do not vary
     *               in)
     * @return the label image written
     * @throws IllegalArgumentException if the labels are not integers, their shape does not fit the image, or
     *                                  the name is taken
     */
    public MultiscaleImage writeLabel(MultiscaleImage image, String name, PixelSource labels) {
        DataType type = labels.dataType();
        if (type.kind() != DataTypeKind.INT && type.kind() != DataTypeKind.UINT) {
            throw new IllegalArgumentException("a label image's values are integers, not " + type);
        }
        if (image.version() != version) {
            throw new IllegalArgumentException("the image is OME-Zarr " + image.version() + "; this writer writes "
                    + version);
        }
        long[] base = image.level(0).shape();
        long[] shape = labels.shape();
        if (shape.length != base.length) {
            throw new IllegalArgumentException("labels of " + shape.length + " dimensions for an image of "
                    + base.length);
        }
        for (int d = 0; d < shape.length; d++) {
            if (shape[d] != base[d] && shape[d] != 1) {
                throw new IllegalArgumentException("the labels' shape " + Arrays.toString(shape)
                        + " does not fit the image's " + Arrays.toString(base) + " (each extent is the image's, or 1)");
            }
        }
        List<long[]> shapes = new ArrayList<>();
        for (int k = 0; k < image.levelCount(); k++) {
            long[] s = image.level(k).shape();
            for (int d = 0; d < s.length; d++) {
                if (shape[d] == 1) {
                    s[d] = 1;
                }
            }
            shapes.add(s);
        }
        ZarrGroup group = image.group();
        Optional<ZarrNode> existing = group.child("labels");
        ZarrGroup labelsGroup;
        if (existing.isPresent()) {
            if (!(existing.get() instanceof ZarrGroup g)) {
                throw new IllegalArgumentException("the image's 'labels' is an array, not a group");
            }
            labelsGroup = g;
        } else {
            labelsGroup = group.createGroup("labels", new JsonObject(Map.of()));
        }
        ZarrGroup labelGroup = labelsGroup.createGroup(name, new JsonObject(Map.of()));
        ImageLabel label = imageLabel != null ? imageLabel : new ImageLabel(null, List.of(), List.of(), "../../");
        Downsampling labelMethod = method == Downsampling.MEAN ? Downsampling.NEAREST : method;
        MultiscaleImage written = writeImage(labelGroup, labels, label, labelMethod,
                new Plan(shapes, image.multiscale().datasets(), name, image.axes()));
        List<String> listed = new ArrayList<>(OmeMetadata.read(labelsGroup.attributes()).map(OmeMetadata::labels)
                .orElse(List.of()));
        if (!listed.contains(name)) {
            listed.add(name);
        }
        labelsGroup.setAttributes(OmeMetadata.of(version).withLabels(listed).toAttributes(labelsGroup.attributes()));
        return written;
    }

    /** A label image's levels, shapes, transformations, and axes, taken from its image. */
    private record Plan(List<long[]> shapes, List<Dataset> datasets, String name, List<Axis> axes) {
    }

    private void requireFormat(ZarrGroup parent) {
        if (parent.zarrFormat() != version.zarrFormat()) {
            throw new IllegalArgumentException("OME-Zarr " + version + " is Zarr v" + version.zarrFormat()
                    + "; the group is Zarr v" + parent.zarrFormat());
        }
    }

    /** Checks that a source is one this writer writes, before anything is written. */
    private void check(PixelSource source) {
        if (!PixelSource.isNumeric(source.dataType())) {
            throw new IllegalArgumentException("an image's pixels are booleans, integers, or floating-point numbers, "
                    + "not " + source.dataType());
        }
        if (source.shape().length != axes.size()) {
            throw new IllegalArgumentException("a " + source.shape().length + "-dimensional image for " + axes.size()
                    + " axes");
        }
    }

    private MultiscaleImage writeImage(ZarrGroup group, PixelSource source, ImageLabel label, Downsampling how,
                                       Plan plan) {
        check(source);
        DataType type = source.dataType();
        long[] shape = source.shape();
        List<long[]> shapes = plan != null ? plan.shapes() : levelShapes(shape);
        List<Dataset> datasets = new ArrayList<>();
        List<ZarrArray> arrays = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "falcon-ome-pyramid");
            t.setDaemon(true);
            return t;
        })) {
            for (int k = 0; k < shapes.size(); k++) {
                long[] s = shapes.get(k);
                ZarrArray array = group.createArray(Integer.toString(k), spec(s, type));
                if (k == 0) {
                    copy(source, array, pool);
                } else {
                    downsample(arrays.getLast(), array, type, how, pool);
                }
                arrays.add(array);
                if (progress != null) {
                    progress.accept(s.clone());
                }
                datasets.add(plan != null ? new Dataset(Integer.toString(k), plan.datasets().get(k).transformations())
                        : level(k, how));
            }
        }
        Multiscale multiscale = new Multiscale(plan != null ? plan.name() : name, how.id(), methodMetadata(how,
                shapes), null, plan != null ? plan.axes() : axes, List.of(), datasets, List.of());
        OmeMetadata metadata = OmeMetadata.of(version).withMultiscale(multiscale)
                .withOmero(plan == null ? omero : null).withImageLabel(label);
        ZarrGroup written = group.setAttributes(metadata.toAttributes(group.attributes()));
        return MultiscaleImage.open(written);
    }

    /** Level {@code k}'s transformations: its pixel size, and its first pixel's center unless all are at 0. */
    private Dataset level(int k, Downsampling how) {
        int n = axes.size();
        double[] scale = new double[n];
        double[] translation = new double[n];
        boolean offset = origin != null;
        for (int d = 0; d < n; d++) {
            double f = Math.pow(downsampled[d] ? factor : 1, k);
            scale[d] = pixelSize[d] * f;
            translation[d] = (origin == null ? 0 : origin[d]) + (how.centered() ? (f - 1) / 2 * pixelSize[d] : 0);
            offset |= translation[d] != 0;
        }
        boolean translated = offset || (how.centered() && (levels == null || levels > 1));
        return Dataset.of(Integer.toString(k), scale, translated ? translation : null);
    }

    private JsonObject methodMetadata(Downsampling how, List<long[]> shapes) {
        List<JsonValue> f = new ArrayList<>();
        for (int value : factors()) {
            f.add(JsonNumber.of(value));
        }
        return MetadataJson.obj("description", new JsonString(switch (how) {
            case MEAN -> "each pixel the mean of its block of the level before";
            case NEAREST -> "each pixel the first of its block of the level before";
            case MODE -> "each pixel the most frequent value of its block of the level before";
        }), "method", new JsonString("com.ebremer.falcon.ome.Downsampling." + how.name()),
                "kwargs", MetadataJson.obj("factors", new JsonArray(f), "levels", JsonNumber.of(shapes.size())));
    }

    private ArraySpec spec(long[] shape, DataType type) {
        int rank = shape.length;
        long[] chunk = new long[rank];
        int spatial = 0;
        for (boolean b : downsampled) {
            spatial += b ? 1 : 0;
        }
        long edge = spatial <= 2 ? 512 : 128;
        for (int d = 0; d < rank; d++) {
            long c = chunks != null ? chunks[d] : downsampled[d] ? edge : 1;
            chunk[d] = Math.max(1, Math.min(c, Math.max(1, shape[d])));
        }
        ArraySpec.Builder b = ArraySpec.builder(shape, type).zarrFormat(version.zarrFormat());
        if (shards != null) {
            long[] shard = new long[rank];
            for (int d = 0; d < rank; d++) {
                long whole = (Math.max(1, shape[d]) + chunk[d] - 1) / chunk[d] * chunk[d];
                long s = Math.max(chunk[d], shards[d] / chunk[d] * chunk[d]);
                shard[d] = Math.min(s, whole);
            }
            b.chunkShape(shard).sharding(chunk);
        } else {
            b.chunkShape(chunk);
        }
        if (version.zarrFormat() == 3) {
            b.dimensionNames(axes.stream().map(Axis::name).toArray(String[]::new));
        } else {
            b.separator("/");
        }
        if (codec != null) {
            codec.accept(b);
        } else {
            b.zstd();
        }
        return b.build();
    }

    /** Level 0: the source's pixels, block by block. */
    private void copy(PixelSource source, ZarrArray target, ExecutorService pool) {
        int size = source.dataType().byteCount();
        run(pool, blocks(target, size), block -> {
            byte[] bytes = source.read(block[0], block[1]);
            if (bytes.length != count(block[1]) * size) {
                throw new IllegalStateException("the source gave " + bytes.length + " bytes for "
                        + count(block[1]) + " elements of " + size);
            }
            target.select(block[0], block[1]).writeRawBytes(bytes);
        });
    }

    /** A smaller level from the level before, block by block. */
    private void downsample(ZarrArray from, ZarrArray to, DataType type, Downsampling how, ExecutorService pool) {
        long[] inShape = from.shape();
        long[] outShape = to.shape();
        int rank = inShape.length;
        int[] f = new int[rank];
        for (int d = 0; d < rank; d++) {
            f[d] = outShape[d] == inShape[d] ? 1
                    : (int) Math.max(1, Math.round((double) inShape[d] / Math.max(1, outShape[d])));
        }
        run(pool, blocks(to, type.byteCount()), block -> {
            long[] inOffset = new long[rank];
            long[] inExtent = new long[rank];
            for (int d = 0; d < rank; d++) {
                inOffset[d] = block[0][d] * f[d];
                inExtent[d] = Math.max(0, Math.min(block[1][d] * f[d], inShape[d] - inOffset[d]));
            }
            byte[] in = count(inExtent) == 0 ? new byte[0] : from.select(inOffset, inExtent).readRawBytes();
            byte[] out = Downsampler.downsample(in, inExtent, block[1], f, type, how);
            to.select(block[0], block[1]).writeRawBytes(out);
        });
    }

    /** The array's blocks, each {offset, shape}: whole chunks (or shards), as many at once as fit 8 MiB. */
    private static List<long[][]> blocks(ZarrArray array, int elementSize) {
        long[] shape = array.shape();
        long[] chunk = array.chunkShape();
        int rank = shape.length;
        long[] block = chunk.clone();
        for (int d = rank - 1; d >= 0; d--) {
            long rest = 1;
            for (int e = 0; e < rank; e++) {
                if (e != d) {
                    rest *= block[e];
                }
            }
            long most = Math.max(1, BLOCK_BYTES / Math.max(1, rest * elementSize));
            long multiple = Math.max(1, most / chunk[d]);
            block[d] = Math.min(chunk[d] * multiple, Math.max(chunk[d], shape[d]));
        }
        long[] grid = new long[rank];
        long total = 1;
        for (int d = 0; d < rank; d++) {
            grid[d] = shape[d] == 0 ? 0 : (shape[d] + block[d] - 1) / block[d];
            total *= grid[d];
        }
        List<long[][]> blocks = new ArrayList<>();
        long[] at = new long[rank];
        for (long b = 0; b < total; b++) {
            long rest = b;
            for (int d = rank - 1; d >= 0; d--) {
                at[d] = rest % grid[d];
                rest /= grid[d];
            }
            long[] offset = new long[rank];
            long[] extent = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = at[d] * block[d];
                extent[d] = Math.min(block[d], shape[d] - offset[d]);
            }
            blocks.add(new long[][] {offset, extent});
        }
        return blocks;
    }

    private static long count(long[] shape) {
        long n = 1;
        for (long s : shape) {
            n *= s;
        }
        return n;
    }

    private interface BlockTask {
        void run(long[][] block);
    }

    private static void run(ExecutorService pool, List<long[][]> blocks, BlockTask task) {
        List<Future<?>> futures = new ArrayList<>(blocks.size());
        for (long[][] block : blocks) {
            futures.add(pool.submit(() -> task.run(block)));
        }
        RuntimeException failure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof RuntimeException r ? r
                            : new IllegalStateException(e.getCause());
                    futures.forEach(f -> f.cancel(false));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("interrupted while writing the image", e);
            } catch (java.util.concurrent.CancellationException e) {
                // cancelled after the first failure
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** A builder of {@link MultiscaleImageWriter}s; not safe for use from several threads. */
    public static final class Builder {

        private final OmeVersion version;
        private final List<Axis> axes;
        private String name;
        private double[] pixelSize;
        private double[] origin;
        private Integer levels;
        private long smallest = 256;
        private boolean[] downsampled;
        private int factor = 2;
        private Downsampling method = Downsampling.MEAN;
        private long[] chunks;
        private long[] shards;
        private Consumer<ArraySpec.Builder> codec;
        private Omero omero;
        private ImageLabel imageLabel;
        private int threads = Runtime.getRuntime().availableProcessors();
        private Consumer<long[]> progress;

        private Builder(OmeVersion version, List<Axis> axes) {
            this.version = Objects.requireNonNull(version, "version");
            this.axes = List.copyOf(axes);
            checkAxes(this.axes);
            this.pixelSize = new double[axes.size()];
            Arrays.fill(pixelSize, 1);
            this.downsampled = new boolean[axes.size()];
            int marked = 0;
            for (int d = axes.size() - 1; d >= 0 && marked < 2; d--) {
                if (axes.get(d).isSpace()) {
                    downsampled[d] = true;
                    marked++;
                }
            }
        }

        private static void checkAxes(List<Axis> axes) {
            if (axes.size() < 2 || axes.size() > 5) {
                throw new IllegalArgumentException("an image has 2 to 5 axes, not " + axes.size());
            }
            long spaces = axes.stream().filter(Axis::isSpace).count();
            if (spaces < 2 || spaces > 3) {
                throw new IllegalArgumentException("an image has 2 or 3 space axes, not " + spaces);
            }
            int stage = -1; // the last axis's: 0 time, 1 channel or other, 2 space
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Axis a : axes) {
                if (!names.add(a.name())) {
                    throw new IllegalArgumentException("axis names must be unique; '" + a.name() + "' repeats");
                }
                int s = a.isTime() ? 0 : a.isSpace() ? 2 : 1;
                if (s < stage || (s == stage && s < 2)) {
                    throw new IllegalArgumentException("axes are ordered: at most one time axis, then at most one "
                            + "channel or other axis, then the space axes");
                }
                stage = s;
            }
        }

        /**
         * Sets the image's name (default: none).
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the full-resolution pixel size along each axis, in its unit (default: 1 for every axis).
         *
         * @param size the size, one for each axis, each positive
         * @return this builder
         * @throws IllegalArgumentException if there is not one positive size for each axis
         */
        public Builder pixelSize(double... size) {
            if (size.length != axes.size()) {
                throw new IllegalArgumentException(size.length + " pixel sizes for " + axes.size() + " axes");
            }
            for (double s : size) {
                if (!(s > 0) || Double.isInfinite(s)) {
                    throw new IllegalArgumentException("a pixel size must be positive and finite, not " + s);
                }
            }
            this.pixelSize = size.clone();
            return this;
        }

        /**
         * Sets the physical position of the first pixel's center (default: the origin, 0 along every axis).
         *
         * @param position the position, one coordinate for each axis
         * @return this builder
         * @throws IllegalArgumentException if there is not one coordinate for each axis
         */
        public Builder origin(double... position) {
            if (position.length != axes.size()) {
                throw new IllegalArgumentException(position.length + " coordinates for " + axes.size() + " axes");
            }
            this.origin = position.clone();
            return this;
        }

        /**
         * Sets how many levels to write, the full resolution among them (default: until the downsampled axes
         * fit {@link #smallest(long)}). Fewer are written if the levels stop getting smaller.
         *
         * @param count the number of levels, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code count} is less than 1
         */
        public Builder levels(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("an image has at least one level, not " + count);
            }
            this.levels = count;
            return this;
        }

        /**
         * Sets the size the smallest level fits when the number of levels is not given: levels are added until
         * every downsampled axis is at most this long (default 256).
         *
         * @param size the size, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code size} is less than 1
         */
        public Builder smallest(long size) {
            if (size < 1) {
                throw new IllegalArgumentException("the smallest level is at least 1 long, not " + size);
            }
            this.smallest = size;
            return this;
        }

        /**
         * Sets the axes that are downsampled (default: the last two space axes, usually y and x).
         *
         * @param names the axes' names
         * @return this builder
         * @throws IllegalArgumentException if a name is not an axis's
         */
        public Builder downsample(String... names) {
            boolean[] marked = new boolean[axes.size()];
            for (String n : names) {
                int d = axes.stream().map(Axis::name).toList().indexOf(n);
                if (d < 0) {
                    throw new IllegalArgumentException("no axis is named '" + n + "'");
                }
                marked[d] = true;
            }
            this.downsampled = marked;
            return this;
        }

        /**
         * Sets how much each level shrinks along the downsampled axes (default 2).
         *
         * @param factor the factor, at least 2
         * @return this builder
         * @throws IllegalArgumentException if {@code factor} is less than 2
         */
        public Builder factor(int factor) {
            if (factor < 2) {
                throw new IllegalArgumentException("the downsampling factor is at least 2, not " + factor);
            }
            this.factor = factor;
            return this;
        }

        /**
         * Sets how a smaller level's pixels are made (default {@link Downsampling#MEAN}).
         *
         * @param method the method
         * @return this builder
         */
        public Builder method(Downsampling method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * Sets the chunk shape (default: 512 along the downsampled axes, or 128 when there are three of them,
         * and 1 along the others), clipped to each level's shape.
         *
         * @param shape the chunk's extent along each axis, each positive
         * @return this builder
         * @throws IllegalArgumentException if there is not one positive extent for each axis
         */
        public Builder chunks(long... shape) {
            this.chunks = positive(shape, "chunk");
            return this;
        }

        /**
         * Stores the chunks in shards of this shape (Zarr v3, so 0.5 and 0.6 only), each a whole number of
         * chunks (default: no shards).
         *
         * @param shape the shard's extent along each axis, each positive
         * @return this builder
         * @throws IllegalArgumentException if there is not one positive extent for each axis, or the version
         *                                  is 0.4
         */
        public Builder shards(long... shape) {
            if (version.zarrFormat() != 3) {
                throw new IllegalArgumentException("shards are Zarr v3; OME-Zarr " + version + " is Zarr v2");
            }
            this.shards = positive(shape, "shard");
            return this;
        }

        private long[] positive(long[] shape, String what) {
            if (shape.length != axes.size()) {
                throw new IllegalArgumentException("a " + what + " shape of " + shape.length + " extents for "
                        + axes.size() + " axes");
            }
            for (long s : shape) {
                if (s < 1) {
                    throw new IllegalArgumentException("a " + what + " extent is positive, not " + s);
                }
            }
            return shape.clone();
        }

        /**
         * Sets how the levels' chunks are compressed (default: zstd): called with each level's
         * {@link ArraySpec.Builder} to set its codecs, such as {@code b -> b.blosc()} or {@code b -> b.gzip(5)}.
         *
         * @param codec what to set on each level's array
         * @return this builder
         */
        public Builder codec(Consumer<ArraySpec.Builder> codec) {
            this.codec = codec;
            return this;
        }

        /**
         * Sets the {@code omero} rendering metadata (default: none).
         *
         * @param rendering the metadata, or null for none
         * @return this builder
         */
        public Builder omero(Omero rendering) {
            this.omero = rendering;
            return this;
        }

        /**
         * Sets the {@code image-label} metadata of the label images written: by {@link #writeLabel}, which
         * otherwise gives them only their source, {@code ../../}; and by {@link #write}, which otherwise writes
         * plain images.
         *
         * @param label the metadata, or null
         * @return this builder
         */
        public Builder imageLabel(ImageLabel label) {
            this.imageLabel = label;
            return this;
        }

        /**
         * Sets how many blocks are made at once (default: the number of processors).
         *
         * @param count the number of threads, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code count} is less than 1
         */
        public Builder threads(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("at least one thread, not " + count);
            }
            this.threads = count;
            return this;
        }

        /**
         * Sets what to tell as each level is written (default: nothing): called with the level's shape, the
         * largest level first.
         *
         * @param levelWritten called after each level is written, from the writing thread
         * @return this builder
         */
        public Builder progress(Consumer<long[]> levelWritten) {
            this.progress = levelWritten;
            return this;
        }

        /** {@return the writer} */
        public MultiscaleImageWriter build() {
            return new MultiscaleImageWriter(this);
        }
    }
}
