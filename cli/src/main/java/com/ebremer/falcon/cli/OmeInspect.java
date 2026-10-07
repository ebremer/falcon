package com.ebremer.falcon.cli;

import com.ebremer.falcon.ome.MultiscaleImage;
import com.ebremer.falcon.ome.OmeMetadata;
import com.ebremer.falcon.ome.OmeZarr;
import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystem;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What {@code ls} and {@code info} say of an OME-Zarr group: what it is, and (for info) what it holds. */
final class OmeInspect {

    private OmeInspect() {
    }

    /** {@return the node's OME-Zarr kind for {@code ls}, such as {@code OME-Zarr 0.5 image}; empty if none} */
    static Optional<String> kind(ZarrNode node) {
        if (!(node instanceof ZarrGroup group)) {
            return Optional.empty();
        }
        try {
            return OmeZarr.metadata(group).map(m -> "OME-Zarr " + m.version() + " "
                    + new OmeZarr.OmeGroup(group, m).kind());
        } catch (RuntimeException e) {
            return Optional.of("OME-Zarr (unreadable: " + Errors.describe(e) + ")");
        }
    }

    /** {@return {@code info}'s rows for the group's OME-Zarr metadata; none if it has none} */
    static List<String[]> rows(ZarrGroup group) {
        List<String[]> rows = new ArrayList<>();
        Optional<OmeMetadata> found;
        try {
            found = OmeZarr.metadata(group);
        } catch (RuntimeException e) {
            rows.add(Hdf5Inspect.field("OME-Zarr", "unreadable: " + Errors.describe(e)));
            return rows;
        }
        if (found.isEmpty()) {
            return rows;
        }
        OmeMetadata m = found.get();
        OmeZarr.OmeGroup ome = new OmeZarr.OmeGroup(group, m);
        rows.add(Hdf5Inspect.field("OME-Zarr", m.version() + " " + ome.kind()));
        if (m.isImage()) {
            image(group, rows);
        }
        if (m.isLabels()) {
            rows.add(Hdf5Inspect.field("label images", String.join(", ", m.labels())));
        }
        if (m.isPlate()) {
            PlateMetadata p = m.plate();
            rows.add(Hdf5Inspect.field("plate", (p.name() == null ? "" : "'" + p.name() + "', ")
                    + count(p.rows().size(), "row") + " x " + count(p.columns().size(), "column") + ", "
                    + count(p.wells().size(), "well")));
            rows.add(Hdf5Inspect.field("wells", Table.clip(String.join(", ",
                    p.wells().stream().map(PlateMetadata.WellRef::path).toList()), 100)));
            for (PlateMetadata.Acquisition a : p.acquisitions()) {
                rows.add(Hdf5Inspect.field(a == p.acquisitions().getFirst() ? "acquisitions" : "",
                        a.id() + (a.name() == null ? "" : " '" + a.name() + "'")
                                + (a.maximumFieldCount() == null ? "" : ", up to "
                                + count(a.maximumFieldCount(), "field"))));
            }
        }
        if (m.isWell()) {
            rows.add(Hdf5Inspect.field("fields", String.join(", ", m.well().images().stream()
                    .map(f -> f.path() + (f.acquisition() == null ? "" : " (acquisition " + f.acquisition() + ")"))
                    .toList())));
        }
        if (m.isCollection()) {
            try {
                rows.add(Hdf5Inspect.field("images", String.join(", ", ome.asCollection().imagePaths())));
            } catch (RuntimeException e) {
                rows.add(Hdf5Inspect.field("images", "unreadable: " + Errors.describe(e)));
            }
        }
        if (!m.series().isEmpty()) {
            rows.add(Hdf5Inspect.field("series", String.join(", ", m.series())));
        }
        if (m.isScene()) {
            for (CoordinateSystem cs : m.scene().coordinateSystems()) {
                rows.add(Hdf5Inspect.field(cs == m.scene().coordinateSystems().getFirst() ? "coordinate systems" : "",
                        system(cs)));
            }
            for (Transformation t : m.scene().transformations()) {
                rows.add(Hdf5Inspect.field(t == m.scene().transformations().getFirst() ? "transformations" : "",
                        transformation(t)));
            }
        }
        return rows;
    }

