package com.ebremer.falcon.ome;

/**
 * How a level of an image pyramid is made from the one before: each pixel from a block of the larger level's
 * pixels (2&times;2 by default), a partial block at the array's far edge from the pixels it has.
 */
public enum Downsampling {

    /**
     * The block's mean: images. Integers are rounded half up; booleans are true when at least half the block
     * is. The level's pixels sit at the blocks' centers, so its translation moves by half a block.
     */
    MEAN("mean"),
    /**
     * The block's first pixel (numpy's {@code [::2, ::2]}): fast, and keeps every value an image had, so suits
     * label images. The level's pixels sit where the blocks' first pixels did.
     */
    NEAREST("nearest"),
    /**
     * The block's most frequent value, the first of them on a tie: label images, whose values are labels, not
     * quantities. The level's pixels sit at the blocks' centers.
     */
    MODE("mode");

    private final String id;

    Downsampling(String id) {
        this.id = id;
    }

    /** {@return the method's name, as a multiscale's {@code type} records it: such as {@code "mean"}} */
    public String id() {
        return id;
    }

    /** {@return whether the method's pixels sit at the centers of their blocks} */
    boolean centered() {
        return this != NEAREST;
    }

    /**
     * {@return the method a name gives, ignoring case}
     *
     * @param name the name, such as {@code "mean"}
     * @throws IllegalArgumentException if no method has that name
     */
    public static Downsampling of(String name) {
        for (Downsampling d : values()) {
            if (d.id.equalsIgnoreCase(name)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown downsampling method '" + name + "': expected mean, nearest, "
                + "or mode");
    }
}
