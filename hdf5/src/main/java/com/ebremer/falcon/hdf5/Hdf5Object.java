package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.io.FileContext;

/**
 * An object in the HDF5 hierarchy &mdash; a {@link Group} or a {@link Dataset} &mdash; identified by
 * its name, its absolute path, and the file address of its object header.
 */
public abstract sealed class Hdf5Object permits Group, Dataset {

    final FileContext ctx;
    private final String name;
    private final String path;
    private final long objectHeaderAddress;

    Hdf5Object(FileContext ctx, String name, String path, long objectHeaderAddress) {
        this.ctx = ctx;
        this.name = name;
        this.path = path;
        this.objectHeaderAddress = objectHeaderAddress;
    }

    /** The object's local link name ({@code ""} for the root group). */
    public String name() {
        return name;
    }

    /** The object's absolute path from the root (e.g. {@code /alpha/beta}). */
    public String path() {
        return path;
    }

    /** The file address of this object's header. */
    public long objectHeaderAddress() {
        return objectHeaderAddress;
    }

    public abstract boolean isGroup();

    /** Joins a parent path and a child name into an absolute path. */
    static String childPath(String parentPath, String childName) {
        return parentPath.equals("/") ? "/" + childName : parentPath + "/" + childName;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + (path.isEmpty() ? "/" : path) + "]";
    }
}
