package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystem;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.Dataset;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading OME-Zarr metadata from attributes, and writing it, as each version lays it out. */
class MetadataTest {

    static final List<Axis> AXES = List.of(Axis.channel("c"), Axis.space("y", "micrometer"),
            Axis.space("x", "micrometer"));

    static Multiscale image() {
        return new Multiscale("img", "mean", null, null, AXES, List.of(), List.of(
                Dataset.of("0", new double[] {1, 0.5, 0.5}, null),
                Dataset.of("1", new double[] {1, 1, 1}, new double[] {0, 0.25, 0.25})), List.of());
    }

    static JsonObject attributes(String json) {
        return Json.parse(json).asObject();
    }

    @Test
    void eachVersionLaysItsMetadataOutItsOwnWay() {
        OmeMetadata m = OmeMetadata.of(OmeVersion.V0_4).withMultiscale(image());
        JsonObject a4 = m.toAttributes(new JsonObject(Map.of()));
        assertTrue(a4.has("multiscales"));
        assertEquals("0.4", a4.get("multiscales").asArray().get(0).asObject().get("version").asString());

        JsonObject a5 = new OmeMetadata(OmeVersion.V0_5, m.multiscales(), null, null, List.of(), null, null, null,
                List.of(), null).toAttributes(new JsonObject(Map.of()));
        assertEquals("0.5", a5.get("ome").asObject().get("version").asString());
        assertFalse(a5.get("ome").asObject().get("multiscales").asArray().get(0).asObject().has("version"));

        JsonObject a6 = new OmeMetadata(OmeVersion.V0_6, m.multiscales(), null, null, List.of(), null, null, null,
                List.of(), null).toAttributes(new JsonObject(Map.of()));
        JsonObject ms = a6.get("ome").asObject().get("multiscales").asArray().get(0).asObject();
        assertEquals("physical", ms.get("coordinateSystems").asArray().get(0).asObject().get("name").asString());
        JsonObject level1 = ms.get("datasets").asArray().get(1).asObject().get("coordinateTransformations").asArray()
                .get(0).asObject();
        assertEquals("sequence", level1.get("type").asString());
        assertEquals("1", level1.get("input").asObject().get("path").asString());
        assertEquals("physical", level1.get("output").asObject().get("name").asString());
        assertTrue(new OmeValidator().metadataOnly(true).validate(a6).isValid());
    }

    @Test
    void whatIsWrittenReadsBackTheSame() {
        PlateMetadata plate = new PlateMetadata("p", null, 2, List.of("A", "B"), List.of("1"),
                List.of(new PlateMetadata.WellRef("A/1", 0, 0)), List.of(new PlateMetadata.Acquisition(3, "a", 2,
                "d", 10L, 20L)));
        Omero omero = new Omero(7L, "name", null, List.of(Omero.Channel.of("c0", "FF0000",
                new Omero.Window(0, 255, 10, 200))), new Omero.Rdefs(0, 1, "color"));
        ImageLabel label = new ImageLabel(null, List.of(new ImageLabel.LabelColor(1, new int[] {1, 2, 3, 4})),
                List.of(attributes("{\"label-value\":1,\"area\":5}")), "../../");
        for (OmeVersion v : OmeVersion.values()) {
            OmeMetadata m = OmeMetadata.of(v).withMultiscale(image()).withOmero(omero).withImageLabel(label)
                    .withPlate(plate).withWell(new WellMetadata(null, List.of(new WellMetadata.FieldOfView("0", 3L))))
                    .withLabels(List.of("cells"));
            OmeMetadata back = OmeMetadata.read(m.toAttributes(new JsonObject(Map.of()))).orElseThrow();
            assertEquals(v, back.version());
            assertEquals(m.multiscales().getFirst().axes(), back.multiscales().getFirst().axes());
            assertEquals(List.of("0", "1"), back.multiscales().getFirst().paths());
            assertEquals(0.25, back.multiscales().getFirst().translation(1)[2]);
            assertEquals(omero.channels(), back.omero().channels());
            assertEquals(omero.rdefs(), back.omero().rdefs());
            assertEquals(label.colors(), back.imageLabel().colors());
            assertEquals(label.properties(), back.imageLabel().properties());
            assertEquals(plate.wells(), back.plate().wells());
            assertEquals(plate.acquisitions(), back.plate().acquisitions());
            assertEquals(List.of("cells"), back.labels());
            assertEquals(3L, back.well().images().getFirst().acquisition());
        }
    }

