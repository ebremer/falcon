package com.ebremer.falcon.ome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.Dataset;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.ome.metadata.Transformation.Affine;
import com.ebremer.falcon.ome.metadata.Transformation.Bijection;
import com.ebremer.falcon.ome.metadata.Transformation.ByDimension;
import com.ebremer.falcon.ome.metadata.Transformation.MapAxis;
import com.ebremer.falcon.ome.metadata.Transformation.ProjectAxis;
import com.ebremer.falcon.ome.metadata.Transformation.Rotation;
import com.ebremer.falcon.ome.metadata.Transformation.Scale;
import com.ebremer.falcon.ome.metadata.Transformation.Sequence;
import com.ebremer.falcon.ome.metadata.Transformation.Translation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each transformation maps points as the 0.6 specification's examples say, and inverts in closed form. */
class TransformationTest {

    static void roundTrip(Transformation t, double... point) {
        double[] there = t.apply(point);
        assertArrayEquals(point, t.inverse().orElseThrow().apply(there), 1e-9, t.toString());
    }

    @Test
    void scaleTranslationAndSequence() {
        assertArrayEquals(new double[] {3.12 * 2, 2 * 5}, Scale.of(3.12, 2).apply(new double[] {2, 5}), 1e-12);
        assertArrayEquals(new double[] {11, 3.58}, Translation.of(9, -1.42).apply(new double[] {2, 5}), 1e-12);
        // the specification's sequence: x = (i + 0.1) * 2, y = (j + 0.9) * 3
        Sequence s = Sequence.of(Translation.of(0.1, 0.9), Scale.of(2, 3));
        assertArrayEquals(new double[] {2.2, 5.7}, s.apply(new double[] {1, 1}), 1e-12);
        roundTrip(s, 4, -7);
        assertTrue(Scale.of(0, 1).inverse().isEmpty());
    }

    @Test
    void axisPermutationsAndProjections() {
        assertArrayEquals(new double[] {5, 4}, new MapAxis(new int[] {1, 0}, null, null, null).apply(new double[] {4, 5}));
        roundTrip(new MapAxis(new int[] {2, 0, 1}, null, null, null), 1, 2, 3);
        // adding c and z in front of (y, x)
        ProjectAxis add = new ProjectAxis(null, new int[] {0, 1}, null, null, null);
        assertArrayEquals(new double[] {0, 0, 7, 8}, add.apply(new double[] {7, 8}));
        roundTrip(add, 7, 8);
        // dropping c and adding z
        ProjectAxis swap = new ProjectAxis(new int[] {0}, new int[] {0}, null, null, null);
        assertArrayEquals(new double[] {0, 7, 8}, swap.apply(new double[] {3, 7, 8}));
        assertTrue(swap.inverse().isEmpty());
    }

    @Test
    void matrices() {
        // the specification's 2D affine: y = 1*j + 2*i + 3, x = 4*j + 5*i + 6, with (j, i) the input
        Affine a = new Affine(new double[][] {{1, 2, 3}, {4, 5, 6}}, null, null, null, null);
        assertArrayEquals(new double[] {1 + 4 + 3, 4 + 10 + 6}, a.apply(new double[] {1, 2}), 1e-12);
        roundTrip(new Affine(new double[][] {{3, 0.4, 30}, {0.3, 2, 20}}, null, null, null, null), 5, -2);
        assertTrue(new Affine(new double[][] {{1, 2, 0}, {2, 4, 0}}, null, null, null, null).inverse().isEmpty());
        // 2D to 3D: not invertible
        Affine up = new Affine(new double[][] {{0, 0, 1}, {3, 4, 2}, {6, 7, 5}}, null, null, null, null);
        assertArrayEquals(new double[] {1, 3 + 8 + 2, 6 + 14 + 5}, up.apply(new double[] {1, 2}), 1e-12);
        assertTrue(up.inverse().isEmpty());
        Rotation r = new Rotation(new double[][] {{0, -1}, {1, 0}}, null, null, null, null);
        assertArrayEquals(new double[] {-2, 1}, r.apply(new double[] {1, 2}), 1e-12);
        roundTrip(r, 1, 2);
    }

    @Test
    void byDimensionAndBijection() {
        ByDimension b = new ByDimension(List.of(
                new ByDimension.Part(Scale.of(2), new int[] {0}, new int[] {0}),
                new ByDimension.Part(Translation.of(-10), new int[] {1}, new int[] {1})), null, null, null);
        assertArrayEquals(new double[] {6, -5}, b.apply(new double[] {3, 5}), 1e-12);
        roundTrip(b, 3, 5);
        ByDimension swap = new ByDimension(List.of(
                new ByDimension.Part(Scale.of(2), new int[] {0}, new int[] {1}),
                new ByDimension.Part(Scale.of(3), new int[] {1}, new int[] {0})), null, null, null);
        assertArrayEquals(new double[] {15, 6}, swap.apply(new double[] {3, 5}), 1e-12);
        roundTrip(swap, 3, 5);
        Bijection bij = new Bijection(Translation.of(1, 1), Translation.of(-1, -1), null,
                CoordinateSystemRef.named("a"), CoordinateSystemRef.named("b"));
        Transformation inverse = bij.inverse().orElseThrow();
        assertArrayEquals(new double[] {0, 0}, inverse.apply(new double[] {1, 1}));
        assertEquals(CoordinateSystemRef.named("b"), inverse.input());
    }

    @Test
    void parametersNotLoadedAreReportedWhenApplied() {
        Scale s = new Scale(null, "params", null, null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.apply(new double[] {1}));
        assertTrue(e.getMessage().contains("params"));
        assertTrue(s.inverse().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Scale.of(1, 2).apply(new double[] {1}));
    }

    @Test
    void aLevelsScaleAndTranslationFoldTheImagesTransformationsIn() {
        // 0.4 and 0.5: the image's transformations come after each level's
        Multiscale m = new Multiscale(null, null, null, null, List.of(Axis.space("y", null), Axis.space("x", null)),
                List.of(), List.of(Dataset.of("0", new double[] {2, 2}, new double[] {1, 1})),
                List.of(Scale.of(3, 5), Translation.of(10, 20)));
        assertArrayEquals(new double[] {6, 10}, m.scale(0), 1e-12);
        assertArrayEquals(new double[] {13, 25}, m.translation(0), 1e-12);
        assertArrayEquals(new double[] {6 * 4 + 13, 10 * 4 + 25}, m.levelTransformation(0).apply(new double[] {4, 4}),
                1e-12);
    }
}
