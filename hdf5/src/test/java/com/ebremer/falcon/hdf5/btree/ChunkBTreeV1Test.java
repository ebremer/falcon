package com.ebremer.falcon.hdf5.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.RangeReader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.io.PagedSource;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The version 1 chunk B-tree of a file read on demand is read ahead (P2 PF12): the nodes below a node
 * together, several requests at once, where a walk read them one request after another; and the nodes on
 * the paths to the chunks of one read, a level at a time. The trees are built here, node by node, so that
 * where each node lies is known: the chunks found, and what a damaged tree fails with, are those of the same
 * bytes read from memory.
 */
@Timeout(300)
class ChunkBTreeV1Test {

    private static final int PAGE = 512;
    private static final int[] CHUNK = {4, 4};
    private static final int KEY = 4 + 4 + 3 * 8;   // a key of a rank 2 dataset
    private static final int ENTRY = KEY + 8;       // and its child's address
    private static final int HEADER = 8 + 2 * 8;

    /** A file that holds one chunk B-tree, and where each of its nodes is. */
    private record Tree(byte[] file, long root, List<List<Long>> levels, List<long[]> cells) {

        int nodes() {
            int count = 0;
            for (List<Long> level : levels) {
                count += level.size();
            }
            return count;
        }

        List<Long> leaves() {
            return levels.get(0);
        }
    }

    /**
     * A tree over a grid {@code width} cells wide, holding those of its first {@code cells} cells (in
     * row-major order) that {@code stored} takes, each node with up to {@code fanout} entries, the nodes
     * {@code spacing} bytes apart in the order they are made: the leaves, then each level above.
     */
    private static Tree tree(int cells, int width, int fanout, int spacing, IntPredicate stored) {
        List<long[]> keys = new ArrayList<>();   // {size, mask, offset 0, offset 1}
        List<Long> children = new ArrayList<>();
        List<long[]> held = new ArrayList<>();
        for (int g = 0; g < cells; g++) {
            if (stored.test(g)) {
                keys.add(new long[] {64 + g % 7, g % 3, (long) (g / width) * CHUNK[0], (long) (g % width) * CHUNK[1]});
                children.add(10_000_000L + g * 128L);
                held.add(new long[] {g / width, g % width});
            }
        }
        int groups = 0;
        for (int n = keys.size(); n > 1; n = (n + fanout - 1) / fanout) {
            groups += (n + fanout - 1) / fanout;
        }
        byte[] file = new byte[4096 + (groups + 1) * spacing + HEADER + fanout * ENTRY + KEY];
        Arrays.fill(file, (byte) 0x6B); // nothing in it looks like a node, or like an undefined address
        ByteBuffer out = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        List<List<Long>> levels = new ArrayList<>();
        long at = 4096;
        for (int level = 0; ; level++) {
            List<long[]> aboveKeys = new ArrayList<>();
            List<Long> aboveChildren = new ArrayList<>();
            List<Long> addresses = new ArrayList<>();
            for (int first = 0; first < keys.size(); first += fanout) {
                int entries = Math.min(fanout, keys.size() - first);
                out.position((int) at);
                out.put(new byte[] {'T', 'R', 'E', 'E', 1, (byte) level}).putShort((short) entries).putLong(-1).putLong(-1);
                for (int i = first; i < first + entries; i++) {
                    long[] key = keys.get(i);
                    out.putInt((int) key[0]).putInt((int) key[1]).putLong(key[2]).putLong(key[3]).putLong(0);
                    out.putLong(children.get(i));
                }
                out.putInt(0).putInt(0).putLong(Long.MAX_VALUE / 2).putLong(0).putLong(0); // the key after the last
                aboveKeys.add(keys.get(first));
                aboveChildren.add(at);
                addresses.add(at);
                at += spacing;
            }
            levels.add(addresses);
            keys = aboveKeys;
            children = aboveChildren;
            if (addresses.size() == 1) {
                return new Tree(file, addresses.get(0), levels, held);
            }
        }
    }

    /** A file in memory that notes each request made of it, and can wait, or fail, at chosen places. */
    private static final class Store implements RangeReader {
        final byte[] file;
        final List<long[]> requests = Collections.synchronizedList(new ArrayList<>()); // {position, length}
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger mostInFlight = new AtomicInteger();
        volatile Request each = (position, length) -> { };

        interface Request {
            void made(long position, int length) throws IOException;
        }

        Store(byte[] file) {
            this.file = file;
        }

