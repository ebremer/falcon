package com.ebremer.falcon.ome.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of {@code multiscales}: an image stored at several resolutions, its levels ordered from the
 * largest to the smallest.
 *
 * <p>Versions differ in how the levels' coordinates become physical ones. In 0.4 and 0.5, the image has
 * {@code axes}; each level maps its array coordinates to them by its own transformations, and then by the
 * image's ({@link #transformations()}). In 0.6, the image has named {@link #coordinateSystems()}; each level
 * maps to the one its transformation's output names, the <em>intrinsic</em> coordinate system, whose axes
 * {@link #axes()} gives; the image's transformations map from that one to other coordinate systems.
 *
 * @param name               the image's name, or null
 * @param type               the downsampling method that built the levels, or null
 * @param metadata           more about the downsampling method, or null
 * @param version            (0.4) the multiscale's own {@code version}, or null
 * @param axes               the dimensions of the levels' arrays: the {@code axes} (0.4, 0.5), or the axes of
 *                           the intrinsic coordinate system (0.6)
 * @param coordinateSystems  (0.6) the image's coordinate systems; empty before 0.6
 * @param datasets           the levels, from the largest to the smallest
 * @param transformations    the image's {@code coordinateTransformations}; empty if none
 */
public record Multiscale(String name, String type, JsonObject metadata, String version, List<Axis> axes,
                         List<CoordinateSystem> coordinateSystems, List<Dataset> datasets,
                         List<Transformation> transformations) {

    /**
     * Creates a multiscale.
     *
     * @throws NullPointerException if a list is null
     */
    public Multiscale {
        axes = List.copyOf(axes);
        coordinateSystems = List.copyOf(coordinateSystems);
        datasets = List.copyOf(datasets);
        transformations = List.copyOf(transformations);
    }

    /**
     * A multiscale with a name, axes, and levels, and nothing else.
     *
     * @param name     the image's name, or null
     * @param axes     the dimensions of its arrays
     * @param datasets its levels
     * @return the multiscale
     */
    public static Multiscale of(String name, List<Axis> axes, List<Dataset> datasets) {
        return new Multiscale(name, null, null, null, axes, List.of(), datasets, List.of());
    }

    /** {@return the number of levels} */
    public int levelCount() {
        return datasets.size();
    }

    /** {@return the levels' array paths, from the largest to the smallest} */
    public List<String> paths() {
        return datasets.stream().map(Dataset::path).toList();
    }

    /** {@return the axes' names, in the order of the arrays' dimensions} */
    public List<String> axisNames() {
        return axes.stream().map(Axis::name).toList();
    }

    /**
     * {@return (0.6) the intrinsic coordinate system: the one the levels' transformations map to; empty
     * before 0.6 or if the metadata names none it defines}
     */
    public Optional<CoordinateSystem> intrinsicCoordinateSystem() {
        if (datasets.isEmpty() || datasets.getFirst().transformations().isEmpty()) {
            return Optional.empty();
        }
        CoordinateSystemRef output = datasets.getFirst().transformations().getFirst().output();
        if (output == null || output.name() == null) {
            return Optional.empty();
        }
        return coordinateSystem(output.name());
    }

    /**
     * {@return (0.6) the coordinate system of that name, if the image defines one}
     *
     * @param name the coordinate system's name
     */
    public Optional<CoordinateSystem> coordinateSystem(String name) {
        return coordinateSystems.stream().filter(cs -> cs.name().equals(name)).findFirst();
    }

    /**
     * The transformation from a level's array coordinates to the image's physical ones: the level's own
     * transformations, then (0.4, 0.5) the image's.
     *
     * @param level the level, 0 for the largest
     * @return the transformation
     * @throws IndexOutOfBoundsException if there is no such level
     */
    public Transformation levelTransformation(int level) {
        List<Transformation> chain = new ArrayList<>(datasets.get(level).transformations());
        if (coordinateSystems.isEmpty()) {
            chain.addAll(transformations);
        }
        return chain.size() == 1 ? chain.getFirst() : new Transformation.Sequence(chain, null, null, null);
    }

    /**
     * The size of a level's pixels along each axis, in the axes' units: the factor its array coordinates are
     * multiplied by on the way to physical ones.
     *
     * @param level the level, 0 for the largest
     * @return a factor for each axis
     * @throws IndexOutOfBoundsException if there is no such level
     * @throws IllegalStateException     if the level's transformations are other than scales, translations,
     *                                   and identities, or are stored at a path and not loaded
     */
    public double[] scale(int level) {
        return affineParts(level)[0];
    }

    /**
     * The physical position of a level's first pixel's center: the offset added on the way to physical
     * coordinates, after the scale.
     *
     * @param level the level, 0 for the largest
     * @return an offset for each axis
     * @throws IndexOutOfBoundsException if there is no such level
     * @throws IllegalStateException     as {@link #scale(int)}
     */
    public double[] translation(int level) {
        return affineParts(level)[1];
    }

    /** The level's transformation as {@code x * scale + translation}: {scale, translation}. */
    private double[][] affineParts(int level) {
        Transformation t = levelTransformation(level);
        int n = axes.size();
        double[] scale = new double[n];
        double[] offset = new double[n];
        java.util.Arrays.fill(scale, 1);
        fold(t, scale, offset);
        return new double[][] {scale, offset};
    }

    private static void fold(Transformation t, double[] scale, double[] offset) {
        switch (t) {
            case Transformation.Identity i -> { }
            case Transformation.Scale s -> {
                double[] f = parameters(s.scale(), s.path(), scale.length);
                for (int i = 0; i < scale.length; i++) {
                    scale[i] *= f[i];
                    offset[i] *= f[i];
                }
            }
            case Transformation.Translation tr -> {
                double[] f = parameters(tr.translation(), tr.path(), scale.length);
                for (int i = 0; i < scale.length; i++) {
                    offset[i] += f[i];
                }
            }
            case Transformation.Sequence seq -> seq.transformations().forEach(c -> fold(c, scale, offset));
            default -> throw new IllegalStateException("a level's transformation is a " + t.type()
                    + ", not a scale or translation");
        }
    }

    private static double[] parameters(double[] values, String path, int n) {
        if (values == null) {
            throw new IllegalStateException("the level's parameters stored at '" + path + "' are not loaded");
        }
        if (values.length != n) {
            throw new IllegalStateException("a level's transformation has " + values.length
                    + " parameters for " + n + " axes");
        }
        return values;
    }

    /**
     * {@return this multiscale with other levels}
     *
     * @param levels the new levels
     */
    public Multiscale withDatasets(List<Dataset> levels) {
        return new Multiscale(name, type, metadata, version, axes, coordinateSystems,
                Objects.requireNonNull(levels, "levels"), transformations);
    }
}
