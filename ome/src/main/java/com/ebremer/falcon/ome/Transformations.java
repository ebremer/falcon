package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.ome.metadata.VectorField;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the parameters a transformation keeps in Zarr arrays ({@code path}), relative to the group whose
 * metadata holds it: the vectors of a scale or translation, the matrix of an affine or rotation, and the
 * vector field of displacements or coordinates.
 */
final class Transformations {

    /** How many bytes of a vector field's chunks are kept, per field. */
    private static final long FIELD_CACHE = 64L << 20;

    private Transformations() {
    }

    static List<Transformation> resolve(List<Transformation> transformations, ZarrGroup base) {
        List<Transformation> list = new ArrayList<>(transformations.size());
        for (Transformation t : transformations) {
            list.add(resolve(t, base));
        }
        return list;
    }

    static Multiscale resolve(Multiscale m, ZarrGroup base) {
        List<com.ebremer.falcon.ome.metadata.Dataset> datasets = new ArrayList<>();
        for (var d : m.datasets()) {
            datasets.add(new com.ebremer.falcon.ome.metadata.Dataset(d.path(), resolve(d.transformations(), base)));
        }
        return new Multiscale(m.name(), m.type(), m.metadata(), m.version(), m.axes(), m.coordinateSystems(),
                datasets, resolve(m.transformations(), base));
    }

    /**
     * The transformation with every parameter it stores at a path loaded, from arrays below {@code base}. A
     * parameter that cannot be loaded is left unloaded, so that applying the transformation says so.
     */
    static Transformation resolve(Transformation t, ZarrGroup base) {
        try {
            return load(t, base);
        } catch (RuntimeException e) {
            return t;
        }
    }

    private static Transformation load(Transformation t, ZarrGroup base) {
        return switch (t) {
            case Transformation.Scale s when s.scale() == null -> s.withScale(vector(base, s.path()));
            case Transformation.Translation tr when tr.translation() == null ->
                    tr.withTranslation(vector(base, tr.path()));
            case Transformation.Affine a when a.affine() == null -> a.withAffine(matrix(base, a.path()));
            case Transformation.Rotation r when r.rotation() == null -> r.withRotation(matrix(base, r.path()));
            case Transformation.Sequence s -> new Transformation.Sequence(resolve(s.transformations(), base), s.name(),
                    s.input(), s.output());
            case Transformation.Bijection b -> new Transformation.Bijection(resolve(b.forward(), base),
                    resolve(b.reverse(), base), b.name(), b.input(), b.output());
            case Transformation.ByDimension b -> {
                List<Transformation.ByDimension.Part> parts = new ArrayList<>();
                for (var part : b.transformations()) {
                    parts.add(new Transformation.ByDimension.Part(resolve(part.transformation(), base),
                            part.inputAxes(), part.outputAxes()));
                }
                yield new Transformation.ByDimension(parts, b.name(), b.input(), b.output());
            }
            case Transformation.Displacements d when d.field() == null ->
                    d.withField(field(base, d.path(), d.interpolation(), true));
            case Transformation.Coordinates c when c.field() == null ->
                    c.withField(field(base, c.path(), c.interpolation(), false));
            default -> t;
        };
    }

    private static ZarrArray array(ZarrGroup base, String path) {
        ZarrNode node = base.child(trim(path)).orElseThrow(() -> new OmeFormatException(
                "transformation parameters at '" + path + "' are not stored"));
        if (node instanceof ZarrArray a) {
            return a;
        }
        if (node instanceof ZarrGroup g) { // a multiscale group of the parameters: its first level
            OmeMetadata m = OmeMetadata.read(g.attributes()).filter(OmeMetadata::isImage).orElseThrow(() ->
                    new OmeFormatException("transformation parameters at '" + path + "' are a group with no image"));
            return g.array(m.multiscales().getFirst().datasets().getFirst().path());
        }
        throw new IllegalStateException();
    }

    private static double[] vector(ZarrGroup base, String path) {
        ZarrArray a = array(base, path);
        if (a.rank() != 1) {
            throw new OmeFormatException("transformation parameters at '" + path + "' must be 1-dimensional");
        }
        return a.readDoubles();
    }

