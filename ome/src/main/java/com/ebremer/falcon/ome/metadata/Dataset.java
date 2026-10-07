package com.ebremer.falcon.ome.metadata;

import java.util.List;
import java.util.Objects;

/**
 * One resolution level of a multiscale image: the array holding it and the transformations that map its
 * array coordinates to the image's physical ones.
 *
 * @param path            the array's path, relative to the image's group
 * @param transformations its {@code coordinateTransformations}: a scale, then possibly a translation (0.4,
 *                        0.5); or one scale, identity, or sequence of a scale and a translation, from the
 *                        array to the intrinsic coordinate system (0.6)
 */
public record Dataset(String path, List<Transformation> transformations) {

    /**
     * Creates a level.
     *
     * @throws NullPointerException if an argument is null
     */
    public Dataset {
        Objects.requireNonNull(path, "path");
        transformations = List.copyOf(transformations);
    }

    /**
     * A level whose array coordinates map to physical ones by a scale and, unless it is null, a translation.
     *
     * @param path        the array's path
     * @param scale       the size of its pixels along each axis
     * @param translation the physical position of its first pixel's center, or null for the origin
     * @return the level
     */
    public static Dataset of(String path, double[] scale, double[] translation) {
        return translation == null ? new Dataset(path, List.of(Transformation.Scale.of(scale)))
                : new Dataset(path, List.of(Transformation.Scale.of(scale), Transformation.Translation.of(translation)));
    }
}
