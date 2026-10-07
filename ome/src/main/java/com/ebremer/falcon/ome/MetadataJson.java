package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystem;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.Dataset;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the metadata records from their JSON, and writes them back, as each version lays them out. Reading
 * is lenient: it needs what the records hold, and reports a missing or mistyped part as an
 * {@link OmeFormatException} naming its JSON pointer.
 */
final class MetadataJson {

    private MetadataJson() {
    }

    // ---------------------------------------------------------------- reading

    static Multiscale multiscale(JsonValue value, OmeVersion version, String at) {
        JsonObject o = object(value, at);
        List<Dataset> datasets = new ArrayList<>();
        JsonArray ds = array(require(o, "datasets", at), at + "/datasets");
        for (int i = 0; i < ds.size(); i++) {
            datasets.add(dataset(ds.get(i), version, at + "/datasets/" + i));
        }
        List<Transformation> transformations = o.has("coordinateTransformations")
                ? transformations(o.get("coordinateTransformations"), version, at + "/coordinateTransformations", false)
                : List.of();
        List<CoordinateSystem> systems = new ArrayList<>();
        List<Axis> axes;
        if (version.hasCoordinateSystems()) {
            if (o.has("coordinateSystems")) {
                JsonArray cs = array(o.get("coordinateSystems"), at + "/coordinateSystems");
                for (int i = 0; i < cs.size(); i++) {
                    systems.add(coordinateSystem(cs.get(i), at + "/coordinateSystems/" + i));
                }
            }
            Multiscale probe = new Multiscale(null, null, null, null, List.of(), systems, datasets, transformations);
            axes = probe.intrinsicCoordinateSystem().map(CoordinateSystem::axes)
                    .orElse(systems.isEmpty() ? List.of() : systems.getFirst().axes());
        } else {
            axes = axes(require(o, "axes", at), at + "/axes");
        }
        return new Multiscale(optString(o, "name", at), optString(o, "type", at), optObject(o, "metadata", at),
                optString(o, "version", at), axes, systems, datasets, transformations);
    }

    static Dataset dataset(JsonValue value, OmeVersion version, String at) {
        JsonObject o = object(value, at);
        String path = string(require(o, "path", at), at + "/path");
        List<Transformation> transformations = o.has("coordinateTransformations")
                ? transformations(o.get("coordinateTransformations"), version, at + "/coordinateTransformations", true)
                : List.of();
        return new Dataset(path, transformations);
    }

