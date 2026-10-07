package com.ebremer.falcon.hdf5.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.datatype.Datatype.StringPadding;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Fixed-length strings: where each ends, by its padding. */
class ElementsStringsTest {

    private static String[] read(String elements, StringPadding padding) {
        byte[] bytes = elements.getBytes(StandardCharsets.ISO_8859_1);
        return Elements.toStrings(MemorySegment.ofArray(bytes), bytes.length / 4,
                new Datatype.StringType(4, padding, Datatype.CharacterSet.ASCII));
    }

    /**
     * A space-padded string ends at its first NUL too, as libhdf5's readers see it: in the HDF5 library's own
     * tstring-at.h5, a space-padded dataset never written is all NULs, which h5dump and h5py read as "" (the
     * HDF5 conformance harness found Falcon reading four NULs).
     */
    @Test
    void aSpacePaddedStringEndsAtItsFirstNulAndLosesItsTrailingSpaces() {
        assertArrayEquals(new String[] {"", "ab", "a b", "ab", "abcd"},
                read("\0\0\0\0" + "ab  " + "a b " + "ab\0 " + "abcd", StringPadding.SPACE_PAD));
    }

    @Test
    void aNulPaddedOrTerminatedStringEndsAtItsFirstNul() {
        for (StringPadding padding : new StringPadding[] {StringPadding.NULL_PAD, StringPadding.NULL_TERMINATE}) {
            assertArrayEquals(new String[] {"", "ab", "ab", "ab  ", "abcd"},
                    read("\0\0\0\0" + "ab\0\0" + "ab\0c" + "ab  " + "abcd", padding));
        }
    }
}
