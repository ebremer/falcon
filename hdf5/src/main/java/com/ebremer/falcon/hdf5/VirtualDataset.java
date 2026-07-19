package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Hyperslab;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Assembles a virtual dataset (layout class 3) from its source datasets. The global-heap mapping block
 * holds, per entry, a source file name, source dataset name, and two serialized dataspace selections
 * (source and virtual). For each mapping this reads the source selection from the (external) source
 * dataset and scatters it into the virtual selection of the output; regions no mapping covers keep the
 * fill value. Source files are resolved relative to the virtual dataset's own file.
 *
 * <p>Regular hyperslab and all-points selections are supported; strided / multi-block / unlimited
 * selections and point selections are a later increment.
 */
final class VirtualDataset {

    private static final int SEL_NONE = 0;
    private static final int SEL_HYPERSLAB = 2;
    private static final int SEL_ALL = 3;

    private VirtualDataset() {
    }

    static byte[] assemble(FileContext ctx, DataLayout.Virtual layout, long[] virtualDims,
                           int elementSize, byte[] fill) {
        long elements = 1;
        for (long d : virtualDims) {
            elements *= d;
        }
        byte[] output = new byte[Math.toIntExact(elements * elementSize)];
        tileFill(output, fill, elementSize);

        byte[] block = GlobalHeap.readObject(ctx, layout.globalHeapAddress(), layout.index());
        HdfBuffer buf = HdfBuffer.of(block);
        int lengths = ctx.sizeOfLengths();
        long entries = buf.getUnsignedValue(1, lengths); // version(1), then entry count
        int p = 1 + lengths;

        Path directory = ctx.path() == null ? null : ctx.path().getParent();
        Map<Path, Hdf5File> sources = new HashMap<>();
        try {
            for (long e = 0; e < entries; e++) {
                int fileEnd = zeroFrom(block, p);
                String sourceFile = new String(block, p, fileEnd - p, StandardCharsets.UTF_8);
                p = fileEnd + 1;
                int datasetEnd = zeroFrom(block, p);
                String sourceDataset = new String(block, p, datasetEnd - p, StandardCharsets.UTF_8);
                p = datasetEnd + 1;
                VdsSelection sourceSelection = readSelection(buf, p);
                p += sourceSelection.byteLength;
                VdsSelection virtualSelection = readSelection(buf, p);
                p += virtualSelection.byteLength;

                if (sourceSelection.type == SEL_NONE || virtualSelection.type == SEL_NONE) {
                    continue;
                }
                Path sourcePath = directory == null ? Path.of(sourceFile) : directory.resolve(sourceFile);
                Hdf5File source = sources.computeIfAbsent(sourcePath, VirtualDataset::openOrNull);
                if (source == null) {
                    continue; // missing source file -> leave the fill value in place
                }
                Dataset sourceDataset2 = navigate(source.root(), sourceDataset);
                long[] sourceDims = sourceDataset2.dataspace().dimensions();

                byte[] sourceBytes = Hyperslab.extract(sourceDataset2.rawData(), sourceDims,
                        sourceSelection.offset(sourceDims), sourceSelection.count(sourceDims), elementSize);
                scatter(output, virtualDims, virtualSelection.offset(virtualDims),
                        virtualSelection.count(virtualDims), sourceBytes, elementSize);
            }
        } finally {
            for (Hdf5File file : sources.values()) {
                if (file != null) {
                    file.close();
                }
            }
        }
        return output;
    }

    /** A parsed source/virtual selection: whole-space (ALL) or a regular hyperslab block. */
    private static final class VdsSelection {
        final int type;
        final long[] start;
        final long[] block;
        final int byteLength;

        VdsSelection(int type, long[] start, long[] block, int byteLength) {
            this.type = type;
            this.start = start;
            this.block = block;
            this.byteLength = byteLength;
        }

        long[] offset(long[] dims) {
            return type == SEL_ALL ? new long[dims.length] : start.clone();
        }

