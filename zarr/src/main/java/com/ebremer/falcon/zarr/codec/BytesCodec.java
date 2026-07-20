package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.nio.ByteOrder;

/**
 * The {@code bytes} array&rarr;bytes codec: it fixes the byte order of the elements' primitives and
 * otherwise passes the flat buffer through unchanged (the stored bytes already hold the elements in C
 * order in that byte order). The order is propagated to callers via {@link #elementByteOrder()} rather
 * than swapped here.
 *
 * <p>The {@code endian} configuration is {@code "little"} or {@code "big"}; when omitted (as it is for
 * single-byte types) little-endian is assumed.
 */
final class BytesCodec implements ArrayBytesCodec {

    private final ByteOrder order;

    private BytesCodec(ByteOrder order) {
        this.order = order;
    }

    static BytesCodec parse(JsonObject configuration, DataType dataType) {
        ByteOrder order = configuration.find("endian")
                .map(v -> switch (v.asString()) {
                    case "little" -> ByteOrder.LITTLE_ENDIAN;
                    case "big" -> ByteOrder.BIG_ENDIAN;
                    default -> throw new ZarrFormatException(
                            "bytes codec: endian must be 'little' or 'big', was '" + v.asString() + "'");
                })
                .orElse(ByteOrder.LITTLE_ENDIAN);
        return new BytesCodec(order);
    }

    @Override
    public String name() {
        return "bytes";
    }

    @Override
    public ByteOrder elementByteOrder() {
        return order;
    }

    @Override
    public ArrayValue decode(ChunkBytes source, int[] shape, int elementSize, byte[] fillElement,
                             int[] regionOrigin, int[] regionShape) {
        byte[] input = source.readAll().orElse(null);
        if (input == null) {
            return null;
        }
        long expected = Pipelines.elementCount(shape) * (long) elementSize;
        if (input.length != expected) {
            throw new ZarrFormatException(
                    "bytes codec: chunk is " + input.length + " bytes, expected " + expected);
        }
        return new ArrayValue(input, shape);
    }
}
