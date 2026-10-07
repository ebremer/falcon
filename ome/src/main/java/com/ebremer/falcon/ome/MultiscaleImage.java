package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * An OME-Zarr multiscale image: a group whose levels are arrays of the same image at decreasing resolutions,
 * with the axes and transformations that place them in physical space. A label image is one too, with
 * {@link #imageLabel()} metadata.
 *
 * <pre>{@code
 * MultiscaleImage image = OmeZarr.open(store).asImage();
 * ZarrArray full = image.level(0);
 * double[] pixel = image.scale(0);                 // the pixel size along each axis
 * ZarrArray thumbnail = image.level(image.levelCount() - 1);
 * }</pre>
 *
 * <p>Where the image's metadata lists several {@code multiscales}, the first is the image, as the
 * specification recommends; {@link #multiscales()} gives them all. Transformation parameters stored in arrays
 * are loaded when the image is opened.
 */
public final class MultiscaleImage {

    private final ZarrGroup group;
    private final OmeMetadata metadata;
    private final List<Multiscale> multiscales;

    private MultiscaleImage(ZarrGroup group, OmeMetadata metadata) {
        this.group = group;
        this.metadata = metadata;
        this.multiscales = metadata.multiscales().stream().map(m -> Transformations.resolve(m, group)).toList();
    }

    /**
     * Opens the multiscale image a group holds.
     *
     * @param group the image's group
     * @return the image
     * @throws OmeFormatException                              if the group is not a multiscale image, or its
     *                                                         metadata cannot be read
     * @throws com.ebremer.falcon.zarr.ZarrUnsupportedException if the metadata is of a version Falcon does
     *                                                         not read
     */
    public static MultiscaleImage open(ZarrGroup group) {
        OmeMetadata m = OmeMetadata.read(group.attributes()).orElseThrow(() ->
                new OmeFormatException(display(group) + " has no OME-Zarr metadata"));
        if (!m.isImage()) {
            throw new OmeFormatException(display(group) + " is not a multiscale image");
        }
        return new MultiscaleImage(group, m);
    }

    /** {@return the image's group} */
    public ZarrGroup group() {
        return group;
    }

    /** {@return the group's OME-Zarr metadata} */
    public OmeMetadata metadata() {
        return metadata;
    }

    /** {@return the OME-Zarr version} */
    public OmeVersion version() {
        return metadata.version();
    }

    /** {@return every multiscale the metadata lists, with their parameters loaded} */
    public List<Multiscale> multiscales() {
        return multiscales;
    }

    /** {@return the image: the first multiscale} */
    public Multiscale multiscale() {
        return multiscales.getFirst();
    }

    /**
     * {@return the multiscale of a name, if the metadata lists one}
     *
     * @param name the multiscale's name
     */
    public Optional<Multiscale> multiscale(String name) {
        return multiscales.stream().filter(m -> name.equals(m.name())).findFirst();
    }

    /** {@return the image's name, or null} */
    public String name() {
        return multiscale().name();
    }

    /** {@return the axes, in the order of the levels' dimensions} */
    public List<Axis> axes() {
        return multiscale().axes();
    }

    /** {@return the number of levels} */
    public int levelCount() {
        return multiscale().levelCount();
    }

    /**
     * Opens a level's array.
     *
     * @param level the level, 0 for the largest
     * @return the array
     * @throws IndexOutOfBoundsException if there is no such level
     * @throws NoSuchElementException    if the level's array is not stored
     */
    public ZarrArray level(int level) {
        String path = multiscale().datasets().get(level).path();
        return group.array(trim(path));
    }

    /**
     * {@return the transformation from a level's array coordinates to the image's physical ones}
     *
     * @param level the level, 0 for the largest
     */
    public Transformation levelTransformation(int level) {
        return multiscale().levelTransformation(level);
    }

    /**
     * {@return a level's pixel size along each axis, in the axes' units}
     *
     * @param level the level, 0 for the largest
     */
    public double[] scale(int level) {
        return multiscale().scale(level);
    }

    /**
     * {@return the physical position of a level's first pixel's center}
     *
     * @param level the level, 0 for the largest
     */
    public double[] translation(int level) {
        return multiscale().translation(level);
    }

    /** {@return the {@code omero} rendering metadata, if the image has it} */
    public Optional<Omero> omero() {
        return Optional.ofNullable(metadata.omero());
    }

    /** {@return whether this is a label image} */
    public boolean isLabel() {
        return metadata.isLabelImage();
    }

    /** {@return a label image's {@code image-label} metadata, if this is one} */
    public Optional<ImageLabel> imageLabel() {
        return Optional.ofNullable(metadata.imageLabel());
    }

    /**
     * {@return the paths of the image's label images, as its {@code labels} group lists them; empty if it has
     * none}
     */
    public List<String> labelNames() {
        Optional<ZarrNode> labels = group.child("labels");
        if (labels.isEmpty() || !(labels.get() instanceof ZarrGroup g)) {
            return List.of();
        }
        return OmeMetadata.read(g.attributes()).map(OmeMetadata::labels).orElse(List.of());
    }

    /**
     * Opens one of the image's label images.
     *
     * @param name the label image's path in the {@code labels} group, as {@link #labelNames()} gives it
     * @return the label image
     * @throws NoSuchElementException if there is no such label image
     * @throws OmeFormatException     if it is not a multiscale image
     */
    public MultiscaleImage label(String name) {
        ZarrNode node = group.child("labels/" + trim(name)).orElseThrow(() ->
                new NoSuchElementException(display(group) + " has no label image '" + name + "'"));
        if (!(node instanceof ZarrGroup g)) {
            throw new OmeFormatException("label image '" + name + "' is an array, not a group");
        }
        return open(g);
    }

    /**
     * {@return a label image's source: the image it labels, at {@code image-label}'s {@code source} (by
     * default {@code ../../}); empty if this is not a label image or the source is not in the store}
     */
    public Optional<MultiscaleImage> sourceImage() {
        if (!isLabel()) {
            return Optional.empty();
        }
        String source = metadata.imageLabel().sourceImage() == null ? "../../" : metadata.imageLabel().sourceImage();
        String path = OmeValidator.resolve(group.path(), source);
        if (path == null) {
            return Optional.empty();
        }
        try {
            ZarrNode node = Zarr.open(group.store(), path);
            return node instanceof ZarrGroup g ? Optional.of(open(g)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static String trim(String path) {
        String p = path;
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    static String display(ZarrGroup group) {
        return group.path().isEmpty() ? "the root group" : "group '" + group.path() + "'";
    }

    @Override
    public String toString() {
        return "MultiscaleImage[" + (group.path().isEmpty() ? "/" : group.path()) + ", " + version() + ", "
                + levelCount() + " levels]";
    }
}
