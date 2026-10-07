package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The validator's rules, one at a time. (The specification's own test suites run against it through
 * {@code tools/ome/run_ome_conformance.sh}.)
 */
class ValidatorTest {

    static final String IMAGE_05 = """
            {"ome": {"version": "0.5", "multiscales": [{"name": "i", "type": "mean", "metadata": {},
              "axes": [{"name": "c", "type": "channel"}, {"name": "y", "type": "space", "unit": "micrometer"},
                       {"name": "x", "type": "space", "unit": "micrometer"}],
              "datasets": [{"path": "0", "coordinateTransformations": [{"type": "scale", "scale": [1, 0.5, 0.5]}]}]}]}}
            """;

    static ValidationReport check(String json) {
        return new OmeValidator().metadataOnly(true).validate(Json.parse(json).asObject());
    }

    static void invalid(String json, String expected) {
        ValidationReport r = check(json);
        assertFalse(r.isValid(), json);
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains(expected)), () -> expected + " in\n" + r);
    }

    @Test
    void aGoodImageIsValidAndStrictlySo() {
        ValidationReport r = check(IMAGE_05);
        assertTrue(r.isValid(), r::toString);
        assertTrue(r.issues().isEmpty(), r::toString);
        assertTrue(new OmeValidator().strict(true).metadataOnly(true).validate(Json.parse(IMAGE_05).asObject()).isValid());
    }

    @Test
    void axes() {
        invalid(IMAGE_05.replace("\"name\": \"c\", \"type\": \"channel\"", "\"name\": \"x\", \"type\": \"channel\""),
                "axis names must be unique");
        invalid(IMAGE_05.replace("{\"name\": \"c\", \"type\": \"channel\"}, ", "")
                        .replace("[1, 0.5, 0.5]", "[0.5, 0.5]")
                        .replace("{\"name\": \"y\", \"type\": \"space\", \"unit\": \"micrometer\"}",
                                "{\"name\": \"t\", \"type\": \"time\"}"),
                "2 or 3 space axes");
        // channel after space
        invalid(IMAGE_05.replace("{\"name\": \"c\", \"type\": \"channel\"}, ", "")
                        .replace("\"unit\": \"micrometer\"}]", "\"unit\": \"micrometer\"}, {\"name\": \"c\", \"type\": \"channel\"}]"),
                "ordered time, then channel");
        ValidationReport units = check(IMAGE_05.replace("\"micrometer\"}]", "\"furlong\"}]"));
        assertTrue(units.isValid());
        assertTrue(units.warnings().stream().anyMatch(w -> w.message().contains("furlong")));
    }

    @Test
    void levelTransformations() {
        invalid(IMAGE_05.replace("[1, 0.5, 0.5]", "[0.5, 0.5]"), "2 values for 3 axes");
        invalid(IMAGE_05.replace("{\"type\": \"scale\", \"scale\": [1, 0.5, 0.5]}",
                "{\"type\": \"translation\", \"translation\": [0, 0, 0]}, {\"type\": \"scale\", \"scale\": [1, 1, 1]}"),
                "scale must come before the translation");
        invalid(IMAGE_05.replace("[{\"type\": \"scale\", \"scale\": [1, 0.5, 0.5]}]", "[]"), "must hold a scale");
        invalid(IMAGE_05.replace("\"type\": \"scale\", \"scale\"", "\"type\": \"affine\", \"scale\""),
                "must be a scale or a translation");
    }

    @Test
    void zeroSixLevelsMapTheirArrayToOneCoordinateSystem() {
        String good = """
                {"ome": {"version": "0.6", "multiscales": [{"name": "i", "type": "t", "metadata": {},
                  "coordinateSystems": [{"name": "physical", "axes": [{"name": "y", "type": "space", "unit": "micrometer"},
                                                                     {"name": "x", "type": "space", "unit": "micrometer"}]}],
                  "datasets": [
                    {"path": "s0", "coordinateTransformations": [{"type": "scale", "scale": [1, 1],
                      "input": {"path": "s0"}, "output": {"name": "physical"}}]},
                    {"path": "s1", "coordinateTransformations": [{"type": "sequence", "input": {"path": "s1"},
                      "output": {"name": "physical"}, "transformations": [{"type": "scale", "scale": [2, 2]},
                      {"type": "translation", "translation": [0.5, 0.5]}]}]}]}]}}
                """;
        assertTrue(check(good).isValid(), () -> check(good).toString());
        invalid(good.replace("\"input\": {\"path\": \"s1\"}", "\"input\": {\"path\": \"s9\"}"), "its own path 's1'");
        invalid(good.replace("\"output\": {\"name\": \"physical\"}}]},", "\"output\": {\"name\": \"other\"}}]},"),
                "no coordinate system 'other'");
        invalid(good.replace("\"scale\": [1, 1],", "\"scale\": [0, 1],"), "scale factor must be positive");
        String scale = "{\"type\": \"scale\", \"scale\": [2, 2]}";
        String translation = "{\"type\": \"translation\", \"translation\": [0.5, 0.5]}";
        invalid(good.replace(scale, "SCALE").replace(translation, scale).replace("SCALE", translation),
                "scale followed by a translation");
        invalid(good.replace("\"input\": {\"path\": \"s0\"}", "\"input\": \"s0\""), "input must be an object");
    }

    @Test
    void zeroSixImageTransformationsAndTheGraph() {
        String base = """
                {"ome": {"version": "0.6", "multiscales": [{"name": "i", "type": "t", "metadata": {},
                  "coordinateSystems": [
                    {"name": "physical", "axes": [{"name": "y", "type": "space"}, {"name": "x", "type": "space"}]},
                    {"name": "other", "axes": [{"name": "y", "type": "space"}, {"name": "x", "type": "space"}]}],
                  "datasets": [{"path": "0", "coordinateTransformations": [{"type": "identity",
                    "input": {"path": "0"}, "output": {"name": "physical"}}]}],
                  "coordinateTransformations": [TRANSFORM]}]}}
                """;
        String ok = "{\"type\": \"rotation\", \"rotation\": [[0, 1], [-1, 0]], \"input\": {\"name\": \"physical\"}, "
                + "\"output\": {\"name\": \"other\"}}";
        assertTrue(check(base.replace("TRANSFORM", ok)).isValid(), () -> check(base.replace("TRANSFORM", ok)).toString());
        invalid(base.replace("TRANSFORM", ok.replace("[[0, 1], [-1, 0]]", "[[1, 1], [0, 1]]")), "orthonormal");
        invalid(base.replace("TRANSFORM", ok.replace("[[0, 1], [-1, 0]]", "[[0, 1], [1, 0]]")), "determinant 1");
        invalid(base.replace("TRANSFORM", ok.replace("\"rotation\", \"rotation\": [[0, 1], [-1, 0]]",
                "\"mapAxis\", \"mapAxis\": [0, 1, 2]")), "of 3 dimensions");
        invalid(base.replace("TRANSFORM", ok.replace("\"rotation\", \"rotation\": [[0, 1], [-1, 0]]",
                "\"affine\", \"affine\": [[1, 0], [0, 1]]")), "3 columns");
        invalid(base.replace("TRANSFORM", ok.replace("\"physical\"", "\"nowhere\"")), "no coordinate system 'nowhere'");
        invalid(base.replace("TRANSFORM", "{\"type\": \"byDimension\", \"input\": {\"name\": \"physical\"}, "
                + "\"output\": {\"name\": \"other\"}, \"transformations\": [{\"transformation\": {\"type\": \"scale\", "
                + "\"scale\": [2]}, \"inputAxes\": [0], \"outputAxes\": [0]}]}"), "output axis 1 appears in no part");
        // two coordinate systems no transformation joins
        String apart = base.replace("\"coordinateTransformations\": [TRANSFORM]", "\"extra\": 1");
        invalid(apart, "not connected");
    }

    @Test
    void platesWellsAndLabels() {
        invalid("""
                {"ome": {"version": "0.5", "plate": {"rows": [{"name": "A"}], "columns": [{"name": "1"}],
                  "wells": [{"path": "1/A", "rowIndex": 0, "columnIndex": 0}]}}}
                """, "which are 'A/1'");
        invalid("""
                {"ome": {"version": "0.5", "plate": {"rows": [{"name": "A"}, {"name": "A"}], "columns": [{"name": "1"}],
                  "wells": [{"path": "A/1", "rowIndex": 0, "columnIndex": 0}]}}}
                """, "unique");
        invalid("{\"ome\": {\"version\": \"0.6\", \"well\": {\"images\": [{\"path\": \"__x\"}]}}}", "field of view's path");
        assertTrue(check("{\"ome\": {\"version\": \"0.6\", \"well\": {\"images\": [{\"path\": \"0_a-b.c\"}]}}}").isValid());
        invalid("{\"ome\": {\"version\": \"0.5\", \"well\": {\"images\": [{\"path\": \"0_a\"}]}}}", "alphanumeric");
        invalid("""
                {"ome": {"version": "0.5", "image-label": {"colors": [{"label-value": 1, "rgba": [0, 0, 0, 0]},
                  {"label-value": 1, "rgba": [1, 1, 1, 1]}]}}}
                """, "more than one color");
        invalid("{\"ome\": {\"version\": \"0.5\", \"image-label\": {\"colors\": [{\"label-value\": 1.5}]}}}",
                "must be an integer");
        // alone, a label image's attributes need not hold its multiscales: they are its group's to hold
        ValidationReport alone = new OmeValidator().validate(Json.parse(
                "{\"ome\": {\"version\": \"0.5\", \"image-label\": {\"colors\": [{\"label-value\": 1}]}}}").asObject());
        assertTrue(alone.isValid(), alone::toString);
        assertTrue(alone.warnings().stream().anyMatch(w -> w.message().contains("multiscale image")));
    }

    @Test
    void strictlyTheRecommendedFieldsAreRequired() {
        String plate = """
                {"ome": {"version": "0.5", "plate": {"rows": [{"name": "A"}], "columns": [{"name": "1"}],
                  "wells": [{"path": "A/1", "rowIndex": 0, "columnIndex": 0}], "acquisitions": [{"id": 0}]}}}
                """;
        assertTrue(check(plate).isValid());
        ValidationReport strict = new OmeValidator().strict(true).metadataOnly(true).validate(Json.parse(plate).asObject());
        assertEquals(3, strict.errors().size(), strict::toString); // the plate's name, the acquisition's two
    }

    @Test
    void versionsAndNothing() {
        invalid("{\"ome\": {\"multiscales\": []}}", "must hold the \"version\"");
        invalid("{\"ome\": {\"version\": \"0.7\"}}", "unsupported");
        invalid("{\"ome\": {\"version\": \"0.5\"}}", "no OME-Zarr metadata");
        invalid("{\"x\": 1}", "no OME-Zarr metadata");
    }

    @Test
    void theHierarchyIsCheckedToo() {
        MemoryStore store = new MemoryStore();
        JsonObject attrs = Json.parse(IMAGE_05).asObject();
        ZarrGroup g = Zarr.createGroup(store, attrs, false, 3);
        assertTrue(new OmeValidator().validate(store).errors().stream()
                .anyMatch(i -> i.message().contains("level '0' is not stored")));
        g.createArray("0", ArraySpec.builder(new long[] {2, 4}, DataType.UINT8).build());
        ValidationReport r = new OmeValidator().validate(store);
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("2 dimensions but the image has 3 axes")), r::toString);
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("no dimension_names")), r::toString);
        // metadata only, the arrays are not opened
        assertTrue(new OmeValidator().metadataOnly(true).validate(store).isValid());
        // a 0.5 hierarchy in Zarr v2
        MemoryStore v2 = new MemoryStore();
        Zarr.createGroup(v2, OmeMetadata.read(attrs).orElseThrow().toAttributes(new JsonObject(Map.of())), false, 2);
        assertTrue(new OmeValidator().validate(v2).errors().stream().anyMatch(i -> i.message().contains("Zarr v3")));
    }

    @Test
    void labelImagesHoldIntegersAndAsManyLevelsAsTheirImage() {
        MemoryStore store = new MemoryStore();
        List<Axis> axes = List.of(Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
        MultiscaleImage image = MultiscaleImageWriter.builder(OmeVersion.V0_5, axes).levels(2).build()
                .write(store, WriterTest.ramp(8, 8));
        ZarrGroup labels = image.group().createGroup("labels", OmeMetadata.of(OmeVersion.V0_5).withLabels(List.of("x"))
                .toAttributes(new JsonObject(Map.of())));
        // a float "label image" of one level
        MultiscaleImageWriter.builder(OmeVersion.V0_5, axes).levels(1).build().write(labels, "x",
                new PixelSource() {
                    public long[] shape() { return new long[] {8, 8}; }
                    public DataType dataType() { return DataType.FLOAT32; }
                    public byte[] read(long[] o, long[] s) { return new byte[(int) (s[0] * s[1] * 4)]; }
                });
        ZarrGroup x = labels.group("x");
        x.setAttributes(OmeMetadata.read(x.attributes()).orElseThrow().withImageLabel(
                com.ebremer.falcon.ome.metadata.ImageLabel.of(List.of())).toAttributes(x.attributes()));
        ValidationReport r = new OmeValidator().validate(store);
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("values must be integers")), r::toString);
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("same number")), r::toString);
    }
}
