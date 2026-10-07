package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The OME-Zarr metadata of one group, as its attributes hold it: whichever of the parts it has, read into
 * the records of {@link com.ebremer.falcon.ome.metadata}.
 *
 * <p>Where the parts are depends on the version: 0.5 and 0.6 keep them under the attributes' {@code ome}
 * key, with the {@code version} beside them; 0.4 keeps them at the attributes' top level, each part with its
 * own {@code version}. {@link #read} finds them either way, and {@link #toAttributes} writes them as their
 * version lays them out.
 *
 * @param version              the specification's version
 * @param multiscales          an image's {@code multiscales}; empty if the group is not an image
 * @param omero                an image's {@code omero} rendering metadata, or null
 * @param imageLabel           a label image's {@code image-label}, or null
 * @param labels               a {@code labels} group's label images, as paths; empty if it is not one
 * @param plate                a plate's {@code plate}, or null
 * @param well                 a well's {@code well}, or null
 * @param bioformats2rawLayout a bioformats2raw collection's {@code bioformats2raw.layout} (3), or null
 * @param series               a bioformats2raw {@code OME} group's {@code series}: the images' paths; empty
 *                             if none
 * @param scene                (0.6) a scene's {@code scene}, or null
 */
public record OmeMetadata(OmeVersion version, List<Multiscale> multiscales, Omero omero, ImageLabel imageLabel,
                          List<String> labels, PlateMetadata plate, WellMetadata well, Integer bioformats2rawLayout,
                          List<String> series, SceneMetadata scene) {

    /** The keys of a 0.4 group's attributes that hold OME-Zarr metadata. */
    static final Set<String> KEYS = Set.of("multiscales", "omero", "image-label", "labels", "plate", "well",
            "bioformats2raw.layout", "series");

    /**
     * Creates the metadata.
     *
     * @throws NullPointerException if {@code version} or a list is null
     */
    public OmeMetadata {
        Objects.requireNonNull(version, "version");
        multiscales = List.copyOf(multiscales);
        labels = List.copyOf(labels);
        series = List.copyOf(series);
    }

    /**
     * {@return metadata of the version with no parts, to add them to with the {@code with} methods}
     *
     * @param version the version
     */
    public static OmeMetadata of(OmeVersion version) {
        return new OmeMetadata(version, List.of(), null, null, List.of(), null, null, null, List.of(), null);
    }

    /**
     * Reads a group's OME-Zarr metadata from its attributes.
     *
     * @param attributes the group's attributes
     * @return the metadata, or empty if the attributes hold none
     * @throws OmeFormatException        if a part cannot be read
     * @throws ZarrUnsupportedException if the metadata is of a version Falcon does not read
     */
    public static Optional<OmeMetadata> read(JsonObject attributes) {
        Optional<Located> found = locate(attributes);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Located l = found.get();
        OmeVersion version = l.version();
        JsonObject ns = l.namespace();
        String at = l.namespaced() ? "/ome" : "";
        List<Multiscale> multiscales = new ArrayList<>();
        if (ns.has("multiscales")) {
            JsonArray ms = MetadataJson.array(ns.get("multiscales"), at + "/multiscales");
            for (int i = 0; i < ms.size(); i++) {
                multiscales.add(MetadataJson.multiscale(ms.get(i), version, at + "/multiscales/" + i));
            }
        }
        Omero omero = ns.has("omero") ? MetadataJson.omero(ns.get("omero"), at + "/omero") : null;
        ImageLabel imageLabel = ns.has("image-label")
                ? MetadataJson.imageLabel(ns.get("image-label"), at + "/image-label") : null;
        List<String> labels = ns.has("labels") ? MetadataJson.strings(ns.get("labels"), at + "/labels") : List.of();
        PlateMetadata plate = ns.has("plate") ? MetadataJson.plate(ns.get("plate"), at + "/plate") : null;
        WellMetadata well = ns.has("well") ? MetadataJson.well(ns.get("well"), at + "/well") : null;
        Integer layout = ns.has("bioformats2raw.layout")
                ? (int) MetadataJson.integer(MetadataJson.number(ns.get("bioformats2raw.layout"),
                at + "/bioformats2raw.layout"), at + "/bioformats2raw.layout") : null;
        List<String> series = ns.has("series") ? MetadataJson.strings(ns.get("series"), at + "/series") : List.of();
        SceneMetadata scene = ns.has("scene") && version == OmeVersion.V0_6
                ? MetadataJson.scene(ns.get("scene"), version, at + "/scene") : null;
        return Optional.of(new OmeMetadata(version, multiscales, omero, imageLabel, labels, plate, well, layout,
                series, scene));
    }

    /**
     * The version of a group's OME-Zarr metadata.
     *
     * @param attributes the group's attributes
     * @return the version, or empty if the attributes hold no OME-Zarr metadata
     * @throws ZarrUnsupportedException if the metadata is of a version Falcon does not read
     */
    public static Optional<OmeVersion> version(JsonObject attributes) {
        return locate(attributes).map(Located::version);
    }

    /**
     * Where a group's metadata is and what version it is.
     *
     * @param version    the version
     * @param namespace  the object holding the parts: the {@code ome} object, or (0.4) the attributes
     * @param namespaced whether it is the {@code ome} object
     */
    record Located(OmeVersion version, JsonObject namespace, boolean namespaced) {
    }

    static Optional<Located> locate(JsonObject attributes) {
        if (attributes.find("ome").orElse(null) instanceof JsonObject ns) {
            String id = ns.find("version").orElse(null) instanceof JsonString s ? s.value() : null;
            OmeVersion version;
            if (id == null) { // not as written: told apart by 0.6's own parts
                version = ns.has("scene") || ns.toJson().contains("\"coordinateSystems\"") ? OmeVersion.V0_6
                        : OmeVersion.V0_5;
            } else {
                version = OmeVersion.of(id).orElseThrow(() -> unsupported(id));
                if (version == OmeVersion.V0_4) {
                    throw new OmeFormatException("OME-Zarr 0.4 metadata is not stored under \"ome\"");
                }
            }
            return Optional.of(new Located(version, ns, true));
        }
        if (KEYS.stream().noneMatch(attributes::has)) {
            return Optional.empty();
        }
        String id = innerVersion(attributes);
        if (id != null && !id.equals("0.4")) {
            throw unsupported(id);
        }
        return Optional.of(new Located(OmeVersion.V0_4, attributes, false));
    }

    /** The version a 0.4 group's parts give, the first that gives one; null if none does. */
    private static String innerVersion(JsonObject attributes) {
        if (attributes.find("multiscales").orElse(null) instanceof JsonArray ms) {
            for (JsonValue m : ms.values()) {
                if (m instanceof JsonObject o && o.find("version").orElse(null) instanceof JsonString s) {
                    return s.value();
                }
            }
        }
        for (String key : List.of("plate", "well", "image-label")) {
            if (attributes.find(key).orElse(null) instanceof JsonObject o
                    && o.find("version").orElse(null) instanceof JsonString s) {
                return s.value();
            }
        }
        return null;
    }

    private static ZarrUnsupportedException unsupported(String id) {
        return new ZarrUnsupportedException("OME-Zarr version " + id + " is not supported: Falcon reads 0.4, 0.5, "
                + "and 0.6");
    }

    /**
     * The attributes with this metadata in them, as its version lays it out: under {@code ome} (0.5, 0.6), or
     * at the top level (0.4). The attributes' other members are kept; OME-Zarr metadata already there is
     * replaced.
     *
     * @param attributes the group's current attributes
     * @return the new attributes
     */
    public JsonObject toAttributes(JsonObject attributes) {
        Map<String, JsonValue> parts = new LinkedHashMap<>();
        if (version != OmeVersion.V0_4) {
            parts.put("version", new JsonString(version.id()));
        }
        if (!multiscales.isEmpty()) {
            List<JsonValue> list = new ArrayList<>();
            for (Multiscale m : multiscales) {
                list.add(MetadataJson.write(m, version));
            }
            parts.put("multiscales", new JsonArray(list));
        }
        if (omero != null) {
            parts.put("omero", MetadataJson.write(omero, version));
        }
        if (imageLabel != null) {
            parts.put("image-label", MetadataJson.write(imageLabel, version));
        }
        if (!labels.isEmpty()) {
            parts.put("labels", new JsonArray(labels.stream().<JsonValue>map(JsonString::new).toList()));
        }
        if (plate != null) {
            parts.put("plate", MetadataJson.write(plate, version));
        }
        if (well != null) {
            parts.put("well", MetadataJson.write(well, version));
        }
        if (bioformats2rawLayout != null) {
            parts.put("bioformats2raw.layout", JsonNumber.of(bioformats2rawLayout));
        }
        if (!series.isEmpty()) {
            parts.put("series", new JsonArray(series.stream().<JsonValue>map(JsonString::new).toList()));
        }
        if (scene != null) {
            parts.put("scene", MetadataJson.write(scene, version));
        }
        Map<String, JsonValue> result = new LinkedHashMap<>();
        if (version == OmeVersion.V0_4) {
            attributes.members().forEach((k, v) -> {
                if (!KEYS.contains(k)) {
                    result.put(k, v);
                }
            });
            result.putAll(parts);
        } else {
            result.putAll(attributes.members());
            result.put("ome", new JsonObject(parts));
        }
        return new JsonObject(result);
    }

    /** {@return whether the group is a multiscale image} */
    public boolean isImage() {
        return !multiscales.isEmpty();
    }

    /** {@return whether the group is a label image: an image with {@code image-label} metadata} */
    public boolean isLabelImage() {
        return imageLabel != null;
    }

    /** {@return whether the group is an image's {@code labels} group, listing its label images} */
    public boolean isLabels() {
        return !labels.isEmpty();
    }

    /** {@return whether the group is a plate} */
    public boolean isPlate() {
        return plate != null;
    }

    /** {@return whether the group is a plate's well} */
    public boolean isWell() {
        return well != null;
    }

    /** {@return whether the group is a bioformats2raw collection of images} */
    public boolean isCollection() {
        return bioformats2rawLayout != null;
    }

    /** {@return whether the group is a scene} */
    public boolean isScene() {
        return scene != null;
    }

    /**
     * {@return this metadata with another image}
     *
     * @param multiscale the image's multiscale
     */
    public OmeMetadata withMultiscale(Multiscale multiscale) {
        return new OmeMetadata(version, List.of(multiscale), omero, imageLabel, labels, plate, well,
                bioformats2rawLayout, series, scene);
    }

    /**
     * {@return this metadata with other rendering metadata}
     *
     * @param rendering the {@code omero} metadata, or null for none
     */
    public OmeMetadata withOmero(Omero rendering) {
        return new OmeMetadata(version, multiscales, rendering, imageLabel, labels, plate, well,
                bioformats2rawLayout, series, scene);
    }

    /**
     * {@return this metadata with other label-image metadata}
     *
     * @param label the {@code image-label} metadata, or null for none
     */
    public OmeMetadata withImageLabel(ImageLabel label) {
        return new OmeMetadata(version, multiscales, omero, label, labels, plate, well, bioformats2rawLayout,
                series, scene);
    }

    /**
     * {@return this metadata with another list of label images}
     *
     * @param paths the label images' paths in the {@code labels} group
     */
    public OmeMetadata withLabels(List<String> paths) {
        return new OmeMetadata(version, multiscales, omero, imageLabel, paths, plate, well, bioformats2rawLayout,
                series, scene);
    }

    /**
     * {@return this metadata with another plate}
     *
     * @param metadata the {@code plate} metadata, or null for none
     */
    public OmeMetadata withPlate(PlateMetadata metadata) {
        return new OmeMetadata(version, multiscales, omero, imageLabel, labels, metadata, well,
                bioformats2rawLayout, series, scene);
    }

    /**
     * {@return this metadata with another well}
     *
     * @param metadata the {@code well} metadata, or null for none
     */
    public OmeMetadata withWell(WellMetadata metadata) {
        return new OmeMetadata(version, multiscales, omero, imageLabel, labels, plate, metadata,
                bioformats2rawLayout, series, scene);
    }

    /**
     * {@return this metadata with another scene}
     *
     * @param metadata the {@code scene} metadata, or null for none
     * @throws IllegalArgumentException if the version is before 0.6, which has no scenes
     */
    public OmeMetadata withScene(SceneMetadata metadata) {
        if (metadata != null && version != OmeVersion.V0_6) {
            throw new IllegalArgumentException("scenes are OME-Zarr 0.6; this metadata is " + version);
        }
        return new OmeMetadata(version, multiscales, omero, imageLabel, labels, plate, well, bioformats2rawLayout,
                series, metadata);
    }
}