    @Test
    void otherAttributesAreKept() {
        JsonObject before = attributes("{\"mine\":1,\"multiscales\":[]}");
        JsonObject a4 = OmeMetadata.of(OmeVersion.V0_4).withMultiscale(image()).toAttributes(before);
        assertEquals(1, a4.get("mine").asNumber().intValue());
        assertEquals(1, a4.get("multiscales").asArray().size());
        JsonObject a5 = OmeMetadata.of(OmeVersion.V0_5).withMultiscale(image()).toAttributes(before);
        assertTrue(a5.has("mine") && a5.has("ome"));
    }

    @Test
    void versionsAreFoundWhereverTheMetadataKeepsThem() {
        assertEquals(OmeVersion.V0_4, OmeMetadata.version(attributes(
                "{\"multiscales\":[{\"version\":\"0.4\",\"axes\":[],\"datasets\":[]}]}")).orElseThrow());
        assertEquals(OmeVersion.V0_4, OmeMetadata.version(attributes("{\"labels\":[\"a\"]}")).orElseThrow());
        assertEquals(OmeVersion.V0_6, OmeMetadata.version(attributes("{\"ome\":{\"version\":\"0.6rc0\"}}")).orElseThrow());
        assertTrue(OmeMetadata.version(attributes("{\"other\":1}")).isEmpty());
        assertThrows(ZarrUnsupportedException.class, () -> OmeMetadata.read(attributes(
                "{\"multiscales\":[{\"version\":\"0.3\",\"axes\":[\"y\",\"x\"],\"datasets\":[]}]}")));
        assertThrows(ZarrUnsupportedException.class, () -> OmeMetadata.read(attributes("{\"ome\":{\"version\":\"0.9\"}}")));
    }

    @Test
    void readingIsLenientButNamesWhatItCannotRead() {
        // 0.3's string axes are read by name; a missing version is 0.4
        OmeMetadata m = OmeMetadata.read(attributes("{\"multiscales\":[{\"axes\":[\"t\",\"c\",\"z\",\"y\",\"x\"],"
                + "\"datasets\":[{\"path\":\"0\"}]}]}")).orElseThrow();
        assertEquals(OmeVersion.V0_4, m.version());
        assertTrue(m.multiscales().getFirst().axes().get(0).isTime());
        OmeFormatException e = assertThrows(OmeFormatException.class, () -> OmeMetadata.read(attributes(
                "{\"ome\":{\"version\":\"0.5\",\"multiscales\":[{\"axes\":[]}]}}")));
        assertTrue(e.getMessage().contains("/ome/multiscales/0"), e.getMessage());
        // an unknown transformation type is kept as it is
        OmeMetadata u = OmeMetadata.read(attributes("{\"ome\":{\"version\":\"0.6\",\"scene\":{\"coordinateTransformations\":"
                + "[{\"type\":\"warp\",\"k\":1,\"input\":{\"name\":\"a\"},\"output\":{\"name\":\"b\"}}]}}}")).orElseThrow();
        Transformation t = u.scene().transformations().getFirst();
        assertEquals("warp", t.type());
        assertEquals(CoordinateSystemRef.named("b"), t.output());
        assertThrows(UnsupportedOperationException.class, () -> t.apply(new double[] {1}));
    }

    @Test
    void theZeroSixDraftsStringReferencesAreRead() {
        OmeMetadata m = OmeMetadata.read(attributes("{\"ome\":{\"version\":\"0.6\",\"multiscales\":[{"
                + "\"coordinateSystems\":[{\"name\":\"p\",\"axes\":[{\"name\":\"y\",\"type\":\"space\"},"
                + "{\"name\":\"x\",\"type\":\"space\"}]}],\"datasets\":[{\"path\":\"0\",\"coordinateTransformations\":"
                + "[{\"type\":\"scale\",\"scale\":[2,2],\"input\":\"0\",\"output\":\"p\"}]}]}]}}")).orElseThrow();
        Transformation t = m.multiscales().getFirst().datasets().getFirst().transformations().getFirst();
        assertEquals(CoordinateSystemRef.array("0"), t.input());
        assertEquals(CoordinateSystemRef.named("p"), t.output());
        assertEquals("p", m.multiscales().getFirst().intrinsicCoordinateSystem().map(CoordinateSystem::name).orElseThrow());
    }

    @Test
    void scenesAreZeroSix() {
        SceneMetadata scene = new SceneMetadata(List.of(), List.of(Transformation.Scale.of(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> OmeMetadata.of(OmeVersion.V0_5).withScene(scene));
        OmeMetadata m = OmeMetadata.of(OmeVersion.V0_6).withScene(scene);
        assertEquals(scene.transformations(), OmeMetadata.read(m.toAttributes(new JsonObject(Map.of())))
                .orElseThrow().scene().transformations());
    }
}
