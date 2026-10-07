package com.ebremer.falcon.cli;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Copies a Zarr store, or the hierarchy below one of its nodes, to another store.
 * <ul>
 *   <li><b>As it is</b> ({@link #copyKeys}): every key's bytes, so the copy is the source exactly, whatever
 *       codecs and extensions it uses. A store that cannot list its keys (HTTP) is walked node by node, each
 *       array's chunks fetched by key.</li>
 *   <li><b>Re-encoded</b> ({@link #reencode}): each group and array created anew, in another Zarr format,
 *       compression, or chunking, and its elements read and written; data type, fill value, byte order,
 *       dimension names (Zarr v3), and attributes carry over.</li>
 * </ul>
 */
final class ZarrCopy {

    private final Context context;
    private final ConvertSettings settings;
    int arrays;
    int groups;
    final AtomicLong keys = new AtomicLong();
    final AtomicLong bytes = new AtomicLong();

    ZarrCopy(Context context, ConvertSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    // ---- as it is -----------------------------------------------------------------------------------------

    /**
     * Copies every key below {@code path} in {@code source} to the root of {@code target}.
     *
     * @param source the store to copy
     * @param path   the node whose keys to copy; empty for the whole store
     * @param target the store to copy to
     * @throws Exception if a key cannot be read or written
     */
    void copyKeys(Store source, String path, Store target) throws Exception {
        String prefix = path.isEmpty() ? "" : path + "/";
        List<String> listed;
        try {
            listed = prefix.isEmpty() ? source.list() : source.listPrefix(prefix);
        } catch (UnsupportedOperationException e) {
            listed = walk(source, path);
        }
        List<String> sourceKeys = listed;
        ForkJoinPool pool = new ForkJoinPool(Math.max(1, settings.threads()));
        try {
            pool.submit(() -> sourceKeys.parallelStream().forEach(key -> {
                source.get(key).ifPresent(value -> {
                    target.set(key.substring(prefix.length()), value);
                    keys.incrementAndGet();
                    bytes.addAndGet(value.length);
                });
            })).get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every key of the hierarchy below {@code path}, found by opening its nodes: metadata and chunk keys. */
    private List<String> walk(Store store, String path) {
        List<String> out = new ArrayList<>();
        ZarrNode root = ZarrInspect.resolve(store, path);
        String prefix = path.isEmpty() ? "" : path + "/";
        if (root.zarrFormat() == 2 && root.isGroup()) {
            out.add(prefix + ".zmetadata");
        }
        walk(root, out);
        return out;
    }

    private void walk(ZarrNode node, List<String> out) {
        String prefix = node.path().isEmpty() ? "" : node.path() + "/";
        if (node.zarrFormat() == 3) {
            out.add(prefix + "zarr.json");
        } else {
            out.add(prefix + (node.isGroup() ? ".zgroup" : ".zarray"));
            out.add(prefix + ".zattrs");
        }
        if (node instanceof ZarrArray array) {
            long[] grid = array.gridShape();
            long[] at = new long[grid.length];
            for (long i = 0, n = array.chunkCount(); i < n; i++) {
                out.add(prefix + array.chunkKey(at));
                for (int d = grid.length - 1; d >= 0; d--) {
                    if (++at[d] < grid[d]) {
                        break;
                    }
                    at[d] = 0;
                }
            }
        } else {
            ZarrGroup group = node.asGroup();
            for (String name : group.childNames()) {
                try {
                    group.child(name).ifPresent(child -> walk(child, out));
                } catch (RuntimeException e) {
                    context.warn("left out /" + prefix + name + ": " + Errors.describe(e));
                }
            }
        }
    }

    // ---- re-encoded ---------------------------------------------------------------------------------------

    /**
     * Writes the node at {@code path} of {@code source}, and everything below it, anew to the root of
     * {@code target}.
     *
     * @param source    the store to copy
     * @param path      the group or array to copy
     * @param target    the store to write
     * @param overwrite whether to replace what the target holds
     * @throws Exception if a node cannot be read or written
     */
    void reencode(Store source, String path, Store target, boolean overwrite) throws Exception {
        ZarrNode node = ZarrInspect.resolve(source, path);
        int format = settings.zarrFormat() == 0 ? node.zarrFormat() : settings.zarrFormat();
        if (node instanceof ZarrGroup group) {
            ZarrGroup root = Zarr.createGroup(target, group.attributes(), overwrite, format);
            groups++;
            children(source, group, root, format);
        } else {
            array(source, node.asArray(), "/", format, spec -> Zarr.createArray(target, spec, overwrite));
        }
    }

    private void children(Store store, ZarrGroup group, ZarrGroup target, int format) throws Exception {
        for (String name : group.childNames()) {
            String path = "/" + (group.path().isEmpty() ? "" : group.path() + "/") + name;
            ZarrNode child;
            try {
                child = group.child(name).orElse(null);
            } catch (RuntimeException e) {
                context.warn("left out " + path + ": " + Errors.describe(e));
                continue;
            }
            if (child instanceof ZarrGroup g) {
                ZarrGroup created = target.createGroup(name, g.attributes());
                groups++;
                children(store, g, created, format);
            } else if (child instanceof ZarrArray a) {
                array(store, a, path, format, spec -> target.createArray(name, spec));
            }
        }
    }

    private void array(Store store, ZarrArray source, String path, int format, Hdf5ToZarr.Creator creator)
            throws Exception {
        ZarrMeta meta = ZarrMeta.of(store, source);
        ByteOrder order = meta.byteOrder();
        DataType type = source.dataType();
        long[] shape = source.shape();
        ArraySpec.Builder spec = ArraySpec.builder(shape, type).zarrFormat(format).attributes(source.attributes());
        if (type.hasByteOrder()) {
            spec.endian(order);
        }
        long[] chunks = null;
        if (shape.length > 0) {
            int elementSize = type.isVariableLength() ? 16 : type.byteCount();
            if (settings.autoChunks() || (source.isRectilinear() && format == 2)) {
                chunks = Chunking.clamp(Chunking.ZARR.guess(shape, elementSize), shape);
                spec.chunkShape(chunks);
            } else if (source.isRectilinear()) {
                long[][] sizes = source.chunkSizes();
                for (int d = 0; d < sizes.length; d++) {
                    spec.chunkLengths(d, sizes[d]);
                }
            } else if (format == 3 && !Arrays.equals(source.innerChunkShape(), source.chunkShape())) {
                chunks = source.chunkShape();
                spec.chunkShape(chunks).sharding(source.innerChunkShape());
            } else {
                chunks = source.innerChunkShape();
                spec.chunkShape(chunks);
            }
        }
        if (format == 3) {
            source.dimensionNames().ifPresent(names -> spec.dimensionNames(names.toArray(String[]::new)));
        }
        try {
            ByteOrder fillOrder = type.kind() == DataTypeKind.STRUCT ? ByteOrder.LITTLE_ENDIAN : order;
            spec.fillValue(type.isVariableLength() ? source.fillValue()
                    : type.encodeFillValue(source.fillValueBytes(fillOrder), fillOrder));
        } catch (RuntimeException e) {
            spec.fillValue(source.fillValue());
        }
        Compression compression = Compression.choose(settings.compression(), Compression.fromZarr(meta.codecs()), true);
        compression.applyTo(spec, format);
        ZarrArray target;
        try {
            target = creator.create(spec.build());
        } catch (IllegalArgumentException e) {
            context.warn("left out " + path + ": " + e.getMessage());
            return;
        }
        ZarrArray cached = source.withChunkCache(256L << 20);
        long[] unit;
        if (shape.length == 0) {
            unit = new long[0];
        } else if (target.isRectilinear()) { // blocks of each dimension's longest chunk
            long[][] sizes = target.chunkSizes();
            unit = new long[sizes.length];
            for (int d = 0; d < sizes.length; d++) {
                unit[d] = Arrays.stream(sizes[d]).max().orElse(1);
            }
        } else {
            unit = target.chunkShape();
        }
        int elementSize = type.isVariableLength() ? 64 : type.byteCount();
        DataTypeKind kind = type.kind();
        Blocks.copy(shape, unit, elementSize, settings.threads(), false,
                (offset, count) -> {
                    var selection = shape.length == 0 ? cached.selectAll() : cached.select(offset, count);
                    return switch (kind) {
                        case STRING -> selection.readStrings();
                        case BYTES -> selection.readByteArrays();
                        default -> selection.readRawBytes();
                    };
                },
                (offset, count, data) -> {
                    var selection = shape.length == 0 ? target.selectAll() : target.select(offset, count);
                    switch (kind) {
                        case STRING -> selection.writeStrings((String[]) data);
                        case BYTES -> selection.writeByteArrays((byte[][]) data);
                        default -> selection.writeRawBytes((byte[]) data);
                    }
                });
        arrays++;
        if (!settings.quiet()) {
            context.out.println(path + "  " + Describe.type(type, order) + " " + Describe.shape(shape)
                    + (chunks == null ? "" : "  chunks " + Describe.shape(chunks)) + "  " + compression.describe());
        }
    }
}
