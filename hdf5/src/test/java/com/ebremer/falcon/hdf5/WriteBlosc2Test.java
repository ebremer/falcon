package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing the Blosc2 filter (32026, P2 S10) as hdf5plugin's hdf5-blosc2 with c-blosc2 3.3.2 writes it. Every
 * dataset of blosc2_write.h5, written again by Falcon from its values with the same settings, has the same
 * filters (id, name, flags, and client data) and the same chunks, byte for byte but for zstd's: plain frames at
 * rank 1, b2nd frames at ranks 2 to 5, partial chunks, every codec, clevel, and filter, compound, array, and
 * 300-byte types, a shuffle or scale-offset before it and a checksum after.
 */
class WriteBlosc2Test {

    @TempDir
    Path dir;

    private static final String[] FILTERS = {"nofilter", "shuffle", "bitshuffle", "delta"};

    @Test
    void writesWhatTheBlosc2FilterWrote() throws IOException {
        Path out = dir.resolve("blosc2_again.h5");
        List<String> names = new ArrayList<>();
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("blosc2_write.h5"));
             Hdf5Writer w = Hdf5Writer.create(out)) {
            for (Hdf5Object object : h5.root().children()) {
                Dataset dataset = (Dataset) object;
                String[] setting = dataset.attribute("blosc2").orElseThrow().readString().split(" ");
                if (setting[2].equals("4") || setting.length > 3) {
                    continue; // truncated precision (which the plugin cannot apply), a block size: see below
                }
                long[] shape = dataset.dataspace().dimensions();
                Hdf5Writer.DatasetWriter d = w.createDataset(dataset.name(), dataset.datatype(), shape)
                        .chunked(dataset.chunkShape().orElseThrow());
                switch (dataset.attribute("before").orElseThrow().readString()) {
                    case "shuffle" -> d.shuffle();
                    case "scaleoffset" -> d.scaleOffset();
                    default -> { }
                }
                d.blosc2(setting[0], Integer.parseInt(setting[1]), FILTERS[Integer.parseInt(setting[2])]);
                if (dataset.attribute("after").orElseThrow().readString().equals("fletcher32")) {
                    d.fletcher32();
                }
                d.writeRaw(new long[shape.length], shape, dataset.readRawBytes());
                names.add(dataset.name());
            }
        }
        int same = compare(Fixtures.path("blosc2_write.h5"), out, names);
        assertEquals(47, names.size());
        assertTrue(same > 200, "chunks compared byte for byte: " + same);
        System.out.println("blosc2_write.h5: " + names.size() + " datasets, " + same + " chunks byte for byte");
    }

    /**
     * Writing into the plugin's own datasets ({@link Hdf5Writer#open}) uses the client data they hold: a row
     * changed in each stores the chunks the plugin stores for the changed values, truncated precision's
     * unfiltered (the plugin's filter fails there, and libhdf5 skips it for the chunk), and a block size in the
     * client data (which hdf5plugin's API never sets) the plugin's blocks, or for b2nd its block shape.
     */
    @Test
    void writesIntoThePluginsDatasets() throws IOException {
        Path copy = dir.resolve("edit.h5");
        Files.copy(Fixtures.path("blosc2_write.h5"), copy, StandardCopyOption.REPLACE_EXISTING);
        List<String> names = new ArrayList<>();
        try (Hdf5File h5 = Hdf5File.open(copy)) {
            for (Hdf5Object object : h5.root().children()) {
                names.add(object.name());
            }
        }
        try (Hdf5Writer w = Hdf5Writer.open(copy)) {
            for (String name : names) {
                Hdf5Writer.DatasetWriter d = w.dataset(name);
                long[] shape = d.shape();
                long[] count = new long[shape.length];
                Arrays.fill(count, 1);
                count[shape.length - 1] = shape[shape.length - 1];
                try (Hdf5File h5 = Hdf5File.open(Fixtures.path("blosc2_write.h5"))) {
                    Dataset source = h5.root().dataset(name);
                    long rowBytes = source.datatype().size() * count[shape.length - 1];
                    d.writeRaw(new long[shape.length], count, Arrays.copyOf(source.readRawBytes(), (int) rowBytes));
                }
            }
        }
        // The values written are the values there: every chunk the same as the plugin's.
        compare(Fixtures.path("blosc2_write.h5"), copy, names);
    }

    /** Compares the datasets' filters, values, and chunks; returns the chunks compared byte for byte. */
    private static int compare(Path plugin, Path falcon, List<String> names) throws IOException {
        byte[] theirs = Files.readAllBytes(plugin);
        byte[] ours = Files.readAllBytes(falcon);
        int same = 0;
        try (Hdf5File p = Hdf5File.open(plugin); Hdf5File f = Hdf5File.open(falcon)) {
            for (String name : names) {
                Dataset expected = p.root().dataset(name);
                Dataset actual = f.root().dataset(name);
                assertEquals(expected.filters(), actual.filters(), name);
                assertArrayEquals(expected.readRawBytes(), actual.readRawBytes(), name);
                boolean zstd = expected.attribute("blosc2").orElseThrow().readString().startsWith("zstd");
                List<ChunkRecord> a = chunks(expected);
                List<ChunkRecord> b = chunks(actual);
                assertEquals(a.size(), b.size(), name);
                for (int i = 0; i < a.size(); i++) {
                    ChunkRecord x = a.get(i);
                    ChunkRecord y = b.get(i);
                    assertArrayEquals(x.offset(), y.offset(), name);
                    assertEquals(x.filterMask(), y.filterMask(), name);
                    if (zstd) {
                        continue; // Falcon's zstd frames are its own
                    }
                    byte[] xs = Arrays.copyOfRange(theirs, (int) x.address(), (int) (x.address() + x.size()));
                    byte[] ys = Arrays.copyOfRange(ours, (int) y.address(), (int) (y.address() + y.size()));
                    assertArrayEquals(xs, ys, name + " chunk " + Arrays.toString(x.offset()));
                    same++;
                }
            }
        }
        return same;
    }

    /** What hdf5plugin's Blosc2 refuses, or writes and cannot read back, Falcon refuses before writing. */
    @Test
    void refusesWhatTheFilterCannotWrite() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("refused.h5"))) {
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("a", Datatype.float64(), new long[] {8}).blosc2());
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("b", Datatype.float64(), new long[] {8}).chunked(4).blosc2("snappy", 5, "shuffle"));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("c", Datatype.float64(), new long[] {8}).chunked(4).blosc2("lz4", 10, "shuffle"));
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("d", Datatype.float64(), new long[] {8}).chunked(4).blosc2("lz4", 5, "trunc_prec"));
            // b2nd chunks of elements over 255 bytes, which the plugin cannot read back; rank 1 is a plain frame
            Datatype opaque = Datatype.opaque(300, "");
            assertThrows(IllegalStateException.class,
                    () -> w.createDataset("e", opaque, new long[] {4, 4}).chunked(2, 2).blosc2());
            w.createDataset("f", opaque, new long[] {4}).chunked(2).blosc2().writeRaw(new long[] {0}, new long[] {4},
                    new byte[1200]);
        }
    }

    private static List<ChunkRecord> chunks(Dataset dataset) {
        DataLayout.Chunked chunked = (DataLayout.Chunked) dataset.dataLayout();
        List<ChunkRecord> all = new ArrayList<>(dataset.chunkIndex(chunked).all());
        all.sort((p, q) -> Arrays.compare(p.offset(), q.offset()));
        return all;
    }
}
