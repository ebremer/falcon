package com.ebremer.falcon.zarr.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegularChunkGridTest {

    @Test
    void gridShapeIsCeilingDivision() {
        assertArrayEquals(new long[] {4}, new RegularChunkGrid(new long[] {10}, new long[] {3}).gridShape());
        assertArrayEquals(new long[] {2, 2},
                new RegularChunkGrid(new long[] {4, 6}, new long[] {2, 3}).gridShape());
        assertArrayEquals(new long[] {1, 1},
                new RegularChunkGrid(new long[] {10, 10}, new long[] {10, 10}).gridShape());
        assertArrayEquals(new long[] {4, 3, 2},
                new RegularChunkGrid(new long[] {7, 5, 3}, new long[] {2, 2, 2}).gridShape());
    }

    @Test
    void chunkCountIsProductOfGrid() {
        assertEquals(4, new RegularChunkGrid(new long[] {10}, new long[] {3}).chunkCount());
        assertEquals(24, new RegularChunkGrid(new long[] {7, 5, 3}, new long[] {2, 2, 2}).chunkCount());
    }

    @Test
    void chunkOrigin() {
        RegularChunkGrid grid = new RegularChunkGrid(new long[] {10, 10}, new long[] {4, 4});
        assertArrayEquals(new long[] {0, 0}, grid.chunkOrigin(new long[] {0, 0}));
        assertArrayEquals(new long[] {8, 4}, grid.chunkOrigin(new long[] {2, 1}));
    }

    @Test
    void edgeChunksReportSmallerExtent() {
        RegularChunkGrid grid = new RegularChunkGrid(new long[] {10}, new long[] {3}); // grid = 4
        assertArrayEquals(new long[] {3}, grid.validExtent(new long[] {0}));
        assertArrayEquals(new long[] {3}, grid.validExtent(new long[] {2}));
        assertArrayEquals(new long[] {1}, grid.validExtent(new long[] {3})); // 10 - 9 = 1
        assertFalse(grid.isEdgeChunk(new long[] {0}));
        assertTrue(grid.isEdgeChunk(new long[] {3}));
    }

    @Test
    void checkCoordsRejectsOutOfRange() {
        RegularChunkGrid grid = new RegularChunkGrid(new long[] {4, 6}, new long[] {2, 3}); // grid 2x2
        assertTrue(grid.containsCoords(new long[] {1, 1}));
        assertFalse(grid.containsCoords(new long[] {2, 0}));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.checkCoords(new long[] {2, 0}));
        assertThrows(IllegalArgumentException.class, () -> grid.checkCoords(new long[] {0}));
    }

    @Test
    void linearIndexRoundTripsOverAllChunks() {
        RegularChunkGrid grid = new RegularChunkGrid(new long[] {7, 5, 3}, new long[] {2, 2, 2});
        long count = grid.chunkCount();
        assertEquals(24, count);
        for (long i = 0; i < count; i++) {
            long[] coords = grid.chunkCoordsAt(i);
            assertTrue(grid.containsCoords(coords));
            assertEquals(i, grid.linearIndex(coords));
        }
    }

    @Test
    void scalarGridHasOneChunk() {
        RegularChunkGrid grid = new RegularChunkGrid(new long[] {}, new long[] {});
        assertEquals(0, grid.rank());
        assertEquals(1, grid.chunkCount());
        assertArrayEquals(new long[] {}, grid.chunkCoordsAt(0));
        assertEquals(0, grid.linearIndex(new long[] {}));
        assertTrue(grid.containsCoords(new long[] {}));
    }

    @Test
    void constructorRejectsMismatchedRankOrBadChunk() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegularChunkGrid(new long[] {4, 4}, new long[] {2}));
        assertThrows(IllegalArgumentException.class,
                () -> new RegularChunkGrid(new long[] {4}, new long[] {0}));
    }
}
