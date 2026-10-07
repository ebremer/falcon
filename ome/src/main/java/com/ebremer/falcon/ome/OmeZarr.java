package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.Store;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Opens OME-Zarr groups, and creates plates, wells, and scenes: entry points to read an image, a plate, a
 * collection, or a scene from any Zarr store (a directory, a ZIP archive, HTTP, or S3 through the s3 module).
 * Images are written with {@link MultiscaleImageWriter}.
 *
 * <pre>{@code
 * OmeGroup root = OmeZarr.open(FileSystemStore.open(Path.of("image.ome.zarr")));
 * if (root.isImage()) {
 *     MultiscaleImage image = root.asImage();
 *     ...
 * }
 * }</pre>
 */
public final class OmeZarr {

    private OmeZarr() {
    }

    /**
     * Opens the store's root group.
     *
     * @param store the store
     * @return the group, with its OME-Zarr metadata
     * @throws OmeFormatException                              if the root is not a group with OME-Zarr
     *                                                         metadata, or its metadata cannot be read
     * @throws com.ebremer.falcon.zarr.ZarrException          if the store holds no Zarr hierarchy
     */
    public static OmeGroup open(Store store) {
        return open(store, "");
    }

    /**
     * Opens a group in the store.
     *
     * @param store the store
     * @param path  the group's path from the root ({@code ""} for the root)
     * @return the group, with its OME-Zarr metadata
     * @throws OmeFormatException                              if the node is not a group with OME-Zarr
     *                                                         metadata, or its metadata cannot be read
     * @throws com.ebremer.falcon.zarr.ZarrException          if there is no Zarr node there
     */
    public static OmeGroup open(Store store, String path) {
        ZarrNode node = Zarr.open(store, path);
        if (!(node instanceof ZarrGroup group)) {
            throw new OmeFormatException("'" + (path.isEmpty() ? "/" : path) + "' is an array; OME-Zarr metadata "
                    + "is in groups");
        }
        return open(group);
    }

    /**
     * Opens a group's OME-Zarr metadata.
     *
     * @param group the group
     * @return the group, with its OME-Zarr metadata
     * @throws OmeFormatException if the group has no OME-Zarr metadata, or it cannot be read
     */
    public static OmeGroup open(ZarrGroup group) {
        OmeMetadata metadata = OmeMetadata.read(group.attributes()).orElseThrow(() ->
                new OmeFormatException(MultiscaleImage.display(group) + " has no OME-Zarr metadata"));
        return new OmeGroup(group, metadata);
    }

    /**
     * Creates a plate as the root group of a store: its metadata, and a group for each row that has wells. Its
     * wells are then created with {@link #createWell}, and their images written with a
     * {@link MultiscaleImageWriter}.
     *
     * @param store   an empty store
     * @param version the OME-Zarr version to write
     * @param plate   the plate's metadata
     * @return the plate
     * @throws IllegalArgumentException      if the store already holds a root node
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static Plate createPlate(Store store, OmeVersion version, PlateMetadata plate) {
        ZarrGroup group = Zarr.createGroup(store, OmeMetadata.of(version).withPlate(plate)
                .toAttributes(new JsonObject(Map.of())), false, version.zarrFormat());
        for (String row : rowsWithWells(plate)) {
            group.createGroup(row);
        }
        return Plate.open(group);
    }

    private static List<String> rowsWithWells(PlateMetadata plate) {
        return plate.wells().stream().map(w -> w.path().substring(0, Math.max(0, w.path().indexOf('/'))))
                .filter(r -> !r.isEmpty()).distinct().toList();
    }

    /**
     * Creates one of a plate's wells: its group, with its metadata. Its images are then written into
     * {@link Well#group()} with a {@link MultiscaleImageWriter}, at the paths the metadata lists.
     *
     * @param plate the plate
     * @param path  the well's path, {@code row/column}, as the plate lists it
     * @param well  the well's metadata
     * @return the well
     * @throws IllegalArgumentException if the plate lists no such well, or it already exists
     */
    public static Well createWell(Plate plate, String path, WellMetadata well) {
        if (!plate.wellPaths().contains(path)) {
            throw new IllegalArgumentException("the plate lists no well '" + path + "'");
        }
        String[] parts = path.split("/");
        ZarrGroup row = plate.group().child(parts[0]).filter(ZarrNode::isGroup).map(ZarrNode::asGroup)
                .orElseGet(() -> plate.group().createGroup(parts[0]));
        ZarrGroup group = row.createGroup(parts[1], OmeMetadata.of(plate.version()).withWell(well)
                .toAttributes(new JsonObject(Map.of())));
        return Well.open(group);
    }

