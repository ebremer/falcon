package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A high-content screening plate: a group of row groups of well groups, each well holding its fields of view
 * as images.
 *
 * <pre>{@code
 * Plate plate = OmeZarr.open(store).asPlate();
 * MultiscaleImage field = plate.well("B", "3").image(0);
 * }</pre>
 */
public final class Plate {

    private final ZarrGroup group;
    private final OmeMetadata metadata;

    private Plate(ZarrGroup group, OmeMetadata metadata) {
        this.group = group;
        this.metadata = metadata;
    }

    /**
     * Opens the plate a group holds.
     *
     * @param group the plate's group
     * @return the plate
     * @throws OmeFormatException if the group is not a plate, or its metadata cannot be read
     */
    public static Plate open(ZarrGroup group) {
        OmeMetadata m = OmeMetadata.read(group.attributes()).filter(OmeMetadata::isPlate).orElseThrow(() ->
                new OmeFormatException(MultiscaleImage.display(group) + " is not a plate"));
        return new Plate(group, m);
    }

    /** {@return the plate's group} */
    public ZarrGroup group() {
        return group;
    }

    /** {@return the OME-Zarr version} */
    public OmeVersion version() {
        return metadata.version();
    }

    /** {@return the plate's metadata: rows, columns, wells, and acquisitions} */
    public PlateMetadata metadata() {
        return metadata.plate();
    }

    /** {@return the wells' paths, {@code row/column}, in the order the metadata lists them} */
    public List<String> wellPaths() {
        return metadata.plate().wells().stream().map(PlateMetadata.WellRef::path).toList();
    }

    /**
     * Opens a well.
     *
     * @param path the well's path, {@code row/column}
     * @return the well
     * @throws NoSuchElementException if the plate lists no such well, or it is not stored
     * @throws OmeFormatException     if it is not a well
     */
    public Well well(String path) {
        if (!wellPaths().contains(path)) {
            throw new NoSuchElementException("the plate has no well '" + path + "'");
        }
        ZarrNode node = group.child(path).orElseThrow(() ->
                new NoSuchElementException("well '" + path + "' is not stored"));
        if (!(node instanceof ZarrGroup g)) {
            throw new OmeFormatException("well '" + path + "' is an array, not a group");
        }
        return Well.open(g);
    }

    /**
     * Opens the well in a row and a column.
     *
     * @param row    the row's name, such as {@code "B"}
     * @param column the column's name, such as {@code "3"}
     * @return the well
     * @throws NoSuchElementException if the plate has no well there
     */
    public Well well(String row, String column) {
        return well(row + "/" + column);
    }

    @Override
    public String toString() {
        PlateMetadata p = metadata.plate();
        return "Plate[" + (group.path().isEmpty() ? "/" : group.path()) + ", " + p.rows().size() + "x"
                + p.columns().size() + ", " + p.wells().size() + " wells]";
    }
}
