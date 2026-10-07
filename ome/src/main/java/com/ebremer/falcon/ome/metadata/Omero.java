package com.ebremer.falcon.ome.metadata;

import java.util.List;

/**
 * The transitional {@code omero} metadata: how an image's channels are named and rendered.
 *
 * @param id       the image's ID in OMERO, or null
 * @param name     the image's name, or null
 * @param version  (0.4) the metadata's version, or null
 * @param channels the channels, in the order of the channel axis
 * @param rdefs    the rendering defaults, or null
 */
public record Omero(Long id, String name, String version, List<Channel> channels, Rdefs rdefs) {

    /**
     * Creates the metadata.
     *
     * @throws NullPointerException if {@code channels} is null
     */
    public Omero {
        channels = List.copyOf(channels);
    }

    /**
     * {@return metadata naming and coloring channels, and nothing else}
     *
     * @param channels the channels
     */
    public static Omero of(List<Channel> channels) {
        return new Omero(null, null, null, channels, null);
    }

    /**
     * One channel.
     *
     * @param label       its name, or null
     * @param color       its color: six hexadecimal digits, RGB, such as {@code "00FF00"}
     * @param active      whether it is shown, or null
     * @param coefficient its rendering coefficient, or null
     * @param family      its rendering family (such as {@code "linear"}), or null
     * @param inverted    whether it is rendered inverted, or null
     * @param window      the range of values it is rendered over
     */
    public record Channel(String label, String color, Boolean active, Double coefficient, String family,
                          Boolean inverted, Window window) {

        /**
         * A channel with a label, a color, and a window, shown linearly.
         *
         * @param label  its name
         * @param color  its color, six hexadecimal digits
         * @param window the range of values it is rendered over
         * @return the channel
         */
        public static Channel of(String label, String color, Window window) {
            return new Channel(label, color, true, 1.0, "linear", false, window);
        }
    }

    /**
     * The range a channel is rendered over: values from {@code start} to {@code end} are shown, within the
     * data's range from {@code min} to {@code max}.
     *
     * @param min   the smallest value the channel can hold
     * @param max   the largest value the channel can hold
     * @param start the value shown darkest
     * @param end   the value shown brightest
     */
    public record Window(double min, double max, double start, double end) {
    }

    /**
     * The rendering defaults.
     *
     * @param defaultT the time point shown first, or null
     * @param defaultZ the z section shown first, or null
     * @param model    {@code "color"} or {@code "greyscale"}, or null
     */
    public record Rdefs(Integer defaultT, Integer defaultZ, String model) {
    }
}
