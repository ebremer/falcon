package com.ebremer.falcon.ome;

import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * A bioformats2raw collection (the transitional {@code bioformats2raw.layout}): the images of one
 * multi-image file, in a stable order, with the file's OME-XML metadata.
 *
 * <p>The images are those the {@code OME} group's {@code series} lists, or, without it, the groups
 * {@code 0}, {@code 1}, ... in turn. A collection that is also a plate is {@linkplain #plate() read as the
 * plate}. As the specification asks, a reader should show that there is more than one image.
 */
public final class ImageCollection {

    private final ZarrGroup group;
    private final OmeMetadata metadata;

    private ImageCollection(ZarrGroup group, OmeMetadata metadata) {
        this.group = group;
        this.metadata = metadata;
    }

    /**
     * Opens the collection a group holds.
     *
     * @param group the collection's group
     * @return the collection
     * @throws OmeFormatException if the group is not a bioformats2raw collection
     */
    public static ImageCollection open(ZarrGroup group) {
        OmeMetadata m = OmeMetadata.read(group.attributes()).filter(OmeMetadata::isCollection).orElseThrow(() ->
                new OmeFormatException(MultiscaleImage.display(group) + " is not a bioformats2raw collection"));
        return new ImageCollection(group, m);
    }

    /** {@return the collection's group} */
    public ZarrGroup group() {
        return group;
    }

    /** {@return the OME-Zarr version} */
    public OmeVersion version() {
        return metadata.version();
    }

    /** {@return the plate, if the collection is one, whose layout then places the images} */
    public Optional<Plate> plate() {
        return metadata.isPlate() ? Optional.of(Plate.open(group)) : Optional.empty();
    }

    /** {@return the images' paths, in the collection's order} */
    public List<String> imagePaths() {
        Optional<ZarrNode> ome = group.child("OME");
        if (ome.isPresent() && ome.get() instanceof ZarrGroup g) {
            List<String> series = OmeMetadata.read(g.attributes()).map(OmeMetadata::series).orElse(List.of());
            if (!series.isEmpty()) {
                return series;
            }
        }
        List<String> paths = new ArrayList<>();
        for (int i = 0; group.child(Integer.toString(i)).isPresent(); i++) {
            paths.add(Integer.toString(i));
        }
        return paths;
    }

    /** {@return the number of images} */
    public int size() {
        return imagePaths().size();
    }

    /**
     * Opens an image.
     *
     * @param index the image's index in the collection, from 0
     * @return the image
     * @throws IndexOutOfBoundsException if there is no such image
     * @throws OmeFormatException        if it is not a multiscale image
     */
    public MultiscaleImage image(int index) {
        String path = imagePaths().get(index);
        ZarrNode node = group.child(path).orElseThrow(() ->
                new NoSuchElementException("image '" + path + "' is not stored"));
        if (!(node instanceof ZarrGroup g)) {
            throw new OmeFormatException("image '" + path + "' is an array, not a group");
        }
        return MultiscaleImage.open(g);
    }

    /** {@return the collection's OME-XML metadata, {@code OME/METADATA.ome.xml}, if it has it} */
    public Optional<String> omeXml() {
        String key = (group.path().isEmpty() ? "" : group.path() + "/") + "OME/METADATA.ome.xml";
        return group.store().get(key).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "ImageCollection[" + (group.path().isEmpty() ? "/" : group.path()) + ", " + size() + " images]";
    }
}
