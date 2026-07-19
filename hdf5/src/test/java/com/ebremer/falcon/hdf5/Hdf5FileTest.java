package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class Hdf5FileTest {

    @Test
    void listsOldStyleGroupTree() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("old_style_groups.h5"))) {
            assertEquals(0, h5.superblockVersion());
            Group root = h5.root();

            assertEquals(List.of("alpha", "empty", "root_ds"),
                    root.childNames().stream().sorted().toList());

            // Full recursive tree: path -> isGroup
            Map<String, Boolean> tree = new TreeMap<>();
            walk(root, tree);

            Map<String, Boolean> expected = new TreeMap<>();
            expected.put("/alpha", true);
            expected.put("/alpha/beta", true);
            expected.put("/alpha/beta/gamma", false);
            expected.put("/alpha/delta", false);
            expected.put("/empty", true);
            expected.put("/root_ds", false);
            assertEquals(expected, tree);
        }
    }

    @Test
    void navigatesByNameAndPath() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("old_style_groups.h5"))) {
            Group root = h5.root();
            assertTrue(root.group("alpha").group("beta").isGroup());
            assertFalse(root.group("alpha").dataset("delta").isGroup());
            assertEquals("/alpha/beta/gamma",
                    root.group("alpha").group("beta").dataset("gamma").path());
            assertTrue(root.group("empty").children().isEmpty());
        }
    }

    @Test
    void newStyleGroupsReportedUnsupportedUntilStageH5() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("new_style_groups.h5"))) {
            assertEquals(3, h5.superblockVersion());
            assertTrue(h5.root().isGroup());
            assertThrows(HdfUnsupportedException.class, () -> h5.root().childNames());
        }
    }

    private static void walk(Group group, Map<String, Boolean> out) {
        for (Hdf5Object object : group.children()) {
            out.put(object.path(), object.isGroup());
            if (object instanceof Group child) {
                walk(child, out);
            }
        }
    }
}