        @Override
        public long size() {
            return file.length;
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            int length = destination.remaining();
            requests.add(new long[] {position, length});
            threads.add(Thread.currentThread());
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                each.made(position, length);
                destination.put(file, (int) position, length);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        /** Each request waits a while for {@code others} to be in flight with it. */
        void waitFor(int others) {
            each = (position, length) -> {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                try {
                    while (inFlight.get() < others && System.nanoTime() < until) {
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            };
        }

        /** How many requests asked for the byte at {@code position}. */
        int asked(long position) {
            int count = 0;
            synchronized (requests) {
                for (long[] request : requests) {
                    if (request[0] <= position && position < request[0] + request[1]) {
                        count++;
                    }
                }
            }
            return count;
        }

        /** Where the requests made start, in order. */
        List<Long> positions() {
            List<Long> out = new ArrayList<>();
            synchronized (requests) {
                for (long[] request : requests) {
                    out.add(request[0]);
                }
            }
            Collections.sort(out);
            return out;
        }

        /** The requests made, in the order of their positions. */
        List<String> sorted() {
            List<long[]> copy;
            synchronized (requests) {
                copy = new ArrayList<>(requests);
            }
            copy.sort((a, b) -> Long.compare(a[0], b[0]));
            List<String> out = new ArrayList<>();
            for (long[] request : copy) {
                out.add(request[0] + "+" + request[1]);
            }
            return out;
        }
    }

    private static FileContext onDemand(Store store, int cachePages, int requests) throws IOException {
        return new FileContext(new HdfBuffer(PagedSource.open(store, PAGE, (long) cachePages * PAGE, requests)), 8, 8);
    }

    private static FileContext inMemory(byte[] file) {
        return new FileContext(HdfBuffer.of(file), 8, 8);
    }

    private static String text(ChunkRecord chunk) {
        return chunk == null ? "none"
                : Arrays.toString(chunk.offset()) + " at " + chunk.address() + ", " + chunk.size() + " bytes, mask " + chunk.filterMask();
    }

    private static List<String> text(List<ChunkRecord> chunks) {
        List<String> out = new ArrayList<>();
        for (ChunkRecord chunk : chunks) {
            out.add(text(chunk));
        }
        return out;
    }

    /**
     * The bytes from one node to the next that keep each a request of its own: a node is read ahead as far as
     * one of 64 entries reaches (six pages), and requests more than a page apart are not put together.
     */
    private static final int APART = 8 * PAGE;

    /** A tree of four levels: 600 chunks, eight to a node, each node on a page of its own, apart from the next. */
    private static Tree fourLevels() {
        Tree tree = tree(600, 30, 8, APART, g -> true);
        assertEquals(4, tree.levels().size());
        assertEquals(75 + 10 + 2 + 1, tree.nodes());
        return tree;
    }

    // ----------------------------------------------------------------- the walk

    @Test
    void aWalkReadsTheNodesBelowANodeTogether() throws IOException {
        Tree tree = fourLevels();
        List<String> expected = text(ChunkBTreeV1.read(inMemory(tree.file()), tree.root(), 2));
        assertEquals(600, expected.size());

        Store store = new Store(tree.file());
        store.waitFor(8);
        assertEquals(expected, text(ChunkBTreeV1.read(onDemand(store, 4096, 8), tree.root(), 2)));
        assertEquals(tree.nodes(), store.requests.size(), "each node is asked for once");
        for (List<Long> level : tree.levels()) {
            for (long node : level) {
                assertEquals(1, store.asked(node));
            }
        }
        assertEquals(8, store.mostInFlight.get(), "eight nodes at once, and never more");
        for (Thread thread : store.threads) {
            assertTrue(thread == Thread.currentThread() || thread.isVirtual());
        }

        // One request at a time: the same requests, each the walking thread's own.
        Store alone = new Store(tree.file());
        assertEquals(expected, text(ChunkBTreeV1.read(onDemand(alone, 4096, 1), tree.root(), 2)));
        assertEquals(store.sorted(), alone.sorted());
        assertEquals(1, alone.mostInFlight.get());
        for (Thread thread : alone.threads) {
            assertSame(Thread.currentThread(), thread);
        }
    }

    @Test
    void nodesNextToEachOtherAreReadInOneRequest() throws IOException {
        // The nodes lie back to back, as a file written in one go has them.
        Tree tree = tree(600, 30, 8, HEADER + 8 * ENTRY + KEY, g -> true);
        List<String> expected = text(ChunkBTreeV1.read(inMemory(tree.file()), tree.root(), 2));
        Store store = new Store(tree.file());
        assertEquals(expected, text(ChunkBTreeV1.read(onDemand(store, 4096, 8), tree.root(), 2)));
        assertTrue(store.requests.size() <= 20, store.requests.size() + " requests for " + tree.nodes() + " nodes");
        long bytes = 0;
        for (long[] request : store.requests) {
            bytes += request[1];
        }
        assertTrue(bytes <= tree.file().length, "and no page of them twice: " + bytes);
    }

    @Test
    void aWalkReadsNoFurtherAheadThanThePageCacheKeeps() throws IOException {
        Tree tree = fourLevels();
        List<String> expected = text(ChunkBTreeV1.read(inMemory(tree.file()), tree.root(), 2));
        for (int cachePages : new int[] {1, 3, 4, 8, 16, 40, 160}) {
            Store store = new Store(tree.file());
            assertEquals(expected, text(ChunkBTreeV1.read(onDemand(store, cachePages, 8), tree.root(), 2)), cachePages + " pages");
            if (cachePages >= 16) {
                // A leaf read ahead is still there when the walk comes to it: none is asked for twice.
                for (long leaf : tree.leaves()) {
                    assertEquals(1, store.asked(leaf), cachePages + " pages, the leaf at " + leaf);
                }
            }
            assertTrue(store.mostInFlight.get() <= Math.max(1, Math.min(8, cachePages / 4)), cachePages + " pages");
        }
    }

    @Test
    void aNodeWithMoreRoomThanItIsTakenToHaveIsReadInTwoRequests() throws IOException {
        // 200 entries a node (K = 100, where libhdf5's default is 32): a node is 8 KiB, sixteen pages.
        Tree tree = tree(3000, 60, 200, 64 * PAGE, g -> g % 11 != 5);
        assertEquals(2, tree.levels().size());
        List<String> expected = text(ChunkBTreeV1.read(inMemory(tree.file()), tree.root(), 2));
        Store store = new Store(tree.file());
        assertEquals(expected, text(ChunkBTreeV1.read(onDemand(store, 4096, 8), tree.root(), 2)));
        assertTrue(store.requests.size() <= 2 * tree.nodes(), store.requests.size() + " requests for " + tree.nodes() + " nodes");
    }

    // ----------------------------------------------------------------- lookups

    /** The cells in rows 2, 3, and 10, every third column: some stored, some not, under a dozen leaves. */
    private static long[][] someCells() {
        long[] columns = new long[10];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = 3L * i;
        }
        return new long[][] {{2, 3, 10}, columns};
    }

    private static List<long[]> product(long[][] cells) {
        List<long[]> out = new ArrayList<>();
        for (long row : cells[0]) {
            for (long column : cells[1]) {
                out.add(new long[] {row, column});
            }
        }
        return out;
    }

    @Test
    void theNodesSeveralLookupsNeedAreReadALevelAtATime() throws IOException {
        Tree tree = tree(600, 30, 6, APART, g -> g != 0 && g % 5 != 3); // the first cell, and one in five, hold no chunk
        assertEquals(4, tree.levels().size());
        ChunkLookup memory = ChunkBTreeV1.lookup(inMemory(tree.file()), tree.root(), 2, CHUNK);
        long[][] cells = someCells();
        List<long[]> each = product(cells);

        // Looked up one after another, with no hint: the nodes on the paths, a request apiece.
        Store plain = new Store(tree.file());
        ChunkLookup unhinted = ChunkBTreeV1.lookup(onDemand(plain, 4096, 8), tree.root(), 2, CHUNK);
        int found = 0;
        for (long[] cell : each) {
            ChunkRecord chunk = unhinted.find(cell.clone());
            assertEquals(text(memory.find(cell.clone())), text(chunk), Arrays.toString(cell));
            found += chunk == null ? 0 : 1;
        }
        assertTrue(found > 15 && found < each.size(), found + " found");
        assertEquals(1, plain.mostInFlight.get());
        assertTrue(plain.requests.size() >= 12, plain.sorted().toString());

        // With the hint: the same nodes, those of a level together, and then the lookups ask for nothing.
        Store store = new Store(tree.file());
        ChunkLookup hinted = ChunkBTreeV1.lookup(onDemand(store, 4096, 8), tree.root(), 2, CHUNK);
        store.waitFor(8);
        hinted.ahead(cells);
        assertEquals(plain.positions(), store.positions());
        assertEquals(8, store.mostInFlight.get());
        int asked = store.requests.size();
        for (long[] cell : each) {
            assertEquals(text(memory.find(cell.clone())), text(hinted.find(cell.clone())), Arrays.toString(cell));
        }
        assertEquals(asked, store.requests.size(), "the lookups find every node there");
        // Cells before the first chunk, and past the last, are under no node or under the last one.
        assertEquals("none", text(hinted.find(new long[] {0, 0})));
        assertEquals("none", text(hinted.find(new long[] {500, 7})));
    }

    @Test
    void aHintNamesNoMoreCellsThanItsCapAndTakesAnyCells() throws IOException {
        Tree tree = fourLevels();
        ChunkLookup memory = ChunkBTreeV1.lookup(inMemory(tree.file()), tree.root(), 2, CHUNK);
        Store store = new Store(tree.file());
        ChunkLookup lookup = ChunkBTreeV1.lookup(onDemand(store, 4096, 8), tree.root(), 2, CHUNK);
        long[] many = new long[200_000];
        Arrays.setAll(many, i -> i);
        lookup.ahead(new long[][] {many, many}); // 40 thousand million cells: the first few thousand are read ahead
        assertTrue(store.requests.size() <= tree.nodes(), store.requests.size() + " requests");
        int asked = store.requests.size();
        assertTrue(asked >= 3, "the path to the first row's cells: " + asked);
        assertEquals(text(memory.find(new long[] {0, 29})), text(lookup.find(new long[] {0, 29})));
        assertEquals(asked, store.requests.size());

        // No cells, one cell, cells of another rank, cells that are not in the grid: a hint says nothing wrong.
        lookup.ahead(new long[][] {{}, {1, 2}});
        lookup.ahead(new long[][] {{5}, {7}});
        lookup.ahead(new long[][] {{1, 2, 3}});
        lookup.ahead(new long[0][]);
        lookup.ahead(new long[][] {{-4, 1_000_000_000_000L, Long.MAX_VALUE / 4}, {-1, 3}});
        assertEquals(text(memory.find(new long[] {19, 29})), text(lookup.find(new long[] {19, 29})));
        // In memory there is nothing to read ahead.
        memory.ahead(someCells());
    }

    // ----------------------------------------------------------------- damage, and failures

    /** What a walk of the tree finds, or fails with. */
    private static String walked(FileContext ctx, long root) {
        try {
            return text(ChunkBTreeV1.read(ctx, root, 2)).toString();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** What lookups of some cells find, after the hint a read gives of them, or fail with. */
    private static String lookedUp(FileContext ctx, long root) {
        ChunkLookup lookup = ChunkBTreeV1.lookup(ctx, root, 2, CHUNK);
        long[][] cells = someCells();
        lookup.ahead(cells);
        StringBuilder out = new StringBuilder();
        try {
            for (long[] cell : product(cells)) {
                out.append(text(lookup.find(cell))).append("; ");
            }
        } catch (RuntimeException e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return out.toString();
    }

    @Test
    void aDamagedTreeFailsAsItDoesReadFromMemory() throws IOException {
        Tree tree = tree(600, 30, 8, APART, g -> g % 5 != 3);
        List<Long> nodes = new ArrayList<>();
        tree.levels().forEach(nodes::addAll);
        Set<String> outcomes = new HashSet<>();
        for (long node : nodes) {
            for (int damage = 0; damage < 11; damage++) {
                byte[] file = tree.file().clone();
                ByteBuffer at = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
                int n = (int) node;
                switch (damage) {
                    case 0 -> file[n] ^= 1;                                         // not a node's signature
                    case 1 -> file[n + 4] = 0;                                      // a group's node
                    case 2 -> file[n + 5]++;                                        // a level too high
                    case 3 -> file[n + 5] ^= 1;                                     // a leaf for a node, or a node for a leaf
                    case 4 -> at.putShort(n + 6, (short) 0xFFFF);                   // entries far past its end
                    case 5 -> at.putShort(n + 6, (short) 0);                        // no entries
                    case 6 -> at.putLong(n + HEADER + KEY, tree.root());            // its first child the root
                    case 7 -> at.putLong(n + HEADER + KEY, -1);                     // or undefined
                    case 8 -> at.putLong(n + HEADER + ENTRY + KEY, file.length + 100L); // its second outside the file
                    case 9 -> at.putLong(n + HEADER + 8, at.getLong(n + HEADER + 8) + 1000); // its first key out of order
                    default -> at.putInt(n + HEADER, -5);                           // a chunk of more than 2 GiB
                }
                String what = "node at " + node + ", damage " + damage;
                String walk = walked(inMemory(file), tree.root());
                String lookups = lookedUp(inMemory(file), tree.root());
                for (int requests : new int[] {1, 8}) {
                    assertEquals(walk, walked(onDemand(new Store(file), 4096, requests), tree.root()), what);
                    assertEquals(lookups, lookedUp(onDemand(new Store(file), 4096, requests), tree.root()), what);
                }
                assertEquals(walk, walked(onDemand(new Store(file), 12, 8), tree.root()), what + ", a small cache");
                outcomes.add(walk.replaceAll("\\d+", "N"));
            }
        }
        // The damage is of several kinds, and some of it is no failure at all.
        assertTrue(outcomes.size() >= 6, outcomes.size() + " kinds of outcome");
    }

    /**
     * A tree whose nodes point back up it has no leaves to come to: a hint of cells under such a node gives
     * up at the first node that is not a level below the one before, where a lookup fails.
     */
    @Test
    void aHintOfATreeThatLoopsEnds() throws IOException {
        Tree tree = fourLevels();
        long[][] cells = {{0}, {0, 1, 2, 3, 4, 5}}; // all under the root's first child, and its first child
        for (int loop = 0; loop < 3; loop++) {
            byte[] file = tree.file().clone();
            ByteBuffer at = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            long second = tree.levels().get(2).get(0);
            switch (loop) {
                case 0 -> at.putLong((int) tree.root() + HEADER + KEY, tree.root());   // the root under itself
                case 1 -> at.putLong((int) second + HEADER + KEY, tree.root());        // the root under its child
                default -> at.putLong((int) second + HEADER + KEY, second);            // that child under itself
            }
            String expected;
            try {
                ChunkBTreeV1.lookup(inMemory(file), tree.root(), 2, CHUNK).find(new long[] {0, 3});
                throw new AssertionError("a lookup in a tree that loops must fail");
            } catch (com.ebremer.falcon.hdf5.HdfFormatException e) {
                expected = e.getMessage();
            }
            Store store = new Store(file);
            ChunkLookup lookup = ChunkBTreeV1.lookup(onDemand(store, 4096, 8), tree.root(), 2, CHUNK);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                    () -> lookup.ahead(cells), "loop " + loop);
            assertTrue(store.requests.size() <= 4, "loop " + loop + ": " + store.sorted());
            assertEquals(expected, org.junit.jupiter.api.Assertions.assertThrows(
                    com.ebremer.falcon.hdf5.HdfFormatException.class, () -> lookup.find(new long[] {0, 3})).getMessage());
        }
    }

    @Test
    void aStoreThatFailsFailsTheWalkWhereItDidOneRequestAtATime() throws IOException {
        Tree tree = fourLevels();
        long leaf = tree.leaves().get(37);
        String expected = "UncheckedIOException: cannot read " + PAGE + " bytes at " + leaf + " of the HDF5 file";
        // A cache too small to read ahead, one request at a time, and eight at once.
        for (int[] how : new int[][] {{3, 1}, {4096, 1}, {4096, 8}, {16, 8}}) {
            Store store = new Store(tree.file());
            store.each = (position, length) -> {
                if (position <= leaf && leaf < position + length) {
                    throw new IOException("the store is down");
                }
            };
            assertEquals(expected, walked(onDemand(store, how[0], how[1]), tree.root()), Arrays.toString(how));
            if (how[0] == 4096) {
                // Read ahead, the request failed and was dropped; the walk made it again, and failed there.
                assertEquals(2, store.asked(leaf));
                assertEquals(0, store.asked(tree.leaves().get(60)), "and the walk went no further");
            }
        }
        // A request the read ahead loses is made again by the walk: failing once, it does not fail the walk.
        Store once = new Store(tree.file());
        AtomicInteger failures = new AtomicInteger();
        once.each = (position, length) -> {
            if (position == leaf && failures.getAndIncrement() == 0) {
                throw new IOException("a moment's trouble");
            }
        };
        assertEquals(text(ChunkBTreeV1.read(inMemory(tree.file()), tree.root(), 2)),
                text(ChunkBTreeV1.read(onDemand(once, 4096, 8), tree.root(), 2)));
        assertEquals(2, once.asked(leaf));
    }
}