    private static void image(ZarrGroup group, List<String[]> rows) {
        MultiscaleImage image;
        try {
            image = MultiscaleImage.open(group);
        } catch (RuntimeException e) {
            rows.add(Hdf5Inspect.field("image", "unreadable: " + Errors.describe(e)));
            return;
        }
        if (image.name() != null) {
            rows.add(Hdf5Inspect.field("name", image.name()));
        }
        rows.add(Hdf5Inspect.field("axes", String.join(", ", image.axes().stream().map(OmeInspect::axis).toList())));
        List<String[]> levels = new ArrayList<>();
        for (int k = 0; k < image.levelCount(); k++) {
            String path = image.multiscale().datasets().get(k).path();
            String shape;
            try {
                ZarrArray array = image.level(k);
                shape = Describe.shape(array.shape()) + " " + Describe.type(array.dataType(), null);
            } catch (RuntimeException e) {
                shape = "not stored";
            }
            String geometry;
            try {
                double[] translation = image.translation(k);
                boolean moved = false;
                for (double t : translation) {
                    moved |= t != 0;
                }
                geometry = "scale " + numbers(image.scale(k)) + (moved ? ", translation " + numbers(translation) : "");
            } catch (RuntimeException e) {
                geometry = "transformation " + image.levelTransformation(k).type();
            }
            levels.add(new String[] {path, shape, geometry});
        }
        for (int k = 0; k < levels.size(); k++) {
            String[] l = levels.get(k);
            rows.add(Hdf5Inspect.field(k == 0 ? "levels" : "", l[0] + ": " + l[1] + "; " + l[2]));
        }
        if (image.multiscale().type() != null) {
            rows.add(Hdf5Inspect.field("downsampling", image.multiscale().type()));
        }
        for (Transformation t : image.multiscale().coordinateSystems().isEmpty() ? List.<Transformation>of()
                : image.multiscale().transformations()) {
            rows.add(Hdf5Inspect.field(t == image.multiscale().transformations().getFirst() ? "transformations" : "",
                    transformation(t)));
        }
        Optional<Omero> omero = image.omero();
        omero.ifPresent(o -> {
            for (int c = 0; c < o.channels().size(); c++) {
                Omero.Channel ch = o.channels().get(c);
                rows.add(Hdf5Inspect.field(c == 0 ? "channels" : "", (ch.label() == null ? "channel " + c : ch.label())
                        + (ch.color() == null ? "" : " #" + ch.color())
                        + (ch.window() == null ? "" : ", window " + number(ch.window().start()) + " to "
                        + number(ch.window().end()))));
            }
        });
        Optional<ImageLabel> label = image.imageLabel();
        label.ifPresent(l -> {
            rows.add(Hdf5Inspect.field("label colors", l.colors().isEmpty() ? "none" : Table.clip(String.join(", ",
                    l.colors().stream().map(c -> c.labelValue() + (c.rgba() == null ? "" : " rgba"
                            + java.util.Arrays.toString(c.rgba()))).toList()), 100)));
            rows.add(Hdf5Inspect.field("source image", l.sourceImage() == null ? "../../" : l.sourceImage()));
        });
        List<String> labels;
        try {
            labels = image.labelNames();
        } catch (RuntimeException e) {
            labels = List.of();
        }
        if (!labels.isEmpty()) {
            rows.add(Hdf5Inspect.field("labels", String.join(", ", labels)));
        }
    }

    private static String count(int n, String thing) {
        return n + " " + thing + (n == 1 ? "" : "s");
    }

    private static String axis(Axis a) {
        List<String> parts = new ArrayList<>();
        if (a.type() != null) {
            parts.add(a.type());
        }
        if (a.unit() != null) {
            parts.add(a.unit());
        }
        return a.name() + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
    }

    private static String system(CoordinateSystem cs) {
        return cs.name() + ": " + String.join(", ", cs.axes().stream().map(OmeInspect::axis).toList());
    }

    static String transformation(Transformation t) {
        return t.type() + (t.name() == null ? "" : " '" + t.name() + "'") + (t.input() == null && t.output() == null ? ""
                : ", " + t.input() + " -> " + t.output());
    }

    private static String numbers(double[] values) {
        List<String> parts = new ArrayList<>();
        for (double v : values) {
            parts.add(number(v));
        }
        return String.join(", ", parts);
    }

    private static String number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }
}
