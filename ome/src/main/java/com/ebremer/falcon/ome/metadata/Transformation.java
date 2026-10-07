package com.ebremer.falcon.ome.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A coordinate transformation: a function from points of its input coordinate system to points of its
 * output one. OME-Zarr 0.4 and 0.5 use {@link Scale} and {@link Translation} (and {@link Identity}) to map a
 * multiscale level's array coordinates to physical ones; 0.6 adds the rest, with named {@link #input()} and
 * {@link #output()} coordinate systems, for transformations between images and coordinate systems.
 *
 * <p>Parameters may be stored in a Zarr array instead of the JSON ({@code path}); a transformation read from
 * a store has them loaded when it is reached through a {@code MultiscaleImage} or {@code Scene}, and
 * {@link #apply} of one whose parameters are not loaded throws {@link IllegalStateException}.
 *
 * <p>{@link #apply} maps a point as the specification defines; {@link #inverse()} gives the inverse where
 * it has a closed form (empty otherwise, as for {@link Displacements}).
 */
public sealed interface Transformation {

    /** {@return the transformation's {@code type}, such as {@code "scale"}} */
    String type();

    /** {@return the transformation's {@code name}, or null} */
    String name();

    /** {@return (0.6) the coordinate system it maps from, or null} */
    CoordinateSystemRef input();

    /** {@return (0.6) the coordinate system it maps to, or null} */
    CoordinateSystemRef output();

    /**
     * {@return this transformation with another name, input, and output}
     *
     * @param name   the new name, or null
     * @param input  the new input, or null
     * @param output the new output, or null
     */
    Transformation with(String name, CoordinateSystemRef input, CoordinateSystemRef output);

    /**
     * Maps a point from the input coordinate system to the output one.
     *
     * @param point the point's coordinates
     * @return the mapped point's coordinates
     * @throws IllegalArgumentException      if the point has the wrong number of coordinates
     * @throws IllegalStateException         if the parameters are stored at a path and not loaded
     * @throws UnsupportedOperationException for a transformation of an unknown type
     */
    double[] apply(double[] point);

    /**
     * {@return the inverse transformation, from the output coordinate system to the input one, if it has a
     * closed form: empty for a singular matrix, a zero scale, a dropped axis, a vector field, or a
     * transformation of an unknown type}
     */
    Optional<Transformation> inverse();

    /**
     * The {@code identity} transformation.
     *
     * @param name   its name, or null
     * @param input  its input, or null
     * @param output its output, or null
     */
    record Identity(String name, CoordinateSystemRef input, CoordinateSystemRef output) implements Transformation {

        /** {@return a nameless identity, with no input or output} */
        public static Identity of() {
            return new Identity(null, null, null);
        }

        @Override
        public String type() {
            return "identity";
        }

        @Override
        public Identity with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Identity(name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            return point.clone();
        }

        @Override
        public Optional<Transformation> inverse() {
            return Optional.of(new Identity(name, output, input));
        }
    }

    /**
     * The {@code mapAxis} transformation, an axis permutation: output axis {@code i} is input axis
     * {@code mapAxis[i]}.
     *
     * @param mapAxis the permutation
     * @param name    its name, or null
     * @param input   its input, or null
     * @param output  its output, or null
     */
    record MapAxis(int[] mapAxis, String name, CoordinateSystemRef input, CoordinateSystemRef output)
            implements Transformation {

        /**
         * Creates the transformation.
         *
         * @throws NullPointerException if {@code mapAxis} is null
         */
        public MapAxis {
            mapAxis = mapAxis.clone();
        }

        /** {@return the permutation: output axis {@code i} is input axis {@code mapAxis[i]}} */
        @Override
        public int[] mapAxis() {
            return mapAxis.clone();
        }

        @Override
        public String type() {
            return "mapAxis";
        }

        @Override
        public MapAxis with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new MapAxis(mapAxis, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            requireLength(point, mapAxis.length);
            double[] out = new double[mapAxis.length];
            for (int i = 0; i < mapAxis.length; i++) {
                out[i] = point[mapAxis[i]];
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            int[] inverse = new int[mapAxis.length];
            for (int i = 0; i < mapAxis.length; i++) {
                inverse[mapAxis[i]] = i;
            }
            return Optional.of(new MapAxis(inverse, name, output, input));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MapAxis m && Arrays.equals(mapAxis, m.mapAxis) && Objects.equals(name, m.name)
                    && Objects.equals(input, m.input) && Objects.equals(output, m.output);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(mapAxis), name, input, output);
        }

        @Override
        public String toString() {
            return "MapAxis" + Arrays.toString(mapAxis);
        }
    }

    /**
     * The {@code projectAxis} transformation: drops the input coordinates at {@code droppedInputs}, then
     * inserts zeros at the output indices {@code createdOutputs}.
     *
     * @param droppedInputs  the input indices dropped (empty for none)
     * @param createdOutputs the output indices created (empty for none)
     * @param name           its name, or null
     * @param input          its input, or null
     * @param output         its output, or null
     */
    record ProjectAxis(int[] droppedInputs, int[] createdOutputs, String name, CoordinateSystemRef input,
                       CoordinateSystemRef output) implements Transformation {

        /** Creates the transformation; a null array means none. */
        public ProjectAxis {
            droppedInputs = droppedInputs == null ? new int[0] : droppedInputs.clone();
            createdOutputs = createdOutputs == null ? new int[0] : createdOutputs.clone();
        }

        /** {@return the input indices dropped; empty for none} */
        @Override
        public int[] droppedInputs() {
            return droppedInputs.clone();
        }

        /** {@return the output indices created; empty for none} */
        @Override
        public int[] createdOutputs() {
            return createdOutputs.clone();
        }

        @Override
        public String type() {
            return "projectAxis";
        }

        @Override
        public ProjectAxis with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new ProjectAxis(droppedInputs, createdOutputs, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            List<Double> kept = new ArrayList<>();
            for (int i = 0; i < point.length; i++) {
                if (!contains(droppedInputs, i)) {
                    kept.add(point[i]);
                }
            }
            int[] created = createdOutputs.clone();
            Arrays.sort(created);
            for (int index : created) {
                if (index > kept.size()) {
                    throw new IllegalArgumentException("projectAxis creates output " + index + " of a "
                            + (kept.size() + 1) + "-dimensional point");
                }
                kept.add(index, 0.0);
            }
            double[] out = new double[kept.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = kept.get(i);
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            return droppedInputs.length == 0
                    ? Optional.of(new ProjectAxis(createdOutputs, new int[0], name, output, input))
                    : Optional.empty();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ProjectAxis p && Arrays.equals(droppedInputs, p.droppedInputs)
                    && Arrays.equals(createdOutputs, p.createdOutputs) && Objects.equals(name, p.name)
                    && Objects.equals(input, p.input) && Objects.equals(output, p.output);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(droppedInputs), Arrays.hashCode(createdOutputs), name, input,
                    output);
        }

        @Override
        public String toString() {
            return "ProjectAxis[dropped=" + Arrays.toString(droppedInputs) + ", created="
                    + Arrays.toString(createdOutputs) + "]";
        }
    }

    /**
     * The {@code scale} transformation: each coordinate multiplied by its factor.
     *
     * @param scale  the factors, or null while they are stored at {@code path} and not loaded
     * @param path   the Zarr array that stores them, or null when the JSON gives them
     * @param name   its name, or null
     * @param input  its input, or null
     * @param output its output, or null
     */
    record Scale(double[] scale, String path, String name, CoordinateSystemRef input, CoordinateSystemRef output)
            implements Transformation {

        /** Creates the transformation. */
        public Scale {
            scale = scale == null ? null : scale.clone();
        }

        /**
         * A nameless scale, with no input or output.
         *
         * @param scale the factors
         * @return the transformation
         */
        public static Scale of(double... scale) {
            return new Scale(Objects.requireNonNull(scale, "scale"), null, null, null, null);
        }

        /** {@return the factors, or null while they are stored at {@link #path()} and not loaded} */
        @Override
        public double[] scale() {
            return scale == null ? null : scale.clone();
        }

        @Override
        public String type() {
            return "scale";
        }

        @Override
        public Scale with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Scale(scale, path, name, input, output);
        }

        /**
         * {@return this transformation with its factors loaded}
         *
         * @param factors the factors stored at {@link #path()}
         */
        public Scale withScale(double[] factors) {
            return new Scale(factors, path, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            double[] s = loaded(scale, path);
            requireLength(point, s.length);
            double[] out = new double[s.length];
            for (int i = 0; i < s.length; i++) {
                out[i] = point[i] * s[i];
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            if (scale == null) {
                return Optional.empty();
            }
            double[] inverse = new double[scale.length];
            for (int i = 0; i < scale.length; i++) {
                if (scale[i] == 0) {
                    return Optional.empty();
                }
                inverse[i] = 1 / scale[i];
            }
            return Optional.of(new Scale(inverse, null, name, output, input));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Scale s && Arrays.equals(scale, s.scale) && Objects.equals(path, s.path)
                    && Objects.equals(name, s.name) && Objects.equals(input, s.input)
                    && Objects.equals(output, s.output);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(scale), path, name, input, output);
        }

        @Override
        public String toString() {
            return "Scale" + (scale != null ? Arrays.toString(scale) : "[path=" + path + "]");
        }
    }

    /**
     * The {@code translation} transformation: each coordinate shifted by its offset.
     *
     * @param translation the offsets, or null while they are stored at {@code path} and not loaded
     * @param path        the Zarr array that stores them, or null when the JSON gives them
     * @param name        its name, or null
     * @param input       its input, or null
     * @param output      its output, or null
     */
    record Translation(double[] translation, String path, String name, CoordinateSystemRef input,
                       CoordinateSystemRef output) implements Transformation {

        /** Creates the transformation. */
        public Translation {
            translation = translation == null ? null : translation.clone();
        }

        /**
         * A nameless translation, with no input or output.
         *
         * @param translation the offsets
         * @return the transformation
         */
        public static Translation of(double... translation) {
            return new Translation(Objects.requireNonNull(translation, "translation"), null, null, null, null);
        }

        /** {@return the offsets, or null while they are stored at {@link #path()} and not loaded} */
        @Override
        public double[] translation() {
            return translation == null ? null : translation.clone();
        }

        @Override
        public String type() {
            return "translation";
        }

        @Override
        public Translation with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Translation(translation, path, name, input, output);
        }

        /**
         * {@return this transformation with its offsets loaded}
         *
         * @param offsets the offsets stored at {@link #path()}
         */
        public Translation withTranslation(double[] offsets) {
            return new Translation(offsets, path, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            double[] t = loaded(translation, path);
            requireLength(point, t.length);
            double[] out = new double[t.length];
            for (int i = 0; i < t.length; i++) {
                out[i] = point[i] + t[i];
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            if (translation == null) {
                return Optional.empty();
            }
            double[] inverse = new double[translation.length];
            for (int i = 0; i < inverse.length; i++) {
                inverse[i] = -translation[i];
            }
            return Optional.of(new Translation(inverse, null, name, output, input));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Translation t && Arrays.equals(translation, t.translation)
                    && Objects.equals(path, t.path) && Objects.equals(name, t.name)
                    && Objects.equals(input, t.input) && Objects.equals(output, t.output);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(translation), path, name, input, output);
        }

        @Override
        public String toString() {
            return "Translation" + (translation != null ? Arrays.toString(translation) : "[path=" + path + "]");
        }
    }

    /**
     * The {@code affine} transformation from N input dimensions to M output ones: the upper M&times;(N+1)
     * rows of its matrix in homogeneous coordinates, so that output {@code r} is
     * {@code sum(affine[r][c] * point[c]) + affine[r][N]}.
     *
     * @param affine the M rows of N+1 numbers, or null while they are stored at {@code path} and not loaded
     * @param path   the Zarr array that stores them (M&times;(N+1)), or null when the JSON gives them
     * @param name   its name, or null
     * @param input  its input, or null
     * @param output its output, or null
     */
    record Affine(double[][] affine, String path, String name, CoordinateSystemRef input, CoordinateSystemRef output)
            implements Transformation {

        /** Creates the transformation. */
        public Affine {
            affine = copy(affine);
        }

        /** {@return the matrix's rows, or null while they are stored at {@link #path()} and not loaded} */
        @Override
        public double[][] affine() {
            return copy(affine);
        }

        @Override
        public String type() {
            return "affine";
        }

        @Override
        public Affine with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Affine(affine, path, name, input, output);
        }

        /**
         * {@return this transformation with its matrix loaded}
         *
         * @param matrix the matrix stored at {@link #path()}
         */
        public Affine withAffine(double[][] matrix) {
            return new Affine(matrix, path, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            double[][] a = loaded(affine, path);
            double[] out = new double[a.length];
            for (int r = 0; r < a.length; r++) {
                requireLength(point, a[r].length - 1);
                double sum = a[r][point.length];
                for (int c = 0; c < point.length; c++) {
                    sum += a[r][c] * point[c];
                }
                out[r] = sum;
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            if (affine == null || affine.length == 0 || affine[0].length != affine.length + 1) {
                return Optional.empty();
            }
            int n = affine.length;
            double[][] linear = new double[n][n];
            double[] offset = new double[n];
            for (int r = 0; r < n; r++) {
                if (affine[r].length != n + 1) {
                    return Optional.empty();
                }
                System.arraycopy(affine[r], 0, linear[r], 0, n);
                offset[r] = affine[r][n];
            }
            double[][] inv = invert(linear);
            if (inv == null) {
                return Optional.empty();
            }
            double[][] result = new double[n][n + 1];
            for (int r = 0; r < n; r++) {
                System.arraycopy(inv[r], 0, result[r], 0, n);
                double t = 0;
                for (int c = 0; c < n; c++) {
                    t -= inv[r][c] * offset[c];
                }
                result[r][n] = t;
            }
            return Optional.of(new Affine(result, null, name, output, input));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Affine a && Arrays.deepEquals(affine, a.affine) && Objects.equals(path, a.path)
                    && Objects.equals(name, a.name) && Objects.equals(input, a.input)
                    && Objects.equals(output, a.output);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.deepHashCode(affine), path, name, input, output);
        }

        @Override
        public String toString() {
            return "Affine" + (affine != null ? Arrays.deepToString(affine) : "[path=" + path + "]");
        }
    }

    /**
     * The {@code rotation} transformation: an N&times;N rotation matrix applied to the point.
     *
     * @param rotation the matrix's rows, or null while they are stored at {@code path} and not loaded
     * @param path     the Zarr array that stores them (N&times;N), or null when the JSON gives them
     * @param name     its name, or null
     * @param input    its input, or null
     * @param output   its output, or null
     */
    record Rotation(double[][] rotation, String path, String name, CoordinateSystemRef input,
                    CoordinateSystemRef output) implements Transformation {

        /** Creates the transformation. */
        public Rotation {
            rotation = copy(rotation);
        }

        /** {@return the matrix's rows, or null while they are stored at {@link #path()} and not loaded} */
        @Override
        public double[][] rotation() {
            return copy(rotation);
        }

        @Override
        public String type() {
            return "rotation";
        }

        @Override
        public Rotation with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Rotation(rotation, path, name, input, output);
        }

        /**
         * {@return this transformation with its matrix loaded}
         *
         * @param matrix the matrix stored at {@link #path()}
         */
        public Rotation withRotation(double[][] matrix) {
            return new Rotation(matrix, path, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            double[][] m = loaded(rotation, path);
            requireLength(point, m.length);
            double[] out = new double[m.length];
            for (int r = 0; r < m.length; r++) {
                requireLength(point, m[r].length);
                double sum = 0;
                for (int c = 0; c < point.length; c++) {
                    sum += m[r][c] * point[c];
                }
                out[r] = sum;
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            if (rotation == null) {
                return Optional.empty();
            }
            int n = rotation.length;
            double[][] transpose = new double[n][n];
            for (int r = 0; r < n; r++) {
                if (rotation[r].length != n) {
                    return Optional.empty();
                }
                for (int c = 0; c < n; c++) {
                    transpose[c][r] = rotation[r][c];
                }
            }
            return Optional.of(new Rotation(transpose, null, name, output, input));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Rotation r && Arrays.deepEquals(rotation, r.rotation)
                    && Objects.equals(path, r.path) && Objects.equals(name, r.name)
                    && Objects.equals(input, r.input) && Objects.equals(output, r.output);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.deepHashCode(rotation), path, name, input, output);
        }

        @Override
        public String toString() {
            return "Rotation" + (rotation != null ? Arrays.deepToString(rotation) : "[path=" + path + "]");
        }
    }

    /**
     * The {@code sequence} transformation: its transformations applied in order, the first first.
     *
     * @param transformations the transformations
     * @param name            its name, or null
     * @param input           its input, or null
     * @param output          its output, or null
     */
    record Sequence(List<Transformation> transformations, String name, CoordinateSystemRef input,
                    CoordinateSystemRef output) implements Transformation {

        /** Creates the transformation. */
        public Sequence {
            transformations = List.copyOf(transformations);
        }

        /**
         * A nameless sequence, with no input or output.
         *
         * @param transformations the transformations, in order
         * @return the transformation
         */
        public static Sequence of(Transformation... transformations) {
            return new Sequence(List.of(transformations), null, null, null);
        }

        @Override
        public String type() {
            return "sequence";
        }

        @Override
        public Sequence with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Sequence(transformations, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            double[] p = point.clone();
            for (Transformation t : transformations) {
                p = t.apply(p);
            }
            return p;
        }

        @Override
        public Optional<Transformation> inverse() {
            List<Transformation> inverses = new ArrayList<>();
            for (Transformation t : transformations) {
                Optional<Transformation> inverse = t.inverse();
                if (inverse.isEmpty()) {
                    return Optional.empty();
                }
                inverses.addFirst(inverse.get());
            }
            return Optional.of(new Sequence(inverses, name, output, input));
        }
    }

    /**
     * The {@code displacements} transformation: the point plus the vector its field holds there.
     *
     * @param path          the multiscale group holding the field
     * @param interpolation how the field is interpolated between samples ({@code "linear"} by default), or
     *                      null when not given
     * @param field         the field, or null while it is not loaded
     * @param name          its name, or null
     * @param input         its input, or null
     * @param output        its output, or null
     */
    record Displacements(String path, String interpolation, VectorField field, String name, CoordinateSystemRef input,
                         CoordinateSystemRef output) implements Transformation {

        @Override
        public String type() {
            return "displacements";
        }

        @Override
        public Displacements with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Displacements(path, interpolation, field, name, input, output);
        }

        /**
         * {@return this transformation with its field loaded}
         *
         * @param loaded the field stored at {@link #path()}
         */
        public Displacements withField(VectorField loaded) {
            return new Displacements(path, interpolation, loaded, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            double[] v = loaded(field, path).at(point);
            requireLength(v, point.length);
            double[] out = new double[point.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = point[i] + v[i];
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            return Optional.empty();
        }
    }

    /**
     * The {@code coordinates} transformation: the point's new coordinates are the vector its field holds
     * there.
     *
     * @param path          the multiscale group holding the field
     * @param interpolation how the field is interpolated between samples ({@code "linear"} by default), or
     *                      null when not given
     * @param field         the field, or null while it is not loaded
     * @param name          its name, or null
     * @param input         its input, or null
     * @param output        its output, or null
     */
    record Coordinates(String path, String interpolation, VectorField field, String name, CoordinateSystemRef input,
                       CoordinateSystemRef output) implements Transformation {

        @Override
        public String type() {
            return "coordinates";
        }

        @Override
        public Coordinates with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Coordinates(path, interpolation, field, name, input, output);
        }

        /**
         * {@return this transformation with its field loaded}
         *
         * @param loaded the field stored at {@link #path()}
         */
        public Coordinates withField(VectorField loaded) {
            return new Coordinates(path, interpolation, loaded, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            return loaded(field, path).at(point).clone();
        }

        @Override
        public Optional<Transformation> inverse() {
            return Optional.empty();
        }
    }

    /**
     * The {@code bijection} transformation: an explicit forward transformation and its inverse.
     *
     * @param forward the forward transformation
     * @param reverse the inverse transformation, as the metadata gives it ({@code inverse})
     * @param name    its name, or null
     * @param input   its input, or null
     * @param output  its output, or null
     */
    record Bijection(Transformation forward, Transformation reverse, String name, CoordinateSystemRef input,
                     CoordinateSystemRef output) implements Transformation {

        /**
         * Creates the transformation.
         *
         * @throws NullPointerException if {@code forward} or {@code reverse} is null
         */
        public Bijection {
            Objects.requireNonNull(forward, "forward");
            Objects.requireNonNull(reverse, "reverse");
        }

        @Override
        public String type() {
            return "bijection";
        }

        @Override
        public Bijection with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Bijection(forward, reverse, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            return forward.apply(point);
        }

        @Override
        public Optional<Transformation> inverse() {
            return Optional.of(new Bijection(reverse, forward, name, output, input));
        }
    }

    /**
     * The {@code byDimension} transformation: lower-dimensional transformations, each mapping some of the
     * input's coordinates to some of the output's.
     *
     * @param transformations the parts
     * @param name            its name, or null
     * @param input           its input, or null
     * @param output          its output, or null
     */
    record ByDimension(List<Part> transformations, String name, CoordinateSystemRef input, CoordinateSystemRef output)
            implements Transformation {

        /** Creates the transformation. */
        public ByDimension {
            transformations = List.copyOf(transformations);
        }

        /**
         * One part of a {@code byDimension}: a transformation from the input coordinates at
         * {@code inputAxes} to the output coordinates at {@code outputAxes}.
         *
         * @param transformation the transformation
         * @param inputAxes      the indices of the input coordinates it takes, in its order
         * @param outputAxes     the indices of the output coordinates it gives, in its order
         */
        public record Part(Transformation transformation, int[] inputAxes, int[] outputAxes) {

            /**
             * Creates the part.
             *
             * @throws NullPointerException if an argument is null
             */
            public Part {
                Objects.requireNonNull(transformation, "transformation");
                inputAxes = inputAxes.clone();
                outputAxes = outputAxes.clone();
            }

            /** {@return the indices of the input coordinates the part takes} */
            @Override
            public int[] inputAxes() {
                return inputAxes.clone();
            }

            /** {@return the indices of the output coordinates the part gives} */
            @Override
            public int[] outputAxes() {
                return outputAxes.clone();
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Part p && transformation.equals(p.transformation)
                        && Arrays.equals(inputAxes, p.inputAxes) && Arrays.equals(outputAxes, p.outputAxes);
            }

            @Override
            public int hashCode() {
                return Objects.hash(transformation, Arrays.hashCode(inputAxes), Arrays.hashCode(outputAxes));
            }

            @Override
            public String toString() {
                return transformation + Arrays.toString(inputAxes) + "->" + Arrays.toString(outputAxes);
            }
        }

        @Override
        public String type() {
            return "byDimension";
        }

        @Override
        public ByDimension with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new ByDimension(transformations, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            int dimension = 0;
            for (Part part : transformations) {
                for (int axis : part.outputAxes) {
                    dimension = Math.max(dimension, axis + 1);
                }
            }
            double[] out = new double[dimension];
            for (Part part : transformations) {
                double[] sub = new double[part.inputAxes.length];
                for (int i = 0; i < sub.length; i++) {
                    if (part.inputAxes[i] < 0 || part.inputAxes[i] >= point.length) {
                        throw new IllegalArgumentException("byDimension takes input axis " + part.inputAxes[i]
                                + " of a " + point.length + "-dimensional point");
                    }
                    sub[i] = point[part.inputAxes[i]];
                }
                double[] mapped = part.transformation.apply(sub);
                requireLength(mapped, part.outputAxes.length);
                for (int i = 0; i < mapped.length; i++) {
                    out[part.outputAxes[i]] = mapped[i];
                }
            }
            return out;
        }

        @Override
        public Optional<Transformation> inverse() {
            List<Part> parts = new ArrayList<>();
            int covered = 0;
            for (Part part : transformations) {
                Optional<Transformation> inverse = part.transformation.inverse();
                if (inverse.isEmpty()) {
                    return Optional.empty();
                }
                parts.add(new Part(inverse.get(), part.outputAxes, part.inputAxes));
                covered += part.inputAxes.length;
            }
            boolean[] seen = new boolean[covered];
            for (Part part : transformations) {
                for (int axis : part.inputAxes) {
                    if (axis < 0 || axis >= covered || seen[axis]) {
                        return Optional.empty(); // an input axis used twice, or not at all: no inverse
                    }
                    seen[axis] = true;
                }
            }
            return Optional.of(new ByDimension(parts, name, output, input));
        }
    }

    /**
     * A transformation of a type this version of Falcon does not know, kept as its JSON.
     *
     * @param type   its {@code type}
     * @param json   the transformation as stored
     * @param name   its name, or null
     * @param input  its input, or null
     * @param output its output, or null
     */
    record Unknown(String type, JsonObject json, String name, CoordinateSystemRef input, CoordinateSystemRef output)
            implements Transformation {

        @Override
        public Unknown with(String name, CoordinateSystemRef input, CoordinateSystemRef output) {
            return new Unknown(type, json, name, input, output);
        }

        @Override
        public double[] apply(double[] point) {
            throw new UnsupportedOperationException("transformation type " + type + " is not supported");
        }

        @Override
        public Optional<Transformation> inverse() {
            return Optional.empty();
        }
    }

    private static void requireLength(double[] point, int length) {
        if (point.length != length) {
            throw new IllegalArgumentException("expected a " + length + "-dimensional point but got "
                    + point.length + " coordinates");
        }
    }

    private static <T> T loaded(T parameters, String path) {
        if (parameters == null) {
            throw new IllegalStateException("the parameters stored at '" + path + "' are not loaded: open the "
                    + "transformation through its MultiscaleImage or Scene");
        }
        return parameters;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    private static double[][] copy(double[][] matrix) {
        if (matrix == null) {
            return null;
        }
        double[][] copy = new double[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    /** The inverse of a square matrix by Gauss-Jordan elimination with partial pivoting, or null if singular. */
    private static double[][] invert(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][2 * n];
        double scale = 0;
        for (int r = 0; r < n; r++) {
            System.arraycopy(matrix[r], 0, a[r], 0, n);
            a[r][n + r] = 1;
            for (int c = 0; c < n; c++) {
                scale = Math.max(scale, Math.abs(matrix[r][c]));
            }
        }
        double tiny = scale * n * 1e-14;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) <= tiny) {
                return null;
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            double p = a[col][col];
            for (int c = 0; c < 2 * n; c++) {
                a[col][c] /= p;
            }
            for (int r = 0; r < n; r++) {
                if (r != col && a[r][col] != 0) {
                    double f = a[r][col];
                    for (int c = 0; c < 2 * n; c++) {
                        a[r][c] -= f * a[col][c];
                    }
                }
            }
        }
        double[][] inverse = new double[n][n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(a[r], n, inverse[r], 0, n);
        }
        return inverse;
    }
}
