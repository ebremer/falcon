package com.ebremer.falcon.hdf5.header;

/**
 * Numeric identifiers for the 24 HDF5 object-header message types (spec section IV.A.2).
 */
public final class MessageType {

    public static final int NIL = 0;
    public static final int DATASPACE = 1;
    public static final int LINK_INFO = 2;
    public static final int DATATYPE = 3;
    public static final int FILL_VALUE_OLD = 4;
    public static final int FILL_VALUE = 5;
    public static final int LINK = 6;
    public static final int EXTERNAL_DATA_FILES = 7;
    public static final int DATA_LAYOUT = 8;
    public static final int BOGUS = 9;
    public static final int GROUP_INFO = 10;
    public static final int FILTER_PIPELINE = 11;
    public static final int ATTRIBUTE = 12;
    public static final int OBJECT_COMMENT = 13;
    public static final int OBJECT_MODIFICATION_TIME_OLD = 14;
    public static final int SHARED_MESSAGE_TABLE = 15;
    public static final int OBJECT_HEADER_CONTINUATION = 16;
    public static final int SYMBOL_TABLE = 17;
    public static final int OBJECT_MODIFICATION_TIME = 18;
    public static final int BTREE_K_VALUES = 19;
    public static final int DRIVER_INFO = 20;
    public static final int ATTRIBUTE_INFO = 21;
    public static final int OBJECT_REFERENCE_COUNT = 22;
    public static final int FILE_SPACE_INFO = 23;

    private MessageType() {
        // No instances.
    }
}
