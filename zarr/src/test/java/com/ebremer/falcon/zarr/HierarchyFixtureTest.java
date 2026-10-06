package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.json.JsonValue;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hierarchies and metadata as zarr-python 3.4 writes them (P1 T3; the stores come from
 * {@code tools/fixtures/gen_zarr_p1_fixtures.py}). The expected values are the ones the generator wrote,
 * which zarr-python reads back.
 */
class HierarchyFixtureTest {

    private static Path fixture(String name) {
        try {
            return Path.of(HierarchyFixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(List<? extends ZarrNode> nodes) {
        return nodes.stream().map(ZarrNode::name).toList();
    }

    private static void assertTree(ZarrGroup root) {
        assertEquals("hierarchy", root.attributes().get("title").asString());
        assertEquals(List.of("a", "g"), root.childNames());
        assertEquals(List.of("a"), names(root.arrays()));
        assertEquals(List.of("g"), names(root.groups()));
        assertArrayEquals(new int[] {1, 2, 3, 4}, root.array("a").readInts());

        ZarrGroup g = root.group("g");
        assertEquals(1, g.attributes().get("level").asNumber().intValue());
        assertEquals(List.of("deep", "inner"), g.childNames());
        assertArrayEquals(new double[] {0.5, 1.5}, g.array("inner").readDoubles());
        assertEquals("g/deep/leaf", g.group("deep").array("leaf").path());
        assertArrayEquals(new int[] {7}, g.group("deep").array("leaf").readInts());
    }

    @Test
    void readsAGroupTree() {
        assertTree(Zarr.open(fixture("p1_hierarchy")).asGroup());
    }

    /**
     * P1 I10: a consolidated group's zarr.json carries "consolidated_metadata", which the group may carry.
     * Since P2 F2 Falcon answers from it (ConsolidatedFixtureTest); either way the tree reads the same.
     */
    @Test
    void readsAConsolidatedGroupTree() {
        assertTree(Zarr.open(fixture("p1_consolidated")).asGroup());
    }

    /** P1 I1: zarr-python writes NaN and the infinities in attributes as bare JSON tokens. */
    @Test
    void readsNaNAndInfinityAttributes() {
        ZarrGroup root = Zarr.open(fixture("p1_nan_attrs")).asGroup();
        assertTrue(Double.isNaN(root.attributes().get("nan").asNumber().doubleValue()));
        assertEquals(Double.POSITIVE_INFINITY, root.attributes().get("inf").asNumber().doubleValue());
        assertEquals(Double.NEGATIVE_INFINITY, root.attributes().get("ninf").asNumber().doubleValue());
        JsonValue list = root.attributes().get("list");
        assertTrue(Double.isNaN(list.asArray().get(1).asNumber().doubleValue()));

        ZarrArray f = root.array("f");
        assertEquals(Double.POSITIVE_INFINITY, f.attributes().get("valid_max").asNumber().doubleValue());
        float[] values = f.readFloats();
        assertEquals(1.0f, values[0]);
        assertEquals(2.0f, values[1]);
        assertTrue(Float.isNaN(values[2])); // never written: the NaN fill

        ZarrArray v2 = root.array("v2");
        assertTrue(Double.isNaN(v2.attributes().get("_FillValue").asNumber().doubleValue()));
        assertEquals(Double.NEGATIVE_INFINITY, v2.attributes().get("valid_min").asNumber().doubleValue());
        assertArrayEquals(new double[] {3.0, 4.0}, v2.readDoubles());
        assertEquals(List.of("f", "v2"), names(root.children()));
    }

    /** P1 I6: v2 arrays with a complex dtype and fill_value null, and with dimension_separator null. */
    @Test
    void readsTheV2Cases() {
        ZarrArray c16 = Zarr.open(fixture("p1_v2_cases/c16_null")).asArray();
        ByteBuffer raw = ByteBuffer.wrap(c16.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
        double[] expected = {1, 2, 3, -4, 0, 0}; // the third element's chunk was never written: zeros
        for (double v : expected) {
            assertEquals(v, raw.getDouble());
        }

        ZarrArray sep = Zarr.open(fixture("p1_v2_cases/sep_null")).asArray();
        assertEquals(".", sep.separator());
        assertArrayEquals(new int[] {1, 2, 3, 4}, sep.readInts());
    }

    /**
     * P1 I3: one child Falcon cannot open (a numpy.datetime64 array, an extension data type; a v2 "&lt;U8"
     * array) aborted children(), arrays(), and groups(). They now leave it out; childNames() lists it, and
     * array(name) says why it fails.
     */
    @Test
    void childrenThatCannotBeOpenedAreLeftOut() {
        ZarrGroup root = Zarr.open(fixture("p1_mixed")).asGroup();
        assertEquals(List.of("good", "text", "when", "zz_group"), root.childNames());
        assertEquals(List.of("good", "zz_group"), names(root.children()));
        assertEquals(List.of("good"), names(root.arrays()));
        assertEquals(List.of("zz_group"), names(root.groups()));
        assertArrayEquals(new double[] {1.25, 2.5}, root.array("good").readDoubles());

        ZarrUnsupportedException when = assertThrows(ZarrUnsupportedException.class, () -> root.array("when"));
        assertTrue(when.getMessage().contains("numpy.datetime64"), when.getMessage());
        ZarrUnsupportedException text = assertThrows(ZarrUnsupportedException.class, () -> root.child("text"));
        assertTrue(text.getMessage().contains("<U8"), text.getMessage());
    }
}