        long[] count(long[] dims) {
            return type == SEL_ALL ? dims.clone() : block.clone();
        }
    }

    private static VdsSelection readSelection(HdfBuffer buf, int offset) {
        int type = (int) buf.getUnsignedInt(offset);
        if (type == SEL_ALL || type == SEL_NONE) {
            // type(4), version(4), padding(4), length(4)
            return new VdsSelection(type, null, null, 16);
        }
        if (type != SEL_HYPERSLAB) {
            throw new HdfUnsupportedException("virtual dataset selection type " + type
                    + " (only hyperslab and all-points selections are supported)");
        }
        int version = (int) buf.getUnsignedInt(offset + 4);
        if (version != 3) {
            throw new HdfUnsupportedException("virtual dataset hyperslab selection version " + version
                    + " is not yet supported");
        }
        int encodeSize = buf.getUnsignedByte(offset + 9); // version(4), flags(1), encode size(1)
        int rank = (int) buf.getUnsignedInt(offset + 10);
        int q = offset + 14;
        long[] start = new long[rank];
        long[] block = new long[rank];
        for (int d = 0; d < rank; d++) {
            long blockCount = buf.getUnsignedValue(q + 2L * encodeSize, encodeSize);
            if (blockCount != 1) {
                throw new HdfUnsupportedException("strided / multi-block virtual dataset selections are not yet supported");
            }
            start[d] = buf.getUnsignedValue(q, encodeSize);
            block[d] = buf.getUnsignedValue(q + 3L * encodeSize, encodeSize);
            q += 4L * encodeSize; // start, stride, count, block
        }
        return new VdsSelection(SEL_HYPERSLAB, start, block, 14 + rank * 4 * encodeSize);
    }

    /** Places {@code source} (row-major over {@code count}) into {@code output} at the given region. */
    private static void scatter(byte[] output, long[] dims, long[] offset, long[] count,
                                byte[] source, int elementSize) {
        int rank = dims.length;
        long[] strides = new long[rank];
        long s = 1;
        for (int i = rank - 1; i >= 0; i--) {
            strides[i] = s;
            s *= dims[i];
        }
        long total = 1;
        for (long c : count) {
            total *= c;
        }
        int[] local = new int[rank];
        for (long i = 0; i < total; i++) {
            long flat = 0;
            for (int d = 0; d < rank; d++) {
                flat += (offset[d] + local[d]) * strides[d];
            }
            System.arraycopy(source, (int) (i * elementSize), output, (int) (flat * elementSize), elementSize);
            for (int d = rank - 1; d >= 0; d--) {
                if (++local[d] < count[d]) {
                    break;
                }
                local[d] = 0;
            }
        }
    }

    private static Dataset navigate(Group root, String path) {
        Hdf5Object current = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!(current instanceof Group group)) {
                throw new HdfFormatException("virtual dataset source path traverses a non-group: " + path);
            }
            current = group.child(part).orElseThrow(
                    () -> new HdfFormatException("virtual dataset source not found: " + path));
        }
        if (!(current instanceof Dataset dataset)) {
            throw new HdfFormatException("virtual dataset source is not a dataset: " + path);
        }
        return dataset;
    }

    private static Hdf5File openOrNull(Path path) {
        try {
            return Hdf5File.open(path);
        } catch (IOException e) {
            return null; // an unavailable source contributes only the fill value
        }
    }

    private static int zeroFrom(byte[] data, int from) {
        int i = from;
        while (i < data.length && data[i] != 0) {
            i++;
        }
        return i;
    }

    private static void tileFill(byte[] output, byte[] fill, int elementSize) {
        if (fill == null || fill.length == 0) {
            return;
        }
        for (int off = 0; off + elementSize <= output.length; off += elementSize) {
            System.arraycopy(fill, 0, output, off, Math.min(elementSize, fill.length));
        }
    }
}
