package com.ebremer.falcon.ome.metadata;

/**
 * (0.6) The vector field of a {@code displacements} or {@code coordinates} transformation: the vector it
 * holds at a point of the transformation's input coordinate system, interpolated between its samples.
 */
@FunctionalInterface
public interface VectorField {

    /**
     * The field's vector at a point.
     *
     * @param point a point in the input coordinate system
     * @return the vector there: a displacement to add to the point, or the point's new coordinates
     */
    double[] at(double[] point);
}