    /**
     * Creates a scene (OME-Zarr 0.6) as the root group of a store: its metadata. The images it places are
     * then written into it with a {@link MultiscaleImageWriter}, at the paths its transformations name.
     *
     * @param store an empty store
     * @param scene the scene's metadata
     * @return the scene
     * @throws IllegalArgumentException      if the store already holds a root node
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static Scene createScene(Store store, SceneMetadata scene) {
        ZarrGroup group = Zarr.createGroup(store, OmeMetadata.of(OmeVersion.V0_6).withScene(scene)
                .toAttributes(new JsonObject(Map.of())), false, 3);
        return Scene.open(group);
    }

    /**
     * {@return a node's OME-Zarr metadata, if it is a group that has some}
     *
     * @param node the node
     * @throws OmeFormatException                              if its metadata cannot be read
     * @throws com.ebremer.falcon.zarr.ZarrUnsupportedException if it is of a version Falcon does not read
     */
    public static Optional<OmeMetadata> metadata(ZarrNode node) {
        return node instanceof ZarrGroup g ? OmeMetadata.read(g.attributes()) : Optional.empty();
    }

    /**
     * A group with OME-Zarr metadata: what it is, and views of it as each kind.
     *
     * @param group    the group
     * @param metadata its OME-Zarr metadata
     */
    public record OmeGroup(ZarrGroup group, OmeMetadata metadata) {

        /** {@return the OME-Zarr version} */
        public OmeVersion version() {
            return metadata.version();
        }

        /** {@return whether the group is a multiscale image (a label image among them)} */
        public boolean isImage() {
            return metadata.isImage();
        }

        /** {@return whether the group is a plate} */
        public boolean isPlate() {
            return metadata.isPlate();
        }

        /** {@return whether the group is a well} */
        public boolean isWell() {
            return metadata.isWell();
        }

        /** {@return whether the group is a bioformats2raw collection} */
        public boolean isCollection() {
            return metadata.isCollection();
        }

        /** {@return whether the group is a scene} */
        public boolean isScene() {
            return metadata.isScene();
        }

        /** {@return whether the group is an image's labels group} */
        public boolean isLabels() {
            return metadata.isLabels();
        }

        /**
         * {@return what the group is, in words: such as {@code "image"}, {@code "label image"},
         * {@code "plate"}, or {@code "bioformats2raw collection"}; several joined by {@code ", "}}
         */
        public String kind() {
            List<String> kinds = new java.util.ArrayList<>();
            if (metadata.isLabelImage()) {
                kinds.add("label image");
            } else if (metadata.isImage()) {
                kinds.add("image");
            }
            if (metadata.isLabels()) {
                kinds.add("labels");
            }
            if (metadata.isPlate()) {
                kinds.add("plate");
            }
            if (metadata.isWell()) {
                kinds.add("well");
            }
            if (metadata.isCollection()) {
                kinds.add("bioformats2raw collection");
            }
            if (!metadata.series().isEmpty()) {
                kinds.add("OME series");
            }
            if (metadata.isScene()) {
                kinds.add("scene");
            }
            return kinds.isEmpty() ? "OME-Zarr group" : String.join(", ", kinds);
        }

        /**
         * {@return the group as a multiscale image}
         *
         * @throws OmeFormatException if it is not one
         */
        public MultiscaleImage asImage() {
            return MultiscaleImage.open(group);
        }

        /**
         * {@return the group as a plate}
         *
         * @throws OmeFormatException if it is not one
         */
        public Plate asPlate() {
            return Plate.open(group);
        }

        /**
         * {@return the group as a well}
         *
         * @throws OmeFormatException if it is not one
         */
        public Well asWell() {
            return Well.open(group);
        }

        /**
         * {@return the group as a bioformats2raw collection}
         *
         * @throws OmeFormatException if it is not one
         */
        public ImageCollection asCollection() {
            return ImageCollection.open(group);
        }

        /**
         * {@return the group as a scene}
         *
         * @throws OmeFormatException if it is not one
         */
        public Scene asScene() {
            return Scene.open(group);
        }
    }
}
