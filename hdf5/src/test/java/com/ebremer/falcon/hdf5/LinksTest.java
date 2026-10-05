package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Links of every kind ({@code links.h5}, {@code links_old.h5}, both from h5py): hard, soft (absolute,
 * relative, chained, to a group, dangling, cyclic) and external links, in compact and dense new-style
 * groups and in old-style symbol-table groups (where soft links are cache-type-2 entries).
 */
class LinksTest {

    @Test
    void listsEveryLinkKind() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("links.h5"))) {
            Group links = h5.root().group("links");
            // Ten links: libhdf5 stores them densely, so they are listed in name-hash order.
            assertEquals(List.of("chain", "dangling", "ext", "hard", "loop_a", "loop_b", "soft_abs", "soft_group",
                    "soft_rel", "sub"), links.childNames().stream().sorted().toList());
            assertEquals(new Link.Soft("soft_abs", "/data/x"), links.link("soft_abs").orElseThrow());
            assertEquals(new Link.Soft("soft_rel", "sub/y"), links.link("soft_rel").orElseThrow());
            assertEquals(new Link.External("ext", "links_ext.h5", "/y"), links.link("ext").orElseThrow());
            Link.Hard hard = (Link.Hard) links.link("hard").orElseThrow();
            assertEquals(h5.root().group("data").dataset("x").objectHeaderAddress(), hard.objectHeaderAddress());
        }
    }

    @Test
    void softLinksResolveWithinTheFile() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("links.h5"))) {
            Group links = h5.root().group("links");
            Dataset viaSoft = links.dataset("soft_abs");
            assertArrayEquals(new int[] {1, 2, 3}, viaSoft.readInts());
            assertEquals("/links/soft_abs", viaSoft.path());
            assertArrayEquals(new int[] {7}, links.dataset("soft_rel").readInts());
            assertArrayEquals(new int[] {1, 2, 3}, links.dataset("chain").readInts());
            assertArrayEquals(new int[] {1, 2, 3}, links.group("soft_group").dataset("x").readInts());
            assertEquals(List.of("chain", "hard", "soft_abs", "soft_group", "soft_rel", "sub"),
                    links.children().stream().map(Hdf5Object::name).sorted().toList());
        }
    }

    @Test
    void unresolvableLinksReachNothing() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("links.h5"))) {
            Group links = h5.root().group("links");
            assertTrue(links.child("dangling").isEmpty());
            assertTrue(links.child("loop_a").isEmpty());   // a soft-link cycle stops after 16 links
            assertTrue(links.child("ext").isEmpty());      // external links are not followed
            HdfUnsupportedException external = assertThrows(HdfUnsupportedException.class, () -> links.dataset("ext"));
            assertTrue(external.getMessage().contains("links_ext.h5:/y"), external.getMessage());
            assertThrows(NoSuchElementException.class, () -> links.dataset("dangling"));
            assertThrows(NoSuchElementException.class, () -> links.dataset("missing"));
        }
    }

    @Test
    void compactGroupsKeepSoftLinks() throws IOException {
        // Under eight links, new-style groups store Link messages in the object header, in creation order.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("links.h5"))) {
            Group data = h5.root().group("data");
            assertEquals(List.of(new Link.Hard("x", data.dataset("x").objectHeaderAddress())), data.links());
        }
    }

    @Test
    void denseGroupsKeepSoftAndExternalLinks() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("links.h5"))) {
            Group dense = h5.root().group("dense");
            assertEquals(14, dense.links().size());
            assertEquals(new Link.External("ext", "links_ext.h5", "/y"), dense.link("ext").orElseThrow());
            assertEquals(13, dense.children().size());
            assertArrayEquals(new int[] {1, 2, 3}, dense.dataset("soft").readInts());
        }
    }

    @Test
    void oldStyleGroupsWithSoftLinksAreReadable() throws IOException {
        // Symbol-table cache type 2: the target path is in the local heap; the header address is undefined.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("links_old.h5"))) {
            Group links = h5.root().group("links");
            assertEquals(List.of("chain", "dangling", "hard", "loop_a", "loop_b", "soft_abs", "soft_group",
                    "soft_rel", "sub"), links.childNames());
            assertEquals(new Link.Soft("soft_abs", "/data/x"), links.link("soft_abs").orElseThrow());
            assertArrayEquals(new int[] {1, 2, 3}, links.dataset("soft_abs").readInts());
            assertArrayEquals(new int[] {7}, links.dataset("soft_rel").readInts());
            assertArrayEquals(new int[] {1, 2, 3}, links.dataset("chain").readInts());
            assertTrue(links.child("dangling").isEmpty());
            assertTrue(links.child("loop_b").isEmpty());
        }
    }
}
