package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.ValidationReport.Issue;
import com.ebremer.falcon.ome.ValidationReport.Severity;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.Store;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks OME-Zarr metadata against the specification of its version (0.4, 0.5, or 0.6): everything its JSON
 * schemas check, and the rules they cannot express, such as an axis order, a well path that matches its row
 * and column, or a rotation matrix that is orthonormal.
 *
 * <p>A group is checked with the hierarchy below it: its levels' arrays (their number of dimensions, data
 * type, and dimension names), its labels, a plate's wells and their images, a collection's images, and a
 * scene's images, each with its own metadata. {@link #metadataOnly(boolean) Metadata only}, the attributes
 * of the group alone are checked, and what they refer to is not opened.
 *
 * <p>An issue is an {@linkplain Severity#ERROR error} where the specification says MUST and a
 * {@linkplain Severity#WARNING warning} where it says SHOULD. {@linkplain #strict(boolean) Strictly}, the
 * recommended fields the specification's strict schemas require (an image's {@code name}, {@code type}, and
 * {@code metadata}; a label image's {@code colors}; a plate's {@code name}; an acquisition's {@code name} and
 * {@code maximumfieldcount}; and in 0.4 each part's {@code version}) are errors too.
 *
 * <p>A validator is immutable, and safe to use from several threads.
 */
public final class OmeValidator {

    private static final Set<String> SPACE_UNITS = Set.of("angstrom", "attometer", "centimeter", "decimeter",
            "exameter", "femtometer", "foot", "gigameter", "hectometer", "inch", "kilometer", "megameter", "meter",
            "micrometer", "mile", "millimeter", "nanometer", "parsec", "petameter", "picometer", "terameter", "yard",
            "yoctometer", "yottameter", "zeptometer", "zettameter");
    private static final Set<String> TIME_UNITS = Set.of("attosecond", "centisecond", "day", "decisecond",
            "exasecond", "femtosecond", "gigasecond", "hectosecond", "hour", "kilosecond", "megasecond",
            "microsecond", "millisecond", "minute", "nanosecond", "petasecond", "picosecond", "second", "terasecond",
            "yoctosecond", "yottasecond", "zeptosecond", "zettasecond");
    private static final Set<String> AXIS_TYPES_06 = Set.of("array", "space", "time", "channel", "coordinate",
            "displacement", "frequency");
    private static final Set<String> TRANSFORMATION_TYPES = Set.of("identity", "mapAxis", "projectAxis", "scale",
            "translation", "affine", "rotation", "sequence", "displacements", "coordinates", "bijection",
            "byDimension");
    private static final Pattern ALPHANUMERIC = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern WELL_PATH = Pattern.compile("[A-Za-z0-9]+/[A-Za-z0-9]+");
    private static final Pattern FIELD_PATH_06 = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern HEX_COLOR = Pattern.compile("[0-9A-Fa-f]{6}");

    private final boolean strict;
    private final boolean metadataOnly;

    /** A validator that checks hierarchies, and recommended fields only as warnings. */
    public OmeValidator() {
        this(false, false);
    }

    private OmeValidator(boolean strict, boolean metadataOnly) {
        this.strict = strict;
        this.metadataOnly = metadataOnly;
    }

    /**
     * {@return a validator like this one that is strict, or not}
     *
     * @param strict whether the recommended fields the strict schemas require are errors when missing
     */
    public OmeValidator strict(boolean strict) {
        return new OmeValidator(strict, metadataOnly);
    }

    /**
     * {@return a validator like this one that checks a group's metadata alone, or with the hierarchy below it}
     *
     * @param metadataOnly whether to leave the nodes the metadata refers to unopened
     */
    public OmeValidator metadataOnly(boolean metadataOnly) {
        return new OmeValidator(strict, metadataOnly);
    }

    /** {@return whether this validator is strict} */
    public boolean isStrict() {
        return strict;
    }

    /** {@return whether this validator checks metadata alone} */
    public boolean isMetadataOnly() {
        return metadataOnly;
    }

    /**
     * Checks one group's attributes, alone: what they say, not what they refer to.
     *
     * @param attributes the attributes, as a group's {@code zarr.json} or {@code .zattrs} holds them
     * @return what was found
     */
    public ValidationReport validate(JsonObject attributes) {
        Run run = (metadataOnly ? this : new OmeValidator(strict, true)).new Run(); // nothing else is there to open
        run.metadata(attributes, "/", null);
        return new ValidationReport(run.issues);
    }

    /**
     * Checks the store's root group, and unless {@linkplain #metadataOnly(boolean) metadata only}, the
     * hierarchy below it.
     *
     * @param store the store
     * @return what was found
     */
    public ValidationReport validate(Store store) {
        ZarrNode root;
        try {
            root = Zarr.open(store);
        } catch (ZarrException e) {
            return new ValidationReport(List.of(new Issue(Severity.ERROR, "/", "", "no Zarr group: "
                    + e.getMessage())));
        }
        if (!(root instanceof ZarrGroup group)) {
            return new ValidationReport(List.of(new Issue(Severity.ERROR, "/", "",
                    "the root is an array; OME-Zarr metadata is in a group")));
        }
        return validate(group);
    }

    /**
     * Checks a group, and unless {@linkplain #metadataOnly(boolean) metadata only}, the hierarchy below it.
     *
     * @param group the group
     * @return what was found
     */
    public ValidationReport validate(ZarrGroup group) {
        Run run = new Run();
        run.group(group, null, null);
        return new ValidationReport(run.issues);
    }

    // ================================================================ one validation

    /** What the hierarchy's checks pass down: the plate a well belongs to. */
    private record Parent(OmeVersion version, JsonObject plate) {
    }

    private final class Run {

        final List<Issue> issues = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        String node = "/";

        void error(String at, String message) {
            issues.add(new Issue(Severity.ERROR, node, at, message));
        }

        void warn(String at, String message) {
            issues.add(new Issue(Severity.WARNING, node, at, message));
        }

        /** A recommended field the strict schemas require. */
        void recommended(String at, String message) {
            issues.add(new Issue(strict ? Severity.ERROR : Severity.WARNING, node, at, message));
        }

        // ------------------------------------------------------------ the hierarchy

        void group(ZarrGroup group, Parent parent, String expected) {
            String display = group.path().isEmpty() ? "/" : group.path();
            if (!visited.add(display)) {
                return;
            }
            String saved = node;
            node = display;
            try {
                Optional<OmeVersion> version = metadata(group.attributes(), display, expected);
                if (version.isEmpty() || metadataOnly) {
                    return;
                }
                if (group.zarrFormat() != version.get().zarrFormat()) {
                    error("", "OME-Zarr " + version.get() + " is stored in Zarr v" + version.get().zarrFormat()
                            + ", but this group is Zarr v" + group.zarrFormat());
                }
                if (parent != null && parent.version() != version.get()) {
                    error("", "OME-Zarr " + version.get() + " below " + parent.version()
                            + ": the version must be the same throughout a hierarchy");
                }
                OmeMetadata metadata;
                try {
                    metadata = OmeMetadata.read(group.attributes()).orElseThrow();
                } catch (ZarrException e) {
                    return; // the metadata checks reported it
                }
                hierarchy(group, metadata, parent);
            } finally {
                node = saved;
            }
        }

        void hierarchy(ZarrGroup group, OmeMetadata m, Parent parent) {
            OmeVersion version = m.version();
            if (m.isImage()) {
                images(group, m);
            }
            if (m.isLabels()) {
                for (String path : m.labels()) {
                    Optional<ZarrNode> child = child(group, path);
                    if (child.isEmpty()) {
                        error("", "label image '" + path + "' is listed but not stored");
                    } else if (child.get() instanceof ZarrGroup g) {
                        group(g, new Parent(version, null), "label image");
                    } else {
                        error("", "label image '" + path + "' is an array, not a group");
                    }
                }
            }
            if (m.isPlate()) {
                JsonObject plateJson = namespace(group.attributes()).find("plate").orElseThrow().asObject();
                for (var well : m.plate().wells()) {
                    Optional<ZarrNode> child = child(group, well.path());
                    if (child.isEmpty()) {
                        error("", "well '" + well.path() + "' is listed but not stored");
                    } else if (child.get() instanceof ZarrGroup g) {
                        group(g, new Parent(version, plateJson), "well");
                    } else {
                        error("", "well '" + well.path() + "' is an array, not a group");
                    }
                }
            }
            if (m.isWell()) {
                wellImages(group, m, parent);
            }
            if (m.isCollection() && !m.isPlate()) {
                collection(group, version);
            }
            if (m.isScene()) {
                sceneImages(group, m);
            }
        }

        void images(ZarrGroup group, OmeMetadata m) {
            OmeVersion version = m.version();
            for (int i = 0; i < m.multiscales().size(); i++) {
                var ms = m.multiscales().get(i);
                String at = (version == OmeVersion.V0_4 ? "" : "/ome") + "/multiscales/" + i;
                long[] previous = null;
                Object dataType = null;
                for (int d = 0; d < ms.datasets().size(); d++) {
                    String path = ms.datasets().get(d).path();
                    String p = at + "/datasets/" + d;
                    Optional<ZarrNode> child = child(group, path);
                    if (child.isEmpty()) {
                        error(p, "level '" + path + "' is not stored");
                        continue;
                    }
                    if (!(child.get() instanceof ZarrArray array)) {
                        error(p, "level '" + path + "' is a group, not an array");
                        continue;
                    }
                    if (array.zarrFormat() != version.zarrFormat()) {
                        error(p, "level '" + path + "' is Zarr v" + array.zarrFormat() + "; OME-Zarr " + version
                                + " is Zarr v" + version.zarrFormat());
                    }
                    if (array.rank() != ms.axes().size()) {
                        error(p, "level '" + path + "' has " + array.rank() + " dimensions but the image has "
                                + ms.axes().size() + " axes");
                    }
                    if (array.rank() > 5) {
                        error(p, "level '" + path + "' has more than 5 dimensions");
                    }
                    if (dataType != null && !dataType.equals(array.dataType()) && version == OmeVersion.V0_6) {
                        error(p, "level '" + path + "' is " + array.dataType() + " but the first level is "
                                + dataType + ": every level must have the same data type");
                    }
                    dataType = dataType == null ? array.dataType() : dataType;
                    if (version == OmeVersion.V0_5) {
                        Optional<List<String>> names = array.dimensionNames();
                        if (names.isEmpty()) {
                            error(p, "level '" + path + "' has no dimension_names; 0.5 requires them, matching "
                                    + "the axes");
                        } else if (!names.get().equals(ms.axisNames())) {
                            error(p, "level '" + path + "' has dimension_names " + names.get()
                                    + " but the axes are " + ms.axisNames());
                        }
                    }
                    long[] shape = array.shape();
                    if (previous != null && previous.length == shape.length) {
                        for (int k = 0; k < shape.length; k++) {
                            if (shape[k] > previous[k]) {
                                error(p, "level '" + path + "' is larger than the level before it (dimension "
                                        + k + ": " + shape[k] + " > " + previous[k] + "): levels go from the "
                                        + "largest to the smallest");
                                break;
                            }
                        }
                    }
                    previous = shape;
                    if (m.isLabelImage() && array.dataType().kind() != DataTypeKind.INT
                            && array.dataType().kind() != DataTypeKind.UINT) {
                        error(p, "label level '" + path + "' is " + array.dataType()
                                + "; a label image's values must be integers");
                    }
                }
                if (m.omero() != null && i == 0) {
                    int c = channelAxis(ms.axes());
                    if (c >= 0 && previous != null && !ms.datasets().isEmpty()) {
                        Optional<ZarrNode> first = child(group, ms.datasets().getFirst().path());
                        if (first.isPresent() && first.get() instanceof ZarrArray a && c < a.rank()
                                && a.shape()[c] != m.omero().channels().size()) {
                            warn((version == OmeVersion.V0_4 ? "" : "/ome") + "/omero/channels", "omero lists "
                                    + m.omero().channels().size() + " channels but the channel axis has "
                                    + a.shape()[c]);
                        }
                    }
                }
            }
            if (m.isLabelImage()) {
                labelSource(group, m);
            }
            Optional<ZarrNode> labels = child(group, "labels");
            if (labels.isPresent() && labels.get() instanceof ZarrGroup g && !m.isLabelImage()) {
                group(g, new Parent(version, null), "labels");
            }
        }

        /** A label image's source image: as many levels as it has. */
        void labelSource(ZarrGroup group, OmeMetadata m) {
            String source = m.imageLabel().sourceImage() == null ? "../../" : m.imageLabel().sourceImage();
            String path = resolve(group.path(), source);
            if (path == null) {
                return;
            }
            try {
                ZarrNode node = Zarr.open(storeOf(group), path);
                if (node instanceof ZarrGroup image) {
                    Optional<OmeMetadata> sm = OmeMetadata.read(image.attributes());
                    if (sm.isPresent() && sm.get().isImage() && !m.multiscales().isEmpty()
                            && sm.get().multiscales().getFirst().levelCount() != m.multiscales().getFirst().levelCount()) {
                        error("", "the label image has " + m.multiscales().getFirst().levelCount()
                                + " levels but its image has " + sm.get().multiscales().getFirst().levelCount()
                                + ": they must have the same number");
                    }
                }
            } catch (ZarrException | NoSuchElementException e) {
                warn("", "the label image's source image '" + source + "' cannot be opened");
            }
        }

        void wellImages(ZarrGroup group, OmeMetadata m, Parent parent) {
            Set<Long> acquisitions = new HashSet<>();
            boolean several = false;
            if (parent != null && parent.plate() != null) {
                if (parent.plate().find("acquisitions").orElse(null) instanceof JsonArray as) {
                    for (JsonValue a : as.values()) {
                        if (a instanceof JsonObject o && o.find("id").orElse(null) instanceof JsonNumber n
                                && isInteger(n)) {
                            acquisitions.add(n.longValue());
                        }
                    }
                    several = as.size() > 1;
                }
            }
            String at = (m.version() == OmeVersion.V0_4 ? "" : "/ome") + "/well/images";
            for (int i = 0; i < m.well().images().size(); i++) {
                var field = m.well().images().get(i);
                if (parent != null && parent.plate() != null) {
                    if (field.acquisition() == null && several) {
                        error(at + "/" + i, "the plate has several acquisitions, so each field of view must say "
                                + "which it belongs to");
                    } else if (field.acquisition() != null && !acquisitions.isEmpty()
                            && !acquisitions.contains(field.acquisition())) {
                        error(at + "/" + i + "/acquisition", "acquisition " + field.acquisition()
                                + " is not one of the plate's");
                    }
                }
                Optional<ZarrNode> child = child(group, field.path());
                if (child.isEmpty()) {
                    error(at + "/" + i, "field of view '" + field.path() + "' is listed but not stored");
                } else if (child.get() instanceof ZarrGroup g) {
                    group(g, new Parent(m.version(), null), "image");
                } else {
                    error(at + "/" + i, "field of view '" + field.path() + "' is an array, not a group");
                }
            }
        }

        void collection(ZarrGroup group, OmeVersion version) {
            Optional<ZarrNode> ome = child(group, "OME");
            List<String> series = null;
            if (ome.isPresent() && ome.get() instanceof ZarrGroup g) {
                try {
                    Optional<OmeMetadata> om = OmeMetadata.read(g.attributes());
                    if (om.isPresent() && !om.get().series().isEmpty()) {
                        series = om.get().series();
                    }
                } catch (ZarrException e) {
                    // reported when the OME group itself is checked
                }
                group(g, new Parent(version, null), null);
            }
            if (series == null) {
                series = new ArrayList<>();
                for (int i = 0; child(group, Integer.toString(i)).isPresent(); i++) {
                    series.add(Integer.toString(i));
                }
                if (series.isEmpty()) {
                    error("", "a bioformats2raw collection with no OME/series lists its images as groups 0, 1, "
                            + "...; there is no group 0");
                }
            }
            for (String path : series) {
                Optional<ZarrNode> child = child(group, path);
                if (child.isEmpty()) {
                    error("", "series image '" + path + "' is not stored");
                } else if (child.get() instanceof ZarrGroup g) {
                    group(g, new Parent(version, null), "image");
                } else {
                    error("", "series image '" + path + "' is an array, not a group");
                }
            }
        }

        void sceneImages(ZarrGroup group, OmeMetadata m) {
            Set<String> paths = new java.util.LinkedHashSet<>();
            for (var t : m.scene().transformations()) {
                for (var ref : new com.ebremer.falcon.ome.metadata.CoordinateSystemRef[] {t.input(), t.output()}) {
                    if (ref != null && ref.path() != null) {
                        paths.add(ref.path());
                    }
                }
            }
            for (String path : paths) {
                Optional<ZarrNode> child = child(group, path);
                if (child.isEmpty()) {
                    error("/ome/scene", "the scene refers to '" + path + "', which is not stored");
                } else if (child.get() instanceof ZarrGroup g) {
                    group(g, new Parent(m.version(), null), "image");
                    Optional<OmeMetadata> im;
                    try {
                        im = OmeMetadata.read(g.attributes());
                    } catch (ZarrException e) {
                        continue;
                    }
                    for (var t : m.scene().transformations()) {
                        for (var ref : new com.ebremer.falcon.ome.metadata.CoordinateSystemRef[] {t.input(), t.output()}) {
                            if (ref != null && path.equals(ref.path()) && ref.name() != null && (im.isEmpty()
                                    || im.get().multiscales().stream().noneMatch(ms -> ms.coordinateSystem(ref.name())
                                    .isPresent()))) {
                                error("/ome/scene", "the scene refers to coordinate system '" + ref.name()
                                        + "' of '" + path + "', which does not define it");
                            }
                        }
                    }
                } else {
                    error("/ome/scene", "the scene refers to '" + path + "', which is an array, not an image");
                }
            }
        }

        Optional<ZarrNode> child(ZarrGroup group, String path) {
            String p = path;
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (p.isEmpty() || p.startsWith("/") || Arrays.asList(p.split("/")).contains("..")) {
                return Optional.empty();
            }
            try {
                return group.child(p);
            } catch (ZarrException | IllegalArgumentException e) {
                error("", "'" + path + "' cannot be opened: " + e.getMessage());
                return Optional.empty();
            }
        }

        // ------------------------------------------------------------ one group's metadata

        /**
         * Checks a group's attributes; {@code expected} names what the group must be, if the hierarchy above
         * says. Returns the version, if the attributes hold OME-Zarr metadata of a known one.
         */
        Optional<OmeVersion> metadata(JsonObject attributes, String display, String expected) {
            String saved = node;
            node = display;
            try {
                return checkMetadata(attributes, expected);
            } finally {
                node = saved;
            }
        }

        Optional<OmeVersion> checkMetadata(JsonObject attributes, String expected) {
            OmeVersion version;
            JsonObject ns;
            String at;
            if (attributes.has("ome")) {
                if (!(attributes.get("ome") instanceof JsonObject o)) {
                    error("/ome", "\"ome\" must be an object");
                    return Optional.empty();
                }
                ns = o;
                at = "/ome";
                if (!ns.has("version")) {
                    error("/ome", "\"ome\" must hold the \"version\"");
                    return Optional.empty();
                }
                if (!(ns.get("version") instanceof JsonString s)) {
                    error("/ome/version", "the version must be a string");
                    return Optional.empty();
                }
                Optional<OmeVersion> v = OmeVersion.of(s.value());
                if (v.isEmpty() || v.get() == OmeVersion.V0_4) {
                    error("/ome/version", "unsupported OME-Zarr version \"" + s.value() + "\": expected 0.5 or 0.6");
                    return Optional.empty();
                }
                version = v.get();
            } else if (OmeMetadata.KEYS.stream().anyMatch(attributes::has)) {
                version = OmeVersion.V0_4;
                ns = attributes;
                at = "";
            } else {
                error("", expected == null ? "no OME-Zarr metadata" : "no OME-Zarr metadata, but the hierarchy "
                        + "says this is a " + expected);
                return Optional.empty();
            }
            Doc doc = new Doc(version, ns, at);
            boolean any = false;
            if (ns.has("multiscales")) {
                any = true;
                doc.multiscales(ns.get("multiscales"), at + "/multiscales");
            }
            if (ns.has("omero")) {
                doc.omero(ns.get("omero"), at + "/omero");
            }
            if (ns.has("image-label")) {
                any = true;
                doc.imageLabel(ns.get("image-label"), at + "/image-label");
                if (!ns.has("multiscales")) {
                    if (metadataOnly) {
                        warn(at + "/image-label", "a label image must also be a multiscale image");
                    } else {
                        error(at + "/image-label", "a label image must also be a multiscale image");
                    }
                }
            }
            if (ns.has("labels")) {
                any = true;
                doc.labels(ns.get("labels"), at + "/labels");
            }
            if (ns.has("plate")) {
                any = true;
                doc.plate(ns.get("plate"), at + "/plate");
            }
            if (ns.has("well")) {
                any = true;
                doc.well(ns.get("well"), at + "/well");
            }
            if (ns.has("bioformats2raw.layout")) {
                any = true;
                JsonValue v = ns.get("bioformats2raw.layout");
                if (!(v instanceof JsonNumber n) || !isInteger(n) || n.longValue() != 3) {
                    error(at + "/bioformats2raw.layout", "bioformats2raw.layout must be 3");
                }
            }
            if (ns.has("series")) {
                any = true;
                doc.strings(ns.get("series"), at + "/series", "series");
            }
            if (ns.has("scene")) {
                if (version == OmeVersion.V0_6) {
                    any = true;
                    doc.scene(ns.get("scene"), at + "/scene");
                } else {
                    warn(at + "/scene", "scenes are OME-Zarr 0.6; ignored in " + version);
                }
            }
            if (!any) {
                error(at.isEmpty() ? "" : at, "no OME-Zarr metadata: expected multiscales, image-label, labels, "
                        + "plate, well, bioformats2raw.layout, series" + (version == OmeVersion.V0_6 ? ", or scene"
                        : ""));
            }
            if ("label image".equals(expected) && !ns.has("image-label")) {
                warn(at, "a label image should have image-label metadata");
            } else if ("well".equals(expected) && !ns.has("well")) {
                error(at, "a plate's well must have well metadata");
            } else if (("image".equals(expected) || "label image".equals(expected)) && !ns.has("multiscales")) {
                error(at, "expected a multiscale image here");
            } else if ("labels".equals(expected) && !ns.has("labels")) {
                error(at, "an image's labels group must list its label images");
            }
            return Optional.of(version);
        }

        // ------------------------------------------------------------ the parts

        /** One group's metadata, of one version. */
        private final class Doc {

            final OmeVersion version;
            final JsonObject ns;
            final String root;

            Doc(OmeVersion version, JsonObject ns, String root) {
                this.version = version;
                this.ns = ns;
                this.root = root;
            }

            void multiscales(JsonValue value, String at) {
                JsonArray ms = array(value, at, "multiscales");
                if (ms == null) {
                    return;
                }
                if (ms.size() == 0) {
                    error(at, "multiscales must hold at least one image");
                }
                unique(ms, at, "multiscales");
                for (int i = 0; i < ms.size(); i++) {
                    if (object(ms.get(i), at + "/" + i, "a multiscale") instanceof JsonObject m) {
                        multiscale(m, at + "/" + i);
                    }
                }
            }

            void multiscale(JsonObject m, String at) {
                optionalString(m, "name", at);
                if (!m.has("name")) {
                    recommended(at, "an image should have a name");
                }
                optionalString(m, "type", at);
                if (!m.has("type")) {
                    recommended(at, "an image should say the downsampling method that made its levels (type)");
                }
                if (m.has("metadata") && !(m.get("metadata") instanceof JsonObject)) {
                    error(at + "/metadata", "metadata must be an object");
                } else if (!m.has("metadata")) {
                    recommended(at, "an image should describe its downsampling method (metadata)");
                }
                if (version == OmeVersion.V0_4) {
                    if (m.has("version")) {
                        if (!(m.get("version") instanceof JsonString s) || !s.value().equals("0.4")) {
                            error(at + "/version", "the version must be \"0.4\"");
                        }
                    } else {
                        recommended(at, "a 0.4 image should give its version");
                    }
                }
                JsonArray datasets = null;
                if (!m.has("datasets")) {
                    error(at, "an image must list its levels (datasets)");
                } else {
                    datasets = array(m.get("datasets"), at + "/datasets", "datasets");
                    if (datasets != null && datasets.size() == 0) {
                        error(at + "/datasets", "an image must have at least one level");
                    }
                }
                if (version == OmeVersion.V0_6) {
                    multiscale06(m, datasets, at);
                } else {
                    multiscale045(m, datasets, at);
                }
            }

            // ........................................................ 0.4 and 0.5

            void multiscale045(JsonObject m, JsonArray datasets, String at) {
                int axes = -1;
                if (m.has("axes")) {
                    axes = axes045(m.get("axes"), at + "/axes");
                } else {
                    error(at, "an image must have axes");
                }
                if (m.has("coordinateSystems")) {
                    warn(at + "/coordinateSystems", "coordinateSystems are OME-Zarr 0.6; ignored in " + version);
                }
                if (datasets != null) {
                    Set<String> paths = new HashSet<>();
                    for (int i = 0; i < datasets.size(); i++) {
                        String p = at + "/datasets/" + i;
                        if (!(object(datasets.get(i), p, "a level") instanceof JsonObject d)) {
                            continue;
                        }
                        String path = requiredString(d, "path", p, "a level");
                        if (path != null && !paths.add(path)) {
                            error(p + "/path", "levels '" + path + "' repeat");
                        }
                        if (!d.has("coordinateTransformations")) {
                            error(p, "a level must have coordinateTransformations");
                        } else {
                            transformations045(d.get("coordinateTransformations"), p + "/coordinateTransformations",
                                    axes);
                        }
                    }
                }
                if (m.has("coordinateTransformations")) {
                    transformations045(m.get("coordinateTransformations"), at + "/coordinateTransformations", axes);
                }
            }

            /** Checks 0.4/0.5 axes; returns their number, or -1 if unknown. */
            int axes045(JsonValue value, String at) {
                JsonArray axes = array(value, at, "axes");
                if (axes == null) {
                    return -1;
                }
                if (axes.size() < 2 || axes.size() > 5) {
                    error(at, "an image has 2 to 5 axes, not " + axes.size());
                }
                unique(axes, at, "axes");
                List<String> types = new ArrayList<>();
                Set<String> names = new HashSet<>();
                for (int i = 0; i < axes.size(); i++) {
                    String p = at + "/" + i;
                    if (!(object(axes.get(i), p, "an axis") instanceof JsonObject a)) {
                        types.add(null);
                        continue;
                    }
                    String name = requiredString(a, "name", p, "an axis");
                    if (name != null && !names.add(name)) {
                        error(p + "/name", "axis names must be unique; '" + name + "' repeats");
                    }
                    String type = null;
                    if (a.has("type")) {
                        if (a.get("type") instanceof JsonString s) {
                            type = s.value();
                            if (!Set.of("space", "time", "channel").contains(type)) {
                                warn(p + "/type", "an axis type should be space, time, or channel, not '" + type + "'");
                            }
                        } else {
                            error(p + "/type", "an axis type must be a string");
                        }
                    } else {
                        warn(p, "an axis should have a type");
                    }
                    unit(a, type, p);
                    types.add(type);
                }
                axisOrder(types, at, true);
                return axes.size();
            }

            void transformations045(JsonValue value, String at, int axes) {
                JsonArray ts = array(value, at, "coordinateTransformations");
                if (ts == null) {
                    return;
                }
                if (ts.size() == 0) {
                    error(at, "coordinateTransformations must hold a scale");
                    return;
                }
                int scales = 0;
                int translations = 0;
                for (int i = 0; i < ts.size(); i++) {
                    String p = at + "/" + i;
                    if (!(object(ts.get(i), p, "a transformation") instanceof JsonObject t)) {
                        continue;
                    }
                    String type = requiredString(t, "type", p, "a transformation");
                    if (type == null) {
                        continue;
                    }
                    switch (type) {
                        case "scale" -> {
                            scales++;
                            if (translations > 0) {
                                error(p, "the scale must come before the translation");
                            }
                            vector045(t, "scale", p, axes);
                        }
                        case "translation" -> {
                            translations++;
                            vector045(t, "translation", p, axes);
                        }
                        default -> error(p + "/type", "a transformation here must be a scale or a translation, not '"
                                + type + "'");
                    }
                }
                if (scales != 1) {
                    error(at, "coordinateTransformations must hold exactly one scale, not " + scales);
                }
                if (translations > 1) {
                    error(at, "coordinateTransformations may hold at most one translation, not " + translations);
                }
            }

            void vector045(JsonObject t, String key, String at, int axes) {
                if (!t.has(key)) {
                    if (t.has("path")) {
                        if (!(t.get("path") instanceof JsonString)) {
                            error(at + "/path", "path must be a string");
                        }
                        warn(at + "/path", "the " + key + " is stored in an array; many readers expect it in the "
                                + "JSON");
                    } else {
                        error(at, "a " + key + " transformation must give its " + key);
                    }
                    return;
                }
                JsonArray v = array(t.get(key), at + "/" + key, key);
                if (v == null) {
                    return;
                }
                for (int i = 0; i < v.size(); i++) {
                    if (!(v.get(i) instanceof JsonNumber n) || !n.isFinite()) {
                        error(at + "/" + key + "/" + i, "a " + key + " is numbers");
                    }
                }
                if (v.size() < 2) {
                    error(at + "/" + key, "a " + key + " has at least 2 values");
                }
                if (axes >= 0 && v.size() != axes) {
                    error(at + "/" + key, "the " + key + " has " + v.size() + " values for " + axes + " axes");
                }
            }

            // ........................................................ 0.6

            void multiscale06(JsonObject m, JsonArray datasets, String at) {
                if (m.has("axes")) {
                    warn(at + "/axes", "0.6 gives axes in coordinateSystems; these are ignored");
                }
                Map<String, Integer> systems = new LinkedHashMap<>();
                if (!m.has("coordinateSystems")) {
                    error(at, "an image must have coordinateSystems");
                } else {
                    systems = coordinateSystems(m.get("coordinateSystems"), at + "/coordinateSystems", true);
                    if (systems != null && systems.isEmpty() && m.get("coordinateSystems") instanceof JsonArray a
                            && a.size() == 0) {
                        error(at + "/coordinateSystems", "an image must have at least one coordinate system");
                    }
                    if (systems == null) {
                        systems = new LinkedHashMap<>();
                    }
                }
                String intrinsic = null;
                if (datasets != null) {
                    Set<String> paths = new HashSet<>();
                    for (int i = 0; i < datasets.size(); i++) {
                        String p = at + "/datasets/" + i;
                        if (!(object(datasets.get(i), p, "a level") instanceof JsonObject d)) {
                            continue;
                        }
                        String path = requiredString(d, "path", p, "a level");
                        if (path != null && !paths.add(path)) {
                            error(p + "/path", "levels '" + path + "' repeat");
                        }
                        if (!d.has("coordinateTransformations")) {
                            error(p, "a level must have coordinateTransformations");
                            continue;
                        }
                        String output = level06(d.get("coordinateTransformations"), p + "/coordinateTransformations",
                                path, systems);
                        if (output != null) {
                            if (intrinsic == null) {
                                intrinsic = output;
                            } else if (!intrinsic.equals(output)) {
                                error(p + "/coordinateTransformations/0/output", "every level must map to the same "
                                        + "coordinate system; this one maps to '" + output + "', the first to '"
                                        + intrinsic + "'");
                            }
                        }
                    }
                }
                if (intrinsic != null && m.find("coordinateSystems").orElse(null) instanceof JsonArray cs) {
                    for (int i = 0; i < cs.size(); i++) {
                        if (cs.get(i) instanceof JsonObject c && c.find("name").orElse(null) instanceof JsonString n
                                && n.value().equals(intrinsic) && c.find("axes").orElse(null) instanceof JsonArray axes) {
                            intrinsicAxes(axes, at + "/coordinateSystems/" + i + "/axes");
                        }
                    }
                }
                List<String[]> edges = new ArrayList<>();
                if (m.has("coordinateTransformations")) {
                    JsonArray ts = array(m.get("coordinateTransformations"), at + "/coordinateTransformations",
                            "coordinateTransformations");
                    if (ts != null) {
                        if (ts.size() == 0) {
                            error(at + "/coordinateTransformations", "coordinateTransformations, if present, must "
                                    + "hold at least one transformation");
                        }
                        unique(ts, at + "/coordinateTransformations", "coordinateTransformations");
                        for (int i = 0; i < ts.size(); i++) {
                            String p = at + "/coordinateTransformations/" + i;
                            if (object(ts.get(i), p, "a transformation") instanceof JsonObject t) {
                                imageTransformation(t, p, systems, intrinsic, edges);
                            }
                        }
                    }
                }
                connected(systems.keySet(), edges, at + "/coordinateSystems");
            }

            /** A 0.6 level's transformation; returns the name of the coordinate system it maps to. */
            String level06(JsonValue value, String at, String path, Map<String, Integer> systems) {
                JsonArray ts = array(value, at, "coordinateTransformations");
                if (ts == null) {
                    return null;
                }
                if (ts.size() != 1) {
                    error(at, "a level must have exactly one transformation, not " + ts.size());
                }
                if (ts.size() == 0 || !(object(ts.get(0), at + "/0", "a transformation") instanceof JsonObject t)) {
                    return null;
                }
                String p = at + "/0";
                String outName = null;
                String type = t.find("type").orElse(null) instanceof JsonString s ? s.value() : null;
                if (type != null && !Set.of("scale", "identity", "sequence").contains(type)) {
                    error(p + "/type", "a level's transformation must be a scale, an identity, or a sequence of a "
                            + "scale and a translation, not '" + type + "'");
                }
                if ("sequence".equals(type) && t.find("transformations").orElse(null) instanceof JsonArray parts) {
                    boolean ok = parts.size() == 2
                            && parts.get(0) instanceof JsonObject a && "scale".equals(typeOf(a))
                            && parts.get(1) instanceof JsonObject b && "translation".equals(typeOf(b));
                    if (!ok) {
                        error(p + "/transformations", "a level's sequence must be a scale followed by a translation");
                    }
                }
                if (!t.has("input")) {
                    error(p, "a level's transformation must have an input, the level's path");
                } else if (t.get("input") instanceof JsonObject in) {
                    if (!(in.find("path").orElse(null) instanceof JsonString ip)) {
                        error(p + "/input", "a level's transformation's input must give the level's path");
                    } else if (path != null && !ip.value().equals(path)) {
                        error(p + "/input/path", "a level's transformation's input must be its own path '" + path
                                + "', not '" + ip.value() + "'");
                    }
                    if (in.has("name")) {
                        warn(p + "/input/name", "a level's transformation's input should have no name");
                    }
                } else {
                    error(p + "/input", "input must be an object");
                }
                if (!t.has("output")) {
                    error(p, "a level's transformation must have an output, a coordinate system");
                } else if (t.get("output") instanceof JsonObject out) {
                    if (!(out.find("name").orElse(null) instanceof JsonString on)) {
                        error(p + "/output", "a level's transformation's output must name a coordinate system");
                    } else {
                        outName = on.value();
                        if (!systems.containsKey(outName)) {
                            error(p + "/output/name", "the image has no coordinate system '" + outName + "'");
                        }
                    }
                    if (out.has("path")) {
                        warn(p + "/output/path", "a level's transformation's output should have no path");
                    }
                } else {
                    error(p + "/output", "output must be an object");
                }
                int dim = outName == null ? -1 : systems.getOrDefault(outName, -1);
                transformation(t, p, dim, dim, true); // its input and output are checked above
                return outName;
            }

            /** An image's own transformation: between its intrinsic system and another of its (or a label's). */
            void imageTransformation(JsonObject t, String at, Map<String, Integer> systems, String intrinsic,
                                     List<String[]> edges) {
                JsonObject in = endpoint(t, "input", at, true);
                JsonObject out = endpoint(t, "output", at, true);
                if (in == null || out == null) {
                    transformation(t, at, -1, -1, true);
                    return;
                }
                String inName = stringOrNull(in, "name");
                String outName = stringOrNull(out, "name");
                String inPath = stringOrNull(in, "path");
                String outPath = stringOrNull(out, "path");
                boolean inIntrinsic = inPath == null && inName != null && inName.equals(intrinsic);
                boolean outIntrinsic = outPath == null && outName != null && outName.equals(intrinsic);
                if (intrinsic != null && !inIntrinsic && !outIntrinsic) {
                    error(at, "one end of an image's transformation must be its intrinsic coordinate system '"
                            + intrinsic + "'");
                }
                for (String[] end : new String[][] {{inName, inPath, "input"}, {outName, outPath, "output"}}) {
                    if (end[1] == null && end[0] != null && !systems.containsKey(end[0])) {
                        error(at + "/" + end[2] + "/name", "the image has no coordinate system '" + end[0] + "'");
                    }
                }
                String type = typeOf(t);
                if ((inPath != null || outPath != null)
                        && !Set.of("identity", "scale", "translation").contains(type)) {
                    error(at + "/type", "a transformation to a label image's coordinate system must be an identity, a "
                            + "scale, or a translation, not '" + type + "'");
                }
                int inDim = inPath == null && inName != null ? systems.getOrDefault(inName, -1) : -1;
                int outDim = outPath == null && outName != null ? systems.getOrDefault(outName, -1) : -1;
                transformation(t, at, inDim, outDim, true);
                if (inPath == null && outPath == null && inName != null && outName != null) {
                    edges.add(new String[] {inName, outName});
                }
            }

            /** The intrinsic coordinate system's axes: as an image's, ordered time, channel, space. */
            void intrinsicAxes(JsonArray axes, String at) {
                if (axes.size() < 2 || axes.size() > 5) {
                    error(at, "an image has 2 to 5 axes, not " + axes.size());
                }
                List<String> types = new ArrayList<>();
                boolean spatial = false;
                for (JsonValue a : axes.values()) {
                    String type = a instanceof JsonObject o && o.find("type").orElse(null) instanceof JsonString s
                            ? s.value() : null;
                    spatial |= "space".equals(type);
                    types.add(type);
                }
                if (spatial) {
                    axisOrder(types, at, false);
                }
            }

            /** Checks coordinate systems; returns their dimensions by name, or null if not an array. */
            Map<String, Integer> coordinateSystems(JsonValue value, String at, boolean inImage) {
                JsonArray cs = array(value, at, "coordinateSystems");
                if (cs == null) {
                    return null;
                }
                unique(cs, at, "coordinateSystems");
                Map<String, Integer> systems = new LinkedHashMap<>();
                for (int i = 0; i < cs.size(); i++) {
                    String p = at + "/" + i;
                    if (!(object(cs.get(i), p, "a coordinate system") instanceof JsonObject c)) {
                        continue;
                    }
                    String name = requiredString(c, "name", p, "a coordinate system");
                    if (name != null && name.isEmpty()) {
                        error(p + "/name", "a coordinate system's name must not be empty");
                    }
                    int dim = -1;
                    if (!c.has("axes")) {
                        error(p, "a coordinate system must have axes");
                    } else {
                        dim = axes06(c.get("axes"), p + "/axes");
                    }
                    if (name != null) {
                        if (systems.containsKey(name)) {
                            error(p + "/name", "coordinate system names must be unique; '" + name + "' repeats");
                        }
                        systems.put(name, dim);
                    }
                }
                return systems;
            }

            /** Checks 0.6 axes; returns their number, or -1 if unknown. */
            int axes06(JsonValue value, String at) {
                JsonArray axes = array(value, at, "axes");
                if (axes == null) {
                    return -1;
                }
                if (axes.size() < 1 || axes.size() > 5) {
                    error(at, "a coordinate system has 1 to 5 axes, not " + axes.size());
                }
                unique(axes, at, "axes");
                Set<String> names = new HashSet<>();
                int space = 0;
                int array = 0;
                for (int i = 0; i < axes.size(); i++) {
                    String p = at + "/" + i;
                    if (!(object(axes.get(i), p, "an axis") instanceof JsonObject a)) {
                        continue;
                    }
                    String name = requiredString(a, "name", p, "an axis");
                    if (name != null && name.isEmpty()) {
                        error(p + "/name", "an axis name must not be empty");
                    }
                    if (name != null && !names.add(name)) {
                        error(p + "/name", "axis names must be unique; '" + name + "' repeats");
                    }
                    optionalString(a, "longName", p);
                    String type = null;
                    if (a.has("type")) {
                        if (a.get("type") instanceof JsonString s) {
                            type = s.value();
                            if (!AXIS_TYPES_06.contains(type)) {
                                warn(p + "/type", "an axis type should be one of " + new java.util.TreeSet<>(AXIS_TYPES_06)
                                        + ", not '" + type + "'");
                            }
                        } else {
                            error(p + "/type", "an axis type must be a string");
                        }
                    } else {
                        warn(p, "an axis should have a type");
                    }
                    if (a.has("discrete") && !(a.get("discrete") instanceof JsonBool)) {
                        error(p + "/discrete", "discrete must be a boolean");
                    }
                    unit(a, type, p);
                    space += "space".equals(type) ? 1 : 0;
                    array += "array".equals(type) ? 1 : 0;
                }
                boolean spatial = space >= 2 && space <= 3;
                boolean arrayLike = array >= 2;
                if (spatial == arrayLike) {
                    error(at, spatial ? "a coordinate system has 2 or 3 space axes, or 2 or more array axes, not both"
                            : "a coordinate system must have 2 or 3 space axes, or 2 or more array axes");
                }
                return axes.size();
            }

            /**
             * Checks any 0.6 transformation, given its input and output dimensions where known (-1 where not);
             * returns its output dimension where it can tell, else -1.
             */
            int transformation(JsonObject t, String at, int inDim, int outDim, boolean endpoints) {
                String type = requiredString(t, "type", at, "a transformation");
                optionalString(t, "name", at);
                if (!endpoints) {
                    for (String key : List.of("input", "output")) {
                        if (t.has(key) && !(t.get(key) instanceof JsonObject)) {
                            error(at + "/" + key, key + " must be an object");
                        }
                    }
                }
                if (type == null) {
                    return -1;
                }
                if (!TRANSFORMATION_TYPES.contains(type)) {
                    error(at + "/type", "unknown transformation type '" + type + "'");
                    return -1;
                }
                switch (type) {
                    case "identity" -> {
                        if (inDim >= 0 && outDim >= 0 && inDim != outDim) {
                            error(at, "an identity maps " + inDim + " dimensions to " + outDim);
                        }
                        return inDim >= 0 ? inDim : outDim;
                    }
                    case "mapAxis" -> {
                        int[] map = indices(t, "mapAxis", at, true, 2, 5);
                        if (map == null) {
                            return -1;
                        }
                        int n = map.length;
                        for (int i = 0; i < n; i++) {
                            if (map[i] >= n) {
                                error(at + "/mapAxis/" + i, "mapAxis index " + map[i] + " is not an axis of a "
                                        + n + "-dimensional input");
                            }
                        }
                        dims(at, "mapAxis", n, inDim, outDim);
                        return n;
                    }
                    case "projectAxis" -> {
                        int[] dropped = t.has("droppedInputs") ? indices(t, "droppedInputs", at, false, 1, 3) : new int[0];
                        int[] created = t.has("createdOutputs") ? indices(t, "createdOutputs", at, false, 1, 3) : new int[0];
                        if (!t.has("droppedInputs") && !t.has("createdOutputs")) {
                            error(at, "a projectAxis must drop inputs or create outputs");
                        }
                        if (dropped == null || created == null) {
                            return -1;
                        }
                        if (inDim >= 0) {
                            for (int d : dropped) {
                                if (d >= inDim) {
                                    error(at + "/droppedInputs", "drops input " + d + " of a " + inDim
                                            + "-dimensional input");
                                }
                            }
                        }
                        int out = inDim >= 0 ? inDim - dropped.length + created.length : outDim;
                        if (inDim >= 0 && outDim >= 0 && out != outDim) {
                            error(at, "a projectAxis that drops " + dropped.length + " and creates " + created.length
                                    + " maps " + inDim + " dimensions to " + out + ", not " + outDim);
                        }
                        int limit = outDim >= 0 ? outDim : out;
                        if (limit >= 0) {
                            for (int c : created) {
                                if (c >= limit) {
                                    error(at + "/createdOutputs", "creates output " + c + " of a " + limit
                                            + "-dimensional output");
                                }
                            }
                        }
                        return out;
                    }
                    case "scale", "translation" -> {
                        if (!t.has(type)) {
                            error(at, "a " + type + " must give its " + type + (t.has("path")
                                    ? " in the JSON; in 0.6 it cannot be stored at a path" : ""));
                            return -1;
                        }
                        JsonArray v = array(t.get(type), at + "/" + type, type);
                        if (v == null) {
                            return -1;
                        }
                        for (int i = 0; i < v.size(); i++) {
                            if (!(v.get(i) instanceof JsonNumber n) || !n.isFinite()) {
                                error(at + "/" + type + "/" + i, "a " + type + " is numbers");
                            } else if (type.equals("scale") && n.doubleValue() <= 0) {
                                error(at + "/scale/" + i, "a scale factor must be positive");
                            }
                        }
                        dims(at, type, v.size(), inDim, outDim);
                        return v.size();
                    }
                    case "affine" -> {
                        int[] shape = matrix(t, "affine", at);
                        if (shape == null) {
                            return outDim;
                        }
                        if (inDim >= 0 && shape[1] != inDim + 1) {
                            error(at + "/affine", "an affine from " + inDim + " dimensions has " + (inDim + 1)
                                    + " columns, not " + shape[1]);
                        }
                        if (outDim >= 0 && shape[0] != outDim) {
                            error(at + "/affine", "an affine to " + outDim + " dimensions has " + outDim
                                    + " rows, not " + shape[0]);
                        }
                        return shape[0];
                    }
                    case "rotation" -> {
                        int[] shape = matrix(t, "rotation", at);
                        if (shape == null) {
                            return outDim;
                        }
                        if (shape[0] != shape[1] || shape[0] < 2 || shape[0] > 5) {
                            error(at + "/rotation", "a rotation is a square matrix of 2 to 5 rows, not " + shape[0]
                                    + " by " + shape[1]);
                            return -1;
                        }
                        dims(at, "rotation", shape[0], inDim, outDim);
                        rotationMatrix(t.get("rotation").asArray(), at + "/rotation");
                        return shape[0];
                    }
                    case "sequence" -> {
                        if (!t.has("transformations")) {
                            error(at, "a sequence must list its transformations");
                            return -1;
                        }
                        JsonArray parts = array(t.get("transformations"), at + "/transformations", "transformations");
                        if (parts == null) {
                            return -1;
                        }
                        if (parts.size() == 0) {
                            error(at + "/transformations", "a sequence must hold at least one transformation");
                        }
                        int dim = inDim;
                        for (int i = 0; i < parts.size(); i++) {
                            String p = at + "/transformations/" + i;
                            if (object(parts.get(i), p, "a transformation") instanceof JsonObject part) {
                                dim = transformation(part, p, dim, i == parts.size() - 1 ? outDim : -1, false);
                            } else {
                                dim = -1;
                            }
                        }
                        return dim >= 0 ? dim : outDim;
                    }
                    case "displacements", "coordinates" -> {
                        requiredString(t, "path", at, "a " + type + " transformation");
                        if (t.has("interpolation")) {
                            if (!(t.get("interpolation") instanceof JsonString s)) {
                                error(at + "/interpolation", "interpolation must be a string");
                            } else if (!Set.of("nearest", "linear", "cubic", "bspline-cubic").contains(s.value())) {
                                error(at + "/interpolation", "interpolation must be nearest, linear, or cubic, not '"
                                        + s.value() + "'");
                            }
                        }
                        if (type.equals("displacements") && inDim >= 0 && outDim >= 0 && inDim != outDim) {
                            error(at, "displacements map " + inDim + " dimensions to " + outDim
                                    + "; they must be the same");
                        }
                        return outDim;
                    }
                    case "bijection" -> {
                        for (String key : List.of("forward", "inverse")) {
                            if (!t.has(key)) {
                                error(at, "a bijection must have its " + key + " transformation");
                            } else if (object(t.get(key), at + "/" + key, "a transformation") instanceof JsonObject part) {
                                boolean forward = key.equals("forward");
                                transformation(part, at + "/" + key, forward ? inDim : outDim, forward ? outDim : inDim,
                                        false);
                            }
                        }
                        if (inDim >= 0 && outDim >= 0 && inDim != outDim) {
                            error(at, "a bijection maps " + inDim + " dimensions to " + outDim
                                    + "; they must be the same");
                        }
                        return outDim >= 0 ? outDim : inDim;
                    }
                    case "byDimension" -> {
                        return byDimension(t, at, inDim, outDim);
                    }
                    default -> {
                        return -1;
                    }
                }
            }

            int byDimension(JsonObject t, String at, int inDim, int outDim) {
                if (!t.has("transformations")) {
                    error(at, "a byDimension must list its transformations");
                    return -1;
                }
                JsonArray parts = array(t.get("transformations"), at + "/transformations", "transformations");
                if (parts == null) {
                    return -1;
                }
                Map<Integer, Integer> outputs = new HashMap<>();
                int top = -1;
                for (int i = 0; i < parts.size(); i++) {
                    String p = at + "/transformations/" + i;
                    if (!(object(parts.get(i), p, "a byDimension part") instanceof JsonObject part)) {
                        continue;
                    }
                    int[] ins = axisList(part, "inputAxes", p);
                    int[] outs = axisList(part, "outputAxes", p);
                    if (!part.has("transformation")) {
                        error(p, "a byDimension part must have its transformation");
                    } else if (object(part.get("transformation"), p + "/transformation", "a transformation")
                            instanceof JsonObject sub) {
                        int subOut = transformation(sub, p + "/transformation", ins == null ? -1 : ins.length,
                                outs == null ? -1 : outs.length, false);
                        if (outs != null && subOut >= 0 && subOut != outs.length) {
                            error(p + "/outputAxes", "the part's transformation gives " + subOut + " coordinates for "
                                    + outs.length + " output axes");
                        }
                    }
                    if (ins != null && inDim >= 0) {
                        for (int a : ins) {
                            if (a >= inDim) {
                                error(p + "/inputAxes", "input axis " + a + " is not one of the input's " + inDim);
                            }
                        }
                    }
                    if (outs != null) {
                        for (int a : outs) {
                            top = Math.max(top, a);
                            if (outDim >= 0 && a >= outDim) {
                                error(p + "/outputAxes", "output axis " + a + " is not one of the output's " + outDim);
                            }
                            if (outputs.merge(a, 1, Integer::sum) == 2) {
                                error(p + "/outputAxes", "output axis " + a + " appears in more than one part");
                            }
                        }
                    }
                }
                int n = outDim >= 0 ? outDim : top + 1;
                for (int a = 0; a < n; a++) {
                    if (!outputs.containsKey(a) && outDim >= 0) {
                        error(at + "/transformations", "output axis " + a + " appears in no part");
                    }
                }
                return n;
            }

            int[] axisList(JsonObject part, String key, String at) {
                if (!part.has(key)) {
                    error(at, "a byDimension part must have " + key);
                    return null;
                }
                JsonArray a = array(part.get(key), at + "/" + key, key);
                if (a == null) {
                    return null;
                }
                int[] axes = new int[a.size()];
                for (int i = 0; i < axes.length; i++) {
                    if (!(a.get(i) instanceof JsonNumber n) || !isInteger(n) || n.longValue() < 0) {
                        error(at + "/" + key + "/" + i, key + " are axis indices: integers from 0");
                        return null;
                    }
                    axes[i] = (int) Math.min(n.longValue(), Integer.MAX_VALUE);
                }
                return axes;
            }

            /** Integer indices 0..4, unique, between min and max of them; null if not. */
            int[] indices(JsonObject t, String key, String at, boolean required, int min, int max) {
                if (!t.has(key)) {
                    if (required) {
                        error(at, "a " + typeOf(t) + " must give its " + key);
                    }
                    return null;
                }
                JsonArray a = array(t.get(key), at + "/" + key, key);
                if (a == null) {
                    return null;
                }
                if (a.size() < min || a.size() > max) {
                    error(at + "/" + key, key + " holds " + min + " to " + max + " indices, not " + a.size());
                }
                int[] values = new int[a.size()];
                Set<Integer> seen = new HashSet<>();
                boolean ok = true;
                for (int i = 0; i < values.length; i++) {
                    if (!(a.get(i) instanceof JsonNumber n) || !isInteger(n) || n.longValue() < 0 || n.longValue() > 4) {
                        error(at + "/" + key + "/" + i, key + " holds axis indices from 0 to 4");
                        ok = false;
                        continue;
                    }
                    values[i] = (int) n.longValue();
                    if (!seen.add(values[i])) {
                        error(at + "/" + key + "/" + i, "index " + values[i] + " repeats in " + key);
                        ok = false;
                    }
                }
                return ok ? values : null;
            }

            /** A matrix given in the JSON or at a path; returns its rows and columns, or null. */
            int[] matrix(JsonObject t, String key, String at) {
                boolean json = t.has(key);
                boolean path = t.has("path");
                if (json == path) {
                    error(at, "a " + key + " must give its matrix either in the JSON (" + key + ") or at a path, "
                            + (json ? "not both" : "and gives neither"));
                    return null;
                }
                if (path) {
                    if (!(t.get("path") instanceof JsonString)) {
                        error(at + "/path", "path must be a string");
                    }
                    return null;
                }
                JsonArray rows = array(t.get(key), at + "/" + key, key);
                if (rows == null) {
                    return null;
                }
                int columns = -1;
                for (int r = 0; r < rows.size(); r++) {
                    JsonArray row = rows.get(r) instanceof JsonArray a ? a : null;
                    if (row == null) {
                        error(at + "/" + key + "/" + r, "a " + key + " matrix is an array of rows");
                        return null;
                    }
                    for (int c = 0; c < row.size(); c++) {
                        if (!(row.get(c) instanceof JsonNumber n) || !n.isFinite()) {
                            error(at + "/" + key + "/" + r + "/" + c, "a " + key + " matrix holds numbers");
                            return null;
                        }
                    }
                    if (columns >= 0 && row.size() != columns) {
                        error(at + "/" + key, "a " + key + " matrix's rows have different lengths");
                        return null;
                    }
                    columns = row.size();
                }
                if (rows.size() == 0) {
                    error(at + "/" + key, "a " + key + " matrix has at least one row");
                    return null;
                }
                return new int[] {rows.size(), columns};
            }

            void rotationMatrix(JsonArray rows, String at) {
                int n = rows.size();
                double[][] m = new double[n][n];
                for (int r = 0; r < n; r++) {
                    for (int c = 0; c < n; c++) {
                        m[r][c] = rows.get(r).asArray().get(c).asNumber().doubleValue();
                    }
                }
                for (int r = 0; r < n; r++) {
                    for (int s = 0; s < n; s++) {
                        double dot = 0;
                        for (int c = 0; c < n; c++) {
                            dot += m[r][c] * m[s][c];
                        }
                        if (Math.abs(dot - (r == s ? 1 : 0)) > 1e-6) {
                            error(at, "a rotation matrix must have orthonormal rows and columns");
                            return;
                        }
                    }
                }
                if (Math.abs(determinant(m) - 1) > 1e-6) {
                    error(at, "a rotation matrix must have determinant 1, not " + determinant(m));
                }
            }

            void dims(String at, String type, int n, int inDim, int outDim) {
                if (inDim >= 0 && n != inDim) {
                    error(at, "a " + type + " of " + n + " dimensions from a " + inDim + "-dimensional input");
                } else if (outDim >= 0 && n != outDim) {
                    error(at, "a " + type + " of " + n + " dimensions to a " + outDim + "-dimensional output");
                }
            }

            /** An input or output that must be an object naming a coordinate system; null if it is not. */
            JsonObject endpoint(JsonObject t, String key, String at, boolean nameRequired) {
                if (!t.has(key)) {
                    error(at, "a transformation here must have an " + key);
                    return null;
                }
                if (!(t.get(key) instanceof JsonObject o)) {
                    error(at + "/" + key, key + " must be an object");
                    return null;
                }
                if (nameRequired && !(o.find("name").orElse(null) instanceof JsonString)) {
                    error(at + "/" + key, key + " must name a coordinate system");
                }
                for (String k : List.of("name", "path")) {
                    if (o.has(k) && !(o.get(k) instanceof JsonString) && !o.get(k).isNull()) {
                        error(at + "/" + key + "/" + k, k + " must be a string");
                    }
                }
                return o;
            }

            void scene(JsonValue value, String at) {
                if (!(object(value, at, "scene") instanceof JsonObject s)) {
                    return;
                }
                Map<String, Integer> systems = new LinkedHashMap<>();
                if (s.has("coordinateSystems")) {
                    Map<String, Integer> found = coordinateSystems(s.get("coordinateSystems"), at + "/coordinateSystems",
                            false);
                    if (found != null) {
                        systems = found;
                    }
                }
                if (!s.has("coordinateTransformations")) {
                    error(at, "a scene must have coordinateTransformations");
                    return;
                }
                JsonArray ts = array(s.get("coordinateTransformations"), at + "/coordinateTransformations",
                        "coordinateTransformations");
                if (ts == null) {
                    return;
                }
                if (ts.size() == 0) {
                    error(at + "/coordinateTransformations", "a scene must hold at least one transformation");
                }
                Set<String> nodes = new java.util.LinkedHashSet<>(systems.keySet());
                List<String[]> edges = new ArrayList<>();
                for (int i = 0; i < ts.size(); i++) {
                    String p = at + "/coordinateTransformations/" + i;
                    if (!(object(ts.get(i), p, "a transformation") instanceof JsonObject t)) {
                        continue;
                    }
                    JsonObject in = endpoint(t, "input", p, true);
                    JsonObject out = endpoint(t, "output", p, true);
                    String[] ends = new String[2];
                    int[] dims = {-1, -1};
                    int k = 0;
                    for (JsonObject end : new JsonObject[] {in, out}) {
                        String key = k == 0 ? "input" : "output";
                        if (end != null) {
                            for (String member : end.members().keySet()) {
                                if (!member.equals("name") && !member.equals("path")) {
                                    error(p + "/" + key + "/" + member, "a scene's " + key + " holds only name and "
                                            + "path");
                                }
                            }
                            String name = stringOrNull(end, "name");
                            String path = stringOrNull(end, "path");
                            if (path == null && name != null) {
                                if (!systems.containsKey(name)) {
                                    error(p + "/" + key + "/name", "the scene has no coordinate system '" + name
                                            + "'; one in an image needs its path");
                                } else {
                                    dims[k] = systems.get(name);
                                }
                                ends[k] = name;
                            } else if (path != null) {
                                ends[k] = "\u0000" + path; // an image: its coordinate systems are connected in it
                            }
                        }
                        k++;
                    }
                    transformation(t, p, dims[0], dims[1], true);
                    if (ends[0] != null && ends[1] != null) {
                        nodes.add(ends[0]);
                        nodes.add(ends[1]);
                        edges.add(ends);
                    }
                }
                connected(nodes, edges, at + "/coordinateTransformations");
            }

            void omero(JsonValue value, String at) {
                if (!(object(value, at, "omero") instanceof JsonObject o)) {
                    return;
                }
                if (!o.has("channels")) {
                    error(at, "omero must list the channels");
                    return;
                }
                JsonArray channels = array(o.get("channels"), at + "/channels", "channels");
                if (channels == null) {
                    return;
                }
                for (int i = 0; i < channels.size(); i++) {
                    String p = at + "/channels/" + i;
                    if (!(object(channels.get(i), p, "a channel") instanceof JsonObject c)) {
                        continue;
                    }
                    if (!c.has("color")) {
                        error(p, "a channel must have a color");
                    } else if (!(c.get("color") instanceof JsonString s)) {
                        error(p + "/color", "a channel's color must be a string");
                    } else if (!HEX_COLOR.matcher(s.value()).matches()) {
                        error(p + "/color", "a channel's color is 6 hexadecimal digits, not '" + s.value() + "'");
                    }
                    if (!c.has("window")) {
                        error(p, "a channel must have a window");
                    } else if (object(c.get("window"), p + "/window", "a window") instanceof JsonObject w) {
                        for (String key : List.of("min", "max", "start", "end")) {
                            if (!w.has(key)) {
                                error(p + "/window", "a window must have " + key);
                            } else if (!(w.get(key) instanceof JsonNumber)) {
                                error(p + "/window/" + key, key + " must be a number");
                            }
                        }
                    }
                    optionalString(c, "label", p);
                    optionalString(c, "family", p);
                    if (c.has("active") && !(c.get("active") instanceof JsonBool)) {
                        error(p + "/active", "active must be a boolean");
                    }
                }
            }

            void imageLabel(JsonValue value, String at) {
                if (!(object(value, at, "image-label") instanceof JsonObject l)) {
                    return;
                }
                if (version == OmeVersion.V0_4) {
                    if (l.has("version")) {
                        if (!(l.get("version") instanceof JsonString s) || !s.value().equals("0.4")) {
                            error(at + "/version", "the version must be \"0.4\"");
                        }
                    } else {
                        recommended(at, "a 0.4 image-label should give its version");
                    }
                }
                if (!l.has("colors")) {
                    recommended(at, "a label image should give its colors");
                } else {
                    labelValues(l.get("colors"), at + "/colors", "colors", false);
                }
                if (l.has("properties")) {
                    labelValues(l.get("properties"), at + "/properties", "properties", true);
                }
                if (l.has("source")) {
                    if (object(l.get("source"), at + "/source", "source") instanceof JsonObject s) {
                        optionalString(s, "image", at + "/source");
                    }
                }
            }

            void labelValues(JsonValue value, String at, String what, boolean properties) {
                JsonArray a = array(value, at, what);
                if (a == null) {
                    return;
                }
                if (a.size() == 0) {
                    error(at, what + ", if present, must not be empty");
                }
                unique(a, at, what);
                Set<String> values = new HashSet<>();
                for (int i = 0; i < a.size(); i++) {
                    String p = at + "/" + i;
                    if (!(object(a.get(i), p, "an entry of " + what) instanceof JsonObject c)) {
                        continue;
                    }
                    if (!c.has("label-value")) {
                        error(p, "an entry of " + what + " must have a label-value");
                    } else if (!(c.get("label-value") instanceof JsonNumber n) || !isInteger(n)) {
                        error(p + "/label-value", "a label-value must be an integer");
                    } else if (!values.add(n.literal()) && !properties) {
                        error(p + "/label-value", "label-value " + n.literal() + " has more than one color");
                    }
                    if (!properties && c.has("rgba")) {
                        boolean ok = c.get("rgba") instanceof JsonArray rgba && rgba.size() == 4
                                && rgba.values().stream().allMatch(v -> v instanceof JsonNumber n && isInteger(n)
                                && n.longValue() >= 0 && n.longValue() <= 255);
                        if (!ok) {
                            error(p + "/rgba", "rgba must be four integers from 0 to 255");
                        }
                    }
                }
            }

            void labels(JsonValue value, String at) {
                strings(value, at, "labels");
            }

            void strings(JsonValue value, String at, String what) {
                JsonArray a = array(value, at, what);
                if (a == null) {
                    return;
                }
                for (int i = 0; i < a.size(); i++) {
                    if (!(a.get(i) instanceof JsonString)) {
                        error(at + "/" + i, what + " are paths: strings");
                    }
                }
            }

            void plate(JsonValue value, String at) {
                if (!(object(value, at, "plate") instanceof JsonObject p)) {
                    return;
                }
                List<String> rows = names(p, "rows", at);
                List<String> columns = names(p, "columns", at);
                if (version == OmeVersion.V0_4) {
                    if (p.has("version")) {
                        if (!(p.get("version") instanceof JsonString s) || !s.value().equals("0.4")) {
                            error(at + "/version", "the version must be \"0.4\"");
                        }
                    } else {
                        recommended(at, "a 0.4 plate should give its version");
                    }
                }
                if (p.has("name")) {
                    optionalString(p, "name", at);
                } else {
                    recommended(at, "a plate should have a name");
                }
                if (p.has("field_count")) {
                    positive(p, "field_count", at);
                } else {
                    warn(at, "a plate should give its field_count");
                }
                Set<Long> ids = new HashSet<>();
                if (p.has("acquisitions")) {
                    JsonArray as = array(p.get("acquisitions"), at + "/acquisitions", "acquisitions");
                    for (int i = 0; as != null && i < as.size(); i++) {
                        String q = at + "/acquisitions/" + i;
                        if (!(object(as.get(i), q, "an acquisition") instanceof JsonObject a)) {
                            continue;
                        }
                        if (!a.has("id")) {
                            error(q, "an acquisition must have an id");
                        } else if (!(a.get("id") instanceof JsonNumber n) || !isInteger(n) || n.longValue() < 0) {
                            error(q + "/id", "an acquisition's id is an integer from 0");
                        } else if (!ids.add(n.longValue())) {
                            error(q + "/id", "acquisition ids must be unique; " + n.literal() + " repeats");
                        }
                        if (a.has("name")) {
                            optionalString(a, "name", q);
                        } else {
                            recommended(q, "an acquisition should have a name");
                        }
                        if (a.has("maximumfieldcount")) {
                            positive(a, "maximumfieldcount", q);
                        } else {
                            recommended(q, "an acquisition should give its maximumfieldcount");
                        }
                        optionalString(a, "description", q);
                        for (String key : List.of("starttime", "endtime")) {
                            if (a.has(key) && (!(a.get(key) instanceof JsonNumber n) || !isInteger(n)
                                    || n.longValue() < 0)) {
                                error(q + "/" + key, key + " is an integer epoch timestamp, from 0");
                            }
                        }
                    }
                }
                if (!p.has("wells")) {
                    error(at, "a plate must list its wells");
                    return;
                }
                JsonArray wells = array(p.get("wells"), at + "/wells", "wells");
                if (wells == null) {
                    return;
                }
                if (wells.size() == 0) {
                    error(at + "/wells", "a plate must have at least one well");
                }
                unique(wells, at + "/wells", "wells");
                for (int i = 0; i < wells.size(); i++) {
                    String q = at + "/wells/" + i;
                    if (!(object(wells.get(i), q, "a well") instanceof JsonObject w)) {
                        continue;
                    }
                    String path = requiredString(w, "path", q, "a well");
                    if (path != null && !WELL_PATH.matcher(path).matches()) {
                        error(q + "/path", "a well's path is its row's name, '/', and its column's name, not '"
                                + path + "'");
                        path = null;
                    }
                    Integer row = index(w, "rowIndex", q, rows);
                    Integer column = index(w, "columnIndex", q, columns);
                    if (path != null && row != null && column != null && rows != null && columns != null
                            && !path.equals(rows.get(row) + "/" + columns.get(column))) {
                        error(q, "well '" + path + "' is at row " + row + " and column " + column + ", which are '"
                                + rows.get(row) + "/" + columns.get(column) + "'");
                    }
                }
            }

            List<String> names(JsonObject p, String key, String at) {
                if (!p.has(key)) {
                    error(at, "a plate must list its " + key);
                    return null;
                }
                JsonArray a = array(p.get(key), at + "/" + key, key);
                if (a == null) {
                    return null;
                }
                if (a.size() == 0) {
                    error(at + "/" + key, "a plate must have at least one of its " + key);
                }
                unique(a, at + "/" + key, key);
                List<String> names = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < a.size(); i++) {
                    String q = at + "/" + key + "/" + i;
                    String name = object(a.get(i), q, "an entry of " + key) instanceof JsonObject o
                            ? requiredString(o, "name", q, "an entry of " + key) : null;
                    if (name != null && !ALPHANUMERIC.matcher(name).matches()) {
                        error(q + "/name", key + " names are alphanumeric, not '" + name + "'");
                    }
                    if (name != null && !seen.add(name)) {
                        error(q + "/name", key + " names must be unique; '" + name + "' repeats");
                    }
                    names.add(name);
                }
                return names.contains(null) ? null : names;
            }

            Integer index(JsonObject w, String key, String at, List<String> of) {
                if (!w.has(key)) {
                    error(at, "a well must have its " + key);
                    return null;
                }
                if (!(w.get(key) instanceof JsonNumber n) || !isInteger(n) || n.longValue() < 0) {
                    error(at + "/" + key, key + " is an integer from 0");
                    return null;
                }
                if (of != null && n.longValue() >= of.size()) {
                    error(at + "/" + key, key + " " + n.literal() + " is past the plate's " + of.size());
                    return null;
                }
                return (int) n.longValue();
            }

            void well(JsonValue value, String at) {
                if (!(object(value, at, "well") instanceof JsonObject w)) {
                    return;
                }
                if (version == OmeVersion.V0_4) {
                    if (w.has("version")) {
                        if (!(w.get("version") instanceof JsonString s) || !s.value().equals("0.4")) {
                            error(at + "/version", "the version must be \"0.4\"");
                        }
                    } else {
                        recommended(at, "a 0.4 well should give its version");
                    }
                }
                if (!w.has("images")) {
                    error(at, "a well must list its images");
                    return;
                }
                JsonArray images = array(w.get("images"), at + "/images", "images");
                if (images == null) {
                    return;
                }
                if (images.size() == 0) {
                    error(at + "/images", "a well must have at least one image");
                }
                unique(images, at + "/images", "images");
                Set<String> paths = new HashSet<>();
                for (int i = 0; i < images.size(); i++) {
                    String q = at + "/images/" + i;
                    if (!(object(images.get(i), q, "an image") instanceof JsonObject image)) {
                        continue;
                    }
                    String path = requiredString(image, "path", q, "a well's image");
                    if (path != null) {
                        boolean ok = version == OmeVersion.V0_6
                                ? FIELD_PATH_06.matcher(path).matches() && !path.matches("\\.+") && !path.startsWith("__")
                                : ALPHANUMERIC.matcher(path).matches();
                        if (!ok) {
                            error(q + "/path", "a field of view's path is " + (version == OmeVersion.V0_6
                                    ? "letters, digits, '-', '_', and '.', not only dots nor starting with '__'"
                                    : "alphanumeric") + ", not '" + path + "'");
                        }
                        if (!paths.add(path)) {
                            error(q + "/path", "field of view paths must be unique; '" + path + "' repeats");
                        }
                    }
                    if (image.has("acquisition") && (!(image.get("acquisition") instanceof JsonNumber n)
                            || !isInteger(n))) {
                        error(q + "/acquisition", "an acquisition is an integer");
                    }
                }
            }

            void positive(JsonObject o, String key, String at) {
                if (!(o.get(key) instanceof JsonNumber n) || !isInteger(n) || n.longValue() <= 0) {
                    error(at + "/" + key, key + " is a positive integer");
                }
            }

            void unit(JsonObject axis, String type, String at) {
                if (!axis.has("unit")) {
                    if ("space".equals(type) || "time".equals(type)) {
                        warn(at, "a " + type + " axis should have a unit");
                    }
                    return;
                }
                if (!(axis.get("unit") instanceof JsonString s)) {
                    error(at + "/unit", "a unit must be a string");
                    return;
                }
                if ("space".equals(type) && !SPACE_UNITS.contains(s.value())) {
                    warn(at + "/unit", "'" + s.value() + "' is not a UDUNITS-2 length the specification lists");
                } else if ("time".equals(type) && !TIME_UNITS.contains(s.value())) {
                    warn(at + "/unit", "'" + s.value() + "' is not a UDUNITS-2 time the specification lists");
                }
            }

            /** Time first, then at most one channel or custom axis, then 2 or 3 space axes (0.4, 0.5). */
            void axisOrder(List<String> types, String at, boolean countSpaces) {
                int time = 0;
                int other = 0;
                int space = 0;
                int stage = 0; // 0: time allowed; 1: channel/custom allowed; 2: space
                boolean ordered = true;
                for (String type : types) {
                    if ("time".equals(type)) {
                        time++;
                        ordered &= stage == 0;
                        stage = 1;
                    } else if ("space".equals(type)) {
                        space++;
                        stage = 2;
                    } else {
                        other++;
                        ordered &= stage <= 1;
                        stage = 2;
                    }
                }
                if (countSpaces && (space < 2 || space > 3)) {
                    error(at, "an image has 2 or 3 space axes, not " + space);
                }
                if (time > 1) {
                    error(at, "an image has at most one time axis, not " + time);
                }
                if (other > 1) {
                    error(at, "an image has at most one channel or custom axis, not " + other);
                }
                if (!ordered && time <= 1 && other <= 1) {
                    error(at, "axes are ordered time, then channel (or custom), then space");
                }
            }
        }

        // ------------------------------------------------------------ helpers

        /** The graph of coordinate systems must be connected. */
        void connected(Set<String> nodes, List<String[]> edges, String at) {
            if (nodes.size() < 2) {
                return;
            }
            Map<String, String> parent = new HashMap<>();
            for (String n : nodes) {
                parent.put(n, n);
            }
            for (String[] e : edges) {
                parent.putIfAbsent(e[0], e[0]);
                parent.putIfAbsent(e[1], e[1]);
                parent.put(find(parent, e[0]), find(parent, e[1]));
            }
            String root = find(parent, nodes.iterator().next());
            for (String n : nodes) {
                if (!find(parent, n).equals(root)) {
                    error(at, "coordinate system '" + display(n) + "' is not connected to '"
                            + display(nodes.iterator().next()) + "' by any transformation");
                    return;
                }
            }
        }

        private static String find(Map<String, String> parent, String n) {
            while (!parent.get(n).equals(n)) {
                n = parent.get(n);
            }
            return n;
        }

        private static String display(String node) {
            return node.startsWith("\u0000") ? node.substring(1) : node;
        }

        JsonArray array(JsonValue v, String at, String what) {
            if (v instanceof JsonArray a) {
                return a;
            }
            error(at, what + " must be an array, not " + v.typeName());
            return null;
        }

        JsonObject object(JsonValue v, String at, String what) {
            if (v instanceof JsonObject o) {
                return o;
            }
            error(at, what + " must be an object, not " + v.typeName());
            return null;
        }

        String requiredString(JsonObject o, String key, String at, String what) {
            if (!o.has(key)) {
                error(at, what + " must have a " + key);
                return null;
            }
            if (o.get(key) instanceof JsonString s) {
                return s.value();
            }
            error(at + "/" + key, key + " must be a string, not " + o.get(key).typeName());
            return null;
        }

        void optionalString(JsonObject o, String key, String at) {
            if (o.has(key) && !(o.get(key) instanceof JsonString)) {
                error(at + "/" + key, key + " must be a string, not " + o.get(key).typeName());
            }
        }

        void unique(JsonArray a, String at, String what) {
            Set<JsonValue> seen = new HashSet<>();
            for (int i = 0; i < a.size(); i++) {
                if (!seen.add(a.get(i))) {
                    error(at + "/" + i, "the entries of " + what + " must be unique; this one repeats");
                    return;
                }
            }
        }
    }

    // ================================================================ static helpers

    /** Whether a number is an integer a {@code long} holds (no OME-Zarr value needs more), so that its value is. */
    static boolean isInteger(JsonNumber n) {
        if (!n.isFinite()) {
            return false;
        }
        try {
            BigDecimal d = new BigDecimal(n.literal());
            if (d.precision() - d.scale() > 19) { // beyond a long, before an exponent such as 1e400 is expanded
                return false;
            }
            BigDecimal whole = d.stripTrailingZeros();
            return whole.scale() <= 0 && whole.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                    && whole.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0;
        } catch (NumberFormatException | ArithmeticException e) {
            return false;
        }
    }

    private static String typeOf(JsonObject t) {
        return t.find("type").orElse(null) instanceof JsonString s ? s.value() : null;
    }

    private static String stringOrNull(JsonObject o, String key) {
        return o.find(key).orElse(null) instanceof JsonString s ? s.value() : null;
    }

    private static int channelAxis(List<com.ebremer.falcon.ome.metadata.Axis> axes) {
        for (int i = 0; i < axes.size(); i++) {
            if (axes.get(i).isChannel()) {
                return i;
            }
        }
        return -1;
    }

    private static double determinant(double[][] m) {
        int n = m.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = m[i].clone();
        }
        double det = 1;
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
                    p = r;
                }
            }
            if (a[p][c] == 0) {
                return 0;
            }
            if (p != c) {
                double[] t = a[p];
                a[p] = a[c];
                a[c] = t;
                det = -det;
            }
            det *= a[c][c];
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < n; k++) {
                    a[r][k] -= f * a[c][k];
                }
            }
        }
        return det;
    }

    /** The namespace of a group's attributes that holds its OME-Zarr metadata. */
    static JsonObject namespace(JsonObject attributes) {
        return attributes.find("ome").orElse(null) instanceof JsonObject o ? o : attributes;
    }

    /** A path relative to a group's, normalized; null if it leaves the store. */
    static String resolve(String base, String relative) {
        List<String> parts = new ArrayList<>();
        if (!base.isEmpty()) {
            parts.addAll(Arrays.asList(base.split("/")));
        }
        for (String part : relative.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    /** The store a group is in. */
    static Store storeOf(ZarrGroup group) {
        return Objects.requireNonNull(group.store(), "store");
    }
}
