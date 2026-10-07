package com.ebremer.falcon.ome.metadata;

/**
 * (0.6) A transformation's {@code input} or {@code output}: a reference to a coordinate system by its
 * {@code name}, in the group at {@code path} (relative to the group holding the transformation), or to an
 * array's own coordinates by its {@code path} alone.
 *
 * @param name the coordinate system's name, or null when the reference is to an array
 * @param path the path to the group (or array) that defines it, or null for the same group
 */
public record CoordinateSystemRef(String name, String path) {

    /**
     * A reference to a coordinate system defined in the same group.
     *
     * @param name the coordinate system's name
     * @return the reference
     */
    public static CoordinateSystemRef named(String name) {
        return new CoordinateSystemRef(name, null);
    }

    /**
     * A reference to an array's coordinates, as a multiscale level's transformation takes them as its input.
     *
     * @param path the array's path
     * @return the reference
     */
    public static CoordinateSystemRef array(String path) {
        return new CoordinateSystemRef(null, path);
    }

    @Override
    public String toString() {
        return path == null ? String.valueOf(name) : name == null ? path : path + "#" + name;
    }
}
