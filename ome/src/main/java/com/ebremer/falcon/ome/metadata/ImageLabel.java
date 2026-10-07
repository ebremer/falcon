package com.ebremer.falcon.ome.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code image-label} metadata of a label image: the colors its values are shown in, their properties,
 * and the image it labels.
 *
 * @param version     (0.4) the metadata's version, or null
 * @param colors      a color for each label value; empty if none
 * @param properties  further properties of the label values, each an object with a {@code label-value};
 *                    empty if none
 * @param sourceImage the path from the label image's group to the image it labels, or null for the default,
 *                    {@code ../../}
 */
public record ImageLabel(String version, List<LabelColor> colors, List<JsonObject> properties, String sourceImage) {

    /**
     * Creates the metadata.
     *
     * @throws NullPointerException if a list is null
     */
    public ImageLabel {
        colors = List.copyOf(colors);
        properties = List.copyOf(properties);
    }

    /**
     * {@return metadata that colors label values, and nothing else}
     *
     * @param colors the colors
     */
    public static ImageLabel of(List<LabelColor> colors) {
        return new ImageLabel(null, colors, List.of(), null);
    }

    /**
     * The color of one label value.
     *
     * @param labelValue the value
     * @param rgba       its color: red, green, blue, and alpha, each 0 to 255; or null
     */
    public record LabelColor(long labelValue, int[] rgba) {

        /** Creates the color. */
        public LabelColor {
            rgba = rgba == null ? null : rgba.clone();
        }

        /** {@return the color: red, green, blue, and alpha, each 0 to 255; or null} */
        @Override
        public int[] rgba() {
            return rgba == null ? null : rgba.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LabelColor c && labelValue == c.labelValue && Arrays.equals(rgba, c.rgba);
        }

        @Override
        public int hashCode() {
            return Objects.hash(labelValue, Arrays.hashCode(rgba));
        }

        @Override
        public String toString() {
            return labelValue + "=" + Arrays.toString(rgba);
        }
    }
}
