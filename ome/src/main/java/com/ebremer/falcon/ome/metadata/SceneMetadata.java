package com.ebremer.falcon.ome.metadata;

import java.util.List;

/**
 * (0.6) The {@code scene} metadata: how the images below a group relate in space, as transformations
 * between their coordinate systems and the scene's own.
 *
 * @param coordinateSystems the scene's own coordinate systems; the first, if any, is the one to show by
 *                          default
 * @param transformations   the transformations between coordinate systems of the scene and of its images
 */
public record SceneMetadata(List<CoordinateSystem> coordinateSystems, List<Transformation> transformations) {

    /**
     * Creates the metadata.
     *
     * @throws NullPointerException if a list is null
     */
    public SceneMetadata {
        coordinateSystems = List.copyOf(coordinateSystems);
        transformations = List.copyOf(transformations);
    }
}
