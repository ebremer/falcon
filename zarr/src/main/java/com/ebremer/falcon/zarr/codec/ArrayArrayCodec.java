package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteOrder;

/**
 * A codec that transforms one array into another (for example {@code transpose}, or {@code cast_value}, which
 * changes the elements' data type).
 *
 * <p>The pipeline calls the typed {@code encode}/{@code decode}, giving each codec the data type on its decoded
 * side (the array's, or what an earlier codec's {@link #encodedType} made it) and the byte order of the
 * elements' primitives. A codec that only moves whole elements implements the untyped forms, which the typed
 * ones call by default.
 */
interface ArrayArrayCodec {

    /** The codec name. */
    String name();

    /** The shape this codec produces on the encode side from {@code inputShape}. */
    int[] encodedShape(int[] inputShape);

    /** The shape decoding produces from an encoded array of {@code encodedShape}. */
    int[] decodedShape(int[] encodedShape);

    /** Decodes {@code input} (the encoded-side array) back toward the logical array. */
    ArrayValue decode(ArrayValue input, int elementSize);

    /** Encodes {@code input} (the logical-side array) toward the stored form. */
    ArrayValue encode(ArrayValue input, int elementSize);

    /**
     * The data type of the elements on this codec's encoded side, for {@code decodedType} on its decoded side:
     * what the codecs after it see. Only a codec that converts values ({@code cast_value}) changes it.
     */
    default DataType encodedType(DataType decodedType) {
        return decodedType;
    }

    /**
     * Whether every element stays where it is, so that a region of the encoded array holds the same region of
     * the decoded one: true for a codec that converts values one by one, false for one that moves elements.
     */
    default boolean keepsLayout() {
        return false;
    }

    /**
     * Encodes {@code input}, whose elements are {@code decodedType} with their primitives in {@code order}, into
     * elements of {@link #encodedType}{@code (decodedType)} in the same order.
     */
    default ArrayValue encode(ArrayValue input, DataType decodedType, ByteOrder order) {
        return encode(input, decodedType.byteCount());
    }

    /**
     * Decodes {@code input}, whose elements are {@link #encodedType}{@code (decodedType)} with their primitives in
     * {@code order}, into elements of {@code decodedType}.
     */
    default ArrayValue decode(ArrayValue input, DataType decodedType, ByteOrder order) {
        return decode(input, decodedType.byteCount());
    }

    /**
     * The fill element ({@code decodedType}, in {@code order}) as the codecs after this one see it: unchanged
     * unless this codec converts values ({@code cast_value} casts the fill value as it casts an element).
     */
    default byte[] encodeFill(byte[] fillElement, DataType decodedType, ByteOrder order) {
        return fillElement;
    }

    /** {@link #encodeFill} the other way: an encoded fill element decoded back to {@code decodedType}. */
    default byte[] decodeFill(byte[] fillElement, DataType decodedType, ByteOrder order) {
        return fillElement;
    }

    /**
     * {@link #decode} for a variable-length chunk of {@code encodedShape}, in C order; the result has the
     * input's runtime type ({@code String[]} or {@code byte[][]}).
     */
    Object[] decodeObjects(Object[] input, int[] encodedShape);

    /**
     * {@link #encode} for a variable-length chunk of {@code shape}, in C order; the result has the input's
     * runtime type.
     */
    Object[] encodeObjects(Object[] input, int[] shape);
}
