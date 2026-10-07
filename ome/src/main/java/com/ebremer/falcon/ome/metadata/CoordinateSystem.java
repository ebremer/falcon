package com.ebremer.falcon.ome.metadata;

import java.util.List;
import java.util.Objects;

/**
 * (0.6) A named coordinate system: its axes, in the order of a point's coordinates.
 *
 * @param name the coordinate system's name, unique among its siblings
 * @param axes its axes; their number is its dimensionality
 */
public record CoordinateSystem(String name, List<Axis> axes) {

    /**
     * Creates a coordinate system.
     *
     * @throws NullPointerException if an argument is null
     */
    public CoordinateSystem {
        Objects.requireNonNull(name, "name");
        axes = List.copyOf(axes);
    }

    /** {@return the number of axes} */
    public int dimension() {
        return axes.size();
    }
}
