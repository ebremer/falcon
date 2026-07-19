package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.io.FileContext;

/**
 * A dataset (or, for now, any non-group leaf) in the HDF5 hierarchy.
 *
 * <p>Stage H1 exposes only identity (name/path). Type, shape, and data access are added in stages
 * H2–H4.
 */
public final class Dataset extends Hdf5Object {

    private Dataset(FileContext ctx, String name, String path, long objectHeaderAddress) {
        super(ctx, name, path, objectHeaderAddress);
    }

    static Dataset child(FileContext ctx, String name, String parentPath, long objectHeaderAddress) {
        return new Dataset(ctx, name, childPath(parentPath, name), objectHeaderAddress);
    }

    @Override
    public boolean isGroup() {
        return false;
    }
}
