package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A {@code blosc} codec's chunks written by c-blosc2 (Blosc format version 5) instead of c-blosc 1.x: Falcon
 * reads them, as a c-blosc2-based reader does, through core's Blosc decoder. The chunks are c-blosc2 3.3.2's
 * (imagecodecs {@code blosc2_encode}); see {@code Blosc2DecoderTest} in core for the full set.
 */
class Blosc2ChunkTest {

    /** int32 {@code 3i - 40} for i in 0..31: zstd, byte shuffle; one byte plane stored raw. */
    private static final String ZSTD_SHUFFLE = "05018504800000008000000090000000000000000001050000000000000000002400"
            + "000020000000d8dbdee1e4e7eaedf0f3f6f9fcff0205080b0e1114171a1d202326292c2f32351400000028b52ffd20205d"
            + "000018ffff00020060161cc0021400000028b52ffd20205d000018ffff00020060161cc0021400000028b52ffd20205d"
            + "000018ffff00020060161cc002";
    /** int32 {@code 3i - 40} for i in 32..63: lz4, bit shuffle. */
    private static final String LZ4_BITSHUFFLE = "05012504800000008000000054000000000000000002010000000000000000002400"
            + "000020000000aaaaaaaa66666666b4b4b4b4c738c738073ff8c007c0ff00f8ffff00000000ff000000000000000000000000";
    /** 32 int32 sevens: a header-only chunk of one repeated value. */
    private static final String SEVENS = "050105048000000080000000240000000000000000000000000000000000003007000000";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static ZarrArray bloscArray(MemoryStore store) {
        ZarrArray array = Zarr.createArray(store,
                ArraySpec.builder(new long[] {96}, DataType.INT32).chunkShape(32).blosc().build());
        int[] ones = new int[96];
        java.util.Arrays.fill(ones, 1);
        array.writeInts(ones); // c-blosc-format chunks, replaced below
        assertEquals(List.of("c/0", "c/1", "c/2", "zarr.json"), store.list());
        return array;
    }

    @Test
    void aBloscCodecReadsCBlosc2Chunks() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = bloscArray(store);
        store.set("c/0", hex(ZSTD_SHUFFLE));
        store.set("c/1", hex(LZ4_BITSHUFFLE));
        store.set("c/2", hex(SEVENS));
        int[] expected = new int[96];
        for (int i = 0; i < 64; i++) {
            expected[i] = 3 * i - 40;
        }
        java.util.Arrays.fill(expected, 64, 96, 7);
        assertArrayEquals(expected, array.readInts());
        assertArrayEquals(expected, Zarr.openArray(store).readInts());
    }

    @Test
    void aBlosc2ChunkClaimingMoreThanTheChunkIsRefused() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = bloscArray(store);
        byte[] zeros = hex(SEVENS.substring(0, 64)); // 32-byte header: all zeros, claiming 1 GiB
        zeros[31] = 0x10;
        zeros[4] = 0;
        zeros[5] = 0;
        zeros[6] = 0;
        zeros[7] = 0x40;
        zeros[12] = 32;
        store.set("c/0", zeros);
        ZarrFormatException e = assertThrows(ZarrFormatException.class, array::readInts);
        assertTrue(e.getMessage().contains("claims 1073741824 bytes"), e.getMessage());
    }

    @Test
    void aBlosc2FeatureFalconDoesNotReadIsUnsupported() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = bloscArray(store);
        byte[] dictionary = hex(ZSTD_SHUFFLE);
        dictionary[31] = 0x01; // compressed with a dictionary
        store.set("c/0", dictionary);
        ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class, array::readInts);
        assertTrue(e.getMessage().contains("dictionary"), e.getMessage());
    }
}
