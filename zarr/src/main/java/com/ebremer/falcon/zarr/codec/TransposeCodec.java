package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code transpose} array&rarr;array codec: its {@code order} is a permutation of the axes applied
 * on the encode side (encoded axis {@code i} is input axis {@code order[i]}). Decoding applies the
 * inverse permutation, moving whole elements; within-element bytes are untouched. It applies to fixed-size
 * elements and to variable-length strings alike.
 */
final class TransposeCodec implements ArrayArrayCodec {

    private final int[] order;
    private final int[] inverse;

    private TransposeCodec(int[] order) {
        this.order = order;
        this.inverse = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            inverse[order[i]] = i;
        }
    }

    static TransposeCodec parse(JsonObject configuration, int rank) {
        JsonArray orderArray = configuration.get("order").asArray();
        if (orderArray.size() != rank) {
            throw new ZarrFormatException("transpose order has length " + orderArray.size()
                    + " but the array rank is " + rank);
        }
        int[] order = new int[rank];
        boolean[] seen = new boolean[rank];
        for (int i = 0; i < rank; i++) {
            int axis = orderArray.get(i).asNumber().intValue();
            if (axis < 0 || axis >= rank || seen[axis]) {
                throw new ZarrFormatException("transpose order is not a permutation of 0.." + (rank - 1));
            }
            seen[axis] = true;
            order[i] = axis;
        }
        return new TransposeCodec(order);
    }

    @Override
    public String name() {
        return "transpose";
    }

    @Override
    public int[] encodedShape(int[] inputShape) {
        return permutedShape(inputShape, order);
    }

    @Override
    public int[] decodedShape(int[] encodedShape) {
        return permutedShape(encodedShape, inverse);
    }

    @Override
    public ArrayValue encode(ArrayValue input, int elementSize) {
        byte[] out = new byte[input.data.length];
        permute(input.data, out, input.shape, order, elementSize);
        return new ArrayValue(out, permutedShape(input.shape, order));
    }

    @Override
    public ArrayValue decode(ArrayValue input, int elementSize) {
        byte[] out = new byte[input.data.length];
        permute(input.data, out, input.shape, inverse, elementSize);
        return new ArrayValue(out, permutedShape(input.shape, inverse));
    }

    @Override
    public String[] encodeStrings(String[] input, int[] shape) {
        String[] out = new String[input.length];
        permute(input, out, shape, order, 1);
        return out;
    }

    @Override
    public String[] decodeStrings(String[] input, int[] encodedShape) {
        String[] out = new String[input.length];
        permute(input, out, encodedShape, inverse, 1);
        return out;
    }

    /** The shape of a permutation: {@code out[i] = shape[perm[i]]}. */
    private static int[] permutedShape(int[] shape, int[] perm) {
        int[] out = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            out[i] = shape[perm[i]];
        }
        return out;
    }

    /**
     * Writes {@code transpose(src, perm)} to {@code out}: the array of shape {@code srcShape[perm[i]]} whose
     * element at multi-index {@code j} is {@code src}'s at the multi-index {@code m} with
     * {@code m[perm[i]] = j[i]}. {@code src} and {@code out} are byte[] (elements of {@code elementSize}
     * bytes) or Object[] ({@code elementSize} 1).
     *
     * <p>The source offset is kept up to date as the output index advances, rather than recomputed per
     * element; when the last axis stays last, whole runs are copied at once.
     */
    private static void permute(Object src, Object out, int[] srcShape, int[] perm, int elementSize) {
        int n = srcShape.length;
        int[] outShape = permutedShape(srcShape, perm);
        int count = Pipelines.elementCount(outShape);
        if (n == 0 || count == 0) {
            System.arraycopy(src, 0, out, 0, count * elementSize);
            return;
        }
        int[] srcStride = Pipelines.strides(srcShape);
        int[] step = new int[n]; // source stride of output axis i
        for (int i = 0; i < n; i++) {
            step[i] = srcStride[perm[i]];
        }
        boolean runs = perm[n - 1] == n - 1; // the last axis is contiguous in both: copy it whole
        int innerAxes = runs ? n - 1 : n;
        int run = runs ? outShape[n - 1] : 1;
        int[] index = new int[n];
        int srcOffset = 0;
        byte[] srcBytes = src instanceof byte[] b ? b : null;
        byte[] outBytes = out instanceof byte[] b ? b : null;
        for (int flat = 0; flat < count; flat += run) {
            if (run > 1 || srcBytes == null || elementSize > 8) {
                System.arraycopy(src, srcOffset * elementSize, out, flat * elementSize, run * elementSize);
            } else {
                int from = srcOffset * elementSize;
                int to = flat * elementSize;
                for (int k = 0; k < elementSize; k++) {
                    outBytes[to + k] = srcBytes[from + k];
                }
            }
            for (int i = innerAxes - 1; i >= 0; i--) {
                srcOffset += step[i];
                if (++index[i] < outShape[i]) {
                    break;
                }
                srcOffset -= step[i] * outShape[i];
                index[i] = 0;
            }
        }
    }
}
