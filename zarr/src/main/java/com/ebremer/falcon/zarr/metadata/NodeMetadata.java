package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * Parsed {@code zarr.json} metadata for one node: either {@link GroupMetadata} or {@link ArrayMetadata}.
 * Obtain instances from {@link Metadata#parse}.
 */
public sealed interface NodeMetadata permits GroupMetadata, ArrayMetadata {

    /** Whether this describes a group or an array. */
    NodeType nodeType();

    /** The node's user attributes; an empty object when none are stored. */
    JsonObject attributes();
}
