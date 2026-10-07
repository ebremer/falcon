package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystem;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.ImageLabel;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.ome.metadata.PlateMetadata;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.ome.metadata.WellMetadata;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Metadata of every shape the validator and the reader may be handed: each value of good documents replaced,
 * in turn, by every kind of JSON value. The validator reports, and never throws; the reader throws only
 * {@link OmeFormatException} (or {@link ZarrUnsupportedException} for a version it does not read).
 */
class RobustnessTest {

    static final List<JsonValue> REPLACEMENTS = List.of(Json.parse("null"), Json.parse("0"), Json.parse("-1"),
            Json.parse("1.5"), Json.parse("1e400"), Json.parse("\"x\""), Json.parse("[]"), Json.parse("{}"),
            Json.parse("[1]"), Json.parse("[[1]]"), Json.parse("true"), Json.parse("[\"a\", {}]"));

    /** Good documents of every kind and version. */
    static List<JsonObject> documents() {
        List<JsonObject> docs = new ArrayList<>();
        List<Axis> axes = List.of(Axis.channel("c"), Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
        for (OmeVersion v : OmeVersion.values()) {
            OmeMetadata image = OmeMetadata.of(v).withMultiscale(MetadataTest.image())
                    .withOmero(Omero.of(List.of(Omero.Channel.of("a", "FF0000", new Omero.Window(0, 1, 0, 1)))))
                    .withImageLabel(new ImageLabel(null, List.of(new ImageLabel.LabelColor(1, new int[] {1, 2, 3, 4})),
                            List.of(), "../../"));
            docs.add(image.toAttributes(new JsonObject(Map.of())));
            docs.add(OmeMetadata.of(v).withPlate(new PlateMetadata("p", null, 1, List.of("A"), List.of("1"),
                    List.of(new PlateMetadata.WellRef("A/1", 0, 0)), List.of(new PlateMetadata.Acquisition(0, "a", 1,
                    null, 1L, 2L)))).withWell(new WellMetadata(null, List.of(new WellMetadata.FieldOfView("0", 0L))))
                    .withLabels(List.of("cells")).toAttributes(new JsonObject(Map.of())));
        }
        docs.add(OmeMetadata.of(OmeVersion.V0_6).withScene(new SceneMetadata(List.of(new CoordinateSystem("w", axes)),
                List.of(new Transformation.ByDimension(List.of(new Transformation.ByDimension.Part(
                        Transformation.Scale.of(2), new int[] {0}, new int[] {0}), new Transformation.ByDimension.Part(
                        new Transformation.Rotation(new double[][] {{0, -1}, {1, 0}}, null, null, null, null),
                        new int[] {1, 2}, new int[] {1, 2})), "b", new CoordinateSystemRef("physical", "i"),
                        CoordinateSystemRef.named("w")), new Transformation.Sequence(List.of(
                        new Transformation.MapAxis(new int[] {2, 1, 0}, null, null, null),
                        new Transformation.ProjectAxis(new int[] {0}, new int[] {0}, null, null, null),
                        new Transformation.Affine(new double[][] {{1, 0, 0, 1}, {0, 1, 0, 1}, {0, 0, 1, 1}}, null, null,
                                null, null), new Transformation.Bijection(Transformation.Translation.of(1, 1, 1),
                                Transformation.Translation.of(-1, -1, -1), null, null, null),
                        new Transformation.Displacements("f", "linear", null, null, null, null)), "s",
                        CoordinateSystemRef.named("w"), new CoordinateSystemRef("other", "j")))))
                .toAttributes(new JsonObject(Map.of())));
        return docs;
    }

    @Test
    void everyValueReplacedByEveryKindOfValue() {
        int cases = 0;
        OmeValidator validator = new OmeValidator();
        OmeValidator strict = new OmeValidator().strict(true);
        for (JsonObject doc : documents()) {
            for (UnaryOperator<JsonValue>[] at : mutations(doc)) {
                for (JsonValue replacement : REPLACEMENTS) {
                    JsonObject mutated = (JsonObject) at[0].apply(replacement);
                    cases++;
                    try {
                        validator.validate(mutated);
                        strict.validate(mutated);
                    } catch (RuntimeException e) {
                        fail("the validator threw on " + mutated.toJson(), e);
                    }
                    try {
                        OmeMetadata.read(mutated).ifPresent(m -> {
                            m.toAttributes(new JsonObject(Map.of()));
                            m.multiscales().forEach(ms -> {
                                ms.intrinsicCoordinateSystem();
                                ms.axisNames();
                            });
                        });
                    } catch (OmeFormatException | ZarrUnsupportedException e) {
                        // a document the reader cannot read: said so
                    } catch (RuntimeException e) {
                        fail("the reader threw " + e + " on " + mutated.toJson(), e);
                    }
                }
            }
        }
        assertTrue(cases > 1000, "only " + cases + " cases");
    }

    @Test
    void hierarchiesWhoseMetadataIsWrongAreReportedNotThrown() {
        for (JsonObject doc : documents()) {
            for (UnaryOperator<JsonValue>[] at : mutations(doc)) {
                for (JsonValue replacement : List.of(Json.parse("null"), Json.parse("\"../..\""), Json.parse("[\"/x\"]"),
                        Json.parse("{}"))) {
                    JsonObject mutated = (JsonObject) at[0].apply(replacement);
                    MemoryStore store = new MemoryStore();
                    int format = OmeMetadata.version(doc).orElseThrow().zarrFormat();
                    com.ebremer.falcon.zarr.Zarr.createGroup(store, mutated, false, format);
                    try {
                        new OmeValidator().validate(store);
                    } catch (RuntimeException e) {
                        fail("the validator threw on the hierarchy of " + mutated.toJson(), e);
                    }
                }
            }
        }
    }

    /** For each value in the document, a function that gives the document with that value replaced. */
    @SuppressWarnings("unchecked")
    static List<UnaryOperator<JsonValue>[]> mutations(JsonValue root) {
        List<UnaryOperator<JsonValue>[]> list = new ArrayList<>();
        collect(root, UnaryOperator.identity(), list, true);
        return list;
    }

    @SuppressWarnings("unchecked")
    private static void collect(JsonValue value, UnaryOperator<JsonValue> rebuild, List<UnaryOperator<JsonValue>[]> list,
                                boolean root) {
        if (!root) {
            list.add(new UnaryOperator[] {rebuild});
        }
        if (value instanceof JsonObject o) {
            for (String key : o.members().keySet()) {
                collect(o.get(key), v -> {
                    Map<String, JsonValue> copy = new LinkedHashMap<>(o.members());
                    copy.put(key, v);
                    return rebuild.apply(new JsonObject(copy));
                }, list, false);
            }
        } else if (value instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) {
                int index = i;
                collect(a.get(i), v -> {
                    List<JsonValue> copy = new ArrayList<>(a.values());
                    copy.set(index, v);
                    return rebuild.apply(new JsonArray(copy));
                }, list, false);
            }
        }
    }
}
