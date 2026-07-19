package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * B-tree 'K' Values message (type 19, spec section IV.A.2.s): the non-default node-split values for a
 * file's version-1 B-trees, stored in the superblock extension. (Files with default values omit it,
 * and version-1 B-tree nodes are self-describing, so these values are informational for a reader.)
 *
 * <p>Body: {@code version(1) · indexed-storage internal-node K(2) · group internal-node K(2) · group
 * leaf-node K(2)}.
 *
 * @param indexedStorageInternalNodeK split value for chunk (indexed-storage) B-tree internal nodes
 * @param groupInternalNodeK          split value for group B-tree internal nodes
 * @param groupLeafNodeK              split value for group B-tree leaf nodes
 */
public record BTreeKValuesMessage(int indexedStorageInternalNodeK, int groupInternalNodeK, int groupLeafNodeK) {

    public static BTreeKValuesMessage parse(HdfBuffer body) {
        return new BTreeKValuesMessage(
                body.getUnsignedShort(1), body.getUnsignedShort(3), body.getUnsignedShort(5));
    }
}