    static List<Axis> axes(JsonValue value, String at) {
        JsonArray a = array(value, at);
        List<Axis> axes = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            JsonValue v = a.get(i);
            if (v instanceof JsonString s) { // 0.3's axes, by name
                axes.add(Axis.of(s.value(), switch (s.value()) {
                    case "x", "y", "z" -> Axis.SPACE;
                    case "c" -> Axis.CHANNEL;
                    case "t" -> Axis.TIME;
                    default -> null;
                }, null));
                continue;
            }
            String p = at + "/" + i;
            JsonObject o = object(v, p);
            axes.add(new Axis(string(require(o, "name", p), p + "/name"), optString(o, "type", p),
                    optString(o, "unit", p), optBoolean(o, "discrete", p), optString(o, "longName", p)));
        }
        return axes;
    }

    static CoordinateSystem coordinateSystem(JsonValue value, String at) {
        JsonObject o = object(value, at);
        return new CoordinateSystem(string(require(o, "name", at), at + "/name"),
                axes(require(o, "axes", at), at + "/axes"));
    }

    static List<Transformation> transformations(JsonValue value, OmeVersion version, String at, boolean dataset) {
        JsonArray a = array(value, at);
        List<Transformation> list = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            list.add(transformation(a.get(i), version, at + "/" + i, dataset));
        }
        return list;
    }

    /**
     * One transformation. A {@code dataset}'s input is an array path; the 0.6 draft's string inputs and
     * outputs are read as references, a dataset's input as its path and anything else as a name.
     */
    static Transformation transformation(JsonValue value, OmeVersion version, String at, boolean dataset) {
        JsonObject o = object(value, at);
        String type = string(require(o, "type", at), at + "/type");
        String name = optString(o, "name", at);
        CoordinateSystemRef input = reference(o, "input", at, dataset);
        CoordinateSystemRef output = reference(o, "output", at, false);
        String path = optString(o, "path", at);
        return switch (type) {
            case "identity" -> new Transformation.Identity(name, input, output);
            case "mapAxis" -> new Transformation.MapAxis(ints(require(o, "mapAxis", at), at + "/mapAxis"), name,
                    input, output);
            case "projectAxis" -> new Transformation.ProjectAxis(
                    o.has("droppedInputs") ? ints(o.get("droppedInputs"), at + "/droppedInputs") : null,
                    o.has("createdOutputs") ? ints(o.get("createdOutputs"), at + "/createdOutputs") : null,
                    name, input, output);
            case "scale" -> new Transformation.Scale(parametersOrPath(o, "scale", path, at), path, name, input,
                    output);
            case "translation" -> new Transformation.Translation(parametersOrPath(o, "translation", path, at), path,
                    name, input, output);
            case "affine" -> new Transformation.Affine(matrixOrPath(o, "affine", path, at), path, name, input,
                    output);
            case "rotation" -> new Transformation.Rotation(matrixOrPath(o, "rotation", path, at), path, name, input,
                    output);
            case "sequence" -> new Transformation.Sequence(transformations(require(o, "transformations", at),
                    version, at + "/transformations", false), name, input, output);
            case "displacements" -> new Transformation.Displacements(string(require(o, "path", at), at + "/path"),
                    optString(o, "interpolation", at), null, name, input, output);
            case "coordinates" -> new Transformation.Coordinates(string(require(o, "path", at), at + "/path"),
                    optString(o, "interpolation", at), null, name, input, output);
            case "bijection" -> new Transformation.Bijection(
                    transformation(require(o, "forward", at), version, at + "/forward", false),
                    transformation(require(o, "inverse", at), version, at + "/inverse", false), name, input, output);
            case "byDimension" -> {
                JsonArray parts = array(require(o, "transformations", at), at + "/transformations");
                List<Transformation.ByDimension.Part> list = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    String p = at + "/transformations/" + i;
                    JsonObject part = object(parts.get(i), p);
                    list.add(new Transformation.ByDimension.Part(
                            transformation(require(part, "transformation", p), version, p + "/transformation", false),
                            ints(require(part, "inputAxes", p), p + "/inputAxes"),
                            ints(require(part, "outputAxes", p), p + "/outputAxes")));
                }
                yield new Transformation.ByDimension(list, name, input, output);
            }
            default -> new Transformation.Unknown(type, o, name, input, output);
        };
    }

    private static CoordinateSystemRef reference(JsonObject o, String key, String at, boolean arrayPath) {
        if (!o.has(key) || o.get(key).isNull()) {
            return null;
        }
        JsonValue v = o.get(key);
        if (v instanceof JsonString s) {
            return arrayPath ? CoordinateSystemRef.array(s.value()) : CoordinateSystemRef.named(s.value());
        }
        String p = at + "/" + key;
        JsonObject ref = object(v, p);
        return new CoordinateSystemRef(optString(ref, "name", p), optString(ref, "path", p));
    }

    private static double[] parametersOrPath(JsonObject o, String key, String path, String at) {
        if (o.has(key)) {
            return doubles(o.get(key), at + "/" + key);
        }
        if (path == null) {
            throw new OmeFormatException("missing \"" + key + "\" at " + pointer(at));
        }
        return null;
    }

    private static double[][] matrixOrPath(JsonObject o, String key, String path, String at) {
        if (o.has(key)) {
            JsonArray rows = array(o.get(key), at + "/" + key);
            double[][] m = new double[rows.size()][];
            for (int r = 0; r < m.length; r++) {
                m[r] = doubles(rows.get(r), at + "/" + key + "/" + r);
            }
            return m;
        }
        if (path == null) {
            throw new OmeFormatException("missing \"" + key + "\" at " + pointer(at));
        }
        return null;
    }

    static Omero omero(JsonValue value, String at) {
        JsonObject o = object(value, at);
        List<Omero.Channel> channels = new ArrayList<>();
        if (o.has("channels")) {
            JsonArray cs = array(o.get("channels"), at + "/channels");
            for (int i = 0; i < cs.size(); i++) {
                String p = at + "/channels/" + i;
                JsonObject c = object(cs.get(i), p);
                Omero.Window window = null;
                if (c.has("window")) {
                    JsonObject w = object(c.get("window"), p + "/window");
                    window = new Omero.Window(optDouble(w, "min", 0, p), optDouble(w, "max", 0, p),
                            optDouble(w, "start", 0, p), optDouble(w, "end", 0, p));
                }
                channels.add(new Omero.Channel(optString(c, "label", p), optString(c, "color", p),
                        optBoolean(c, "active", p), c.has("coefficient") ? finite(number(c.get("coefficient"), p), p)
                        : null, optString(c, "family", p), optBoolean(c, "inverted", p), window));
            }
        }
        Omero.Rdefs rdefs = null;
        if (o.has("rdefs")) {
            JsonObject r = object(o.get("rdefs"), at + "/rdefs");
            rdefs = new Omero.Rdefs(optInt(r, "defaultT", at), optInt(r, "defaultZ", at), optString(r, "model", at));
        }
        Long id = o.has("id") && o.get("id") instanceof JsonNumber n ? integer(n, at + "/id") : null;
        return new Omero(id, optString(o, "name", at), optString(o, "version", at), channels, rdefs);
    }

    static ImageLabel imageLabel(JsonValue value, String at) {
        JsonObject o = object(value, at);
        List<ImageLabel.LabelColor> colors = new ArrayList<>();
        if (o.has("colors")) {
            JsonArray cs = array(o.get("colors"), at + "/colors");
            for (int i = 0; i < cs.size(); i++) {
                String p = at + "/colors/" + i;
                JsonObject c = object(cs.get(i), p);
                int[] rgba = c.has("rgba") ? ints(c.get("rgba"), p + "/rgba") : null;
                colors.add(new ImageLabel.LabelColor(integer(number(require(c, "label-value", p),
                        p + "/label-value"), p + "/label-value"), rgba));
            }
        }
        List<JsonObject> properties = new ArrayList<>();
        if (o.has("properties")) {
            JsonArray ps = array(o.get("properties"), at + "/properties");
            for (int i = 0; i < ps.size(); i++) {
                properties.add(object(ps.get(i), at + "/properties/" + i));
            }
        }
        String source = null;
        if (o.has("source")) {
            source = optString(object(o.get("source"), at + "/source"), "image", at + "/source");
        }
        return new ImageLabel(optString(o, "version", at), colors, properties, source);
    }

    static List<String> strings(JsonValue value, String at) {
        JsonArray a = array(value, at);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            list.add(string(a.get(i), at + "/" + i));
        }
        return list;
    }

    static PlateMetadata plate(JsonValue value, String at) {
        JsonObject o = object(value, at);
        List<String> rows = names(require(o, "rows", at), at + "/rows");
        List<String> columns = names(require(o, "columns", at), at + "/columns");
        List<PlateMetadata.WellRef> wells = new ArrayList<>();
        JsonArray ws = array(require(o, "wells", at), at + "/wells");
        for (int i = 0; i < ws.size(); i++) {
            String p = at + "/wells/" + i;
            JsonObject w = object(ws.get(i), p);
            String path = string(require(w, "path", p), p + "/path");
            int row = w.has("rowIndex") ? (int) integer(number(w.get("rowIndex"), p), p + "/rowIndex")
                    : rows.indexOf(path.substring(0, Math.max(0, path.indexOf('/'))));
            int column = w.has("columnIndex") ? (int) integer(number(w.get("columnIndex"), p), p + "/columnIndex")
                    : columns.indexOf(path.substring(path.indexOf('/') + 1));
            wells.add(new PlateMetadata.WellRef(path, row, column));
        }
        List<PlateMetadata.Acquisition> acquisitions = new ArrayList<>();
        if (o.has("acquisitions")) {
            JsonArray as = array(o.get("acquisitions"), at + "/acquisitions");
            for (int i = 0; i < as.size(); i++) {
                String p = at + "/acquisitions/" + i;
                JsonObject a = object(as.get(i), p);
                acquisitions.add(new PlateMetadata.Acquisition(integer(number(require(a, "id", p), p + "/id"),
                        p + "/id"), optString(a, "name", p), optInt(a, "maximumfieldcount", p),
                        optString(a, "description", p), optLong(a, "starttime", p), optLong(a, "endtime", p)));
            }
        }
        return new PlateMetadata(optString(o, "name", at), optString(o, "version", at),
                optInt(o, "field_count", at), rows, columns, wells, acquisitions);
    }

    private static List<String> names(JsonValue value, String at) {
        JsonArray a = array(value, at);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            names.add(string(require(object(a.get(i), at + "/" + i), "name", at + "/" + i), at + "/" + i + "/name"));
        }
        return names;
    }

    static WellMetadata well(JsonValue value, String at) {
        JsonObject o = object(value, at);
        List<WellMetadata.FieldOfView> images = new ArrayList<>();
        JsonArray is = array(require(o, "images", at), at + "/images");
        for (int i = 0; i < is.size(); i++) {
            String p = at + "/images/" + i;
            JsonObject image = object(is.get(i), p);
            images.add(new WellMetadata.FieldOfView(string(require(image, "path", p), p + "/path"),
                    optLong(image, "acquisition", p)));
        }
        return new WellMetadata(optString(o, "version", at), images);
    }

    static SceneMetadata scene(JsonValue value, OmeVersion version, String at) {
        JsonObject o = object(value, at);
        List<CoordinateSystem> systems = new ArrayList<>();
        if (o.has("coordinateSystems")) {
            JsonArray cs = array(o.get("coordinateSystems"), at + "/coordinateSystems");
            for (int i = 0; i < cs.size(); i++) {
                systems.add(coordinateSystem(cs.get(i), at + "/coordinateSystems/" + i));
            }
        }
        List<Transformation> transformations = o.has("coordinateTransformations")
                ? transformations(o.get("coordinateTransformations"), version, at + "/coordinateTransformations", false)
                : List.of();
        return new SceneMetadata(systems, transformations);
    }

    // ---------------------------------------------------------------- writing

    static JsonObject write(Multiscale m, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        if (version.hasCoordinateSystems()) {
            List<CoordinateSystem> systems = m.coordinateSystems();
            String intrinsic;
            if (systems.isEmpty()) {
                intrinsic = "physical";
                systems = List.of(new CoordinateSystem(intrinsic, m.axes()));
            } else {
                intrinsic = m.intrinsicCoordinateSystem().map(CoordinateSystem::name).orElse(systems.getFirst().name());
            }
            List<JsonValue> cs = new ArrayList<>();
            for (CoordinateSystem s : systems) {
                cs.add(write(s));
            }
            o.put("coordinateSystems", new JsonArray(cs));
            List<JsonValue> datasets = new ArrayList<>();
            for (Dataset d : m.datasets()) {
                datasets.add(obj("path", new JsonString(d.path()), "coordinateTransformations",
                        JsonArray.of(write(levelTransformation(d, intrinsic), version))));
            }
            o.put("datasets", new JsonArray(datasets));
        } else {
            o.put("axes", writeAxes(m.axes()));
            List<JsonValue> datasets = new ArrayList<>();
            for (Dataset d : m.datasets()) {
                datasets.add(obj("path", new JsonString(d.path()), "coordinateTransformations",
                        write(d.transformations(), version)));
            }
            o.put("datasets", new JsonArray(datasets));
        }
        if (!m.transformations().isEmpty()) {
            o.put("coordinateTransformations", write(m.transformations(), version));
        }
        putString(o, "name", m.name());
        putString(o, "type", m.type());
        if (m.metadata() != null) {
            o.put("metadata", m.metadata());
        }
        if (version == OmeVersion.V0_4) {
            o.put("version", new JsonString(version.id()));
        }
        return new JsonObject(o);
    }

    /** A 0.6 level's one transformation: its scale (and translation) from the array to the intrinsic system. */
    private static Transformation levelTransformation(Dataset d, String intrinsic) {
        CoordinateSystemRef input = CoordinateSystemRef.array(d.path());
        CoordinateSystemRef output = CoordinateSystemRef.named(intrinsic);
        List<Transformation> ts = d.transformations();
        Transformation t;
        if (ts.size() == 1) {
            t = ts.getFirst();
        } else if (ts.isEmpty()) {
            t = Transformation.Identity.of();
        } else {
            List<Transformation> plain = ts.stream().map(x -> x.with(x.name(), null, null)).toList();
            t = new Transformation.Sequence(plain, null, null, null);
        }
        return t.with(t.name(), input, output);
    }

    static JsonObject write(CoordinateSystem s) {
        return obj("name", new JsonString(s.name()), "axes", writeAxes(s.axes()));
    }

    static JsonArray writeAxes(List<Axis> axes) {
        List<JsonValue> list = new ArrayList<>();
        for (Axis a : axes) {
            Map<String, JsonValue> o = new LinkedHashMap<>();
            o.put("name", new JsonString(a.name()));
            putString(o, "type", a.type());
            putString(o, "unit", a.unit());
            if (a.discrete() != null) {
                o.put("discrete", JsonBool.of(a.discrete()));
            }
            putString(o, "longName", a.longName());
            list.add(new JsonObject(o));
        }
        return new JsonArray(list);
    }

    static JsonArray write(List<Transformation> transformations, OmeVersion version) {
        List<JsonValue> list = new ArrayList<>();
        for (Transformation t : transformations) {
            list.add(write(t, version));
        }
        return new JsonArray(list);
    }

    static JsonObject write(Transformation t, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        o.put("type", new JsonString(t.type()));
        switch (t) {
            case Transformation.Identity i -> { }
            case Transformation.MapAxis m -> o.put("mapAxis", ints(m.mapAxis()));
            case Transformation.ProjectAxis p -> {
                if (p.droppedInputs().length > 0) {
                    o.put("droppedInputs", ints(p.droppedInputs()));
                }
                if (p.createdOutputs().length > 0) {
                    o.put("createdOutputs", ints(p.createdOutputs()));
                }
            }
            case Transformation.Scale s -> parametersOrPath(o, "scale", s.scale(), s.path());
            case Transformation.Translation tr -> parametersOrPath(o, "translation", tr.translation(), tr.path());
            case Transformation.Affine a -> matrixOrPath(o, "affine", a.affine(), a.path());
            case Transformation.Rotation r -> matrixOrPath(o, "rotation", r.rotation(), r.path());
            case Transformation.Sequence s -> o.put("transformations", write(s.transformations(), version));
            case Transformation.Displacements d -> {
                o.put("path", new JsonString(d.path()));
                putString(o, "interpolation", d.interpolation());
            }
            case Transformation.Coordinates c -> {
                o.put("path", new JsonString(c.path()));
                putString(o, "interpolation", c.interpolation());
            }
            case Transformation.Bijection b -> {
                o.put("forward", write(b.forward(), version));
                o.put("inverse", write(b.reverse(), version));
            }
            case Transformation.ByDimension b -> {
                List<JsonValue> parts = new ArrayList<>();
                for (Transformation.ByDimension.Part part : b.transformations()) {
                    parts.add(obj("transformation", write(part.transformation(), version),
                            "inputAxes", ints(part.inputAxes()), "outputAxes", ints(part.outputAxes())));
                }
                o.put("transformations", new JsonArray(parts));
            }
            case Transformation.Unknown u -> {
                return u.json();
            }
        }
        if (version.hasCoordinateSystems()) {
            putString(o, "name", t.name());
            if (t.input() != null) {
                o.put("input", write(t.input()));
            }
            if (t.output() != null) {
                o.put("output", write(t.output()));
            }
        }
        return new JsonObject(o);
    }

    private static JsonObject write(CoordinateSystemRef ref) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        putString(o, "name", ref.name());
        putString(o, "path", ref.path());
        return new JsonObject(o);
    }

    private static void parametersOrPath(Map<String, JsonValue> o, String key, double[] values, String path) {
        if (path != null) {
            o.put("path", new JsonString(path));
        } else {
            o.put(key, doubles(values));
        }
    }

    private static void matrixOrPath(Map<String, JsonValue> o, String key, double[][] matrix, String path) {
        if (path != null) {
            o.put("path", new JsonString(path));
        } else {
            List<JsonValue> rows = new ArrayList<>();
            for (double[] row : matrix) {
                rows.add(doubles(row));
            }
            o.put(key, new JsonArray(rows));
        }
    }

    static JsonObject write(Omero omero, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        if (omero.id() != null) {
            o.put("id", JsonNumber.of(omero.id()));
        }
        putString(o, "name", omero.name());
        if (version == OmeVersion.V0_4) {
            o.put("version", new JsonString(version.id()));
        }
        List<JsonValue> channels = new ArrayList<>();
        for (Omero.Channel c : omero.channels()) {
            Map<String, JsonValue> ch = new LinkedHashMap<>();
            if (c.active() != null) {
                ch.put("active", JsonBool.of(c.active()));
            }
            if (c.coefficient() != null) {
                ch.put("coefficient", number(c.coefficient()));
            }
            putString(ch, "color", c.color());
            putString(ch, "family", c.family());
            if (c.inverted() != null) {
                ch.put("inverted", JsonBool.of(c.inverted()));
            }
            putString(ch, "label", c.label());
            if (c.window() != null) {
                Omero.Window w = c.window();
                ch.put("window", obj("end", number(w.end()), "max", number(w.max()), "min", number(w.min()),
                        "start", number(w.start())));
            }
            channels.add(new JsonObject(ch));
        }
        o.put("channels", new JsonArray(channels));
        if (omero.rdefs() != null) {
            Map<String, JsonValue> r = new LinkedHashMap<>();
            if (omero.rdefs().defaultT() != null) {
                r.put("defaultT", JsonNumber.of(omero.rdefs().defaultT()));
            }
            if (omero.rdefs().defaultZ() != null) {
                r.put("defaultZ", JsonNumber.of(omero.rdefs().defaultZ()));
            }
            putString(r, "model", omero.rdefs().model());
            o.put("rdefs", new JsonObject(r));
        }
        return new JsonObject(o);
    }

    static JsonObject write(ImageLabel label, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        if (version == OmeVersion.V0_4) {
            o.put("version", new JsonString(version.id()));
        }
        if (!label.colors().isEmpty()) {
            List<JsonValue> colors = new ArrayList<>();
            for (ImageLabel.LabelColor c : label.colors()) {
                Map<String, JsonValue> co = new LinkedHashMap<>();
                co.put("label-value", JsonNumber.of(c.labelValue()));
                if (c.rgba() != null) {
                    co.put("rgba", ints(c.rgba()));
                }
                colors.add(new JsonObject(co));
            }
            o.put("colors", new JsonArray(colors));
        }
        if (!label.properties().isEmpty()) {
            o.put("properties", new JsonArray(new ArrayList<>(label.properties())));
        }
        if (label.sourceImage() != null) {
            o.put("source", obj("image", new JsonString(label.sourceImage())));
        }
        return new JsonObject(o);
    }

    static JsonObject write(PlateMetadata plate, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        if (!plate.acquisitions().isEmpty()) {
            List<JsonValue> list = new ArrayList<>();
            for (PlateMetadata.Acquisition a : plate.acquisitions()) {
                Map<String, JsonValue> ao = new LinkedHashMap<>();
                ao.put("id", JsonNumber.of(a.id()));
                putString(ao, "name", a.name());
                if (a.maximumFieldCount() != null) {
                    ao.put("maximumfieldcount", JsonNumber.of(a.maximumFieldCount()));
                }
                putString(ao, "description", a.description());
                if (a.startTime() != null) {
                    ao.put("starttime", JsonNumber.of(a.startTime()));
                }
                if (a.endTime() != null) {
                    ao.put("endtime", JsonNumber.of(a.endTime()));
                }
                list.add(new JsonObject(ao));
            }
            o.put("acquisitions", new JsonArray(list));
        }
        o.put("columns", namesJson(plate.columns()));
        if (plate.fieldCount() != null) {
            o.put("field_count", JsonNumber.of(plate.fieldCount()));
        }
        putString(o, "name", plate.name());
        o.put("rows", namesJson(plate.rows()));
        if (version != OmeVersion.V0_6) { // 0.5's text still asks for it, and ome-zarr-models and ome-zarr-py want it
            o.put("version", new JsonString(version.id()));
        }
        List<JsonValue> wells = new ArrayList<>();
        for (PlateMetadata.WellRef w : plate.wells()) {
            wells.add(obj("path", new JsonString(w.path()), "rowIndex", JsonNumber.of(w.rowIndex()),
                    "columnIndex", JsonNumber.of(w.columnIndex())));
        }
        o.put("wells", new JsonArray(wells));
        return new JsonObject(o);
    }

    private static JsonArray namesJson(List<String> names) {
        List<JsonValue> list = new ArrayList<>();
        for (String n : names) {
            list.add(obj("name", new JsonString(n)));
        }
        return new JsonArray(list);
    }

    static JsonObject write(WellMetadata well, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        List<JsonValue> images = new ArrayList<>();
        for (WellMetadata.FieldOfView f : well.images()) {
            Map<String, JsonValue> io = new LinkedHashMap<>();
            if (f.acquisition() != null) {
                io.put("acquisition", JsonNumber.of(f.acquisition()));
            }
            io.put("path", new JsonString(f.path()));
            images.add(new JsonObject(io));
        }
        o.put("images", new JsonArray(images));
        if (version == OmeVersion.V0_4) {
            o.put("version", new JsonString(version.id()));
        }
        return new JsonObject(o);
    }

    static JsonObject write(SceneMetadata scene, OmeVersion version) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        o.put("coordinateTransformations", write(scene.transformations(), version));
        if (!scene.coordinateSystems().isEmpty()) {
            List<JsonValue> cs = new ArrayList<>();
            for (CoordinateSystem s : scene.coordinateSystems()) {
                cs.add(write(s));
            }
            o.put("coordinateSystems", new JsonArray(cs));
        }
        return new JsonObject(o);
    }

    // ---------------------------------------------------------------- JSON helpers

    static JsonObject obj(Object... keysAndValues) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            o.put((String) keysAndValues[i], (JsonValue) keysAndValues[i + 1]);
        }
        return new JsonObject(o);
    }

    private static void putString(Map<String, JsonValue> o, String key, String value) {
        if (value != null) {
            o.put(key, new JsonString(value));
        }
    }

    static JsonNumber number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? JsonNumber.of((long) value) : JsonNumber.of(value);
    }

    static JsonArray doubles(double[] values) {
        List<JsonValue> list = new ArrayList<>();
        for (double v : values) {
            list.add(JsonNumber.of(v));
        }
        return new JsonArray(list);
    }

    static JsonArray ints(int[] values) {
        List<JsonValue> list = new ArrayList<>();
        for (int v : values) {
            list.add(JsonNumber.of(v));
        }
        return new JsonArray(list);
    }

    /** {@code at}, a JSON pointer; the attributes' root for an empty one. */
    static String pointer(String at) {
        return at.isEmpty() ? "/" : at;
    }

    static JsonValue require(JsonObject o, String key, String at) {
        if (!o.has(key)) {
            throw new OmeFormatException("missing \"" + key + "\" at " + pointer(at));
        }
        return o.get(key);
    }

    static JsonObject object(JsonValue v, String at) {
        if (v instanceof JsonObject o) {
            return o;
        }
        throw new OmeFormatException("expected an object at " + pointer(at) + " but found " + v.typeName());
    }

    static JsonArray array(JsonValue v, String at) {
        if (v instanceof JsonArray a) {
            return a;
        }
        throw new OmeFormatException("expected an array at " + pointer(at) + " but found " + v.typeName());
    }

    static String string(JsonValue v, String at) {
        if (v instanceof JsonString s) {
            return s.value();
        }
        throw new OmeFormatException("expected a string at " + pointer(at) + " but found " + v.typeName());
    }

    static JsonNumber number(JsonValue v, String at) {
        if (v instanceof JsonNumber n) {
            return n;
        }
        throw new OmeFormatException("expected a number at " + pointer(at) + " but found " + v.typeName());
    }

    static long integer(JsonNumber n, String at) {
        try {
            return n.longValue();
        } catch (JsonException e) {
            throw new OmeFormatException("expected an integer at " + pointer(at) + " but found " + n.literal(), e);
        }
    }

    static double[] doubles(JsonValue v, String at) {
        JsonArray a = array(v, at);
        double[] values = new double[a.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = finite(number(a.get(i), at + "/" + i), at + "/" + i);
        }
        return values;
    }

    /** A number's value, which must be finite: JSON has no infinities, and a literal such as 1e400 is one. */
    static double finite(JsonNumber n, String at) {
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            throw new OmeFormatException("expected a finite number at " + pointer(at) + " but found " + n.literal());
        }
        return d;
    }

    static int[] ints(JsonValue v, String at) {
        JsonArray a = array(v, at);
        int[] values = new int[a.size()];
        for (int i = 0; i < values.length; i++) {
            long l = integer(number(a.get(i), at + "/" + i), at + "/" + i);
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new OmeFormatException("integer out of range at " + pointer(at + "/" + i));
            }
            values[i] = (int) l;
        }
        return values;
    }

    static String optString(JsonObject o, String key, String at) {
        return o.has(key) && !o.get(key).isNull() ? string(o.get(key), at + "/" + key) : null;
    }

    static Boolean optBoolean(JsonObject o, String key, String at) {
        if (!o.has(key) || o.get(key).isNull()) {
            return null;
        }
        if (o.get(key) instanceof JsonBool b) {
            return b.value();
        }
        throw new OmeFormatException("expected a boolean at " + pointer(at + "/" + key));
    }

    static JsonObject optObject(JsonObject o, String key, String at) {
        return o.has(key) && !o.get(key).isNull() ? object(o.get(key), at + "/" + key) : null;
    }

    static Integer optInt(JsonObject o, String key, String at) {
        Long l = optLong(o, key, at);
        return l == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
    }

    static Long optLong(JsonObject o, String key, String at) {
        return o.has(key) && !o.get(key).isNull() ? integer(number(o.get(key), at + "/" + key), at + "/" + key) : null;
    }

    private static double optDouble(JsonObject o, String key, double fallback, String at) {
        return o.has(key) && !o.get(key).isNull() ? finite(number(o.get(key), at + "/" + key), at + "/" + key) : fallback;
    }
}
