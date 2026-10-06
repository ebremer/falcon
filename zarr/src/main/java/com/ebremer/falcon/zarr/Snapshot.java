package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.metadata.ConsolidatedMetadata;

/**
 * The consolidated metadata a group answers from, shared by every handle opened through it: the group that
 * holds it and the groups below it, each viewing the part under its own path. It changes in one way only:
 * deleting a child through one of those handles removes the child here too, so none of them lists it
 * afterwards. Everything else Falcon writes leaves it as it was read: a snapshot.
 */
final class Snapshot {

    private final String source; // the key it was read from, for diagnostics
    private volatile ConsolidatedMetadata metadata;

    Snapshot(ConsolidatedMetadata metadata, String source) {
        this.metadata = metadata;
        this.source = source;
    }

    ConsolidatedMetadata get() {
        return metadata;
    }

    /** Names the entry at {@code relativePath} in diagnostics. */
    String describe(String relativePath) {
        return source + " (consolidated entry '" + relativePath + "')";
    }

    /** Forgets the node at {@code relativePath} and everything below it. */
    synchronized void remove(String relativePath) {
        metadata = metadata.without(relativePath);
    }
}
