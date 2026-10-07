package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.util.List;
import java.util.NoSuchElementException;

/** A plate's well: a group holding the well's fields of view, each a multiscale image. */
public final class Well {

    private final ZarrGroup group;
    private final OmeMetadata metadata;

    private Well(ZarrGroup group, OmeMetadata metadata) {
        this.group = group;
        this.metadata = metadata;
    }

    /**
     * Opens the well a group holds.
     *
     * @param group the well's group
     * @return the well
     * @throws OmeFormatException if the group is not a well, or its metadata cannot be read
     */
    public static Well open(ZarrGroup group) {
        OmeMetadata m = OmeMetadata.read(group.attributes()).filter(OmeMetadata::isWell).orElseThrow(() ->
                new OmeFormatException(MultiscaleImage.display(group) + " is not a well"));
        return new Well(group, m);
    }

    /** {@return the well's group} */
    public ZarrGroup group() {
        return group;
    }

    /** {@return the well's metadata: its fields of view} */
    public WellMetadata metadata() {
        return metadata.well();
    }

    /** {@return the fields of view's paths, in the order the metadata lists them} */
    public List<String> imagePaths() {
        return metadata.well().images().stream().map(WellMetadata.FieldOfView::path).toList();
    }

    /**
     * Opens a field of view.
     *
     * @param path its path in the well's group
     * @return the image
     * @throws NoSuchElementException if it is not stored
     * @throws OmeFormatException     if it is not a multiscale image
     */
    public MultiscaleImage image(String path) {
        ZarrNode node = group.child(path).orElseThrow(() ->
                new NoSuchElementException("field of view '" + path + "' is not stored"));
        if (!(node instanceof ZarrGroup g)) {
            throw new OmeFormatException("field of view '" + path + "' is an array, not a group");
        }
        return MultiscaleImage.open(g);
    }

    /**
     * Opens a field of view by its place in the metadata's list.
     *
     * @param index the field's index, from 0
     * @return the image
     * @throws IndexOutOfBoundsException if there is no such field
     */
    public MultiscaleImage image(int index) {
        return image(imagePaths().get(index));
    }

    @Override
    public String toString() {
        return "Well[" + group.path() + ", " + metadata.well().images().size() + " fields]";
    }
}
