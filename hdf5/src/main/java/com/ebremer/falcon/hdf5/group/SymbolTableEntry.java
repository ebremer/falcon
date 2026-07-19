package com.ebremer.falcon.hdf5.group;

/**
 * One symbol-table entry (spec section III.C): a single link within an old-style group.
 *
 * @param linkNameOffset       byte offset of the link name in the group's local heap
 * @param objectHeaderAddress  file address of the linked object's header
 * @param cacheType            0 = no cache (dataset / committed type), 1 = group (scratch pad caches
 *                             its B-tree and heap), 2 = symbolic link
 */
public record SymbolTableEntry(long linkNameOffset, long objectHeaderAddress, int cacheType) {

    public boolean isGroup() {
        return cacheType == 1;
    }
}
