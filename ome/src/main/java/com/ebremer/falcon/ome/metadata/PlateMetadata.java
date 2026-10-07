package com.ebremer.falcon.ome.metadata;

import java.util.List;
import java.util.Objects;

/**
 * The {@code plate} metadata of a high-content screening plate: its rows and columns, the wells that hold
 * images, and the acquisitions they were imaged in.
 *
 * @param name         the plate's name, or null
 * @param version      (0.4) the metadata's version, or null
 * @param fieldCount   the most fields of view any well has, or null
 * @param rows         the rows' names, in order; every row of the physical plate
 * @param columns      the columns' names, in order; every column of the physical plate
 * @param wells        the wells that hold images
 * @param acquisitions the acquisitions; empty if none are listed
 */
public record PlateMetadata(String name, String version, Integer fieldCount, List<String> rows, List<String> columns,
                            List<WellRef> wells, List<Acquisition> acquisitions) {

    /**
     * Creates the metadata.
     *
     * @throws NullPointerException if a list is null
     */
    public PlateMetadata {
        rows = List.copyOf(rows);
        columns = List.copyOf(columns);
        wells = List.copyOf(wells);
        acquisitions = List.copyOf(acquisitions);
    }

    /**
     * A well of the plate.
     *
     * @param path        the well's group, {@code row/column}
     * @param rowIndex    its row's index in {@link #rows()}
     * @param columnIndex its column's index in {@link #columns()}
     */
    public record WellRef(String path, int rowIndex, int columnIndex) {

        /**
         * Creates the reference.
         *
         * @throws NullPointerException if {@code path} is null
         */
        public WellRef {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * An acquisition: one run of imaging the plate.
     *
     * @param id                its identifier, unique in the plate, at least 0
     * @param name              its name, or null
     * @param maximumFieldCount the most fields of view a well has in it, or null
     * @param description       its description, or null
     * @param startTime         when it started, in seconds since the epoch, or null
     * @param endTime           when it ended, in seconds since the epoch, or null
     */
    public record Acquisition(long id, String name, Integer maximumFieldCount, String description, Long startTime,
                              Long endTime) {
    }
}
