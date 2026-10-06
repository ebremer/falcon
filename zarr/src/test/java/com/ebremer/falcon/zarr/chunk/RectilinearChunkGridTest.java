package com.ebremer.falcon.zarr.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.chunk.RectilinearChunkGrid.Axis;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The rectilinear chunk grid's arithmetic and its metadata form (P2 F14). */
class RectilinearChunkGridTest {

    @Test
    void chunksFollowTheListedLengths() {
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {10, 7},
                Axis.listed(3, 3, 4), Axis.listed(2, 5));
        assertArrayEquals(new long[] {3, 2}, grid.gridShape());
        assertEquals(6, grid.chunkCount());
        assertArrayEquals(new long[] {3, 2}, grid.chunkOrigin(new long[] {1, 1}));
        assertArrayEquals(new long[] {3, 5}, grid.chunkShape(new long[] {1, 1}));
        assertArrayEquals(new long[] {6, 2}, grid.chunkOrigin(new long[] {2, 1}));
        assertArrayEquals(new long[] {4, 5}, grid.chunkShape(new long[] {2, 1}));
        assertFalse(grid.isEdgeChunk(new long[] {2, 1}));
        assertEquals(0, grid.chunkAt(0, 2));
        assertEquals(1, grid.chunkAt(0, 3));
        assertEquals(2, grid.chunkAt(0, 9));
        assertEquals(1, grid.chunkAt(1, 2));
        assertArrayEquals(new long[][] {{3, 3, 4}, {2, 5}}, grid.chunkSizes());
        assertFalse(grid.isRegular());
    }

    /** The extension's own example: shape 6 in every dimension. */
    @Test
    void theSpecificationsExample() {
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {6, 6, 6, 6, 6},
                Axis.repeating(4), Axis.listed(1, 2, 3), Axis.runs(new long[] {4}, new long[] {2}),
                Axis.runs(new long[] {1, 3}, new long[] {3, 1}), Axis.listed(4, 4, 4));
        assertArrayEquals(new long[] {2, 3, 2, 4, 2}, grid.gridShape());
        assertArrayEquals(new long[][] {{4, 2}, {1, 2, 3}, {4, 2}, {1, 1, 1, 3}, {4, 2}}, grid.chunkSizes());
        // An edge chunk keeps its listed length, its tail past the array.
        assertArrayEquals(new long[] {4, 3, 4, 3, 4}, grid.chunkShape(new long[] {1, 2, 1, 3, 1}));
        assertArrayEquals(new long[] {2, 3, 2, 3, 2}, grid.validExtent(new long[] {1, 2, 1, 3, 1}));
        assertTrue(grid.isEdgeChunk(new long[] {1, 2, 1, 3, 1}));
        assertEquals("{\"name\":\"rectilinear\",\"configuration\":{\"kind\":\"inline\",\"chunk_shapes\":"
                + "[4,[1,2,3],[[4,2]],[[1,3],3],[[4,3]]]}}", grid.toJson().toJson());
    }

    /** Written as zarr-python 3.4 writes them: compressed only when that is shorter. */
    @Test
    void metadataMatchesZarrPython() {
        assertEquals("[[[3,2],4],[2,5]]", shapes(new long[] {10, 7}, Axis.listed(3, 3, 4), Axis.listed(2, 5)));
        assertEquals("[[[4,3]]]", shapes(new long[] {10}, Axis.listed(4, 4, 4)));
        assertEquals("[[[10,2],[5,2]]]", shapes(new long[] {30}, Axis.listed(10, 10, 5, 5)));
        assertEquals("[[[5,2]],4]", shapes(new long[] {10, 9}, Axis.listed(5, 5), Axis.repeating(4)));
        assertEquals("[[4,8],[8]]", shapes(new long[] {12, 8}, Axis.listed(4, 8), Axis.listed(8)));
        assertEquals("[[[10,9],[5,2]]]",
                shapes(new long[] {100}, Axis.listed(10, 10, 10, 10, 10, 10, 10, 10, 10, 5, 5)));
        assertEquals("[[[3,2],4,2],[2,5]]", shapes(new long[] {12, 7}, Axis.listed(3, 3, 4, 2), Axis.listed(2, 5)));
        // Neighbouring runs of one length are one run.
        assertEquals("[[[2,5]]]", shapes(new long[] {10}, Axis.runs(new long[] {2, 2}, new long[] {2, 3})));
    }

    private static String shapes(long[] shape, Axis... axes) {
        return new RectilinearChunkGrid(shape, axes).toJson().asObject().get("configuration").asObject()
                .get("chunk_shapes").toJson();
    }

    @Test
    void listedLengthsMayRunPastTheArray() {
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {10}, Axis.listed(4, 4, 4, 8));
        assertArrayEquals(new long[] {3}, grid.gridShape()); // the fourth chunk lies wholly past the array
        assertArrayEquals(new long[] {4}, grid.chunkShape(new long[] {2}));
        assertArrayEquals(new long[] {2}, grid.validExtent(new long[] {2}));
        assertEquals(3, grid.chunkAt(0, 15)); // inside the listed chunks, though past the array
        assertThrows(IndexOutOfBoundsException.class, () -> grid.chunkAt(0, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.checkCoords(new long[] {3}));
        assertThrows(IllegalArgumentException.class,
                () -> new RectilinearChunkGrid(new long[] {11}, Axis.listed(4, 4))); // short of the extent
    }

    @Test
    void anEmptyDimensionHasNoChunks() {
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {0, 3}, Axis.listed(5), Axis.listed(1, 2));
        assertArrayEquals(new long[] {0, 2}, grid.gridShape());
        assertEquals(0, grid.chunkCount());
        assertArrayEquals(new long[][] {{}, {1, 2}}, grid.chunkSizes());
    }

    @Test
    void aScalarHasOneChunk() {
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {});
        assertEquals(1, grid.chunkCount());
        assertArrayEquals(new long[] {}, grid.chunkCoordsAt(0));
        assertEquals("[]", shapes(new long[] {}));
    }

    /** Runs are not expanded: a run of 10^15 chunks costs one entry, and indexing it is arithmetic. */
    @Test
    void longRunsAreNotExpanded() {
        long n = 1_000_000_000_000_000L;
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {3 * n + 5},
                Axis.runs(new long[] {3, 7}, new long[] {n, 1}));
        assertArrayEquals(new long[] {n + 1}, grid.gridShape());
        assertEquals(n - 1, grid.chunkAt(0, 3 * n - 1));
        assertEquals(n, grid.chunkAt(0, 3 * n));
        assertEquals(3 * n, grid.chunkStart(0, n));
        assertEquals(7, grid.chunkLength(0, n));
        assertArrayEquals(new long[] {5}, grid.validExtent(new long[] {n}));
        assertThrows(IllegalArgumentException.class, () -> Axis.runs(new long[] {2}, new long[] {Long.MAX_VALUE}));
        assertThrows(IllegalArgumentException.class, () -> Axis.listed(Long.MAX_VALUE, 1));
    }

    @Test
    void badLengthsAreRefused() {
        assertThrows(IllegalArgumentException.class, Axis::listed);
        assertThrows(IllegalArgumentException.class, () -> Axis.listed(3, 0));
        assertThrows(IllegalArgumentException.class, () -> Axis.repeating(0));
        assertThrows(IllegalArgumentException.class, () -> Axis.runs(new long[] {3}, new long[] {0}));
        assertThrows(IllegalArgumentException.class, () -> Axis.runs(new long[] {3}, new long[] {1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> new RectilinearChunkGrid(new long[] {3}, Axis.listed(3), Axis.listed(3)));
    }

    /** Resizing follows zarr-python's update_shape: grow past the lengths, gain one chunk; shrink, keep them. */
    @Test
    void resizingKeepsTheLengths() {
        RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {10, 7}, Axis.listed(3, 3, 4),
                Axis.repeating(4));
        RectilinearChunkGrid grown = grid.resized(new long[] {12, 20});
        assertEquals("[[[3,2],4,2],4]", grown.toJson().asObject().get("configuration").asObject()
                .get("chunk_shapes").toJson());
        assertArrayEquals(new long[] {4, 5}, grown.gridShape());
        RectilinearChunkGrid shrunk = grown.resized(new long[] {4, 7});
        assertEquals(grown.axis(0), shrunk.axis(0));
        assertArrayEquals(new long[] {2, 2}, shrunk.gridShape());
        assertSame(grid.axis(1), grid.resized(new long[] {10, 100}).axis(1));
    }

    /** Every index's chunk, start, and length agree with the lengths expanded, over random runs. */
    @Test
    void indexingAgreesWithExpandedLengths() {
        Random random = new Random(14);
        for (int trial = 0; trial < 200; trial++) {
            int runs = 1 + random.nextInt(6);
            long[] lengths = new long[runs];
            long[] counts = new long[runs];
            long total = 0;
            for (int r = 0; r < runs; r++) {
                lengths[r] = 1 + random.nextInt(5);
                counts[r] = 1 + random.nextInt(4);
                total += lengths[r] * counts[r];
            }
            long extent = random.nextInt((int) total + 1);
            RectilinearChunkGrid grid = new RectilinearChunkGrid(new long[] {extent}, Axis.runs(lengths, counts));
            long index = 0;
            long chunk = 0;
            for (int r = 0; r < runs; r++) {
                for (long k = 0; k < counts[r]; k++, chunk++) {
                    assertEquals(index, grid.chunkStart(0, chunk));
                    assertEquals(lengths[r], grid.chunkLength(0, chunk));
                    for (long j = 0; j < lengths[r]; j++) {
                        assertEquals(chunk, grid.chunkAt(0, index + j));
                    }
                    index += lengths[r];
                }
            }
            long overlapping = extent == 0 ? 0 : grid.chunkAt(0, extent - 1) + 1;
            assertEquals(overlapping, grid.gridShape()[0]);
        }
    }

    /** A regular grid answers the per-dimension questions too, and its metadata is the core form. */
    @Test
    void theRegularGridIsAChunkGrid() {
        ChunkGrid grid = new RegularChunkGrid(new long[] {10}, new long[] {4});
        assertEquals(2, grid.chunkAt(0, 9));
        assertEquals(8, grid.chunkStart(0, 2));
        assertEquals(4, grid.chunkLength(0, 2));
        assertArrayEquals(new long[][] {{4, 4, 2}}, grid.chunkSizes());
        assertTrue(grid.isRegular());
        assertEquals("{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[4]}}", grid.toJson().toJson());
        assertArrayEquals(new long[] {5}, grid.resized(new long[] {17}).gridShape());
    }
}
