package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code transpose} array&rarr;array codec: its {@code order} is a permutation of the axes applied
 * on the encode side (encoded axis {@code i} is input axis {@code order[i]}). Decoding applies the
 * inverse permutation, moving whole elements; within-element bytes are untouched.
 */
final class TransposeCodec implements ArrayArrayCodec {

    private final int[] order;

    private TransposeCodec(int[] order) {
        this.order = order;
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
        int[] out = new int[inputShape.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = inputShape[order[i]];
        }
        return out;
    }

    @Override
    public ArrayValue encode(ArrayValue input, int elementSize) {
        return new ArrayValue(permute(input.data, input.shape, order, elementSize),
                permutedShape(input.shape, order));
    }

    @Override
    public ArrayValue decode(ArrayValue input, int elementSize) {
        int[] inverse = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            inverse[order[i]] = i;
        }
        int[] outShape = permutedShape(input.shape, inverse);
        byte[] out = permute(input.data, input.shape, inverse, elementSize);
        return new ArrayValue(out, outShape);
    }

    /** The shape of {@code permute(_, shape, perm, _)}: {@code out[i] = shape[perm[i]]}. */
    private static int[] permutedShape(int[] shape, int[] perm) {
        int[] out = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            out[i] = shape[perm[i]];
        }
        return out;
    }

    /**
     * Produces {@code transpose(src, perm)}: an array of shape {@code srcShape[perm[i]]} whose element at
     * multi-index {@code j} is {@code src} at the multi-index {@code m} with {@code m[perm[i]] = j[i]}.
     */
    private static byte[] permute(byte[] src, int[] srcShape, int[] perm, int elementSize) {
        int n = srcShape.length;
        int[] outShape = permutedShape(srcShape, perm);
        int count = 1;
        for (int d : outShape) {
            count *= d;
        }
        int[] srcStride = cStrides(srcShape);
        byte[] out = new byte[count * elementSize];
        int[] index = new int[n]; // multi-index into the output, C order
        for (int flat = 0; flat < count; flat++) {
            int srcOffset = 0;
            for (int i = 0; i < n; i++) {
                srcOffset += index[i] * srcStride[perm[i]];
            }
            System.arraycopy(src, srcOffset * elementSize, out, flat * elementSize, elementSize);
            for (int i = n - 1; i >= 0; i--) {
                if (++index[i] < outShape[i]) {
                    break;
                }
                index[i] = 0;
            }
        }
        return out;
    }

    private static int[] cStrides(int[] shape) {
        int[] stride = new int[shape.length];
        int acc = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            stride[i] = acc;
            acc *= shape[i];
        }
        return stride;
    }
}