    private static double[][] matrix(ZarrGroup base, String path) {
        ZarrArray a = array(base, path);
        if (a.rank() != 2) {
            throw new OmeFormatException("a matrix at '" + path + "' must be 2-dimensional");
        }
        int rows = Math.toIntExact(a.shape()[0]);
        int columns = Math.toIntExact(a.shape()[1]);
        double[] values = a.readDoubles();
        double[][] m = new double[rows][columns];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(values, r * columns, m[r], 0, columns);
        }
        return m;
    }

    private static String trim(String path) {
        String p = path;
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * The vector field stored as the multiscale image at {@code path}: its first level has one dimension per
     * input axis and one (of type {@code displacement} or {@code coordinate}) for the vectors' components,
     * and maps its array coordinates to the input coordinate system by its scale and translation.
     */
    private static VectorField field(ZarrGroup base, String path, String interpolation, boolean displacements) {
        ZarrNode node = base.child(trim(path)).orElseThrow(() -> new OmeFormatException(
                "the vector field at '" + path + "' is not stored"));
        if (!(node instanceof ZarrGroup g)) {
            throw new OmeFormatException("the vector field at '" + path + "' must be a multiscale image");
        }
        MultiscaleImage image = MultiscaleImage.open(g);
        Multiscale m = image.multiscale();
        String vectorType = displacements ? "displacement" : "coordinate";
        int vectorAxis = -1;
        List<Axis> axes = m.axes();
        for (int i = 0; i < axes.size(); i++) {
            if (vectorType.equals(axes.get(i).type())) {
                vectorAxis = i;
            }
        }
        if (vectorAxis < 0) {
            throw new OmeFormatException("the vector field at '" + path + "' has no axis of type " + vectorType);
        }
        return new Field(image.level(0).withChunkCache(FIELD_CACHE), vectorAxis, m.scale(0), m.translation(0),
                !"nearest".equals(interpolation));
    }

    /**
     * A field sampled on a grid: the point is mapped to the grid's array coordinates by inverting the level's
     * scale and translation, and the vector there is read, interpolated multilinearly (or from the nearest
     * sample), with points beyond the grid taking the edge's values. Cubic interpolation is done linearly.
     */
    private record Field(ZarrArray array, int vectorAxis, double[] scale, double[] translation, boolean linear)
            implements VectorField {

        @Override
        public double[] at(double[] point) {
            int rank = array.rank();
            int n = rank - 1;
            if (point.length != n) {
                throw new IllegalArgumentException("the vector field takes " + n + "-dimensional points, not "
                        + point.length);
            }
            long[] shape = array.shape();
            int components = Math.toIntExact(shape[vectorAxis]);
            double[] index = new double[rank];
            int k = 0;
            for (int d = 0; d < rank; d++) {
                if (d == vectorAxis) {
                    continue;
                }
                double s = scale[d] == 0 ? 1 : scale[d];
                index[d] = (point[k++] - translation[d]) / s;
            }
            long[] lower = new long[rank];
            double[] weight = new double[rank];
            long[] extent = new long[rank];
            for (int d = 0; d < rank; d++) {
                if (d == vectorAxis) {
                    lower[d] = 0;
                    extent[d] = components;
                    continue;
                }
                double x = Math.max(0, Math.min(shape[d] - 1, index[d]));
                if (linear) {
                    lower[d] = Math.min((long) Math.floor(x), Math.max(0, shape[d] - 2));
                    weight[d] = x - lower[d];
                    extent[d] = Math.min(2, shape[d]);
                } else {
                    lower[d] = Math.round(x);
                    extent[d] = 1;
                }
            }
            double[] box = array.select(lower, extent).readDoubles();
            double[] result = new double[components];
            long corners = 1;
            for (int d = 0; d < rank; d++) {
                if (d != vectorAxis) {
                    corners *= extent[d];
                }
            }
            long[] stride = new long[rank];
            long s = 1;
            for (int d = rank - 1; d >= 0; d--) {
                stride[d] = s;
                s *= extent[d];
            }
            for (long c = 0; c < corners; c++) {
                double w = 1;
                long offset = 0;
                long rest = c;
                for (int d = rank - 1; d >= 0; d--) {
                    if (d == vectorAxis) {
                        continue;
                    }
                    long bit = rest % extent[d];
                    rest /= extent[d];
                    offset += bit * stride[d];
                    if (extent[d] == 2) {
                        w *= bit == 1 ? weight[d] : 1 - weight[d];
                    }
                }
                for (int v = 0; v < components; v++) {
                    result[v] += w * box[(int) (offset + v * stride[vectorAxis])];
                }
            }
            return result;
        }
    }
}
