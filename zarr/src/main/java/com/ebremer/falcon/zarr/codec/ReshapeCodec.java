package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.Arrays;

/**
 * The {@code reshape} array&rarr;array codec (zarr-extensions {@code codecs/reshape}): the encoded array
 * holds the same elements in the same C order as its input, in another shape. Its {@code shape}
 * configuration gives each output dimension as one of
 * <ul>
 *   <li>a positive size;</li>
 *   <li>an array of input dimensions, whose sizes multiply to the output's (so the codec suits chunks of
 *       different shapes, as a rectilinear grid has); the input dimensions named, taken over the whole
 *       {@code shape} in order, must strictly increase, so that no {@code shape} looks like a transpose;</li>
 *   <li>{@code -1}, at most once: the size that makes the element counts agree.</li>
 * </ul>
 * The element counts must agree, and an output dimension given by input dimensions {@code d0 .. dk} must
 * span exactly those: the output dimensions before it must hold as many elements as the input dimensions
 * before {@code d0}, and those after it as many as those after {@code dk}. The spec writes the first of these
 * as {@code prod(B_shape[:i]) == prod(A_shape[input_dims[0]])}, the slice's colon missing: its own example
 * holds only with {@code A_shape[:input_dims[0]]}.
 *
 * <p>A pipeline is built for one chunk shape, so this codec is too: {@link #parse} resolves {@code shape}
 * against the shape the codec is handed and checks every rule there. Neither direction moves an element;
 * only the shape changes, for fixed-size and variable-length elements alike.
 */
final class ReshapeCodec implements ArrayArrayCodec {

    private final int[] inputShape;
    private final int[] outputShape;

    private ReshapeCodec(int[] inputShape, int[] outputShape) {
        this.inputShape = inputShape;
        this.outputShape = outputShape;
    }

    /**
     * Builds the codec for arrays of {@code inputShape}.
     *
     * @throws ZarrFormatException if {@code shape} is malformed or breaks a rule for this input shape
     */
    static ReshapeCodec parse(JsonObject configuration, int[] inputShape) {
        JsonArray spec = configuration.get("shape").asArray();
        int rank = spec.size();
        long total = Pipelines.elementCount(inputShape);
        long[] out = new long[rank];
        int[][] inputDims = new int[rank][]; // null where the size is given (or -1)
        int auto = -1;                       // the dimension given as -1
        int lastDim = -1;                    // the input dimensions named must strictly increase
        for (int i = 0; i < rank; i++) {
            JsonValue v = spec.get(i);
            if (v instanceof JsonArray dims) {
                int[] d = new int[dims.size()];
                long size = 1;
                for (int k = 0; k < d.length; k++) {
                    int dim = dims.get(k).asNumber().intValue();
                    if (dim < 0 || dim >= inputShape.length) {
                        throw new ZarrFormatException("reshape shape[" + i + "] names input dimension " + dim
                                + ", but the input has " + inputShape.length + " dimensions");
                    }
                    if (dim <= lastDim) {
                        throw new ZarrFormatException("reshape shape names input dimension " + dim + " after "
                                + lastDim + ": the input dimensions must strictly increase");
                    }
                    lastDim = dim;
                    d[k] = dim;
                    size *= inputShape[dim]; // distinct dimensions of one chunk: fits
                }
                inputDims[i] = d;
                out[i] = size;
            } else {
                long size = v.asNumber().longValue();
                if (size == -1) {
                    if (auto >= 0) {
                        throw new ZarrFormatException("reshape shape has -1 more than once");
                    }
                    auto = i;
                } else if (size < 1) {
                    throw new ZarrFormatException("reshape shape[" + i + "] is " + size
                            + ": a size must be positive, or -1");
                } else {
                    out[i] = size;
                }
            }
        }

        // prod(B_shape) == prod(A_shape), -1 resolved to satisfy it
        long known = 1;
        for (int i = 0; i < rank; i++) {
            if (i != auto) {
                if (out[i] > total || (known *= out[i]) > total) { // neither can overflow: total < 2^31
                    known = total + 1;
                    break;
                }
            }
        }
        if (auto >= 0) {
            if (known == 0 || known > total || total % known != 0) {
                throw mismatch(spec, inputShape, "no size for -1 makes the element counts agree");
            }
            out[auto] = total / known;
        } else if (known != total) {
            throw mismatch(spec, inputShape, "the element counts differ");
        }

        // An output dimension given by input dimensions d0..dk spans exactly them.
        for (int i = 0; i < rank; i++) {
            int[] d = inputDims[i];
            if (d == null || d.length == 0) {
                continue;
            }
            if (product(out, 0, i) != product(inputShape, 0, d[0])
                    || product(out, i + 1, rank) != product(inputShape, d[d.length - 1] + 1, inputShape.length)) {
                throw mismatch(spec, inputShape, "output dimension " + i + " does not span input dimensions "
                        + Arrays.toString(d) + " alone");
            }
        }

        int[] shape = new int[rank];
        for (int i = 0; i < rank; i++) {
            shape[i] = (int) out[i]; // each at most total
        }
        return new ReshapeCodec(inputShape.clone(), shape);
    }

    private static ZarrFormatException mismatch(JsonArray spec, int[] inputShape, String why) {
        return new ZarrFormatException("reshape shape " + spec.toJson() + " does not fit a chunk of "
                + Arrays.toString(inputShape) + ": " + why);
    }

    private static long product(long[] shape, int from, int to) {
        long p = 1;
        for (int i = from; i < to; i++) {
            p *= shape[i];
        }
        return p;
    }

    private static long product(int[] shape, int from, int to) {
        long p = 1;
        for (int i = from; i < to; i++) {
            p *= shape[i];
        }
        return p;
    }

    @Override
    public String name() {
        return "reshape";
    }

    @Override
    public int[] encodedShape(int[] inputShape) {
        requireShape(inputShape, this.inputShape);
        return outputShape.clone();
    }

    @Override
    public int[] decodedShape(int[] encodedShape) {
        requireShape(encodedShape, outputShape);
        return inputShape.clone();
    }

    @Override
    public ArrayValue encode(ArrayValue input, int elementSize) {
        requireShape(input.shape, inputShape);
        return new ArrayValue(input.data, outputShape.clone());
    }

    @Override
    public ArrayValue decode(ArrayValue input, int elementSize) {
        requireShape(input.shape, outputShape);
        return new ArrayValue(input.data, inputShape.clone());
    }

    @Override
    public Object[] encodeObjects(Object[] input, int[] shape) {
        requireShape(shape, inputShape);
        return input;
    }

    @Override
    public Object[] decodeObjects(Object[] input, int[] encodedShape) {
        requireShape(encodedShape, outputShape);
        return input;
    }

    private static void requireShape(int[] shape, int[] expected) {
        if (!Arrays.equals(shape, expected)) {
            throw new ZarrFormatException("reshape codec built for " + Arrays.toString(expected) + " was handed "
                    + Arrays.toString(shape));
        }
    }
}
