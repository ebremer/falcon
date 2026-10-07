package com.ebremer.falcon.ome.metadata;

import java.util.Objects;

/**
 * One dimension of an image or a coordinate system: an entry of {@code axes}.
 *
 * @param name     the axis's name, unique among its siblings (such as {@code "x"})
 * @param type     its type: {@code "space"}, {@code "time"}, {@code "channel"}, (0.6) {@code "array"},
 *                 {@code "coordinate"}, {@code "displacement"}, or a custom type; or null when not given
 * @param unit     its physical unit, such as {@code "micrometer"} or {@code "second"}; or null
 * @param discrete (0.6) whether the axis is discrete, so only integers index it; or null when not given
 * @param longName (0.6) a longer name or description; or null
 */
public record Axis(String name, String type, String unit, Boolean discrete, String longName) {

    /** The type of a spatial axis. */
    public static final String SPACE = "space";
    /** The type of a time axis. */
    public static final String TIME = "time";
    /** The type of a channel axis. */
    public static final String CHANNEL = "channel";

    /**
     * Creates an axis.
     *
     * @throws NullPointerException if {@code name} is null
     */
    public Axis {
        Objects.requireNonNull(name, "name");
    }

    /**
     * An axis with a name, a type, and a unit (which may be null).
     *
     * @param name the axis's name
     * @param type its type, or null
     * @param unit its unit, or null
     * @return the axis
     */
    public static Axis of(String name, String type, String unit) {
        return new Axis(name, type, unit, null, null);
    }

    /**
     * A spatial axis.
     *
     * @param name the axis's name, such as {@code "x"}
     * @param unit its unit, such as {@code "micrometer"}, or null
     * @return the axis
     */
    public static Axis space(String name, String unit) {
        return of(name, SPACE, unit);
    }

    /**
     * A time axis.
     *
     * @param name the axis's name, such as {@code "t"}
     * @param unit its unit, such as {@code "second"}, or null
     * @return the axis
     */
    public static Axis time(String name, String unit) {
        return of(name, TIME, unit);
    }

    /**
     * A channel axis, which has no unit.
     *
     * @param name the axis's name, such as {@code "c"}
     * @return the axis
     */
    public static Axis channel(String name) {
        return of(name, CHANNEL, null);
    }

    /** {@return whether this is a spatial axis} */
    public boolean isSpace() {
        return SPACE.equals(type);
    }

    /** {@return whether this is a time axis} */
    public boolean isTime() {
        return TIME.equals(type);
    }

    /** {@return whether this is a channel axis} */
    public boolean isChannel() {
        return CHANNEL.equals(type);
    }

    /**
     * {@return this axis with another unit}
     *
     * @param unit the new unit, or null
     */
    public Axis withUnit(String unit) {
        return new Axis(name, type, unit, discrete, longName);
    }
}
