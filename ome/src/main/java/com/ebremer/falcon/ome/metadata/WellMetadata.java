package com.ebremer.falcon.ome.metadata;

import java.util.List;
import java.util.Objects;

/**
 * The {@code well} metadata of a plate's well: its fields of view.
 *
 * @param version (0.4) the metadata's version, or null
 * @param images  the fields of view, each an image group in the well's group
 */
public record WellMetadata(String version, List<FieldOfView> images) {

    /**
     * Creates the metadata.
     *
     * @throws NullPointerException if {@code images} is null
     */
    public WellMetadata {
        images = List.copyOf(images);
    }

    /**
     * A field of view.
     *
     * @param path        the image group's name in the well's group
     * @param acquisition the acquisition it belongs to, or null
     */
    public record FieldOfView(String path, Long acquisition) {

        /**
         * Creates the field of view.
         *
         * @throws NullPointerException if {@code path} is null
         */
        public FieldOfView {
            Objects.requireNonNull(path, "path");
        }
    }
}
