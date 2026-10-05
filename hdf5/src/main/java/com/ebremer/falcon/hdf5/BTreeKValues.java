package com.ebremer.falcon.hdf5;

/**
 * The split values ('K') of a file's version-1 B-trees, set when the file was created
 * ({@code H5Pset_sym_k}, {@code H5Pset_istore_k}). A node holds up to 2K entries before it splits.
 * Readers need none of them, since every node records its own entry count; they describe how the file
 * was written (see {@link Hdf5File#btreeKValues()}).
 *
 * @param groupLeafNodeK              group symbol-table leaf nodes (libhdf5's default 4)
 * @param groupInternalNodeK          group B-tree internal nodes (default 16)
 * @param indexedStorageInternalNodeK chunk (indexed-storage) B-tree internal nodes (default 32)
 */
public record BTreeKValues(int groupLeafNodeK, int groupInternalNodeK, int indexedStorageInternalNodeK) {

    /** libhdf5's defaults, which a file records only when it differs from them. */
    public static final BTreeKValues DEFAULTS = new BTreeKValues(4, 16, 32);
}
