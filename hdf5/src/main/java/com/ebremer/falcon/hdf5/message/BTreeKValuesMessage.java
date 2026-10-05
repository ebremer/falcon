package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.BTreeKValues;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * B-tree 'K' Values message (type 19, spec section IV.A.2.s): the non-default node-split values for a
 * file's version-1 B-trees, stored in the superblock extension. (Files with default values omit it,
 * and version-1 B-tree nodes are self-describing, so these values are informational for a reader.)
 *
 * <p>Body: {@code version(1)=0 · indexed-storage internal-node K(2) · group internal-node K(2) · group
 * leaf-node K(2)}.
 */
public final class BTreeKValuesMessage {

    private BTreeKValuesMessage() {
    }

    public static BTreeKValues parse(HdfBuffer body) {
        if (body.size() < 7 || body.getUnsignedByte(0) != 0) {
            throw new HdfFormatException("unsupported B-tree 'K' values message");
        }
        return new BTreeKValues(body.getUnsignedShort(5), body.getUnsignedShort(3), body.getUnsignedShort(1));
    }
}
