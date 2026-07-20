package com.ebremer.falcon.zarr.metadata;

/** Whether a {@code zarr.json} describes a group or an array (its {@code node_type}). */
public enum NodeType {
    GROUP,
    ARRAY
}
