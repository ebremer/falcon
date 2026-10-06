package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.checksum.Fletcher32;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.data.SelectedElements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.FilterPipelineMessage;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.filter.Nbit;
import com.ebremer.falcon.hdf5.filter.ScaleOffset;
import com.ebremer.falcon.hdf5.filter.Szip;
import com.ebremer.falcon.hdf5.filter.ThirdPartyFilters;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.write.ChunkIndexWriter;
import com.ebremer.falcon.hdf5.write.BTreeV2Writer;
import com.ebremer.falcon.hdf5.write.DatatypeEncoder;
import com.ebremer.falcon.hdf5.write.FractalHeapWriter;
import com.ebremer.falcon.hdf5.write.GrowBuffer;
import com.ebremer.falcon.hdf5.write.ObjectHeaderEditor;
import com.ebremer.falcon.hdf5.write.OutputFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * Writes an HDF5 file: groups, datasets of any datatype, attributes, soft and external links, and object
 * and region references, in the modern format (a version-3 superblock and checksummed version-2 object
 * headers) or the earliest one ({@link Format#EARLIEST}), which every HDF5 version reads.
 *
 * <p><b>Two ways to add a dataset.</b> {@link GroupWriter#createDataset} makes a dataset of any
 * {@link Datatype} whose data is written afterwards, piece by piece ({@link DatasetWriter#write},
 * {@link DatasetWriter#append}), and may grow ({@link DatasetWriter#maxShape}). The per-type methods
 * ({@link GroupWriter#intDataset}, {@link GroupWriter#compoundDataset}, ...) take the whole data at once,
 * and write it when the next dataset or group is added: configure such a dataset (its filters, layout,
 * fill value) before then.
 *
 * <pre>{@code
 * try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
 *     w.intDataset("counts", new int[] {1, 2, 3}, new long[] {3}).intAttribute("scale", new int[] {2}, new long[] {});
 *     GroupWriter g = w.group("run");
 *     g.stringAttribute("units", "m/s");
 *     DatasetWriter frames = g.createDataset("frames", Datatype.uint16(), 0, 512, 512)
 *             .chunked(1, 512, 512).maxShape(Hdf5Writer.UNLIMITED, 512, 512).deflate(4);
 *     for (short[] frame : camera) {
 *         frames.append(frame);
 *     }
 *     g.softLink("latest", "/run/frames");
 * }
 * }</pre>
 *
 * <p><b>Streaming.</b> The file is written beside its path under a temporary name and moved into place by
 * {@link #close()}. Raw data goes to it as it is written (a chunk as soon as all of its elements are),
 * at 64-bit offsets, so a file may be far larger than memory or 2 GB; the metadata (object headers, chunk
 * indexes, groups) is written after it, on close. What stays in memory until then: chunks partly written,
 * the data of the last dataset given it whole (written when the next dataset or group is added), the
 * current global-heap collection of variable-length data (at most about 1 MiB), each dataset's chunk index
 * entries, and object references in chunks and attributes, whose targets' addresses are known only at the
 * end.
 *
 * <p><b>Changing a file.</b> {@link #open} opens an existing file to change it in place: add to it,
 * write into its datasets, hard-link, move, and delete its links, and change its attributes; see there.
 *
 * <pre>{@code
 * try (Hdf5Writer w = Hdf5Writer.open(Path.of("data.h5"))) {
 *     w.group("run").stringAttribute("status", "reviewed").delete("scratch");
 *     w.group("run").dataset("frames").append(lastFrame);
 * }
 * }</pre>
 *
 * <p>Not thread-safe: use a writer from one thread.
 */
public final class Hdf5Writer implements AutoCloseable {

    private static final byte[] HDF5_SIGNATURE = {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'};
    private static final byte[] OHDR_SIGNATURE = {'O', 'H', 'D', 'R'};
    private static final byte[] GCOL_SIGNATURE = {'G', 'C', 'O', 'L'};
    private static final byte[] TREE_SIGNATURE = {'T', 'R', 'E', 'E'};
    private static final byte[] SNOD_SIGNATURE = {'S', 'N', 'O', 'D'};
    private static final byte[] HEAP_SIGNATURE = {'H', 'E', 'A', 'P'};
    private static final long UNDEFINED = -1L;
    private static final int SUPERBLOCK_SIZE = 48;
    private static final int LEGACY_SUPERBLOCK_SIZE = 96; // v0 superblock with 8-byte offsets/lengths
    private static final int GROUP_INTERNAL_K = 16;       // libhdf5's default: v1 B-tree entries per node = 2 * K
    private static final int GROUP_LEAF_K = 4;            // libhdf5's default: symbol-table node symbols = 2 * K
    private static final int SYMBOL_ENTRY_SIZE = 2 * 8 + 4 + 4 + 16; // name off, header, cache type, rsv, scratch

    // Above this many links/attributes, the writer switches from compact header messages to dense
    // storage (a fractal heap indexed by a version-2 B-tree), matching the library's default threshold.
    private static final int MAX_COMPACT = 8;
    private static final int MIN_DENSE = 6;     // ... and below this many, back from dense storage to compact
    // Dense-storage fractal heaps, as libhdf5 makes them: heap ids of a type byte, the offset (in
    // ceil(bits / 8) bytes) and the length (2 bytes), for heaps of up to 2^bits bytes.
    private static final int ATTR_HEAP_ID = 8;
    private static final int ATTR_HEAP_BITS = 40;
    private static final int LINK_HEAP_ID = 7;
    private static final int LINK_HEAP_BITS = 32;
    // Object-header message bodies: a version-2 header stores the size in 16 bits, and a version-1
    // header pads each body to 8 bytes within that limit.
    private static final int MAX_MESSAGE_BODY = 65528;
    private static final int MAX_COMPACT_DATA = MAX_MESSAGE_BODY - 4; // the compact layout message's own fields
    // An attribute message must also fit, as one managed object, in a dense-storage heap's direct block.
    private static final int MAX_ATTRIBUTE_MESSAGE = FractalHeapWriter.maxObjectSize(ATTR_HEAP_BITS);
    // v2 B-tree record types for dense storage.
    private static final int BT2_ATTR_NAME = 8;    // 17-byte record: heap id(8) + flags(1) + corder(4) + hash(4)
    private static final int BT2_LINK_NAME = 5;    // 11-byte record: hash(4) + heap id(7)
    private static final int BT2_LINK_ORDER = 6;   // 15-byte record: creation order(8) + heap id(7)
    private static final int BT2_ATTR_ORDER = 9;   // 13-byte record: heap id(8) + flags(1) + corder(4)

    private static final byte[] DATATYPE_INT8 = {0x10, 0x08, 0, 0, 1, 0, 0, 0, 0, 0, 0x08, 0};
    private static final byte[] DATATYPE_INT16 = {0x10, 0x08, 0, 0, 2, 0, 0, 0, 0, 0, 0x10, 0};
    private static final byte[] DATATYPE_INT32 = {0x10, 0x08, 0, 0, 4, 0, 0, 0, 0, 0, 0x20, 0};
    private static final byte[] DATATYPE_INT64 = {0x10, 0x08, 0, 0, 8, 0, 0, 0, 0, 0, 0x40, 0};
    private static final byte[] DATATYPE_FLOAT32 = {
        0x11, 0x20, 0x1f, 0, 4, 0, 0, 0, 0, 0, 0x20, 0, 0x17, 0x08, 0, 0x17, 0x7f, 0, 0, 0
    };
    private static final byte[] DATATYPE_FLOAT64 = {
        0x11, 0x20, 0x3f, 0, 8, 0, 0, 0, 0, 0, 0x40, 0, 0x34, 0x0b, 0, 0x34, (byte) 0xff, 0x03, 0, 0
    };
    // Variable-length UTF-8 string (class 9): 16-byte global-heap id, string base. Verbatim from h5py.
    private static final byte[] DATATYPE_VLEN_STRING = {
        0x19, 0x01, 0x01, 0, 0x10, 0, 0, 0, 0x10, 0, 0, 0, 0x01, 0, 0, 0, 0, 0, 0x08, 0
    };
    // Object reference (class 7, kind 0): an 8-byte object-header address. Verbatim from h5py.
    private static final byte[] DATATYPE_OBJECT_REFERENCE = {0x17, 0, 0, 0, 8, 0, 0, 0};

    /** A maximum dimension with no limit ({@code H5S_UNLIMITED}), for {@link DatasetWriter#maxShape}. */
    public static final long UNLIMITED = -1L;

    private final Path path;
    private final boolean legacy;
    private final byte[] userBlock;            // a new file's user block (P2 WF7), or empty
    private final GroupSpec root = new GroupSpec();
    private final GroupWriter rootWriter;
    private OutputFile output;                 // the temporary file, created when the first bytes are written
    private final GlobalHeaps heaps = new GlobalHeaps();
    // Object references (in contiguous data, and in region references' heap objects), patched in the file
    // once the objects' addresses are known.
    private final List<FileReference> fileReferences = new ArrayList<>();
    // Each layout of the metadata: where each object went (and a group's symbol table), the objects being laid
    // out (a cycle of hard links reaches one again), whether one was reached so before the first layout placed
    // it, and the first layout's places, which the second confirms (see complete()).
    private Map<ObjectSpec, GroupResult> laidOut = new HashMap<>();
    private final Set<ObjectSpec> inProgress = new HashSet<>();
    private boolean placeholders;
    private Map<ObjectSpec, GroupResult> firstLayout = Map.of();
    private Map<ObjectSpec, Integer> referenceCounts = Map.of(); // the session's objects: the links reaching each
    // The dataset last given its data whole, written when the next dataset or group is added (or on close).
    private DatasetSpec unwritten;
    private String unwrittenPath;
    private long rootAddress = UNDEFINED;      // the root group's object header, once laid out
    private final int groupLeafK;              // the 'K's of the file's symbol tables and group B-trees
    private final int groupInternalK;
    private final int chunkK;                  // ... and of its chunk B-trees
    // A file being changed in place (P2 WF6), else null; and, for each layout of the metadata, the editors of
    // its object headers, and the chunks of them to write over the file.
    private final ExistingFile existing;
    private final Map<Long, ObjectHeaderEditor> editors = new LinkedHashMap<>();
    private final List<ObjectHeaderEditor.Patch> headerPatches = new ArrayList<>();
    private final List<SharedMessages.Release> sharedReleases = new ArrayList<>(); // messages no longer held
    private final Map<Path, java.nio.channels.FileChannel> externalFiles = new LinkedHashMap<>(); // external raw data
    // The other files written through virtual datasets (P2 WF11), each changed in a session of its own.
    private final Map<Path, Hdf5Writer> sourceWriters = new LinkedHashMap<>();
    // Writes through virtual datasets whose sources are virtual: nested no deeper than reads are.
    private static final int MAX_VIRTUAL_NESTING = 32;
    private static final ThreadLocal<int[]> VIRTUAL_NESTING = ThreadLocal.withInitial(() -> new int[1]);
    // A file being changed: the writes over it (superblock first), once journaled and on disk (P2 WF10).
    private List<ObjectHeaderEditor.Patch> journal;
    // For tests: the writes over a file after which writing them fails, as a crash would stop it (-1: never).
    static volatile int interruptAfter = -1;
    // The objects of the file opened to change them (groups and datasets), by object header address: each once,
    // however many links reach it.
    private final Map<Long, ObjectSpec> opened = new LinkedHashMap<>();
    private long legacyRootBtree = UNDEFINED;  // root group's symbol-table B-tree / local heap (legacy superblock)
    private long legacyRootHeap = UNDEFINED;
    private boolean rootTableChanged;          // ... changed in this session (rebuilt, or gone: converted)
    private final Lifecycle lifecycle = new Lifecycle();

    /** Whether the writer (and every group and dataset handle it gave out) still accepts additions. */
    private static final class Lifecycle {
        private boolean closed;

        void check() {
            if (closed) {
                throw new HdfClosedException("the HDF5 writer is closed");
            }
        }
    }

    /**
     * How szip codes a chunk ({@link DatasetWriter#szip(SzipCoding, int)}): entropy coding alone, or after
     * nearest-neighbour preprocessing (each element coded as its difference from the one before, which
     * suits smooth data). libhdf5's {@code H5_SZIP_EC_OPTION_MASK} and {@code H5_SZIP_NN_OPTION_MASK}.
     */
    public enum SzipCoding {
        /** Entropy coding alone: libhdf5's {@code H5_SZIP_EC_OPTION_MASK}, h5py's "ec". */
        ENTROPY,
        /** Entropy coding after nearest-neighbour preprocessing: {@code H5_SZIP_NN_OPTION_MASK}, h5py's "nn". */
        NEAREST_NEIGHBOUR
    }

    /** On-disk format version: {@link #EARLIEST} writes the original (v0 superblock, symbol-table
     * groups, v1 object headers); {@link #LATEST} the modern checksummed format. */
    public enum Format {
        /** The original format, libhdf5's default: a version-0 superblock and symbol-table groups. */
        EARLIEST,
        /** The modern format: a version-3 superblock and checksummed version-2 object headers. */
        LATEST
    }

    private Hdf5Writer(Path path, Format format, ExistingFile existing, byte[] userBlock) {
        this.path = path;
        this.legacy = format == Format.EARLIEST;
        this.existing = existing;
        this.userBlock = userBlock;
        this.groupLeafK = existing != null ? existing.groupLeafK : GROUP_LEAF_K;
        this.groupInternalK = existing != null ? existing.groupInternalK : GROUP_INTERNAL_K;
        this.chunkK = existing != null ? existing.chunkK : ChunkIndexWriter.BTREE_K;
        if (existing != null) {
            root.address = existing.rootAddress;
            root.object = existing.file.root();
            opened.put(root.address, root);
        }
        this.rootWriter = new GroupWriter(this, root, "", legacy, lifecycle);
    }

    /**
     * Begins writing a new HDF5 file at {@code path} in the modern format (completed on {@link #close()}).
     *
     * @param path where the file goes; a file already there is replaced when the writer closes
     * @return the writer
     */
    public static Hdf5Writer create(Path path) {
        return new Hdf5Writer(path, Format.LATEST, null, new byte[0]);
    }

    /**
     * Begins writing a new HDF5 file at {@code path} in the given on-disk {@link Format}.
     *
     * @param path   where the file goes; a file already there is replaced when the writer closes
     * @param format the on-disk format
     * @return the writer
     */
    public static Hdf5Writer create(Path path, Format format) {
        return new Hdf5Writer(path, format, null, new byte[0]);
    }

    /**
     * Begins writing a new HDF5 file at {@code path} that starts with a user block (P2 WF7): bytes of the
     * application's own, which HDF5 readers skip, as libhdf5's {@code H5Pset_userblock} reserves them. The
     * block is {@code userBlock}, zero-padded to the smallest size libhdf5 allows that holds it (512, 1024,
     * 2048, ... bytes); the HDF5 data follows. MATLAB v7.3 files keep their 128-byte header in one:
     *
     * <pre>{@code
     * byte[] header = Arrays.copyOf("MATLAB 7.3 MAT-file ...".getBytes(StandardCharsets.US_ASCII), 128);
     * try (Hdf5Writer w = Hdf5Writer.create(Path.of("data.mat"), Hdf5Writer.Format.LATEST, header)) { ... }
     * }</pre>
     *
     * @param path      where the file goes; a file already there is replaced when the writer closes
     * @param format    the on-disk format
     * @param userBlock the user block's content, at most 2^30 bytes
     * @return the writer
     * @throws IllegalArgumentException if the block holds the HDF5 signature at offset 0, 512, 1024, ...
     *         (where readers look for the superblock), or is larger than 2^30 bytes
     */
    public static Hdf5Writer create(Path path, Format format, byte[] userBlock) {
        return new Hdf5Writer(path, format, null, userBlock(userBlock));
    }

    /** {@code content}, zero-padded to a user block's size: a power of two of at least 512 bytes. */
    private static byte[] userBlock(byte[] content) {
        java.util.Objects.requireNonNull(content, "userBlock");
        if (content.length > 1 << 30) {
            throw new IllegalArgumentException("a user block of " + content.length + " bytes is larger than 2^30");
        }
        int size = 512;
        while (size < content.length) {
            size *= 2;
        }
        for (int at = 0; at + HDF5_SIGNATURE.length <= content.length; at = at == 0 ? 512 : at * 2) {
            if (java.util.Arrays.equals(content, at, at + HDF5_SIGNATURE.length, HDF5_SIGNATURE, 0, HDF5_SIGNATURE.length)) {
                throw new IllegalArgumentException("the user block holds the HDF5 signature at offset " + at
                        + ", where readers look for the superblock");
            }
        }
        return java.util.Arrays.copyOf(content, size);
    }

    /**
     * Opens the existing HDF5 file at {@code path} to change it in place (P2 WF6, WF10, WF11): add groups,
     * datasets, links and attributes anywhere in it; open its groups ({@link GroupWriter#group}) and datasets
     * ({@link GroupWriter#dataset}) to write their data (a virtual dataset's into its sources, those of other
     * files too) or set and delete their attributes (those kept in its shared-message table too); and
     * hard-link ({@link GroupWriter#hardLink}), move ({@link GroupWriter#move}) and delete
     * ({@link GroupWriter#delete}) its links. New objects are written in the file's own format: the earliest
     * one for a file with a version 0&ndash;1 superblock, else the modern one.
     *
     * <p>The file is changed in place, not rewritten: new data and metadata go after its end as they are
     * written, and {@link #close()} then points the file's existing structures at them (a group's link
     * storage, an object's header), through a journal: a change interrupted then is redone when the file is
     * next opened here. What a deleted link or a replaced attribute held is left as unused space, as libhdf5
     * leaves it between sessions (a session never writes over what the file holds until {@code close()}, so
     * that {@link #abort()} and a crash before then leave it as it was); {@code h5repack} reclaims it. Data
     * written into an existing dataset's contiguous storage (or its external raw data files) goes there at
     * once, which {@link #abort()} cannot undo; anything else is undone by it. The file must not be open
     * elsewhere while it is changed.
     *
     * @param path the file to change
     * @return the writer, which writes the changes over the file on {@link #close()}
     * @throws IOException if the file cannot be read or opened for writing
     * @throws HdfUnsupportedException for a file Falcon does not change: with 4-byte addresses, of a file
     *         driver other than the default (family, multi, ...), tracking its free space, or marked as open
     *         by a writer (without a journal of Falcon's to redo)
     */
    public static Hdf5Writer open(Path path) throws IOException {
        OutputFile.recover(path); // a change interrupted while it was written over the file: redone first
        ExistingFile existing = ExistingFile.open(path);
        try {
            Hdf5Writer writer = new Hdf5Writer(path, existing.superblockVersion < 2 ? Format.EARLIEST : Format.LATEST,
                    existing, new byte[0]);
            writer.output = OutputFile.openExisting(path, existing.base, existing.endOfFile);
            return writer;
        } catch (IOException | RuntimeException e) {
            existing.close();
            throw e;
        }
    }

    /**
     * The root group writer, on which the same operations are available as any subgroup.
     *
     * @return the root group's writer
     */
    public GroupWriter root() {
        return rootWriter;
    }

    // Convenience delegates to the root group.

    /**
     * A dataset of any datatype, written with {@link DatasetWriter#write} (see {@link GroupWriter#createDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param type  its datatype
     * @param shape its dimensions now (none for a scalar), which it may grow within its maximum shape
     * @return the dataset's writer
     */
    public DatasetWriter createDataset(String name, Datatype type, long... shape) {
        return rootWriter.createDataset(name, type, shape);
    }

    /**
     * A signed 32-bit integer dataset in the root group (see {@link GroupWriter#intDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its values, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter intDataset(String name, int[] data, long[] shape) {
        return rootWriter.intDataset(name, data, shape);
    }

    /**
     * A 64-bit floating-point dataset in the root group (see {@link GroupWriter#doubleDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its values, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter doubleDataset(String name, double[] data, long[] shape) {
        return rootWriter.doubleDataset(name, data, shape);
    }

    /**
     * A variable-length UTF-8 string dataset in the root group (see {@link GroupWriter#stringDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its strings, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter stringDataset(String name, String[] data, long[] shape) {
        return rootWriter.stringDataset(name, data, shape);
    }

    /**
     * A signed 8-bit integer dataset in the root group (see {@link GroupWriter#byteDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its values, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter byteDataset(String name, byte[] data, long[] shape) {
        return rootWriter.byteDataset(name, data, shape);
    }

    /**
     * A signed 16-bit integer dataset in the root group (see {@link GroupWriter#shortDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its values, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter shortDataset(String name, short[] data, long[] shape) {
        return rootWriter.shortDataset(name, data, shape);
    }

    /**
     * A signed 64-bit integer dataset in the root group (see {@link GroupWriter#longDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its values, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter longDataset(String name, long[] data, long[] shape) {
        return rootWriter.longDataset(name, data, shape);
    }

    /**
     * A 32-bit floating-point dataset in the root group (see {@link GroupWriter#floatDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its values, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter floatDataset(String name, float[] data, long[] shape) {
        return rootWriter.floatDataset(name, data, shape);
    }

    /**
     * A fixed-length string dataset in the root group (see
     * {@link GroupWriter#fixedStringDataset(String, String[], long[])}).
     *
     * @param name  the dataset's name in the root group
     * @param data  its strings, row-major
     * @param shape its dimensions; their product must be {@code data.length}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape) {
        return rootWriter.fixedStringDataset(name, data, shape);
    }

    /**
     * A chunked {@code int32} dataset in the root group (see {@link GroupWriter#intChunkedDataset}).
     *
     * @param name       the dataset's name in the root group
     * @param data       its values, row-major
     * @param shape      its dimensions; their product must be {@code data.length}
     * @param chunkShape its chunks' dimensions: the same rank, each at least 1
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter intChunkedDataset(String name, int[] data, long[] shape, long[] chunkShape) {
        return rootWriter.intChunkedDataset(name, data, shape, chunkShape);
    }

    /**
     * A chunked {@code float64} dataset in the root group (see {@link GroupWriter#doubleChunkedDataset}).
     *
     * @param name       the dataset's name in the root group
     * @param data       its values, row-major
     * @param shape      its dimensions; their product must be {@code data.length}
     * @param chunkShape its chunks' dimensions: the same rank, each at least 1
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter doubleChunkedDataset(String name, double[] data, long[] shape, long[] chunkShape) {
        return rootWriter.doubleChunkedDataset(name, data, shape, chunkShape);
    }

    /**
     * A compound (record) dataset in the root group (see {@link GroupWriter#compoundDataset}).
     *
     * @param name   the dataset's name in the root group
     * @param shape  its dimensions; their product must be each field's count of values
     * @param fields its members, in order
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter compoundDataset(String name, long[] shape, CompoundField... fields) {
        return rootWriter.compoundDataset(name, shape, fields);
    }

    /**
     * An enumerated dataset in the root group (see {@link GroupWriter#enumDataset}).
     *
     * @param name   the dataset's name in the root group
     * @param shape  its dimensions; their product must be {@code values.length}
     * @param type   the enumeration
     * @param values each element's code, row-major
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter enumDataset(String name, long[] shape, EnumType type, int[] values) {
        return rootWriter.enumDataset(name, shape, type, values);
    }

    /**
     * An object-reference dataset in the root group (see {@link GroupWriter#referenceDataset}).
     *
     * @param name    the dataset's name in the root group
     * @param shape   its dimensions; their product must be {@code targets.length}
     * @param targets each element's target, an absolute path, or {@code null}; row-major
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter referenceDataset(String name, long[] shape, String[] targets) {
        return rootWriter.referenceDataset(name, shape, targets);
    }

    /**
     * A dataset of region references (see {@link GroupWriter#regionReferenceDataset}).
     *
     * @param name    the dataset's name in the root group
     * @param shape   its dimensions; their product must be {@code regions.length}
     * @param regions each element's region, or {@code null}; row-major
     * @return the dataset's writer
     */
    public DatasetWriter regionReferenceDataset(String name, long[] shape, Region[] regions) {
        return rootWriter.regionReferenceDataset(name, shape, regions);
    }

    /**
     * A dataset of fixed-shape {@code float32} arrays in the root group (see {@link GroupWriter#float32ArrayDataset}).
     *
     * @param name      the dataset's name in the root group
     * @param shape     its dimensions
     * @param arrayDims each element's array dimensions
     * @param data      every element's array, one after another, row-major
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter float32ArrayDataset(String name, long[] shape, int[] arrayDims, float[] data) {
        return rootWriter.float32ArrayDataset(name, shape, arrayDims, data);
    }

    /**
     * A dataset of fixed-shape {@code int32} arrays in the root group (see {@link GroupWriter#int32ArrayDataset}).
     *
     * @param name      the dataset's name in the root group
     * @param shape     its dimensions
     * @param arrayDims each element's array dimensions
     * @param data      every element's array, one after another, row-major
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter int32ArrayDataset(String name, long[] shape, int[] arrayDims, int[] data) {
        return rootWriter.int32ArrayDataset(name, shape, arrayDims, data);
    }

    /**
     * A native complex-number dataset in the root group (see {@link GroupWriter#complexDataset}).
     *
     * @param name      the dataset's name in the root group
     * @param shape     its dimensions; their product must be {@code real.length}
     * @param real      each element's real part, row-major
     * @param imaginary each element's imaginary part, as many as {@code real}
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter complexDataset(String name, long[] shape, double[] real, double[] imaginary) {
        return rootWriter.complexDataset(name, shape, real, imaginary);
    }

    /**
     * A variable-length {@code int32} sequence dataset in the root group (see {@link GroupWriter#intSequenceDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param shape its dimensions; their product must be {@code rows.length}
     * @param rows  each element's values, row-major; rows may differ in length
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter intSequenceDataset(String name, long[] shape, int[][] rows) {
        return rootWriter.intSequenceDataset(name, shape, rows);
    }

    /**
     * A variable-length {@code float64} sequence dataset in the root group (see
     * {@link GroupWriter#doubleSequenceDataset}).
     *
     * @param name  the dataset's name in the root group
     * @param shape its dimensions; their product must be {@code rows.length}
     * @param rows  each element's values, row-major; rows may differ in length
     * @return the dataset's writer, to configure it before the next dataset or group is added
     */
    public DatasetWriter doubleSequenceDataset(String name, long[] shape, double[][] rows) {
        return rootWriter.doubleSequenceDataset(name, shape, rows);
    }

    /**
     * A group in the root group: added, or in a file being changed opened (see {@link GroupWriter#group}).
     *
     * @param name the group's name in the root group
     * @return the group's writer
     */
    public GroupWriter group(String name) {
        return rootWriter.group(name);
    }

    /**
     * A dataset of the root group (see {@link GroupWriter#dataset}).
     *
     * @param name the dataset's name in the root group
     * @return the dataset's writer
     */
    public DatasetWriter dataset(String name) {
        return rootWriter.dataset(name);
    }

    /**
     * Deletes a link of the root group (see {@link GroupWriter#delete}).
     *
     * @param name the link's name in the root group
     * @return the root group's writer
     */
    public GroupWriter delete(String name) {
        return rootWriter.delete(name);
    }

    /**
     * A soft link in the root group (see {@link GroupWriter#softLink}).
     *
     * @param name       the link's name in the root group
     * @param targetPath the path it stands for
     * @return the root group's writer
     */
    public GroupWriter softLink(String name, String targetPath) {
        return rootWriter.softLink(name, targetPath);
    }

    /**
     * An external link in the root group (see {@link GroupWriter#externalLink}).
     *
     * @param name       the link's name in the root group
     * @param fileName   the file the object is in (a relative name is found next to this file)
     * @param objectPath the object's path in that file
     * @return the root group's writer
     */
    public GroupWriter externalLink(String name, String fileName, String objectPath) {
        return rootWriter.externalLink(name, fileName, objectPath);
    }

    /**
     * A hard link in the root group (see {@link GroupWriter#hardLink}).
     *
     * @param name       the link's name in the root group
     * @param targetPath the object's absolute path
     * @return the root group's writer
     */
    public GroupWriter hardLink(String name, String targetPath) {
        return rootWriter.hardLink(name, targetPath);
    }

    /**
     * Moves (or renames) a link of the root group (see {@link GroupWriter#move}).
     *
     * @param name    the link to move: its name in the root group, or its path
     * @param newPath its new path, absolute or relative to the root group
     * @return the root group's writer
     */
    public GroupWriter move(String name, String newPath) {
        return rootWriter.move(name, newPath);
    }

    /**
     * Completes the file and closes the writer. The file is written beside {@code path} under a temporary
     * name (raw data as it is written, then the metadata here) and moved into place, so {@code path} ends
     * up either as the complete new file or as it was. If completing it fails, the writer stays open, so
     * the cause (such as a reference to an object never added) can be fixed and {@code close()} called
     * again. Once the file is written, or after {@link #abort()}, further calls do nothing, and adding to
     * the writer throws {@link HdfClosedException}.
     *
     * <p>A file being changed ({@link #open}) is completed in place: the new metadata is written after its
     * end, with a journal of every write over the file's own structures (its superblock, the object headers
     * and indexes that change), and flushed to disk; then those writes are made, and the journal cut off. A
     * failure before the journal is on disk leaves the file as it was (but for data written into its
     * contiguous datasets); one after it is redone by retrying {@code close()}, or by the next
     * {@link #open} of the file (after a crash, or {@link #abort()}). Meanwhile a version-3 superblock is
     * marked as open by a writer, as libhdf5 marks it, so libhdf5 refuses the file until the change is whole.
     * The other files written through its virtual datasets are then completed, each in the same way; one
     * that fails is completed by retrying {@code close()} (or undone by {@link #abort()}).
     *
     * <p>{@code close()} cannot tell that the code building the file failed: inside
     * try-with-resources, call {@link #abort()} on failure to avoid writing what was added so far.
     */
    @Override
    public void close() throws IOException {
        if (lifecycle.closed && sourceWriters.isEmpty()) {
            return;
        }
        if (!lifecycle.closed) {
            try {
                complete();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            } catch (HdfException | IllegalArgumentException | IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new HdfException("could not write " + path + ": " + e, e);
            }
            lifecycle.closed = true;
        }
        // Then the files written through virtual datasets (P2 WF11), each through its own journal, once this
        // file's reader, which may hold them open, is closed. One that fails is completed by a retry.
        java.util.Iterator<Hdf5Writer> sources = sourceWriters.values().iterator();
        while (sources.hasNext()) {
            sources.next().close();
            sources.remove();
        }
    }

    /**
     * Closes the writer without writing anything: the temporary file is deleted, and a file already at
     * {@code path} is left as it was (a file being changed loses what was added after its end; data written
     * into its contiguous datasets stays). After a {@link #close()} of a file being changed failed while
     * writing over the file, its journal is kept instead, and the change is redone when the file is next
     * opened. The other files written through its virtual datasets are left likewise. Use it when building
     * the file failed part-way:
     *
     * <pre>{@code
     * Hdf5Writer w = Hdf5Writer.create(path);
     * try {
     *     ... build ...
     *     w.close();
     * } catch (RuntimeException | IOException e) {
     *     w.abort();
     *     throw e;
     * }
     * }</pre>
     */
    public void abort() {
        if (!lifecycle.closed) {
            lifecycle.closed = true;
            try {
                closeExternalFiles(false);
            } catch (UncheckedIOException e) {
                // what was written there stays, as data written into the file's contiguous datasets does
            }
            if (existing != null) {
                existing.close();
            }
            if (output != null && journal != null) {
                output.closeKeepingJournal(); // interrupted while written over: redone when the file is next opened
            } else if (output != null) {
                output.discard();
            }
        }
        // Then the files written through virtual datasets (P2 WF11), once this file's reader, which may hold
        // them open (and so keep them from being cut back), is closed.
        for (Hdf5Writer source : sourceWriters.values()) {
            source.abort();
        }
        sourceWriters.clear();
    }

    /** Closes the external raw data files written to, first flushing them to disk if {@code force}. */
    private void closeExternalFiles(boolean force) {
        UncheckedIOException failure = null;
        for (Map.Entry<Path, java.nio.channels.FileChannel> file : externalFiles.entrySet()) {
            try (java.nio.channels.FileChannel channel = file.getValue()) {
                if (force) {
                    channel.force(true);
                }
            } catch (IOException e) {
                failure = failure != null ? failure : new UncheckedIOException("cannot write " + file.getKey(), e);
            }
        }
        externalFiles.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * True until the file is written or the writer aborted.
     *
     * @return whether the writer still accepts additions
     */
    public boolean isOpen() {
        return !lifecycle.closed;
    }

    /** The output file, created when first needed: its first bytes are kept for the superblock. */
    private OutputFile output() {
        if (output == null) {
            output = OutputFile.create(path, legacy ? LEGACY_SUPERBLOCK_SIZE : SUPERBLOCK_SIZE, userBlock);
        }
        return output;
    }

    /**
     * Completes the file: every dataset's remaining data (the data of datasets made with their values,
     * and partly written chunks), the variable-length data, then the metadata after it all, the
     * references, and the superblock. Repeatable: what failed part-way is done again.
     */
    private void complete() throws IOException {
        if (journal != null) {
            // Retried after a failure while the file was written over: that is redone, and nothing else.
            applyJournal();
            output().commit();
            return;
        }
        Map<ObjectSpec, String> objects = objects();
        referenceCounts = referenceCounts(objects);
        finishData(objects);
        heaps.sealAll();
        resolveAttributeIds(objects);
        OutputFile out = output();
        long base = align8(out.end());
        firstLayout = Map.of();
        placeholders = false;
        GrowBuffer buf = layOutMetadata(base, objects);
        // A second layout when object references wait for the first's addresses, a cycle of hard links was
        // laid out, or (changing a file) groups' symbol tables are cached in entries laid out before them.
        boolean twoLayouts = waitsForAddresses(objects) || placeholders || existing != null;
        // Every reference's target must exist, which is checked before anything more is written.
        Set<String> targets = new HashSet<>();
        heldTargets(objects, targets);
        for (FileReference reference : fileReferences) {
            targets.add(reference.targetPath());
        }
        for (String target : targets) {
            addressOf(target);
        }
        if (out.allocate(buf.size()) != base) {
            throw new IllegalStateException("the metadata's space moved");
        }
        if (twoLayouts) {
            // Object references in chunks, compact data and attributes (P2 WF8) are filled in once the first
            // layout has placed every object, and the chunks written after the metadata's space; the second
            // layout is the same metadata with them in place, so every object stays where the first put it.
            Map<ObjectSpec, GroupResult> placed = laidOut;
            int size = buf.size();
            writeHeld(objects);
            firstLayout = placed;
            buf = layOutMetadata(base, objects);
            if (buf.size() != size || !laidOut.equals(placed)) {
                throw new IllegalStateException("the metadata changed between its two layouts");
            }
        }
        for (FileReference reference : fileReferences) {
            out.writeU64(reference.position(), addressOf(reference.targetPath()));
        }
        closeExternalFiles(true);
        out.write(base, buf.toByteArray());
        long endOfFile = out.end();
        if (existing != null) {
            // What is new is on disk, with a journal of every write over the file's own structures, before
            // any is made: the superblock (which covers the new space), the changed object headers and indexes.
            List<ObjectHeaderEditor.Patch> writes = new ArrayList<>();
            writes.add(new ObjectHeaderEditor.Patch(0, changedSuperblock(endOfFile)));
            writes.addAll(headerPatches);
            out.writeJournal(writes);
            journal = writes;
            applyJournal();
        } else {
            // With a user block, the base address is where the superblock is, and the end-of-file address
            // absolute, as libhdf5 writes them.
            long fileBase = userBlock.length;
            out.write(0, legacy ? superblockV0(rootAddress, fileBase, endOfFile)
                    : superblock(rootAddress, fileBase, endOfFile));
        }
        out.commit();
    }

    /**
     * Writes the journaled writes over the file (P2 WF10), each step once the last is on disk: the superblock
     * (in version 3 marked as open by a writer, as libhdf5 marks a file it writes, so libhdf5 refuses the file
     * until the change is whole), the changed structures, then (version 3) the superblock unmarked. Should this
     * be interrupted, {@link #open} redoes it from the journal.
     */
    private void applyJournal() {
        OutputFile out = output();
        byte[] superblock = journal.getFirst().bytes();
        boolean mark = existing.superblockVersion >= 3;
        if (mark) {
            byte[] marked = superblock.clone();
            marked[11] |= 0x01; // H5F_SUPER_WRITE_ACCESS
            writeU32(marked, 44, Lookup3.hashLittle(marked, 0, 44, 0));
            out.write(0, marked);
        } else {
            out.write(0, superblock);
        }
        out.force();
        for (int i = 1; i < journal.size(); i++) {
            if (interruptAfter >= 0 && i > interruptAfter) {
                throw new UncheckedIOException(new IOException("interrupted after " + interruptAfter + " writes (a test)"));
            }
            out.write(journal.get(i).address(), journal.get(i).bytes());
        }
        out.force();
        if (mark) {
            out.write(0, superblock);
            out.force();
        }
        existing.close();
    }

    /**
     * The superblock of the file being changed, with its new end-of-file address, and in versions 0&ndash;1
     * the root group's symbol table, if rebuilt, in its entry's cache.
     */
    private byte[] changedSuperblock(long endOfFile) {
        int version = existing.superblockVersion;
        byte[] sb = output().read(0, version >= 2 ? 48 : version == 1 ? 100 : 96);
        // The base address is where the superblock is; the end-of-file address is absolute, from it.
        int baseAt = version == 0 ? 24 : version == 1 ? 28 : 12;
        putU64(sb, baseAt, existing.base);
        putU64(sb, baseAt + 16, existing.base + endOfFile);
        int cache = version == 0 ? 72 : 76;
        if (version < 2 && rootTableChanged && legacyRootBtree == UNDEFINED) {
            // The root group converted to the new format: its entry caches no symbol table.
            java.util.Arrays.fill(sb, cache, cache + 24, (byte) 0);
        } else if (version < 2 && legacyRootBtree != UNDEFINED) {
            if (sb[cache] == 1 && sb[cache + 1] == 0 && sb[cache + 2] == 0 && sb[cache + 3] == 0) {
                putU64(sb, cache + 8, legacyRootBtree);
                putU64(sb, cache + 16, legacyRootHeap);
            }
        }
        if (version >= 2) {
            writeU32(sb, 44, Lookup3.hashLittle(sb, 0, 44, 0));
        }
        return sb;
    }

    /** The editor of the object header at {@code address}, in this layout of the metadata. */
    private ObjectHeaderEditor editor(long address) {
        return editors.computeIfAbsent(address, at -> ObjectHeaderEditor.load(output()::read, at));
    }

    /** Lays out the metadata of every object at {@code base}, recording where each object's header goes. */
    private GrowBuffer layOutMetadata(long base, Map<ObjectSpec, String> objects) {
        laidOut = new HashMap<>();
        inProgress.clear();
        editors.clear();
        headerPatches.clear();
        sharedReleases.clear();
        GrowBuffer buf = new GrowBuffer(base);
        GroupResult rootResult = layOut(buf, root);
        rootAddress = rootResult.headerAddress();
        legacyRootBtree = rootResult.btreeAddress();
        legacyRootHeap = rootResult.heapAddress();
        rootTableChanged = rootResult.changed();
        for (ObjectSpec spec : objects.keySet()) {
            layOut(buf, spec); // the file's objects opened, however they are reached (the rest are laid out by now)
        }
        adjustReferenceCounts(objects);
        if (!sharedReleases.isEmpty()) {
            headerPatches.addAll(existing.sharedMessages().release(buf, sharedReleases));
        }
        // The existing object headers that changed: new chunks here, the changed ones written over the file.
        for (ObjectHeaderEditor editor : editors.values()) {
            if (editor.changed()) {
                headerPatches.addAll(editor.write(buf));
            }
        }
        return buf;
    }

    /**
     * Lays out an object once in each layout of the metadata (the first link reaching it writes it; the
     * others point at it), returning where its header is and, for a group of the original format, its symbol
     * table. An object reached again while it is laid out (through a cycle of hard links) takes the first
     * layout's place, which the second layout confirms (see {@link #complete}).
     */
    private GroupResult layOut(GrowBuffer buf, ObjectSpec spec) {
        GroupResult done = laidOut.get(spec);
        if (done != null) {
            return done;
        }
        if (!inProgress.add(spec)) {
            GroupResult first = firstLayout.get(spec);
            if (first != null) {
                return first;
            }
            placeholders = true;
            return spec.inFile() ? storedResult(spec.address) : new GroupResult(0, UNDEFINED, UNDEFINED, false);
        }
        GroupResult result;
        if (spec instanceof GroupSpec group) {
            result = group.inFile() ? writeExistingGroup(buf, group) : writeGroup(buf, group);
        } else {
            DatasetSpec dataset = (DatasetSpec) spec;
            if (dataset.inFile()) {
                writeExistingDataset(buf, dataset);
                result = new GroupResult(dataset.address, UNDEFINED, UNDEFINED, false);
            } else {
                result = new GroupResult(writeDataset(buf, dataset), UNDEFINED, UNDEFINED, false);
            }
        }
        inProgress.remove(spec);
        laidOut.put(spec, result);
        return result;
    }

    /** The file's object at {@code address} as the file has it: its symbol table, if a group of the original format. */
    private GroupResult storedResult(long address) {
        long[] table = existing.symbolTable(address);
        return table == null ? new GroupResult(address, UNDEFINED, UNDEFINED, false)
                : new GroupResult(address, table[0], table[1], false);
    }

    /** The file's object at {@code address}: laid out if opened, else as the file has it. */
    private GroupResult fileObjectResult(GrowBuffer buf, long address) {
        ObjectSpec spec = opened.get(address);
        return spec != null ? layOut(buf, spec) : storedResult(address);
    }

    /**
     * Every object to write, each with a path that reaches it (for messages): those reached from the root by
     * the session's links (subgroups, datasets, and hard links), and every object of the file opened, whose
     * changes apply however it is reached, or if it is no longer.
     */
    private Map<ObjectSpec, String> objects() {
        Map<ObjectSpec, String> found = new LinkedHashMap<>();
        collect(root, "/", found);
        for (ObjectSpec spec : opened.values()) {
            found.putIfAbsent(spec, display(spec.object.path()));
        }
        return found;
    }

    private static void collect(ObjectSpec spec, String path, Map<ObjectSpec, String> found) {
        if (found.putIfAbsent(spec, path) != null) {
            return;
        }
        if (spec instanceof GroupSpec group) {
            String prefix = path.equals("/") ? "/" : path + "/";
            for (GroupSpec subgroup : group.groups) {
                collect(subgroup, prefix + subgroup.name, found);
            }
            for (DatasetSpec dataset : group.datasets) {
                collect(dataset, prefix + dataset.name, found);
            }
            for (LinkSpec link : group.links) {
                if (link.object() != null) {
                    collect(link.object(), prefix + link.name(), found);
                }
            }
        }
    }

    /**
     * The links reaching each of the session's objects, from the groups written: its hard-link count (a new
     * file's root group starts at 1, the superblock's).
     */
    private Map<ObjectSpec, Integer> referenceCounts(Map<ObjectSpec, String> objects) {
        Map<ObjectSpec, Integer> counts = new HashMap<>();
        if (!root.inFile()) {
            counts.put(root, 1);
        }
        for (ObjectSpec spec : objects.keySet()) {
            if (spec instanceof GroupSpec group) {
                for (GroupSpec subgroup : group.groups) {
                    if (!subgroup.inFile()) {
                        counts.merge(subgroup, 1, Integer::sum);
                    }
                }
                for (DatasetSpec dataset : group.datasets) {
                    if (!dataset.inFile()) {
                        counts.merge(dataset, 1, Integer::sum);
                    }
                }
                for (LinkSpec link : group.links) {
                    if (link.object() != null) {
                        counts.merge(link.object(), 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    /**
     * The hard links added to objects of the file, less those of theirs deleted, change their hard-link
     * counts (kept at least 1: an object no link reaches stays in the file, as unused space).
     */
    private void adjustReferenceCounts(Map<ObjectSpec, String> objects) {
        Map<Long, Integer> change = new LinkedHashMap<>();
        for (ObjectSpec spec : objects.keySet()) {
            if (!(spec instanceof GroupSpec group)) {
                continue;
            }
            for (LinkSpec link : group.links) {
                if (link.address() != UNDEFINED) {
                    change.merge(link.address(), 1, Integer::sum);
                }
            }
            if (group.inFile()) {
                for (String name : group.deletedLinks) {
                    if (((Group) group.object).link(name).orElse(null) instanceof Link.Hard hard) {
                        change.merge(hard.objectHeaderAddress(), -1, Integer::sum);
                    }
                }
            }
        }
        for (Map.Entry<Long, Integer> entry : change.entrySet()) {
            if (entry.getValue() != 0) {
                ObjectHeaderEditor editor = editor(entry.getKey());
                editor.setReferenceCount(Math.max(1, editor.referenceCount() + entry.getValue()));
            }
        }
    }

    /**
     * The object at absolute path {@code path} as the session leaves the file (its links added, moved and
     * deleted), followed through hard links: one of the session's (added, or opened from the file), or the
     * header address of one of the file's not opened; null if there is none.
     */
    private Object resolve(String path) {
        if (!path.startsWith("/")) {
            return null;
        }
        Object node = root;
        for (String name : path.split("/")) {
            if (!name.isEmpty() && !name.equals(".")) {
                node = child(node, name);
                if (node == null) {
                    return null;
                }
            }
        }
        return node;
    }

    /** The object a group (one of the session's, or the file's at an address) links to as {@code name}, or null. */
    private Object child(Object node, String name) {
        if (node instanceof Long address) {
            ObjectSpec spec = opened.get(address);
            if (spec == null) {
                if (!(existing.object("", "", address) instanceof Group group)) {
                    return null;
                }
                return group.link(name).orElse(null) instanceof Link.Hard hard ? fileNode(hard.objectHeaderAddress()) : null;
            }
            node = spec;
        }
        if (!(node instanceof GroupSpec group)) {
            return null;
        }
        for (GroupSpec subgroup : group.groups) {
            if (subgroup.name.equals(name)) {
                return subgroup;
            }
        }
        for (DatasetSpec dataset : group.datasets) {
            if (dataset.name.equals(name)) {
                return dataset;
            }
        }
        for (LinkSpec link : group.links) {
            if (link.name().equals(name)) {
                return link.object() != null ? link.object() : link.address() != UNDEFINED ? fileNode(link.address()) : null;
            }
        }
        if (group.hasFileLink(name) && ((Group) group.object).link(name).orElse(null) instanceof Link.Hard hard) {
            return fileNode(hard.objectHeaderAddress());
        }
        return null;
    }

    /**
     * Where {@code spec} is now, an absolute path ("/" for the root): through the session's links, or for an
     * object of the file opened but reached by none of them, as the file named it.
     *
     * @throws IllegalArgumentException if no link reaches it any more
     */
    private String pathOf(ObjectSpec spec) {
        String path = objects().get(spec);
        if (path == null) {
            throw new IllegalArgumentException("the group is no longer in the file: its link was deleted");
        }
        return path;
    }

    /** The file's object at {@code address}: its spec if opened, else its address. */
    private Object fileNode(long address) {
        ObjectSpec spec = opened.get(address);
        return spec != null ? spec : (Object) address;
    }

    /** A hard link {@code name} to {@code target} (one of the session's objects, or of the file's). */
    private static LinkSpec hardLinkTo(String name, Object target) {
        if (target instanceof ObjectSpec spec && !spec.inFile()) {
            spec.hardLinks++;
            return new LinkSpec(name, null, null, spec, UNDEFINED);
        }
        return new LinkSpec(name, null, null, null, target instanceof ObjectSpec spec ? spec.address : (Long) target);
    }

    /** The group at {@code node} (opened if it is one of the file's not opened yet), or null if it is not a group. */
    private GroupSpec groupAt(Object node, String path) {
        if (node instanceof GroupSpec group) {
            return group;
        }
        if (node instanceof Long address && existing.object("", path, address) instanceof Group group) {
            return openGroup(group, path.substring(path.lastIndexOf('/') + 1));
        }
        return null;
    }

    /** A group of the file, opened to change it. */
    private GroupSpec openGroup(Group group, String name) {
        GroupSpec spec = new GroupSpec();
        spec.name = name;
        spec.address = group.objectHeaderAddress();
        spec.object = group;
        opened.put(spec.address, spec);
        return spec;
    }

    /**
     * The object header address of the object at absolute path {@code path} as the session leaves the file,
     * once the metadata is laid out.
     */
    private long addressOf(String path) {
        Object target = resolve(path);
        if (target instanceof ObjectSpec spec) {
            if (spec.inFile()) {
                return spec.address;
            }
            GroupResult result = laidOut.get(spec);
            if (result != null) {
                return result.headerAddress();
            }
        } else if (target instanceof Long address) {
            return address;
        }
        throw new IllegalArgumentException("reference target does not exist: " + path);
    }

    /** True if object references wait for the metadata's layout (see {@link #complete}). */
    private static boolean waitsForAddresses(Map<ObjectSpec, String> objects) {
        for (ObjectSpec spec : objects.keySet()) {
            for (AttributeSpec attribute : spec.attributes) {
                if (!attribute.refs().isEmpty()) {
                    return true;
                }
            }
            if (spec instanceof DatasetSpec dataset && dataset.storage != null && dataset.storage.waitsForAddresses()) {
                return true;
            }
        }
        return false;
    }

    /** Adds the targets of the object references that wait for the layout. */
    private static void heldTargets(Map<ObjectSpec, String> objects, Set<String> targets) {
        for (ObjectSpec spec : objects.keySet()) {
            if (spec instanceof DatasetSpec dataset && dataset.storage != null) {
                dataset.storage.heldTargets(targets);
            }
            for (AttributeSpec attribute : spec.attributes) {
                for (ValueEncoder.RefPatch ref : attribute.refs()) {
                    targets.add(ref.path());
                }
            }
        }
    }

    /** Fills in the object references that waited, and writes the chunks that waited for them. */
    private void writeHeld(Map<ObjectSpec, String> objects) {
        for (ObjectSpec spec : objects.keySet()) {
            if (spec instanceof DatasetSpec dataset && dataset.storage != null) {
                dataset.storage.writeHeld();
            }
            for (AttributeSpec attribute : spec.attributes) {
                for (ValueEncoder.RefPatch ref : attribute.refs()) {
                    putU64(attribute.data(), (int) ref.offset(), addressOf(ref.path()));
                }
            }
        }
    }

    /** Writes, for every dataset written, the data still to write, and fixes its layout. */
    private void finishData(Map<ObjectSpec, String> objects) {
        for (Map.Entry<ObjectSpec, String> entry : objects.entrySet()) {
            if (entry.getKey() instanceof DatasetSpec dataset && (!dataset.inFile() || dataset.storage != null)) {
                storage(dataset, entry.getValue()).finish(); // an existing dataset's data written or not
            }
        }
    }

    /** Fills in the variable-length ids in attribute values, now that every heap collection is placed. */
    private void resolveAttributeIds(Map<ObjectSpec, String> objects) {
        for (ObjectSpec spec : objects.keySet()) {
            for (AttributeSpec attribute : spec.attributes) {
                for (ValueEncoder.IdPatch id : attribute.ids()) {
                    putU64(attribute.data(), (int) id.offset(), heaps.address(id.collection()));
                }
            }
        }
    }

    private static void putU64(byte[] out, int at, long value) {
        for (int b = 0; b < 8; b++) {
            out[at + b] = (byte) (value >>> (8 * b));
        }
    }

    /** An object reference at file offset {@code position}, to the object at {@code targetPath}. */
    private record FileReference(long position, String targetPath) {
    }

    // --------------------------------------------------------------- API handles

    /** Builds a group: datasets, subgroups, and attributes. */
    public static final class GroupWriter {
        private final Hdf5Writer writer;
        private final GroupSpec spec;
        private final String path;
        private final boolean legacy;
        private final Lifecycle lifecycle;

        private GroupWriter(Hdf5Writer writer, GroupSpec spec, String path, boolean legacy, Lifecycle lifecycle) {
            this.writer = writer;
            this.spec = spec;
            this.path = path;
            this.legacy = legacy;
            this.lifecycle = lifecycle;
        }

        /**
         * A dataset of any datatype and shape, whose data is written afterwards with
         * {@link DatasetWriter#write}, {@link DatasetWriter#append} and their variants, and streamed to the
         * file as it is written. Configure it first: its {@linkplain DatasetWriter#chunked chunk shape},
         * {@linkplain DatasetWriter#maxShape maximum shape} (to grow it), filters, and fill value. Elements
         * never written read as the fill value.
         *
         * <pre>{@code
         * DatasetWriter images = w.createDataset("images", Datatype.uint16(), 0, 512, 512)
         *         .chunked(1, 512, 512).maxShape(Hdf5Writer.UNLIMITED, 512, 512).deflate(4);
         * for (short[] image : source) {
         *     images.append(image);                      // one 512 x 512 image at a time
         * }
         * }</pre>
         *
         * @param name  the dataset's name in this group
         * @param type  its datatype
         * @param shape its dimensions now (none for a scalar), which it may grow within its
         *              {@linkplain DatasetWriter#maxShape maximum shape}
         * @return the dataset's writer
         * @throws IllegalArgumentException if a dimension is negative, or the type cannot be written
         * @throws HdfUnsupportedException for a type the format does not hold (complex in the earliest format)
         */
        public DatasetWriter createDataset(String name, Datatype type, long... shape) {
            lifecycle.check();
            java.util.Objects.requireNonNull(type, "type");
            for (long d : shape) {
                if (d < 0) {
                    throw new IllegalArgumentException("dimensions must not be negative: " + java.util.Arrays.toString(shape));
                }
            }
            if (legacy) {
                requireUsualUnusedBits(type, "dataset '" + name + "'");
            }
            byte[] datatype = DatatypeEncoder.encode(type, legacy);
            requireMessageSize(datatype.length, "the datatype of '" + name + "'");
            DatasetSpec dataset = new DatasetSpec(name, datatype, type.size(), shape.clone(), null, null, null);
            dataset.type = type;
            return addDataset(dataset);
        }

        /**
         * A dataset of region references: each points at a region of a dataset in this file, by its path
         * (which may be added before or after this dataset), or is {@code null}.
         *
         * @param name    the dataset's name in this group
         * @param shape   its dimensions; their product must be {@code regions.length}
         * @param regions each element's region, or {@code null}; row-major
         * @return the dataset's writer, its data written
         */
        public DatasetWriter regionReferenceDataset(String name, long[] shape, Region[] regions) {
            return createDataset(name, Datatype.regionReference(), shape).write(regions);
        }

        /**
         * A soft link: a name that stands for the object at {@code targetPath} in this file (absolute, or
         * relative to this group), which need not exist.
         *
         * @param name       the link's name in this group
         * @param targetPath the path it stands for: absolute, or relative to this group
         * @return this group's writer
         */
        public GroupWriter softLink(String name, String targetPath) {
            lifecycle.check();
            requireName(targetPath, "soft link target");
            claimLinkName(spec, name);
            spec.links.add(new LinkSpec(name, targetPath, null, null, UNDEFINED));
            return this;
        }

        /**
         * An external link: a name that stands for the object at {@code objectPath} in the file
         * {@code fileName} (relative names are found next to this file), which need not exist. A group of the
         * original format (a symbol table, which holds hard and soft links only) is written in the new one
         * instead, as libhdf5 converts it: link messages, in its version-1 object header; HDF5 1.8 and later
         * read it.
         *
         * @param name       the link's name in this group
         * @param fileName   the file the object is in (a relative name is found next to this file)
         * @param objectPath the object's path in that file
         * @return this group's writer
         */
        public GroupWriter externalLink(String name, String fileName, String objectPath) {
            lifecycle.check();
            requireName(fileName, "external link file");
            requireName(objectPath, "external link object");
            claimLinkName(spec, name);
            spec.links.add(new LinkSpec(name, objectPath, fileName, null, UNDEFINED));
            return this;
        }

        /**
         * A hard link: another name for the object at {@code targetPath}, an absolute path followed through
         * hard links, which must exist now (an object added in this session, or one of the file being changed),
         * as libhdf5's {@code H5Lcreate_hard} requires. The object is then reached by either name, and its
         * hard-link count is one more; deleting one name leaves it to the other.
         *
         * @param name       the new link's name in this group
         * @param targetPath the object's absolute path, followed through hard links
         * @return this group's writer
         * @throws IllegalArgumentException if the name is taken or invalid, or nothing is at {@code targetPath}
         */
        public GroupWriter hardLink(String name, String targetPath) {
            lifecycle.check();
            requireName(targetPath, "hard link target");
            Object target = writer.resolve(targetPath);
            if (target == null) {
                throw new IllegalArgumentException("no object at '" + targetPath + "' for hard link '" + name
                        + "' (an absolute path, followed through hard links)");
            }
            claimLinkName(spec, name);
            spec.links.add(hardLinkTo(name, target));
            return this;
        }

        /**
         * Moves a link to {@code newPath} ({@code H5Lmove}): the link {@code name} of this group, or at a path
         * (absolute, or relative to this group) through hard links. {@code newPath} is absolute, or relative to
         * this group; its last component is the link's new name, in the group the rest of it names, which must
         * exist. Renaming a link is moving it within its group. The object the link leads to is not changed:
         * its other links, and references to it, still reach it; it moves with its own links (a group's
         * subgroups go with it). A link moved into a group of the original format that cannot hold it (an
         * external link) converts that group, as {@link #externalLink} does.
         *
         * <p>Paths are resolved as the session leaves the file: references by path, resolved on
         * {@link Hdf5Writer#close()}, name an object's new place.
         *
         * @param name    the link to move: its name in this group, or its path (absolute, or relative to this
         *                group)
         * @param newPath its new path (absolute, or relative to this group), whose last component is its new name
         * @return this group's writer
         * @throws IllegalArgumentException if there is no such link, the new name is taken or invalid, there is
         *         no group to move it into, or a group would move into itself
         * @throws HdfUnsupportedException for a link of a user-defined type
         */
        public GroupWriter move(String name, String newPath) {
            lifecycle.check();
            requireName(name, "link");
            requireName(newPath, "new path");
            String here = writer.pathOf(spec);
            String prefix = here.equals("/") ? "/" : here + "/";
            String source = name.startsWith("/") ? name : prefix + name;
            String target = newPath.startsWith("/") ? newPath : prefix + newPath;
            if (target.equals(source)) {
                return this;
            }
            if (target.startsWith(source + "/")) {
                throw new IllegalArgumentException("cannot move " + source + " into itself (" + target + ")");
            }
            int cut = source.lastIndexOf('/');
            String from = cut == 0 ? "/" : source.substring(0, cut);
            String link = source.substring(cut + 1);
            GroupSpec group = writer.groupAt(writer.resolve(from), from);
            int slash = target.lastIndexOf('/');
            String newName = target.substring(slash + 1);
            String parentPath = slash == 0 ? "/" : target.substring(0, slash);
            GroupSpec destination = writer.groupAt(writer.resolve(parentPath), parentPath);
            if (group == null) {
                throw new IllegalArgumentException("no group at " + from + " to move " + link + " from");
            }
            if (destination == null) {
                throw new IllegalArgumentException("no group at " + parentPath + " to move " + source + " into");
            }
            if (group.linkNames.contains(link)) {
                claimLinkName(destination, newName);
                group.linkNames.remove(link);
                for (GroupSpec subgroup : new ArrayList<>(group.groups)) {
                    if (!subgroup.inFile() && subgroup.name.equals(link)) {
                        group.groups.remove(subgroup);
                        subgroup.name = newName;
                        destination.groups.add(subgroup);
                    }
                }
                for (DatasetSpec dataset : new ArrayList<>(group.datasets)) {
                    if (!dataset.inFile() && dataset.name.equals(link)) {
                        group.datasets.remove(dataset);
                        dataset.name = newName;
                        destination.datasets.add(dataset);
                    }
                }
                for (LinkSpec added : new ArrayList<>(group.links)) {
                    if (added.name().equals(link)) {
                        group.links.remove(added);
                        destination.links.add(new LinkSpec(newName, added.target(), added.file(), added.object(), added.address()));
                    }
                }
            } else if (group.hasFileLink(link)) {
                LinkSpec moved = switch (((Group) group.object).link(link).orElseThrow()) {
                    case Link.Hard hard -> new LinkSpec(newName, null, null, null, hard.objectHeaderAddress());
                    case Link.Soft soft -> new LinkSpec(newName, soft.targetPath(), null, null, UNDEFINED);
                    case Link.External external -> new LinkSpec(newName, external.objectPath(), external.fileName(), null, UNDEFINED);
                    case Link.UserDefined user -> throw new HdfUnsupportedException("link " + source
                            + " is of a user-defined type (" + user.type() + "), which Falcon does not move");
                };
                claimLinkName(destination, newName);
                group.deletedLinks.add(link);
                group.groups.removeIf(g -> g.inFile() && g.name.equals(link)); // opened: still written, however reached
                group.datasets.removeIf(d -> d.inFile() && d.name.equals(link));
                destination.links.add(moved);
            } else {
                throw new IllegalArgumentException("no link " + source + " to move");
            }
            return this;
        }

        /**
         * An attribute of any datatype: {@code values} as {@link DatasetWriter#write} takes them, for
         * {@code shape} elements ({@code new long[0]} for a scalar). An object or region reference's
         * target may be added before or after it.
         *
         * @param name   the attribute's name; one the group has in a file being changed is replaced
         * @param type   its datatype
         * @param shape  its dimensions ({@code new long[0]} for a scalar)
         * @param values its values, as {@link DatasetWriter#write} takes them
         * @return this group's writer
         * @throws IllegalArgumentException if the values do not fit the type, or the attribute needs more
         *         than 64 KiB
         */
        public GroupWriter attribute(String name, Datatype type, long[] shape, Object values) {
            lifecycle.check();
            writer.requireUsualAttributeType(spec, name, type);
            addAttribute(spec, writer.typedAttribute(name, type, shape, values), legacy);
            return this;
        }

        /**
         * A scalar string attribute ({@code units}, a CF convention, ...): fixed-length, UTF-8.
         *
         * @param name  the attribute's name; one the group has in a file being changed is replaced
         * @param value its value
         * @return this group's writer
         */
        public GroupWriter stringAttribute(String name, String value) {
            return attribute(name, stringType(value), new long[0], new String[] {value});
        }

        /**
         * A signed 32-bit integer dataset.
         *
         * @param name  the dataset's name in this group
         * @param data  its values, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter intDataset(String name, int[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, 4, shape, null, intBytes(data), null));
        }

        /**
         * A 64-bit floating-point dataset.
         *
         * @param name  the dataset's name in this group
         * @param data  its values, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter doubleDataset(String name, double[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, 8, shape, null, doubleBytes(data), null));
        }

        /**
         * A signed 8-bit integer dataset.
         *
         * @param name  the dataset's name in this group
         * @param data  its values, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter byteDataset(String name, byte[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT8, 1, shape, null, data.clone(), null));
        }

        /**
         * A signed 16-bit integer dataset.
         *
         * @param name  the dataset's name in this group
         * @param data  its values, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter shortDataset(String name, short[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT16, 2, shape, null, shortBytes(data), null));
        }

        /**
         * A signed 64-bit integer dataset.
         *
         * @param name  the dataset's name in this group
         * @param data  its values, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter longDataset(String name, long[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT64, 8, shape, null, longBytes(data), null));
        }

        /**
         * A 32-bit floating-point dataset.
         *
         * @param name  the dataset's name in this group
         * @param data  its values, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter floatDataset(String name, float[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT32, 4, shape, null, float32Bytes(data), null));
        }

        /**
         * A fixed-length string dataset. Each element is stored, UTF-8 encoded, in {@code length} bytes
         * (the longest string's byte length if not given), null-padded. The datatype's character set is
         * UTF-8 if any string is non-ASCII, else ASCII.
         *
         * @param name  the dataset's name in this group
         * @param data  its strings, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape) {
            lifecycle.check();
            int length = 1;
            for (String s : data) {
                length = Math.max(length, s.getBytes(StandardCharsets.UTF_8).length);
            }
            return fixedStringDataset(name, data, shape, length);
        }

        /**
         * A fixed-length string dataset with an explicit per-element byte {@code length}. A string whose
         * UTF-8 encoding is longer is truncated at the last whole character that fits.
         *
         * @param name   the dataset's name in this group
         * @param data   its strings, row-major
         * @param shape  its dimensions; their product must be {@code data.length}
         * @param length each element's size in bytes, at least 1
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape, int length) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            if (length < 1) {
                throw new IllegalArgumentException("fixed-length strings need at least 1 byte, not " + length);
            }
            byte[] bytes = new byte[Math.multiplyExact(data.length, length)];
            boolean utf8 = false;
            for (int i = 0; i < data.length; i++) {
                byte[] s = data[i].getBytes(StandardCharsets.UTF_8);
                int n = Math.min(s.length, length);
                while (n < s.length && n > 0 && (s[n] & 0xC0) == 0x80) {
                    n--; // never split a multi-byte character
                }
                System.arraycopy(s, 0, bytes, i * length, n);
                for (int b = 0; b < n; b++) {
                    utf8 |= s[b] < 0;
                }
            }
            return addDataset(new DatasetSpec(name, fixedStringDatatype(length, utf8), length, shape, null, bytes, null));
        }

        /**
         * A chunked {@code int32} dataset (fixed-array index).
         *
         * @param name       the dataset's name in this group
         * @param data       its values, row-major
         * @param shape      its dimensions; their product must be {@code data.length}
         * @param chunkShape its chunks' dimensions: the same rank, each at least 1
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter intChunkedDataset(String name, int[] data, long[] shape, long[] chunkShape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            requireChunkShape(shape, chunkShape, 4);
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, 4, shape, chunkShape.clone(), intBytes(data), null));
        }

        /**
         * A chunked {@code float64} dataset (fixed-array index).
         *
         * @param name       the dataset's name in this group
         * @param data       its values, row-major
         * @param shape      its dimensions; their product must be {@code data.length}
         * @param chunkShape its chunks' dimensions: the same rank, each at least 1
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter doubleChunkedDataset(String name, double[] data, long[] shape, long[] chunkShape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            requireChunkShape(shape, chunkShape, 8);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, 8, shape, chunkShape.clone(), doubleBytes(data), null));
        }

        /**
         * A variable-length UTF-8 string dataset (values stored in a global heap).
         *
         * @param name  the dataset's name in this group
         * @param data  its strings, row-major
         * @param shape its dimensions; their product must be {@code data.length}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter stringDataset(String name, String[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            List<byte[]> bytes = new ArrayList<>();
            for (String s : data) {
                bytes.add(s.getBytes(StandardCharsets.UTF_8));
            }
            return addDataset(new DatasetSpec(name, DATATYPE_VLEN_STRING, 16, shape, null, null, bytes));
        }

        /**
         * A compound (record) dataset. Each {@link CompoundField} supplies one named, typed column;
         * fields are packed in order (no alignment gaps) and every column must have one value per
         * element.
         *
         * @param name   the dataset's name in this group
         * @param shape  its dimensions; their product must be each field's count of values
         * @param fields its members, in order
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter compoundDataset(String name, long[] shape, CompoundField... fields) {
            lifecycle.check();
            long count = elementCount(shape);
            int recordSize = 0;
            int[] offsets = new int[fields.length];
            Set<String> names = new HashSet<>();
            for (int i = 0; i < fields.length; i++) {
                if (!names.add(fields[i].name)) {
                    throw new IllegalArgumentException("duplicate compound field name \"" + fields[i].name + "\"");
                }
                if (fields[i].count != count) {
                    throw new IllegalArgumentException("compound field '" + fields[i].name + "' has "
                            + fields[i].count + " values but the shape implies " + count);
                }
                offsets[i] = recordSize;
                recordSize += fields[i].size;
            }
            byte[] data = new byte[Math.toIntExact(count * recordSize)];
            for (int r = 0; r < count; r++) {
                for (int i = 0; i < fields.length; i++) {
                    System.arraycopy(fields[i].column, r * fields[i].size,
                            data, r * recordSize + offsets[i], fields[i].size);
                }
            }
            byte[] datatype = compoundDatatype(fields, offsets, recordSize, legacy);
            requireMessageSize(datatype.length, "the compound datatype of '" + name + "'");
            return addDataset(new DatasetSpec(name, datatype, recordSize, shape, null, data, null));
        }

        /**
         * An enumerated dataset over a 32-bit base type: each value must be one of {@code type}'s codes.
         *
         * @param name   the dataset's name in this group
         * @param shape  its dimensions; their product must be {@code values.length}
         * @param type   the enumeration
         * @param values each element's code, row-major
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter enumDataset(String name, long[] shape, EnumType type, int[] values) {
            lifecycle.check();
            requireElementCount(shape, values.length);
            byte[] datatype = enumDatatype(type, legacy);
            requireMessageSize(datatype.length, "the enum datatype of '" + name + "'");
            return addDataset(new DatasetSpec(name, datatype, 4, shape, null, intBytes(values), null));
        }

        /**
         * A dataset whose every element is a fixed-shape {@code float32} array. {@code data} holds all
         * elements' sub-arrays concatenated row-major (element count &times; {@code prod(arrayDims)} values).
         *
         * @param name      the dataset's name in this group
         * @param shape     its dimensions
         * @param arrayDims each element's array dimensions
         * @param data      every element's array, one after another, row-major
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter float32ArrayDataset(String name, long[] shape, int[] arrayDims, float[] data) {
            lifecycle.check();
            int perElement = product(arrayDims);
            requireArrayData(shape, perElement, data.length);
            return addDataset(new DatasetSpec(name, arrayDatatype(arrayDims, 4, DATATYPE_FLOAT32, legacy),
                    perElement * 4, shape, null, float32Bytes(data), null));
        }

        /**
         * A dataset whose every element is a fixed-shape {@code int32} array (see {@link #float32ArrayDataset}).
         *
         * @param name      the dataset's name in this group
         * @param shape     its dimensions
         * @param arrayDims each element's array dimensions
         * @param data      every element's array, one after another, row-major
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter int32ArrayDataset(String name, long[] shape, int[] arrayDims, int[] data) {
            lifecycle.check();
            int perElement = product(arrayDims);
            requireArrayData(shape, perElement, data.length);
            return addDataset(new DatasetSpec(name, arrayDatatype(arrayDims, 4, DATATYPE_INT32, legacy),
                    perElement * 4, shape, null, intBytes(data), null));
        }

        /**
         * A native complex-number dataset (128-bit: {@code float64} real and imaginary parts).
         *
         * @param name      the dataset's name in this group
         * @param shape     its dimensions; their product must be {@code real.length}
         * @param real      each element's real part, row-major
         * @param imaginary each element's imaginary part, as many as {@code real}
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter complexDataset(String name, long[] shape, double[] real, double[] imaginary) {
            lifecycle.check();
            requireElementCount(shape, real.length);
            if (imaginary.length != real.length) {
                throw new IllegalArgumentException("real and imaginary parts differ in length");
            }
            byte[] data = new byte[real.length * 16];
            for (int i = 0; i < real.length; i++) {
                putDoubleLittleEndian(data, i * 16, real[i]);
                putDoubleLittleEndian(data, i * 16 + 8, imaginary[i]);
            }
            return addDataset(new DatasetSpec(name, complex128Datatype(), 16, shape, null, data, null));
        }

        /**
         * A variable-length {@code int32} sequence (ragged array) dataset; {@code rows[i]} is element i.
         *
         * @param name  the dataset's name in this group
         * @param shape its dimensions; their product must be {@code rows.length}
         * @param rows  each element's values, row-major; rows may differ in length
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter intSequenceDataset(String name, long[] shape, int[][] rows) {
            lifecycle.check();
            requireElementCount(shape, rows.length);
            List<byte[]> payloads = new ArrayList<>();
            int[] counts = new int[rows.length];
            for (int i = 0; i < rows.length; i++) {
                payloads.add(intBytes(rows[i]));
                counts[i] = rows[i].length;
            }
            return addVlenSequence(name, shape, DATATYPE_INT32, payloads, counts);
        }

        /**
         * A variable-length {@code float64} sequence (ragged array) dataset; {@code rows[i]} is element i.
         *
         * @param name  the dataset's name in this group
         * @param shape its dimensions; their product must be {@code rows.length}
         * @param rows  each element's values, row-major; rows may differ in length
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter doubleSequenceDataset(String name, long[] shape, double[][] rows) {
            lifecycle.check();
            requireElementCount(shape, rows.length);
            List<byte[]> payloads = new ArrayList<>();
            int[] counts = new int[rows.length];
            for (int i = 0; i < rows.length; i++) {
                payloads.add(doubleBytes(rows[i]));
                counts[i] = rows[i].length;
            }
            return addVlenSequence(name, shape, DATATYPE_FLOAT64, payloads, counts);
        }

        private DatasetWriter addVlenSequence(String name, long[] shape, byte[] base,
                                              List<byte[]> payloads, int[] counts) {
            DatasetSpec spec = new DatasetSpec(name, vlenSequenceDatatype(base), 16, shape, null, null, payloads);
            spec.vlenElementCounts = counts;
            return addDataset(spec);
        }

        /**
         * An object-reference dataset. Each target is an absolute path ({@code "/name"} or
         * {@code "/group/name"}) to another object in this file, or {@code null} for a null reference.
         * Targets may be defined before or after this dataset; addresses are resolved when the file is
         * written (an unresolved target path is an error).
         *
         * @param name    the dataset's name in this group
         * @param shape   its dimensions; their product must be {@code targets.length}
         * @param targets each element's target, an absolute path, or {@code null}; row-major
         * @return the dataset's writer, to configure it before the next dataset or group is added
         */
        public DatasetWriter referenceDataset(String name, long[] shape, String[] targets) {
            lifecycle.check();
            requireElementCount(shape, targets.length);
            DatasetSpec spec = new DatasetSpec(name, DATATYPE_OBJECT_REFERENCE, 8, shape, null,
                    new byte[targets.length * 8], null);
            spec.referenceTargets = java.util.Arrays.asList(targets);
            return addDataset(spec);
        }

        /**
         * A subgroup. Link names (of groups and datasets) must be non-empty, unique within their group,
         * and not {@code "."}, and may not contain {@code '/'} or NUL. In a file being changed
         * ({@link Hdf5Writer#open}), a group this one already holds (by a hard link) is opened, to add to it
         * and change it.
         *
         * @param name the subgroup's name
         * @return the subgroup's writer
         * @throws IllegalArgumentException if the name is taken by a dataset or link, or is invalid
         */
        public GroupWriter group(String name) {
            lifecycle.check();
            Object child = writer.child(spec, name);
            if (child instanceof GroupSpec group && group.inFile()) {
                return new GroupWriter(writer, group, path + "/" + name, legacy, lifecycle); // opened already
            }
            if (child instanceof Long address) {
                // A group of the file being changed: opened, to add to it and change it.
                if (!(writer.existing.object(display(path), name, address) instanceof Group group)) {
                    throw new IllegalArgumentException("'" + name + "' in " + display(path) + " is not a group");
                }
                GroupSpec opened = writer.openGroup(group, name);
                if (spec.hasFileLink(name)) {
                    spec.groups.add(opened); // the group's own link to it
                }
                return new GroupWriter(writer, opened, path + "/" + name, legacy, lifecycle);
            }
            if (spec.hasFileLink(name)) {
                writer.fileObject(spec, name, path); // not a hard link: refused, naming it
            }
            claimLinkName(spec, name);
            writer.writeGivenData();
            GroupSpec added = new GroupSpec();
            added.name = name;
            spec.groups.add(added);
            return new GroupWriter(writer, added, path + "/" + name, legacy, lifecycle);
        }

        /**
         * The dataset {@code name} of this group: one added to it, or, in a file being changed
         * ({@link Hdf5Writer#open}), one it holds, to write its data ({@link DatasetWriter#write},
         * {@link DatasetWriter#append}, ...) and set or delete its attributes. A dataset of the file keeps its
         * datatype, shape limits, layout, and filters: it grows only within its maximum shape, and its chunks
         * are written with its filters, as libhdf5 would encode them (deflate, shuffle, fletcher32, szip,
         * n-bit, scale-offset, and with their plugins LZF, Blosc, LZ4, bitshuffle and Zstandard; no other
         * third-party filter), its partial edge chunks unfiltered if it keeps them
         * so. Data in external raw files is written there. A virtual dataset's elements are written into its
         * sources (P2 WF11), as libhdf5's {@code H5Dwrite} writes them: each into the source element its
         * mapping pairs it with, in this file or another (changed in a session of its own, completed when
         * this writer closes). A write must cover only elements a mapping whose source exists covers, each
         * once, or it is refused before anything is written.
         *
         * @param name the dataset's name in this group
         * @return the dataset's writer
         * @throws IllegalArgumentException if the group has no dataset of that name (a soft or external link
         *         is not followed: open the dataset where it is)
         */
        public DatasetWriter dataset(String name) {
            lifecycle.check();
            Object child = writer.child(spec, name);
            if (child instanceof DatasetSpec dataset) {
                return new DatasetWriter(writer, dataset, path + "/" + name, legacy, lifecycle);
            }
            if (child instanceof Long address && writer.existing.object(display(path), name, address) instanceof Dataset dataset) {
                DatasetSpec opened = writer.existingDataset(name, dataset);
                writer.opened.put(opened.address, opened);
                if (spec.hasFileLink(name)) {
                    spec.datasets.add(opened); // the group's own link to it
                }
                return new DatasetWriter(writer, opened, path + "/" + name, legacy, lifecycle);
            }
            if (child == null && spec.hasFileLink(name)) {
                writer.fileObject(spec, name, path); // not a hard link: refused, naming it
            }
            throw new IllegalArgumentException("no dataset named \"" + name + "\" in " + display(path));
        }

        /**
         * Deletes the link {@code name}: one added in this session (with what it holds), or, in a file being
         * changed, one the group holds. The object it led to stays in the file, unreachable unless another
         * link leads to it (its hard-link count is lowered); a new link may then take the name.
         *
         * @param name the link's name in this group
         * @return this group's writer
         * @throws IllegalArgumentException if the group has no link of that name
         */
        public GroupWriter delete(String name) {
            lifecycle.check();
            if (spec.linkNames.remove(name)) {
                for (DatasetSpec dataset : spec.datasets) {
                    if (!dataset.inFile() && dataset.name.equals(name) && writer.unwritten == dataset && dataset.hardLinks == 0) {
                        writer.unwritten = null; // no other link reaches it: its data is not written
                    }
                }
                spec.groups.removeIf(g -> !g.inFile() && g.name.equals(name)); // written if a hard link reaches it
                spec.datasets.removeIf(d -> !d.inFile() && d.name.equals(name));
                for (LinkSpec link : spec.links) {
                    if (link.name().equals(name) && link.object() != null) {
                        link.object().hardLinks--;
                    }
                }
                spec.links.removeIf(l -> l.name().equals(name));
            } else if (spec.hasFileLink(name)) {
                spec.deletedLinks.add(name);
                spec.groups.removeIf(g -> g.inFile() && g.name.equals(name)); // opened: still written, however reached
                spec.datasets.removeIf(d -> d.inFile() && d.name.equals(name));
            } else {
                throw new IllegalArgumentException("no link named \"" + name + "\" in " + display(path));
            }
            return this;
        }

        /**
         * Deletes the attribute {@code name}: one added in this session, or one the group has in a file being
         * changed.
         *
         * @param name the attribute's name
         * @return this group's writer
         * @throws IllegalArgumentException if the group has no attribute of that name
         */
        public GroupWriter deleteAttribute(String name) {
            lifecycle.check();
            Hdf5Writer.deleteAttribute(spec, name, path);
            return this;
        }

        /**
         * A signed 32-bit integer attribute.
         *
         * @param name  the attribute's name; one the group has in a file being changed is replaced
         * @param data  its values, row-major
         * @param shape its dimensions ({@code new long[0]} for a scalar); their product must be {@code data.length}
         * @return this group's writer
         */
        public GroupWriter intAttribute(String name, int[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec, new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)), legacy);
            return this;
        }

        /**
         * A 64-bit floating-point attribute.
         *
         * @param name  the attribute's name; one the group has in a file being changed is replaced
         * @param data  its values, row-major
         * @param shape its dimensions ({@code new long[0]} for a scalar); their product must be {@code data.length}
         * @return this group's writer
         */
        public GroupWriter doubleAttribute(String name, double[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec, new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)), legacy);
            return this;
        }

        private DatasetWriter addDataset(DatasetSpec dataset) {
            claimLinkName(spec, dataset.name);
            writer.writeGivenData();
            spec.datasets.add(dataset);
            String datasetPath = path + "/" + dataset.name;
            if (dataset.type == null) {
                writer.unwritten = dataset;
                writer.unwrittenPath = datasetPath;
            }
            return new DatasetWriter(writer, dataset, datasetPath, legacy, lifecycle);
        }

        private void requireChunkShape(long[] shape, long[] chunkShape, int elementSize) {
            checkChunkShape(shape, chunkShape, elementSize);
        }
    }

    /** Checks a chunk shape against a dataset's shape: the same rank, each dimension at least 1, under 2 GiB. */
    private static void checkChunkShape(long[] shape, long[] chunkShape, int elementSize) {
        {
            if (chunkShape == null || chunkShape.length != shape.length) {
                throw new IllegalArgumentException("chunk shape " + java.util.Arrays.toString(chunkShape)
                        + " must have the dataset's rank " + shape.length);
            }
            if (shape.length == 0) {
                throw new IllegalArgumentException("a scalar dataset cannot be chunked");
            }
            long bytes = elementSize;
            for (long c : chunkShape) {
                if (c < 1) {
                    throw new IllegalArgumentException("chunk dimensions must be at least 1: "
                            + java.util.Arrays.toString(chunkShape));
                }
                bytes = c > Integer.MAX_VALUE ? Long.MAX_VALUE : Math.min(Long.MAX_VALUE / 2, bytes * c);
            }
            if (bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("a chunk of " + java.util.Arrays.toString(chunkShape)
                        + " elements exceeds 2 GiB");
            }
        }
    }

    /** Validates a link name and reserves it in {@code group}; see {@link GroupWriter#group}. */
    private static void claimLinkName(GroupSpec group, String name) {
        requireName(name, "link");
        if (name.equals(".") || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("a link name may not be \".\" or contain '/': \"" + name + "\"");
        }
        if (group.hasFileLink(name) || !group.linkNames.add(name)) {
            throw new IllegalArgumentException("this group already has a link named \"" + name + "\"");
        }
    }

    private static void requireName(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(what + " name must not be empty");
        }
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " name must not contain NUL: \"" + name.replace('\0', '?') + "\"");
        }
    }

    /**
     * Adds an attribute after checking its name (non-empty, no NUL, unique on its object) and its size:
     * its header message must stay under 64 KiB, the limit of an object-header message and of a managed
     * object in dense storage.
     */
    private static void addAttribute(ObjectSpec spec, AttributeSpec attribute, boolean legacy) {
        requireName(attribute.name(), "attribute");
        int size = attributeBody(attribute, legacy).length;
        if (size > MAX_ATTRIBUTE_MESSAGE) {
            throw new IllegalArgumentException("attribute '" + attribute.name() + "' needs a " + size
                    + "-byte header message; at most " + MAX_ATTRIBUTE_MESSAGE + " bytes fit (store large values in a dataset)");
        }
        if (!spec.attributeNames.add(attribute.name())) {
            throw new IllegalArgumentException("duplicate attribute name \"" + attribute.name() + "\"");
        }
        if (spec.hasFileAttribute(attribute.name())) {
            spec.deletedAttributes.add(attribute.name()); // an attribute in the file: replaced
        }
        spec.attributes.add(attribute);
    }

    /** Deletes the attribute {@code name}: one added in this session, or one the object has in the file. */
    private static void deleteAttribute(ObjectSpec spec, String name, String objectPath) {
        if (spec.attributeNames.remove(name)) {
            spec.attributes.removeIf(a -> a.name().equals(name));
        } else if (spec.hasFileAttribute(name)) {
            spec.deletedAttributes.add(name);
        } else {
            throw new IllegalArgumentException("no attribute named \"" + name + "\" on " + display(objectPath));
        }
    }

    private static String display(String path) {
        return path.isEmpty() ? "/" : path;
    }

    private static void requireMessageSize(int size, String what) {
        if (size > MAX_MESSAGE_BODY) {
            throw new IllegalArgumentException(what + " needs a " + size + "-byte header message; at most "
                    + MAX_MESSAGE_BODY + " bytes fit");
        }
    }

    /**
     * Attaches attributes and (for chunked datasets) filters to a dataset. Filters form a pipeline
     * applied to each chunk in the order they are added (and reversed on read), as in libhdf5; each may
     * be added once.
     *
     * <p>The storage (chunk shape, maximum shape, filters, fill value, compact layout) is configured
     * before any data is written: for a {@link GroupWriter#createDataset} dataset, before the first
     * {@link #write}; for one given its data when made, before the next dataset or group is added, which
     * writes that data. Afterwards those methods throw {@link IllegalStateException}. Attributes may be
     * added until the writer is closed.
     */
    public static final class DatasetWriter {
        private final Hdf5Writer writer;
        private final DatasetSpec spec;
        private final String path;
        private final boolean legacy;
        private final Lifecycle lifecycle;

        private DatasetWriter(Hdf5Writer writer, DatasetSpec spec, String path, boolean legacy, Lifecycle lifecycle) {
            this.writer = writer;
            this.spec = spec;
            this.path = path;
            this.legacy = legacy;
            this.lifecycle = lifecycle;
        }

        // ------------------------------------------------------------ configuration

        /**
         * Stores the dataset in chunks of {@code chunkShape} (one per dimension), each written, and
         * filtered, as soon as all of its elements are. Before any data is written.
         *
         * @param chunkShape the chunks' dimensions: the dataset's rank, each at least 1, a chunk under 2 GiB
         * @return this dataset's writer
         */
        public DatasetWriter chunked(long... chunkShape) {
            lifecycle.check();
            requireConfigurable();
            requireStreaming("chunked");
            if (spec.compact) {
                throw new IllegalStateException("a compact dataset cannot be chunked");
            }
            checkChunkShape(spec.shape, chunkShape, spec.elementSize);
            spec.chunkShape = chunkShape.clone();
            return this;
        }

        /**
         * The dataset's maximum shape, which {@link #extend} and {@link #append} may grow it to:
         * {@link Hdf5Writer#UNLIMITED} for a dimension with no limit. A dataset that can grow must be
         * {@linkplain #chunked chunked}. Before any data is written.
         *
         * @param maxShape each dimension's limit, none below its size now, or {@link Hdf5Writer#UNLIMITED}
         * @return this dataset's writer
         */
        public DatasetWriter maxShape(long... maxShape) {
            lifecycle.check();
            requireConfigurable();
            requireStreaming("maxShape");
            if (maxShape.length != spec.shape.length) {
                throw new IllegalArgumentException("maximum shape " + java.util.Arrays.toString(maxShape)
                        + " must have the dataset's rank " + spec.shape.length);
            }
            for (int d = 0; d < maxShape.length; d++) {
                if (maxShape[d] != UNLIMITED && maxShape[d] < spec.shape[d]) {
                    throw new IllegalArgumentException("maximum dimension " + maxShape[d] + " is below the shape's "
                            + spec.shape[d] + " (use Hdf5Writer.UNLIMITED for no limit)");
                }
            }
            spec.maxShape = maxShape.clone();
            return this;
        }

        // ------------------------------------------------------------ data

        /**
         * The dataset's current shape.
         *
         * @return its dimensions now (a copy)
         */
        public long[] shape() {
            return spec.shape.clone();
        }

        /**
         * Writes every element: {@code values} holds the whole dataset's values in row-major order, as
         * {@link #write(long[], long[], Object)} takes them.
         *
         * @param values every element's values, row-major
         * @return this dataset's writer
         */
        public DatasetWriter write(Object values) {
            return write(new long[spec.shape.length], spec.shape.clone(), values);
        }

        /**
         * Writes the elements of the box {@code [offset, offset + count)}, row-major, converting
         * {@code values} to the dataset's datatype exactly (a number written to a floating-point type is
         * rounded to the nearest):
         * <ul>
         *   <li>integers (and bit fields): {@code byte[]}, {@code short[]}, {@code int[]}, {@code long[]},
         *       {@code BigInteger[]}, or whole {@code double[]} values, each in the type's range;</li>
         *   <li>floats: any numeric array; complex numbers: (real, imaginary) pairs;</li>
         *   <li>enumerations: member names ({@code String[]}) or values, and {@code boolean[]} for
         *       {@link Datatype#bool()}; time: {@code Instant[]} or seconds;</li>
         *   <li>strings: {@code String[]}; opaque data: {@code byte[][]};</li>
         *   <li>compounds: a {@code Map} of each member's values; arrays: their base type's values,
         *       flattened; sequences: rows ({@code int[][]}, {@code double[][]}, ...);</li>
         *   <li>object references: absolute paths ({@code String[]}); region references:
         *       {@link Region}{@code []}.</li>
         * </ul>
         * The data goes to the file now: for contiguous data, at its place; for chunked data, each chunk
         * once all of its elements are written (until then, it is kept in memory), so write whole chunks,
         * or whole rows of chunks, to keep memory small. Writing elements again replaces them. The chunks
         * of a dataset of object references are kept until {@link Hdf5Writer#close()}, which learns where
         * their targets are.
         *
         * @param offset the box's first corner
         * @param count  its size in each dimension
         * @param values its elements' values, row-major
         * @return this dataset's writer
         * @throws IllegalArgumentException if the box lies outside the dataset, or the values do not fit
         */
        public DatasetWriter write(long[] offset, long[] count, Object values) {
            lifecycle.check();
            requireStreaming("write");
            requireBox(offset, count);
            long n = elementCount(count);
            if (spec.virtual) {
                VirtualDataset.requireFixedSize(spec.type);
                ValueEncoder.Encoded encoded = ValueEncoder.encode(spec.type, n, values, writer.heaps, "dataset " + path);
                writer.writeVirtual(spec, path, offset, count, encoded.bytes());
                return this;
            }
            ValueEncoder.Encoded encoded = ValueEncoder.encode(spec.type, n, values, writer.heaps, "dataset " + path);
            writer.storage(spec, path).write(offset, count, encoded);
            return this;
        }

        /**
         * Writes elements' bytes as stored, in the datatype's byte order, for the box
         * {@code [offset, offset + count)}: {@code bytes} holds {@code count} elements, row-major. Not for
         * types that hold variable-length data or references.
         *
         * @param offset the box's first corner
         * @param count  its size in each dimension
         * @param bytes  its elements, row-major: as many as {@code count} holds, times the element size
         * @return this dataset's writer
         */
        public DatasetWriter writeRaw(long[] offset, long[] count, byte[] bytes) {
            lifecycle.check();
            requireStreaming("writeRaw");
            requireBox(offset, count);
            if (ValueEncoder.isHeapType(spec.type)) {
                throw new IllegalStateException("writeRaw cannot write variable-length data or references; use write");
            }
            long expected = elementCount(count) * spec.elementSize;
            if (bytes.length != expected) {
                throw new IllegalArgumentException(bytes.length + " bytes given for " + expected);
            }
            if (spec.virtual) {
                writer.writeVirtual(spec, path, offset, count, bytes);
                return this;
            }
            writer.storage(spec, path).write(offset, count, new ValueEncoder.Encoded(bytes, List.of(), List.of()));
            return this;
        }

        /**
         * Before a write through a virtual dataset writes into this dataset (P2 WF11): checks that the box
         * from {@code low} to {@code high} lies in it, and that its data can be written (its storage made).
         */
        void prepareWrite(long[] low, long[] high) {
            lifecycle.check();
            for (int d = 0; d < low.length; d++) {
                if (low[d] < 0 || high[d] >= spec.shape[d]) {
                    throw new IllegalArgumentException("a virtual dataset maps elements past the extent of its source "
                            + path + " (in dimension " + d + ", up to " + high[d] + " of " + spec.shape[d] + ")");
                }
            }
            if (!spec.virtual) {
                writer.storage(spec, path);
            }
        }

        /**
         * Appends {@code values} along the first dimension: the dataset grows by as many rows as they fill
         * (each row being every element of the other dimensions), which are then written.
         *
         * @param values whole rows of values, row-major, as {@link #write(Object)} takes them
         * @return this dataset's writer
         * @throws IllegalStateException if the first dimension cannot grow that far (see {@link #maxShape})
         * @throws IllegalArgumentException if the values do not fill whole rows
         */
        public DatasetWriter append(Object values) {
            lifecycle.check();
            requireStreaming("append");
            if (spec.shape.length == 0) {
                throw new IllegalStateException("a scalar dataset has no rows to append");
            }
            long perRow = 1;
            for (int d = 1; d < spec.shape.length; d++) {
                perRow *= spec.shape[d];
            }
            long given = ValueEncoder.valueCount(spec.type, values);
            if (perRow == 0 || given % perRow != 0) {
                throw new IllegalArgumentException(given + " values do not fill rows of " + perRow + " elements");
            }
            long rows = given / perRow;
            long[] offset = new long[spec.shape.length];
            offset[0] = spec.shape[0];
            long[] grown = spec.shape.clone();
            grown[0] += rows;
            extend(grown);
            long[] count = grown.clone();
            count[0] = rows;
            return write(offset, count, values);
        }

        /**
         * Grows the dataset to {@code shape}, within its {@linkplain #maxShape maximum shape}. New elements
         * read as the fill value until written.
         *
         * @param shape the new dimensions, none smaller than now
         * @return this dataset's writer
         * @throws IllegalStateException if the dataset cannot grow (it has no larger maximum shape)
         * @throws IllegalArgumentException if a dimension would shrink or pass its maximum
         */
        public DatasetWriter extend(long... shape) {
            lifecycle.check();
            requireStreaming("extend");
            if (shape.length != spec.shape.length) {
                throw new IllegalArgumentException("shape " + java.util.Arrays.toString(shape) + " must have rank " + spec.shape.length);
            }
            if (java.util.Arrays.equals(shape, spec.shape)) {
                return this;
            }
            if (spec.virtual) {
                throw new IllegalStateException("dataset " + path + " is virtual: its sources set its extent; grow those");
            }
            if (spec.maxShape == null || spec.chunkShape == null) {
                throw new IllegalStateException("dataset " + path + " cannot grow: give it a chunk shape and a larger maxShape");
            }
            for (int d = 0; d < shape.length; d++) {
                if (shape[d] < spec.shape[d] || (spec.maxShape[d] != UNLIMITED && shape[d] > spec.maxShape[d])) {
                    throw new IllegalArgumentException("dimension " + d + " of " + path + " can grow from " + spec.shape[d]
                            + " to " + (spec.maxShape[d] == UNLIMITED ? "any size" : spec.maxShape[d]) + ", not " + shape[d]);
                }
            }
            writer.storage(spec, path); // the extent is fixed from here on, as data is
            spec.shape = shape.clone();
            return this;
        }

        private void requireBox(long[] offset, long[] count) {
            long[] shape = spec.shape;
            if (offset.length != shape.length || count.length != shape.length) {
                throw new IllegalArgumentException("box rank does not match the dataset's rank " + shape.length);
            }
            for (int d = 0; d < shape.length; d++) {
                if (offset[d] < 0 || count[d] < 0 || offset[d] > shape[d] || count[d] > shape[d] - offset[d]) {
                    throw new IllegalArgumentException("box out of bounds in dimension " + d + ": offset=" + offset[d]
                            + " count=" + count[d] + " dim=" + shape[d]);
                }
            }
        }

        /** Only a dataset made by {@code createDataset} is written piece by piece. */
        private void requireStreaming(String op) {
            if (spec.type == null) {
                throw new IllegalStateException(op + " is for datasets made with createDataset; '" + spec.name
                        + "' was given its data when it was made");
            }
        }

        /**
         * Deletes the attribute {@code name}: one added in this session, or one the dataset has in a file
         * being changed.
         *
         * @param name the attribute's name
         * @return this dataset's writer
         * @throws IllegalArgumentException if the dataset has no attribute of that name
         */
        public DatasetWriter deleteAttribute(String name) {
            lifecycle.check();
            Hdf5Writer.deleteAttribute(spec, name, path);
            return this;
        }

        /** Its storage is set once data is written to it (or it grows). */
        private void requireConfigurable() {
            if (spec.inFile()) {
                throw new IllegalStateException("dataset " + path + " is in the file already: its storage is set");
            }
            if (spec.storage != null) {
                throw new IllegalStateException("configure dataset " + path + " before writing data to it"
                        + (spec.type == null ? " (its data was written when the next dataset or group was added)" : ""));
            }
        }

        /**
         * An attribute of any datatype, as {@link GroupWriter#attribute} adds one.
         *
         * @param name   the attribute's name; one the dataset has in a file being changed is replaced
         * @param type   its datatype
         * @param shape  its dimensions ({@code new long[0]} for a scalar)
         * @param values its values, as {@link #write} takes them
         * @return this dataset's writer
         */
        public DatasetWriter attribute(String name, Datatype type, long[] shape, Object values) {
            lifecycle.check();
            writer.requireUsualAttributeType(spec, name, type);
            addAttribute(spec, writer.typedAttribute(name, type, shape, values), legacy);
            return this;
        }

        /**
         * A scalar string attribute ({@code units}, a CF convention, ...): fixed-length, UTF-8.
         *
         * @param name  the attribute's name; one the dataset has in a file being changed is replaced
         * @param value its value
         * @return this dataset's writer
         */
        public DatasetWriter stringAttribute(String name, String value) {
            return attribute(name, stringType(value), new long[0], new String[] {value});
        }

        /**
         * Compresses each chunk with deflate (gzip) at the given level (0&ndash;9). Chunked datasets only.
         *
         * @param level the compression level: 0 (none) to 9 (smallest)
         * @return this dataset's writer
         */
        public DatasetWriter deflate(int level) {
            lifecycle.check();
            requireConfigurable();
            requireChunked();
            if (level < 0 || level > 9) {
                throw new IllegalArgumentException("deflate level must be 0-9, not " + level);
            }
            addFilter(Filters.DEFLATE, level);
            return this;
        }

        /**
         * Byte-shuffles each chunk (grouping like-position bytes) to improve compression. Chunked only.
         *
         * @return this dataset's writer
         */
        public DatasetWriter shuffle() {
            lifecycle.check();
            requireConfigurable();
            requireChunked();
            addFilter(Filters.SHUFFLE, 0);
            return this;
        }

        /**
         * Appends a Fletcher-32 checksum to each stored chunk. Chunked datasets only.
         *
         * @return this dataset's writer
         */
        public DatasetWriter fletcher32() {
            lifecycle.check();
            requireConfigurable();
            requireChunked();
            addFilter(Filters.FLETCHER32, 0);
            return this;
        }

        /**
         * Losslessly compresses integer chunks with the scale-offset filter, exactly as libhdf5 does with
         * automatic minbits: elements equal to the fill value get a reserved code, the rest are stored
         * as their offset from the chunk minimum in as few bits as the range needs. Chunked integer
         * datasets only, as the first filter.
         *
         * @return this dataset's writer
         */
        public DatasetWriter scaleOffset() {
            lifecycle.check();
            requireConfigurable();
            requireChunked();
            requireInteger("scaleOffset");
            requireFirst("scaleOffset");
            addFilter(Filters.SCALEOFFSET, 0);
            return this;
        }

        /**
         * Stores each element in only its {@code precision} low bits with the n-bit filter (an unsigned
         * integer datatype of that precision). Chunked integer datasets only, as the first filter; every
         * value (and the fill value, if set) must be non-negative and fit in {@code precision} bits.
         *
         * @param precision the bits kept of each element: 1 to the element's size in bits
         * @return this dataset's writer
         * @throws IllegalArgumentException if a value does not fit
         */
        public DatasetWriter nbit(int precision) {
            lifecycle.check();
            requireConfigurable();
            requireChunked();
            requireInteger("nbit");
            requireFirst("nbit");
            if (precision < 1 || precision > spec.elementSize * 8) {
                throw new IllegalArgumentException("n-bit precision must be 1-" + spec.elementSize * 8 + ", not " + precision);
            }
            if (legacy) {
                requireUsualUnusedBits(new Datatype.FixedPoint(spec.elementSize, java.nio.ByteOrder.LITTLE_ENDIAN, false, 0,
                        precision), "n-bit(" + precision + ") dataset '" + spec.name + "'");
            }
            int size = spec.elementSize;
            for (int i = 0; spec.data != null && i < spec.data.length / size; i++) {
                long value = littleEndianSigned(spec.data, i * size, size);
                if (!fitsUnsigned(value, precision)) {
                    throw new IllegalArgumentException("n-bit(" + precision + ") stores unsigned " + precision
                            + "-bit values, but element " + i + " is " + value);
                }
            }
            if (spec.fillValue != null && !fitsUnsigned(littleEndianSigned(spec.fillValue, 0, size), precision)) {
                throw new IllegalArgumentException("the fill value does not fit n-bit(" + precision + ")");
            }
            spec.nbitPrecision = precision;
            addFilter(Filters.NBIT, precision);
            return this;
        }

        /**
         * Compresses chunks with szip's entropy coding, 8 pixels per block: {@code szip(SzipCoding.ENTROPY, 8)}.
         *
         * @return this dataset's writer
         */
        public DatasetWriter szip() {
            return szip(SzipCoding.ENTROPY, 8);
        }

        /**
         * Compresses chunks with the szip filter, as libhdf5 + libaec store it ({@code H5Pset_szip}): byte for
         * byte libaec's coding, by Falcon's pure-Java CCSDS 121.0 encoder. Chunked integer or floating-point
         * datasets; only {@link #shuffle()} may come before it. A chunk that szip cannot shrink is stored
         * unfiltered, as libhdf5 does.
         *
         * @param coding         entropy coding alone, or after nearest-neighbour preprocessing (which suits
         *                       smooth data); h5py's {@code "ec"} and {@code "nn"}
         * @param pixelsPerBlock elements coded together: even, 2 to 32 (libaec's standard sizes are 8, 16
         *                       and 32), and at most a chunk's elements
         * @return this dataset's writer
         */
        public DatasetWriter szip(SzipCoding coding, int pixelsPerBlock) {
            lifecycle.check();
            java.util.Objects.requireNonNull(coding, "coding");
            requireConfigurable();
            requireChunked();
            int typeClass = spec.datatype[0] & 0x0F;
            if (typeClass != 0 && typeClass != 1) {
                throw new IllegalStateException("szip requires an integer or floating-point dataset");
            }
            for (FilterSpec filter : spec.filters) {
                if (filter.id() != Filters.SHUFFLE) {
                    throw new IllegalStateException("szip must come before every filter except shuffle");
                }
            }
            if (pixelsPerBlock < 2 || pixelsPerBlock > 32 || pixelsPerBlock % 2 != 0) {
                throw new IllegalArgumentException("szip pixels per block must be even and 2-32, not " + pixelsPerBlock);
            }
            if (elementCount(spec.chunkShape) < pixelsPerBlock) {
                throw new IllegalStateException("szip needs at least " + pixelsPerBlock + " elements per chunk");
            }
            addFilter(Filters.SZIP, pixelsPerBlock | (coding == SzipCoding.NEAREST_NEIGHBOUR ? SZIP_NN_PARAMETER : 0));
            return this;
        }

        /**
         * Compresses chunks with LZF, h5py's own filter (32000; {@code compression="lzf"}), byte for byte as
         * h5py's liblzf does. A chunk LZF cannot shrink is stored unfiltered, as h5py's filter leaves it.
         * Chunked datasets only. libhdf5 reads it through h5py, which registers the filter, or an LZF plugin.
         *
         * @return this dataset's writer
         */
        public DatasetWriter lzf() {
            return thirdParty(Filter.LZF);
        }

        /**
         * Compresses chunks with Blosc as {@code hdf5plugin.Blosc()} sets it up: LZ4 at clevel 5, after a byte
         * shuffle. The same as {@code blosc("lz4", 5, "shuffle")}.
         *
         * @return this dataset's writer
         */
        public DatasetWriter blosc() {
            return blosc("lz4", 5, "shuffle");
        }

        /**
         * Compresses chunks with Blosc (filter 32001, {@code hdf5-blosc} with c-blosc 1.21): byte for byte
         * c-blosc's buffer for every compressor but zstd (whose frames are Falcon's own, which c-blosc reads). A
         * chunk Blosc cannot shrink is stored unfiltered, as hdf5-blosc leaves it. The element size Blosc
         * shuffles by is the datatype's (an array's base type; one above 255 bytes as single bytes). Chunked
         * datasets only.
         *
         * @param cname   the internal compressor: {@code blosclz}, {@code lz4}, {@code lz4hc}, {@code zlib},
         *                {@code zstd}, or {@code snappy}
         * @param clevel  the compression level, 0 (none: every chunk stored unfiltered) to 9
         * @param shuffle {@code noshuffle}, {@code shuffle} (bytes), or {@code bitshuffle}
         * @return this dataset's writer
         */
        public DatasetWriter blosc(String cname, int clevel, String shuffle) {
            java.util.Objects.requireNonNull(cname, "cname");
            java.util.Objects.requireNonNull(shuffle, "shuffle");
            int compressor = switch (cname) {
                case "blosclz", "lz4", "lz4hc", "snappy", "zlib", "zstd" ->
                        com.ebremer.falcon.core.compress.blosc.BloscEncoder.compressor(cname);
                default -> throw new IllegalArgumentException(
                        "blosc cname must be blosclz, lz4, lz4hc, zlib, zstd, or snappy, not " + cname);
            };
            if (clevel < 0 || clevel > 9) {
                throw new IllegalArgumentException("blosc clevel must be 0-9, not " + clevel);
            }
            int mode = switch (shuffle) {
                case "noshuffle" -> 0;
                case "shuffle" -> 1;
                case "bitshuffle" -> 2;
                default -> throw new IllegalArgumentException(
                        "blosc shuffle must be noshuffle, shuffle, or bitshuffle, not " + shuffle);
            };
            return thirdParty(Filter.BLOSC, clevel, mode, compressor);
        }

        /**
         * Compresses chunks with LZ4 as {@code hdf5plugin.LZ4()} sets it up: each chunk one block.
         *
         * @return this dataset's writer
         */
        public DatasetWriter lz4() {
            return lz4(0);
        }

        /**
         * Compresses chunks with LZ4 (filter 32004, the HDF Group's {@code H5Zlz4.c}), byte for byte as the
         * plugin does: in blocks of {@code blockBytes}, each LZ4-compressed, or stored raw if that does not
         * shrink it. Chunked datasets only.
         *
         * @param blockBytes the bytes of each block, 1 to 2,113,929,216 (larger chunks are cut into blocks), or
         *                   0 for 1 GiB, so a chunk is one block
         * @return this dataset's writer
         */
        public DatasetWriter lz4(int blockBytes) {
            if (blockBytes < 0 || blockBytes > 0x7E000000) {
                throw new IllegalArgumentException("lz4 block size must be 0-2113929216 bytes, not " + blockBytes);
            }
            return thirdParty(Filter.LZ4, blockBytes);
        }

        /**
         * Bit-shuffles chunks, then compresses them with LZ4, as {@code hdf5plugin.Bitshuffle()} sets it up:
         * {@code bitshuffle("lz4", 0, 3)}.
         *
         * @return this dataset's writer
         */
        public DatasetWriter bitshuffle() {
            return bitshuffle("lz4", 0, 3);
        }

        /**
         * Bit-shuffles chunks (filter 32008, Kiyoshi Masui's {@code bitshuffle}): bit <i>k</i> of every element
         * of a block stored together, which suits numeric data; then, optionally, compresses each block with LZ4
         * or zstd. Byte for byte the plugin's output, but for zstd (Falcon's own frames, which libzstd reads).
         * Chunked datasets only.
         *
         * @param compression    {@code none}, {@code lz4}, or {@code zstd}
         * @param blockElements  the elements in a block: a multiple of 8, or 0 for bitshuffle's default
         *                       (8 KiB of elements, at least 128)
         * @param zstdLevel      zstd's level, 0 (its default, 3) to 22; recorded for {@code zstd} only
         * @return this dataset's writer
         */
        public DatasetWriter bitshuffle(String compression, int blockElements, int zstdLevel) {
            java.util.Objects.requireNonNull(compression, "compression");
            if (blockElements < 0 || blockElements % 8 != 0) {
                throw new IllegalArgumentException("bitshuffle block size must be a multiple of 8 elements, not "
                        + blockElements);
            }
            if (zstdLevel < 0 || zstdLevel > 22) {
                throw new IllegalArgumentException("bitshuffle zstd level must be 0-22, not " + zstdLevel);
            }
            return switch (compression) {
                case "none" -> thirdParty(Filter.BITSHUFFLE, blockElements, 0);
                case "lz4" -> thirdParty(Filter.BITSHUFFLE, blockElements, BITSHUFFLE_LZ4);
                case "zstd" -> thirdParty(Filter.BITSHUFFLE, blockElements, BITSHUFFLE_ZSTD, zstdLevel);
                default -> throw new IllegalArgumentException(
                        "bitshuffle compression must be none, lz4, or zstd, not " + compression);
            };
        }

        /**
         * Compresses chunks with Zstandard at level 3, as {@code hdf5plugin.Zstd()} sets it up.
         *
         * @return this dataset's writer
         */
        public DatasetWriter zstd() {
            return zstd(3);
        }

        /**
         * Compresses each chunk into one Zstandard frame (filter 32015, the HDF Group's {@code H5Zzstd.c}), by
         * Falcon's pure-Java encoder, which libzstd reads (its frames are not libzstd's byte for byte). Chunked
         * datasets only.
         *
         * @param level the compression level: -131072 (fastest) to 22 (smallest); 0 is zstd's default, 3
         * @return this dataset's writer
         */
        public DatasetWriter zstd(int level) {
            if (level < -131072 || level > 22) {
                throw new IllegalArgumentException("zstd level must be -131072 to 22, not " + level);
            }
            return thirdParty(Filter.ZSTD, level);
        }

        /**
         * Compresses chunks with bzip2 in blocks of 900,000 bytes, as {@code hdf5plugin.BZip2()} sets it up.
         *
         * @return this dataset's writer
         */
        public DatasetWriter bzip2() {
            return bzip2(9);
        }

        /**
         * Compresses each chunk into one bzip2 stream (filter 307, PyTables' {@code H5Zbzip2.c}), byte for byte
         * libbzip2 1.0.8's: the plugin stores it even where it is larger than the chunk. Chunked datasets only.
         *
         * @param blockSize the block size in units of 100,000 bytes: 1 to 9 (bzip2's {@code -1} to {@code -9})
         * @return this dataset's writer
         */
        public DatasetWriter bzip2(int blockSize) {
            if (blockSize < 1 || blockSize > 9) {
                throw new IllegalArgumentException("bzip2 block size must be 1-9, not " + blockSize);
            }
            return thirdParty(Filter.BZIP2, blockSize);
        }

        /** Adds third-party filter {@code id} with the values hdf5plugin passes to {@code H5Pset_filter}. */
        private DatasetWriter thirdParty(int id, int... options) {
            lifecycle.check();
            requireConfigurable();
            requireChunked();
            addFilter(new FilterSpec(id, 0, null, options));
            return this;
        }

        private void requireChunked() {
            if (spec.chunkShape == null) {
                throw new IllegalStateException("filters require a chunked dataset");
            }
        }

        private void requireInteger(String filter) {
            if ((spec.datatype[0] & 0x0F) != 0) {
                throw new IllegalStateException(filter + " requires an integer dataset");
            }
            if ((spec.datatype[1] & 0x01) != 0) {
                throw new IllegalStateException(filter + " requires a little-endian integer dataset");
            }
        }

        private void requireFirst(String filter) {
            if (!spec.filters.isEmpty()) {
                throw new IllegalStateException(filter + " must be the first filter in the pipeline");
            }
        }

        private void addFilter(int id, int parameter) {
            addFilter(new FilterSpec(id, parameter));
        }

        private void addFilter(FilterSpec added) {
            for (FilterSpec filter : spec.filters) {
                if (filter.id() == added.id()) {
                    throw new IllegalStateException("filter " + added.id() + " is already in the pipeline");
                }
            }
            if (spec.nbitPrecision >= 0 && added.id() == Filters.SCALEOFFSET) {
                throw new IllegalStateException("scaleOffset cannot follow nbit");
            }
            spec.filters.add(added);
        }

        /**
         * Stores the element data inline in the object header (compact layout) rather than in a separate
         * block. For small contiguous datasets only; the data must be at most 65524 bytes (an object-header
         * message holds under 64 KiB).
         *
         * @return this dataset's writer
         */
        public DatasetWriter compact() {
            lifecycle.check();
            requireConfigurable();
            if (spec.chunkShape != null || spec.maxShape != null) {
                throw new IllegalStateException("compact layout requires a contiguous dataset of fixed shape");
            }
            long bytes = elementCount(spec.shape) * spec.elementSize;
            if (bytes > MAX_COMPACT_DATA) {
                throw new IllegalStateException("compact layout data must be at most " + MAX_COMPACT_DATA
                        + " bytes, not " + bytes);
            }
            spec.compact = true;
            return this;
        }

        /**
         * Sets the fill value (for unallocated or unwritten elements), converted to the dataset's type: an
         * integer dataset stores it exactly (it must be in range), a floating-point one as the nearest
         * value. Integer, enum, and floating-point datasets only.
         *
         * @param value the fill value
         * @return this dataset's writer
         * @throws IllegalArgumentException if the value is out of range for an integer dataset
         * @throws IllegalStateException if the dataset's type has no numeric fill value
         */
        public DatasetWriter fillValue(long value) {
            lifecycle.check();
            requireConfigurable();
            if (spec.type != null && typeClass() != 0 && typeClass() != 1 && typeClass() != 8) {
                throw noNumericFill();
            }
            if (spec.type != null) {
                spec.fillValue = ValueEncoder.encode(spec.type, 1, new long[] {value}, writer.heaps, "the fill value").bytes();
                return this;
            }
            switch (typeClass()) {
                case 0, 8 -> spec.fillValue = integerFill(value);
                case 1 -> spec.fillValue = floatFill(value);
                default -> throw noNumericFill();
            }
            return this;
        }

        /**
         * Sets the fill value from a floating-point value, converted to the dataset's type: a
         * floating-point dataset stores it (rounded to float32 if that is the type), an integer one only a
         * whole number in range.
         *
         * @param value the fill value
         * @return this dataset's writer
         * @throws IllegalArgumentException if an integer dataset is given a fraction or an out-of-range value
         * @throws IllegalStateException if the dataset's type has no numeric fill value
         */
        public DatasetWriter fillValue(double value) {
            lifecycle.check();
            requireConfigurable();
            if (spec.type != null && typeClass() != 0 && typeClass() != 1 && typeClass() != 8) {
                throw noNumericFill();
            }
            if (spec.type != null) {
                spec.fillValue = ValueEncoder.encode(spec.type, 1, new double[] {value}, writer.heaps, "the fill value").bytes();
                return this;
            }
            switch (typeClass()) {
                case 0, 8 -> {
                    if (value != Math.rint(value) || Math.abs(value) >= 0x1p63) {
                        throw new IllegalArgumentException("fill value " + value + " is not an integer in range");
                    }
                    spec.fillValue = integerFill((long) value);
                }
                case 1 -> spec.fillValue = floatFill(value);
                default -> throw noNumericFill();
            }
            return this;
        }

        private int typeClass() {
            return spec.datatype[0] & 0x0F;
        }

        private IllegalStateException noNumericFill() {
            return new IllegalStateException("a numeric fill value needs an integer, enum, or floating-point dataset; '"
                    + spec.name + "' has datatype class " + typeClass());
        }

        /** {@code value} in the dataset's integer encoding, if it is representable. */
        private byte[] integerFill(long value) {
            int size = spec.elementSize;
            boolean fits = spec.nbitPrecision >= 0 ? fitsUnsigned(value, spec.nbitPrecision)
                    : size >= 8 || (value >= -(1L << (8 * size - 1)) && value < 1L << (8 * size - 1));
            if (!fits) {
                throw new IllegalArgumentException("fill value " + value + " does not fit the "
                        + (spec.nbitPrecision >= 0 ? spec.nbitPrecision + "-bit unsigned" : 8 * size + "-bit")
                        + " integer type of '" + spec.name + "'");
            }
            byte[] fill = new byte[size];
            for (int b = 0; b < size; b++) {
                fill[b] = (byte) (value >>> (8 * b));
            }
            return fill;
        }

        private byte[] floatFill(double value) {
            return spec.elementSize == 8 ? doubleBytes(new double[] {value}) : float32Bytes(new float[] {(float) value});
        }

        /**
         * A signed 32-bit integer attribute.
         *
         * @param name  the attribute's name; one the dataset has in a file being changed is replaced
         * @param data  its values, row-major
         * @param shape its dimensions ({@code new long[0]} for a scalar); their product must be {@code data.length}
         * @return this dataset's writer
         */
        public DatasetWriter intAttribute(String name, int[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec, new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)), legacy);
            return this;
        }

        /**
         * A 64-bit floating-point attribute.
         *
         * @param name  the attribute's name; one the dataset has in a file being changed is replaced
         * @param data  its values, row-major
         * @param shape its dimensions ({@code new long[0]} for a scalar); their product must be {@code data.length}
         * @return this dataset's writer
         */
        public DatasetWriter doubleAttribute(String name, double[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec, new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)), legacy);
            return this;
        }
    }

    /** The two's-complement value of a little-endian integer of {@code size} bytes. */
    private static long littleEndianSigned(byte[] data, int offset, int size) {
        long value = 0;
        for (int b = 0; b < size; b++) {
            value |= (long) (data[offset + b] & 0xff) << (8 * b);
        }
        return size >= 8 ? value : value << (64 - 8 * size) >> (64 - 8 * size);
    }

    private static boolean fitsUnsigned(long value, int precision) {
        return value >= 0 && (precision >= 63 || value < 1L << precision);
    }

    /** One named, typed column of a {@link GroupWriter#compoundDataset compound dataset}. */
    public static final class CompoundField {
        private final String name;
        private final byte[] datatype;
        private final int size;
        private final byte[] column;
        private final int count;

        private CompoundField(String name, byte[] datatype, int size, byte[] column, int count) {
            this.name = name;
            this.datatype = datatype;
            this.size = size;
            this.column = column;
            this.count = count;
        }

        /**
         * An {@code int32} field. Field names must be non-empty, without NUL, and unique in the record.
         *
         * @param name   the field's name
         * @param values its value in each element, row-major
         * @return the field
         */
        public static CompoundField int32(String name, int[] values) {
            requireName(name, "compound field");
            return new CompoundField(name, DATATYPE_INT32, 4, intBytes(values), values.length);
        }

        /**
         * A {@code float64} field.
         *
         * @param name   the field's name
         * @param values its value in each element, row-major
         * @return the field
         */
        public static CompoundField float64(String name, double[] values) {
            requireName(name, "compound field");
            return new CompoundField(name, DATATYPE_FLOAT64, 8, doubleBytes(values), values.length);
        }
    }

    /** An enumeration type over a 32-bit base: an ordered list of {@code name -> code} members. */
    public static final class EnumType {
        private final List<String> names = new ArrayList<>();
        private final List<Integer> values = new ArrayList<>();

        /** An enumeration with no members yet, as {@link Hdf5Writer#enumType()} makes. */
        public EnumType() {
        }

        /**
         * Adds a member; returns {@code this} for chaining. Names must be non-empty and without NUL, and
         * names and values unique, as libhdf5 requires.
         *
         * @param name  the member's name
         * @param value its code
         * @return this enumeration
         */
        public EnumType add(String name, int value) {
            requireName(name, "enum member");
            if (names.contains(name) || values.contains(value)) {
                throw new IllegalArgumentException("duplicate enum member " + name + " = " + value);
            }
            names.add(name);
            values.add(value);
            return this;
        }
    }

    /**
     * Starts building an {@link EnumType}.
     *
     * @return an enumeration with no members yet
     */
    public static EnumType enumType() {
        return new EnumType();
    }

    /**
     * A region of a dataset in the file being written, for a region reference ({@code H5R_DATASET_REGION}):
     * the dataset's absolute path, and a selection of it: all of it, a block, a regular hyperslab, or
     * points. The dataset may be added before or after the reference.
     *
     * <pre>{@code
     * w.regionReferenceDataset("roi", new long[] {2}, new Hdf5Writer.Region[] {
     *         Hdf5Writer.Region.block("/image", new long[] {10, 20}, new long[] {64, 64}),
     *         Hdf5Writer.Region.points("/image", new long[][] {{0, 0}, {5, 7}})});
     * }</pre>
     */
    public static final class Region {
        private static final int POINTS = 1;
        private static final int HYPERSLAB = 2;
        private static final int ALL = 3;

        private final String datasetPath;
        private final int kind;
        private final long[][] starts; // points, or blocks' first corners
        private final long[][] ends;   // blocks' last corners (inclusive)

        private Region(String datasetPath, int kind, long[][] starts, long[][] ends) {
            requireName(datasetPath, "region reference dataset");
            this.datasetPath = datasetPath;
            this.kind = kind;
            this.starts = starts;
            this.ends = ends;
        }

        /**
         * All of the dataset.
         *
         * @param datasetPath the dataset's absolute path
         * @return the region
         */
        public static Region all(String datasetPath) {
            return new Region(datasetPath, ALL, new long[0][], new long[0][]);
        }

        /**
         * The block of {@code count} elements in each dimension from {@code offset}.
         *
         * @param datasetPath the dataset's absolute path
         * @param offset      the block's first corner
         * @param count       its size in each dimension
         * @return the region
         */
        public static Region block(String datasetPath, long[] offset, long[] count) {
            return hyperslab(datasetPath, offset, null, count.clone(), null);
        }

        /**
         * A regular hyperslab, as {@code Dataset.select(start, stride, count, block)} selects one: in each
         * dimension, {@code count} blocks of {@code block} indices, {@code stride} apart ({@code null} stride
         * and block are 1).
         *
         * @param datasetPath the dataset's absolute path
         * @param start       the first block's first corner
         * @param stride      the distance from one block's start to the next in each dimension, or {@code null}
         * @param count       the blocks in each dimension
         * @param block       each block's size in each dimension, or {@code null}
         * @return the region
         */
        public static Region hyperslab(String datasetPath, long[] start, long[] stride, long[] count, long[] block) {
            int rank = start.length;
            long[] s = stride != null ? stride : ones(rank);
            long[] b = block != null ? block : ones(rank);
            if (count.length != rank || s.length != rank || b.length != rank) {
                throw new IllegalArgumentException("a hyperslab's arguments must have the same rank");
            }
            boolean contiguous = true;
            long blocks = 1;
            for (int d = 0; d < rank; d++) {
                if (start[d] < 0 || count[d] < 1 || s[d] < 1 || b[d] < 1 || (count[d] > 1 && s[d] < b[d])) {
                    throw new IllegalArgumentException("invalid hyperslab in dimension " + d);
                }
                contiguous &= count[d] == 1 || s[d] == b[d];
                blocks = Math.multiplyExact(blocks, count[d]);
            }
            if (contiguous) { // one block
                long[] end = new long[rank];
                for (int d = 0; d < rank; d++) {
                    end[d] = start[d] + count[d] * b[d] - 1;
                }
                return new Region(datasetPath, HYPERSLAB, new long[][] {start.clone()}, new long[][] {end});
            }
            long[][] starts = new long[Math.toIntExact(blocks)][rank];
            long[][] ends = new long[starts.length][rank];
            int[] at = new int[rank];
            for (int i = 0; i < starts.length; i++) {
                for (int d = 0; d < rank; d++) {
                    starts[i][d] = start[d] + at[d] * s[d];
                    ends[i][d] = starts[i][d] + b[d] - 1;
                }
                for (int d = rank - 1; d >= 0 && ++at[d] == count[d]; d--) {
                    at[d] = 0;
                }
            }
            return new Region(datasetPath, HYPERSLAB, starts, ends);
        }

        /**
         * Single elements, in the order given.
         *
         * @param datasetPath the dataset's absolute path
         * @param points      each element's coordinates, all of the dataset's rank
         * @return the region
         */
        public static Region points(String datasetPath, long[][] points) {
            long[][] copy = new long[points.length][];
            for (int i = 0; i < points.length; i++) {
                copy[i] = points[i].clone();
                if (copy[i].length != copy[0].length) {
                    throw new IllegalArgumentException("every point must have the same rank");
                }
            }
            return new Region(datasetPath, POINTS, copy, null);
        }

        /**
         * The dataset's absolute path.
         *
         * @return the path the region was made with
         */
        public String datasetPath() {
            return datasetPath;
        }

        /**
         * The selection, serialized as libhdf5 writes a region reference's ({@code H5S_SELECT_SERIALIZE},
         * version 1): "all"; points; or a hyperslab as a list of blocks, coordinates in 4 bytes each.
         */
        byte[] serialize() {
            GrowBuffer b = new GrowBuffer();
            b.u32(kind);
            b.u32(1);              // version
            b.u32(0);              // reserved
            if (kind == ALL) {
                b.u32(0);          // length of what follows
                return b.toByteArray();
            }
            int rank = starts.length == 0 ? 0 : starts[0].length;
            int perItem = kind == POINTS ? rank : 2 * rank;
            b.u32(8 + 4L * perItem * starts.length);
            b.u32(rank);
            b.u32(starts.length);
            for (int i = 0; i < starts.length; i++) {
                coordinates(b, starts[i]);
                if (kind == HYPERSLAB) {
                    coordinates(b, ends[i]);
                }
            }
            return b.toByteArray();
        }

        private static void coordinates(GrowBuffer b, long[] values) {
            for (long v : values) {
                if (v < 0 || v > 0xFFFFFFFEL) {
                    throw new IllegalArgumentException("a region reference's coordinates must be below 2^32 - 1: " + v);
                }
                b.u32(v);
            }
        }

        private static long[] ones(int rank) {
            long[] ones = new long[rank];
            java.util.Arrays.fill(ones, 1);
            return ones;
        }
    }

    /** A fixed-length string type that holds {@code value}: UTF-8 if it is not ASCII, null-padded. */
    private static Datatype.StringType stringType(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new Datatype.StringType(Math.max(1, bytes.length), Datatype.StringPadding.NULL_PAD,
                isAscii(bytes) ? Datatype.CharacterSet.ASCII : Datatype.CharacterSet.UTF8);
    }

    /**
     * An attribute of {@code type} holding {@code values}; its variable-length data goes to the heap now,
     * and its object references are filled in on close.
     */
    private AttributeSpec typedAttribute(String name, Datatype type, long[] shape, Object values) {
        java.util.Objects.requireNonNull(type, "type");
        for (long d : shape) {
            if (d < 0) {
                throw new IllegalArgumentException("dimensions must not be negative: " + java.util.Arrays.toString(shape));
            }
        }
        byte[] datatype = DatatypeEncoder.encode(type, legacy);
        ValueEncoder.Encoded encoded = ValueEncoder.encode(type, elementCount(shape), values, heaps,
                "attribute '" + name + "'");
        return new AttributeSpec(name, datatype, shape.clone(), encoded.bytes(), encoded.ids(), encoded.refs());
    }

    /**
     * Refuses an attribute's type that libhdf5 would refuse to read in its object's header: one of the file
     * (version 1, without a checksum) or a new object's in the earliest format (see
     * {@link #requireUsualUnusedBits}).
     */
    private void requireUsualAttributeType(ObjectSpec spec, String name, Datatype type) {
        boolean unchecksummed = spec.inFile() && spec.object != null ? spec.object.header().version() == 1 : legacy;
        if (unchecksummed) {
            requireUsualUnusedBits(type, "attribute '" + name + "'");
        }
    }

    /**
     * Refuses, for an object header without a checksum (version 1, the earliest format's), a datatype with a
     * numeric part libhdf5 1.14.4 and later take for corruption there: an integer, float, or bitfield of two
     * or more bytes whose precision and offset reach less than half its bits ({@code
     * H5T_is_numeric_with_unusual_unused_bits}), alone or in a compound, array, enumeration, variable-length,
     * or complex type. libhdf5 creates no such dataset ("creating dataset with unusual datatype") and reads
     * no such datatype, an attribute's included, unless {@code H5Pset_relax_file_integrity_checks} allows it.
     *
     * @throws IllegalArgumentException for such a type
     */
    private static void requireUsualUnusedBits(Datatype type, String what) {
        Datatype unusual = unusualUnusedBits(type);
        if (unusual != null) {
            int[] bits = switch (unusual) {
                case Datatype.FixedPoint t -> new int[] {t.bitPrecision(), t.bitOffset()};
                case Datatype.FloatingPoint t -> new int[] {t.bitPrecision(), t.bitOffset()};
                case Datatype.BitField t -> new int[] {t.bitPrecision(), t.bitOffset()};
                default -> throw new IllegalStateException();
            };
            throw new IllegalArgumentException(what + " has a " + unusual.size() + "-byte type of precision "
                    + bits[0] + (bits[1] != 0 ? " at bit offset " + bits[1] : "") + ", which leaves more than half its bits"
                    + " unused: libhdf5 refuses that in the earliest format's version-1 object headers (they have"
                    + " no checksum) unless relaxed by H5Pset_relax_file_integrity_checks; use Format.LATEST, or"
                    + " use at least " + (unusual.size() * 4 - bits[1]) + " bits");
        }
    }

    /** The first numeric part of {@code type} with more than half its bits unused, or null (libhdf5's rule). */
    private static Datatype unusualUnusedBits(Datatype type) {
        return switch (type) {
            case Datatype.FixedPoint t -> unusual(t, t.bitPrecision(), t.bitOffset());
            case Datatype.FloatingPoint t -> unusual(t, t.bitPrecision(), t.bitOffset());
            case Datatype.BitField t -> unusual(t, t.bitPrecision(), t.bitOffset());
            case Datatype.Compound t -> {
                for (Datatype.Compound.Member member : t.members()) {
                    Datatype found = unusualUnusedBits(member.type());
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            case Datatype.Array t -> unusualUnusedBits(t.base());
            case Datatype.Enumeration t -> unusualUnusedBits(t.base());
            case Datatype.VariableLength t -> t.base() != null ? unusualUnusedBits(t.base()) : null;
            case Datatype.Complex t -> unusualUnusedBits(t.base());
            default -> null;
        };
    }

    private static Datatype unusual(Datatype type, int precision, int offset) {
        int bits = type.size() * 8;
        return type.size() > 1 && precision < bits && bits > 2 * (precision + offset) ? type : null;
    }

    // --------------------------------------------------------------- changing a file in place (P2 WF6)

    /**
     * The object the group {@code spec} of the file links to as {@code name}, by a hard link: the objects
     * opened to change them are reached as they are stored, not through soft or external links.
     */
    private Hdf5Object fileObject(GroupSpec spec, String name, String groupPath) {
        Link link = ((Group) spec.object).link(name).orElseThrow();
        if (!(link instanceof Link.Hard hard)) {
            throw new IllegalArgumentException("'" + name + "' in " + display(groupPath) + " is a "
                    + (link instanceof Link.Soft ? "soft" : "non-hard") + " link: open its target where it is stored");
        }
        return existing.object(spec.object.path(), name, hard.objectHeaderAddress());
    }

    /** A dataset of the file, opened to write its data or change its attributes: as the file stores it. */
    private DatasetSpec existingDataset(String name, Dataset dataset) {
        Datatype type = dataset.datatype();
        com.ebremer.falcon.hdf5.header.HeaderMessage typeMessage =
                dataset.header().find(com.ebremer.falcon.hdf5.header.MessageType.DATATYPE);
        if (typeMessage != null) {
            typeMessage = com.ebremer.falcon.hdf5.header.SharedMessage.resolve(dataset.ctx, typeMessage);
        }
        byte[] datatype = typeMessage == null ? new byte[0]
                : typeMessage.buffer().getBytes(typeMessage.bodyOffset(), typeMessage.bodySize());
        long[] shape = dataset.dataspace().dimensions();
        DatasetSpec spec = new DatasetSpec(name, datatype, type.size(), shape.clone(), null, null, null);
        spec.address = dataset.objectHeaderAddress();
        spec.object = dataset;
        spec.type = type;
        spec.fileShape = shape.clone();
        long[] max = dataset.dataspace().maxDimensions();
        spec.maxShape = max == null ? null : max.clone();
        switch (dataset.dataLayout()) {
            case com.ebremer.falcon.hdf5.layout.DataLayout.Chunked chunked -> {
                spec.chunkShape = new long[chunked.chunkDimensions().length];
                for (int d = 0; d < spec.chunkShape.length; d++) {
                    spec.chunkShape[d] = chunked.chunkDimensions()[d];
                }
            }
            case com.ebremer.falcon.hdf5.layout.DataLayout.Compact compact -> spec.compact = true;
            case com.ebremer.falcon.hdf5.layout.DataLayout.Virtual virtual -> spec.virtual = true;
            default -> {
            }
        }
        spec.fillValue = dataset.fillValue();
        return spec;
    }

    /**
     * Writes the box {@code [offset, offset + count)} of a virtual dataset of the file into its sources (P2
     * WF11), as libhdf5's {@code H5Dwrite} on one does: each element into the source element its mapping
     * pairs it with, converted to the source's byte order. A source in this file is written in this session;
     * one in another file, in a session of that file's, completed when this one is. As libhdf5 does, the write
     * is refused if an element is mapped by no mapping, or by one whose source is missing ("write requested
     * to unmapped portion of virtual dataset"), or by several; and the check is made, and each source opened
     * to write it, before anything is written.
     */
    private void writeVirtual(DatasetSpec spec, String path, long[] offset, long[] count, byte[] bytes) {
        int[] nesting = VIRTUAL_NESTING.get();
        if (nesting[0] >= MAX_VIRTUAL_NESTING) {
            throw new HdfFormatException("virtual dataset sources nest more than " + MAX_VIRTUAL_NESTING
                    + " levels deep (a virtual dataset that maps itself?)");
        }
        nesting[0]++;
        try {
            Dataset dataset = (Dataset) spec.object;
            long wanted = elementCount(count);
            long mapped = 0;
            List<SourceWrite> writes = new ArrayList<>();
            for (VirtualDataset.Part part : dataset.virtualDataset().parts(spec.shape, offset, count)) {
                long n = part.pairs();
                int rank = part.selected().rank();
                long[] low = new long[rank];
                long[] high = new long[rank];
                java.util.Arrays.fill(low, Long.MAX_VALUE);
                java.util.Arrays.fill(high, Long.MIN_VALUE);
                long[] at = new long[rank];
                long[] inBox = new long[1];
                part.target().forEachInBox(offset, count, (position, coordinates) -> {
                    if (position < n) {
                        part.selected().coordinates(position, at);
                        for (int d = 0; d < rank; d++) {
                            low[d] = Math.min(low[d], at[d]);
                            high[d] = Math.max(high[d], at[d]);
                        }
                        inBox[0]++;
                    }
                });
                if (inBox[0] == 0) {
                    continue;
                }
                mapped += inBox[0];
                boolean swap = VirtualDataset.byteSwapNeeded(part.source().datatype(), spec.type, part.sourceName());
                DatasetWriter source = sourceDataset(part.source(), path);
                source.prepareWrite(low, high);
                writes.add(new SourceWrite(part, swap, source));
            }
            if (mapped != wanted) {
                throw new IllegalArgumentException("the box of virtual dataset " + path + " written holds " + wanted
                        + " elements, but its mappings to sources found map " + mapped
                        + (mapped < wanted ? " of them: write only elements a mapping covers, whose source exists"
                        : ": elements mapped more than once are not written") + " (libhdf5 refuses such a write too)");
            }
            for (SourceWrite write : writes) {
                write.write(offset, count, bytes, spec.elementSize);
            }
        } finally {
            nesting[0]--;
        }
    }

    /**
     * A source dataset of a virtual dataset of the file, opened to write it (P2 WF11): in this file, in this
     * session; in another, in a session of that file's, opened once.
     */
    private DatasetWriter sourceDataset(Dataset source, String virtualPath) {
        Hdf5Writer writer = this;
        if (source.ctx != existing.ctx) {
            Path file = source.ctx.path();
            if (file == null) {
                throw new HdfUnsupportedException("virtual dataset " + virtualPath + " maps " + source.path()
                        + " in a file read through a resolver: Falcon writes only files it opens from a path");
            }
            try {
                Path real = file.toRealPath();
                writer = sourceWriters.get(real);
                if (writer == null) {
                    writer = open(real);
                    sourceWriters.put(real, writer);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open " + file + ", a source of virtual dataset " + virtualPath
                        + ", to write it", e);
            }
        }
        return writer.fileDataset(source.objectHeaderAddress(), source.path());
    }

    /** A dataset of the file being changed, by its header's address, opened (once) to write it. */
    private DatasetWriter fileDataset(long address, String path) {
        ObjectSpec spec = opened.get(address);
        if (spec == null) {
            int slash = path.lastIndexOf('/');
            String name = path.substring(slash + 1);
            if (!(existing.object(slash <= 0 ? "/" : path.substring(0, slash), name, address) instanceof Dataset dataset)) {
                throw new IllegalArgumentException(path + " is not a dataset");
            }
            spec = existingDataset(name, dataset);
            opened.put(address, spec);
        }
        if (!(spec instanceof DatasetSpec dataset)) {
            throw new IllegalArgumentException(path + " is not a dataset");
        }
        return new DatasetWriter(this, dataset, display(path), legacy, lifecycle);
    }

    /** One source's share of a write through a virtual dataset: its mapping's part, and where it goes. */
    private record SourceWrite(VirtualDataset.Part part, boolean swap, DatasetWriter source) {

        /**
         * Writes the elements of the box {@code [offset, offset + count)} (whose bytes are {@code bytes},
         * row-major) that this part maps: in runs of elements next to each other both in the box and in the
         * source's last dimension.
         */
        void write(long[] offset, long[] count, byte[] bytes, int size) {
            SelectedElements selected = part.selected();
            long n = part.pairs();
            int rank = selected.rank();
            long[] boxStride = rowMajorStride(count);
            long[] at = new long[rank];
            long[] runAt = new long[rank];
            long[] run = new long[2]; // its length, and its first element's place in the box
            part.target().forEachInBox(offset, count, (position, coordinates) -> {
                if (position >= n) {
                    return;
                }
                long place = 0;
                for (int d = 0; d < coordinates.length; d++) {
                    place += (coordinates[d] - offset[d]) * boxStride[d];
                }
                selected.coordinates(position, at);
                if (run[0] > 0 && rank > 0 && place == run[1] + run[0] && continues(runAt, at, run[0])) {
                    run[0]++;
                    return;
                }
                if (run[0] > 0) {
                    flush(runAt, run[0], run[1], bytes, size);
                }
                System.arraycopy(at, 0, runAt, 0, rank);
                run[0] = 1;
                run[1] = place;
            });
            if (run[0] > 0) {
                flush(runAt, run[0], run[1], bytes, size);
            }
        }

        /** True if {@code at} is the element {@code length} after {@code start} in the last dimension. */
        private static boolean continues(long[] start, long[] at, long length) {
            int last = start.length - 1;
            for (int d = 0; d < last; d++) {
                if (at[d] != start[d]) {
                    return false;
                }
            }
            return at[last] == start[last] + length;
        }

        private void flush(long[] start, long length, long place, byte[] bytes, int size) {
            long[] runCount = new long[start.length];
            java.util.Arrays.fill(runCount, 1);
            if (runCount.length > 0) {
                runCount[runCount.length - 1] = length;
            }
            byte[] run = java.util.Arrays.copyOfRange(bytes, (int) (place * size), (int) ((place + length) * size));
            if (swap) {
                for (int e = 0; e < run.length; e += size) {
                    for (int b = 0; b < size / 2; b++) {
                        byte t = run[e + b];
                        run[e + b] = run[e + size - 1 - b];
                        run[e + size - 1 - b] = t;
                    }
                }
            }
            source.writeRaw(start.clone(), runCount, run);
        }
    }

    /**
     * The storage of a dataset of the file, made when data is first written to it: its block, inline data,
     * or chunks (which a rewritten chunk replaces, its index written anew), with its filters.
     *
     * @throws HdfUnsupportedException for a virtual dataset, or a filter Falcon does not write (a third-party one
     *         other than LZF, Blosc, LZ4, bitshuffle, and Zstandard)
     */
    private Storage existingStorage(DatasetSpec spec, String path) {
        Dataset dataset = (Dataset) spec.object;
        Storage storage = new Storage(spec);
        com.ebremer.falcon.hdf5.header.HeaderMessage external =
                dataset.header().find(com.ebremer.falcon.hdf5.header.MessageType.EXTERNAL_DATA_FILES);
        if (external != null) {
            // Its data in external raw files (P2 WF10): written there, the files found as the reader finds them.
            storage.external = com.ebremer.falcon.hdf5.message.ExternalFileList.parse(dataset.ctx, external.bodyOffset());
            storage.externalAccess = dataset.ctx.externalFileAccess();
            storage.externalDirectory = dataset.ctx.directory();
        }
        switch (dataset.dataLayout()) {
            case com.ebremer.falcon.hdf5.layout.DataLayout.Virtual virtual ->
                    throw new IllegalStateException("dataset " + path + " is virtual: it is written into its sources");
            case com.ebremer.falcon.hdf5.layout.DataLayout.Compact compact -> {
                if (compact.data().length != elementCount(spec.shape) * spec.elementSize) {
                    throw new HdfFormatException("dataset " + path + " holds " + compact.data().length + " bytes of compact data");
                }
                storage.compact = compact.data().clone();
            }
            case com.ebremer.falcon.hdf5.layout.DataLayout.Contiguous contiguous -> storage.address = contiguous.address();
            case com.ebremer.falcon.hdf5.layout.DataLayout.Chunked chunked -> {
                FilterPipeline pipeline = dataset.filterPipeline();
                if (pipeline != null && !pipeline.filters().isEmpty()) {
                    spec.filters.addAll(encoders(pipeline, spec, path));
                    storage.decoder = pipeline;
                    // Partial edge chunks stored unfiltered (H5D_CHUNK_DONT_FILTER_PARTIAL_CHUNKS): so they stay.
                    storage.partialEdgesUnfiltered = chunked.dontFilterPartialBoundChunks();
                }
                for (com.ebremer.falcon.hdf5.layout.ChunkRecord chunk : dataset.chunkIndex(chunked).all()) {
                    long[] scaled = new long[spec.chunkShape.length];
                    for (int d = 0; d < scaled.length; d++) {
                        scaled[d] = chunk.offset()[d] / spec.chunkShape[d];
                    }
                    Cell cell = new Cell(scaled);
                    storage.stored.put(cell, new ChunkIndexWriter.Entry(scaled, chunk.address(), chunk.size(), chunk.filterMask()));
                    if (storage.partialEdgesUnfiltered && storage.partial(cell)) {
                        storage.unfiltered.add(cell);
                    }
                }
            }
        }
        return storage;
    }

    /**
     * The writer's form of a dataset's filters, as the file applies them: the built-in ones, each with the
     * client data libhdf5 stored for it (P2 WF10: n-bit, scale-offset and szip of either coding included), and
     * LZF, Blosc, LZ4, bitshuffle, Zstandard and bzip2 with the client data their plugins stored (P2 S8).
     */
    private static List<FilterSpec> encoders(FilterPipeline pipeline, DatasetSpec spec, String path) {
        List<FilterSpec> filters = new ArrayList<>();
        for (FilterPipeline.Filter filter : pipeline.filters()) {
            int[] data = filter.clientData().clone();
            switch (filter.id()) {
                case Filters.DEFLATE -> filters.add(new FilterSpec(Filters.DEFLATE, data.length > 0 ? data[0] : 6, data));
                case Filters.SHUFFLE -> filters.add(new FilterSpec(Filters.SHUFFLE, 0, data));
                case Filters.FLETCHER32 -> filters.add(new FilterSpec(Filters.FLETCHER32, 0, data));
                case Filters.SZIP, Filters.NBIT, Filters.SCALEOFFSET -> {
                    // szip's four parameters; n-bit's through its datatype's size; scale-offset's up to its fill value
                    int needed = filter.id() == Filters.SZIP ? 4 : filter.id() == Filters.NBIT ? 5 : 8;
                    if (data.length < needed) {
                        throw new HdfFormatException("dataset " + path + " has " + data.length + " client-data values for filter "
                                + filter.id());
                    }
                    filters.add(new FilterSpec(filter.id(), 0, data));
                }
                case Filter.LZF, Filter.LZ4, Filter.ZSTD, Filter.BZIP2 -> filters.add(new FilterSpec(filter.id(), 0, data));
                case Filter.BLOSC, Filter.BITSHUFFLE -> {
                    // set_local stores Blosc's type and chunk size (4 values), bitshuffle's element size (3)
                    int needed = filter.id() == Filter.BLOSC ? 4 : 3;
                    if (data.length < needed) {
                        throw new HdfFormatException("dataset " + path + " has " + data.length + " client-data values for filter "
                                + filter.id());
                    }
                    if (filter.id() == Filter.BITSHUFFLE && data.length > 4 && data[4] != 0 && data[4] != BITSHUFFLE_LZ4
                            && data[4] != BITSHUFFLE_ZSTD) {
                        throw new HdfUnsupportedException("dataset " + path + " is bitshuffled with compression " + data[4]
                                + ", which Falcon does not write");
                    }
                    filters.add(new FilterSpec(filter.id(), 0, data));
                }
                default -> throw new HdfUnsupportedException("dataset " + path + " is filtered with "
                        + (filter.name() != null ? filter.name() + " " : "") + "(filter " + filter.id()
                        + "), which Falcon does not write");
            }
        }
        return filters;
    }

    /**
     * A link a group gains, as it is written: a hard link (to a group of the original format, with its symbol
     * table, for its entry's cache), or a soft or external one.
     */
    private record NewLink(String name, long address, long btree, long heap, String target, String file) {

        static NewLink hard(String name, GroupResult object) {
            return new NewLink(name, object.headerAddress(), object.btreeAddress(), object.heapAddress(), null, null);
        }

        boolean external() {
            return file != null;
        }

        /** Its Link message, with a creation order unless negative. */
        byte[] message(long creationOrder) {
            if (target == null) {
                return linkBody(name, address, creationOrder);
            }
            return file == null ? softLinkBody(name, target, creationOrder)
                    : externalLinkBody(name, file, target, creationOrder);
        }

        /** Its symbol-table entry. */
        SymbolChild entry() {
            if (target != null) {
                return new SymbolChild(name, UNDEFINED, 2, UNDEFINED, UNDEFINED, target);
            }
            return btree != UNDEFINED ? new SymbolChild(name, address, 1, btree, heap, null)
                    : new SymbolChild(name, address, 0, UNDEFINED, UNDEFINED, null);
        }
    }

    /**
     * The links a group gains in this session, with the objects they reach laid out: its subgroups and
     * datasets added (not those of the file opened in it, which it links already), and its links added.
     */
    private List<NewLink> addedLinks(GrowBuffer buf, GroupSpec group) {
        List<NewLink> links = new ArrayList<>();
        for (GroupSpec subgroup : group.groups) {
            GroupResult result = layOut(buf, subgroup);
            if (!subgroup.inFile()) {
                links.add(NewLink.hard(subgroup.name, result));
            }
        }
        for (DatasetSpec dataset : group.datasets) {
            GroupResult result = layOut(buf, dataset);
            if (!dataset.inFile()) {
                links.add(NewLink.hard(dataset.name, result));
            }
        }
        for (LinkSpec link : group.links) {
            if (link.object() != null) {
                links.add(NewLink.hard(link.name(), layOut(buf, link.object())));
            } else if (link.address() != UNDEFINED) {
                links.add(NewLink.hard(link.name(), fileObjectResult(buf, link.address())));
            } else {
                links.add(new NewLink(link.name(), UNDEFINED, UNDEFINED, UNDEFINED, link.target(), link.file()));
            }
        }
        return links;
    }

    /**
     * Lays out the changes to a group of the file: the objects added to it and the changes of those opened in
     * it, then its links (added and deleted) and attributes, through its object header's editor. Returns its
     * header and its symbol table (an original-format group's: as stored, or rebuilt; none once converted).
     */
    private GroupResult writeExistingGroup(GrowBuffer buf, GroupSpec group) {
        List<NewLink> added = addedLinks(buf, group);
        ObjectHeaderEditor editor = editor(group.address);
        boolean linksChanged = !added.isEmpty() || !group.deletedLinks.isEmpty();
        ObjectHeaderEditor.Message symbolTable = editor.find(17);
        GroupResult result = new GroupResult(group.address, UNDEFINED, UNDEFINED, false);
        if (symbolTable != null) {
            byte[] body = symbolTable.body();
            long btree = u64(body, 0);
            long heap = u64(body, 8);
            // A subgroup opened in it whose symbol table changed is cached anew in its entry.
            boolean cacheChanged = false;
            for (GroupSpec subgroup : group.groups) {
                GroupResult child = laidOut.get(subgroup);
                cacheChanged |= subgroup.inFile() && child != null && child.changed();
            }
            if (added.stream().anyMatch(NewLink::external)) {
                convertSymbolTable(buf, editor, symbolTable, group, added, btree, heap);
                result = new GroupResult(group.address, UNDEFINED, UNDEFINED, true);
            } else if (linksChanged || cacheChanged) {
                SymbolTable table = rewriteSymbolTable(buf, editor, symbolTable, group, added, btree, heap);
                result = new GroupResult(group.address, table.btree(), table.heap(), true);
            } else {
                result = new GroupResult(group.address, btree, heap, false);
            }
        } else if (linksChanged) {
            rewriteLinks(buf, editor, group, added);
        }
        rewriteAttributes(buf, editor, group);
        return result;
    }

    /** The original-format group's entries the session keeps (all but those deleted), as they are written now. */
    private List<SymbolChild> keptEntries(GrowBuffer buf, GroupSpec group, long btree, long heap) {
        List<SymbolChild> children = new ArrayList<>();
        for (ExistingFile.StoredEntry entry : existing.symbolEntries(btree, heap)) {
            if (group.deletedLinks.contains(entry.name())) {
                continue;
            }
            if (entry.cacheType() == 2) {
                children.add(new SymbolChild(entry.name(), UNDEFINED, 2, UNDEFINED, UNDEFINED, entry.softTarget()));
                continue;
            }
            // A group's symbol table: as laid out if it is opened (rebuilt, or gone), else as stored.
            GroupResult object = opened.containsKey(entry.address()) ? fileObjectResult(buf, entry.address())
                    : entry.cacheType() == 1 ? storedResult(entry.address()) : null;
            children.add(object == null || object.btreeAddress() == UNDEFINED
                    ? new SymbolChild(entry.name(), entry.address(), 0, UNDEFINED, UNDEFINED, null)
                    : new SymbolChild(entry.name(), entry.address(), 1, object.btreeAddress(), object.heapAddress(), null));
        }
        return children;
    }

    /**
     * Rebuilds an original-format group's symbol table: its entries but those deleted, those added, and the
     * groups' caches of their symbol tables, then points its Symbol Table message at the new one.
     */
    private SymbolTable rewriteSymbolTable(GrowBuffer buf, ObjectHeaderEditor editor, ObjectHeaderEditor.Message message,
                                           GroupSpec group, List<NewLink> added, long btree, long heap) {
        List<SymbolChild> children = keptEntries(buf, group, btree, heap);
        for (NewLink link : added) {
            children.add(link.entry());
        }
        SymbolTable table = writeSymbolTable(buf, children);
        editor.replace(message, symbolTableBody(table));
        return table;
    }

    /**
     * Converts an original-format group to the new format, as libhdf5 does when the group gains a link its
     * symbol table cannot hold, an external one ({@code H5G_obj_insert}): its entries (but those deleted) and
     * the links added become Link messages (in dense storage beyond 8), with Link Info and Group Info messages
     * in place of its Symbol Table message. Its old symbol table is left as unused space.
     */
    private void convertSymbolTable(GrowBuffer buf, ObjectHeaderEditor editor, ObjectHeaderEditor.Message message,
                                    GroupSpec group, List<NewLink> added, long btree, long heap) {
        List<ExistingFile.StoredLink> links = new ArrayList<>();
        for (SymbolChild child : keptEntries(buf, group, btree, heap)) {
            byte[] body = child.cacheType() == 2 ? softLinkBody(child.name(), child.linkValue())
                    : linkBody(child.name(), child.headerAddress());
            links.add(new ExistingFile.StoredLink(child.name(), body, -1));
        }
        for (NewLink link : added) {
            links.add(new ExistingFile.StoredLink(link.name(), link.message(-1), -1));
        }
        editor.remove(message);
        if (links.size() > MAX_COMPACT) {
            editor.add(2, 0, writeLinkStorage(buf, links, 0, -1));
        } else {
            editor.add(2, 0, linkInfoBody());
            for (ExistingFile.StoredLink link : links) {
                editor.add(6, 0, link.message());
            }
        }
        if (editor.find(10) == null) {
            editor.add(10, 0x01, new byte[] {0, 0});
        }
    }

    /**
     * Changes a new-style group's links: compact ones (Link messages) are removed or added in its header
     * while they stay within its compact limit (its Group Info message's, or 8); beyond, or already dense,
     * they are written anew densely (with a creation-order index if the group keeps one) and its Link Info
     * message points at them. In a group that tracks creation order, an added link takes the next one.
     */
    private void rewriteLinks(GrowBuffer buf, ObjectHeaderEditor editor, GroupSpec group, List<NewLink> added) {
        ObjectHeaderEditor.Message info = editor.find(2);
        if (info == null) {
            throw new HdfFormatException("group at " + group.address + " has neither a symbol table nor link info");
        }
        byte[] body = info.body();
        int flags = body[1] & 0xff;
        boolean tracked = (flags & 0x01) != 0;
        int p = 2;
        long nextOrder = -1; // libhdf5's "maximum creation index" is the next one to give (H5G_obj_insert)
        if (tracked) {
            nextOrder = u64(body, p);
            p += 8;
        }
        long heap = u64(body, p);
        long nameIndex = u64(body, p + 8);
        boolean dense = heap != UNDEFINED;
        List<ExistingFile.StoredLink> links = new ArrayList<>();
        if (dense) {
            for (ExistingFile.StoredLink link : existing.denseLinks(heap, nameIndex)) {
                if (!group.deletedLinks.contains(link.name())) {
                    links.add(link);
                }
            }
        } else {
            for (ObjectHeaderEditor.Message message : editor.messages(6)) {
                ExistingFile.StoredLink link = ExistingFile.storedLink(message.body());
                if (group.deletedLinks.contains(link.name())) {
                    editor.remove(message);
                } else {
                    links.add(link);
                }
            }
        }
        List<ExistingFile.StoredLink> fresh = new ArrayList<>();
        for (NewLink link : added) {
            long order = tracked ? nextOrder++ : -1;
            fresh.add(new ExistingFile.StoredLink(link.name(), link.message(order), order));
        }
        if (tracked) {
            putU64(body, 2, nextOrder);
        }
        int maxCompact = MAX_COMPACT;
        int minDense = MIN_DENSE;
        ObjectHeaderEditor.Message groupInfo = editor.find(10);
        if (groupInfo != null && (groupInfo.body()[1] & 0x01) != 0) {
            byte[] g = groupInfo.body();
            maxCompact = (g[2] & 0xff) | (g[3] & 0xff) << 8;
            minDense = (g[4] & 0xff) | (g[5] & 0xff) << 8;
        }
        int total = links.size() + fresh.size();
        if (!dense && total <= maxCompact) {
            for (ExistingFile.StoredLink link : fresh) {
                editor.add(6, 0, link.message());
            }
            if (tracked) {
                editor.replace(info, body);
            }
            return;
        }
        if (dense && total < minDense) {
            // Fewer than the group's minimum for dense storage: its links go back into its header, as libhdf5
            // moves them (H5G__obj_remove_update_linfo), and its Link Info message points at no heap or index.
            links.addAll(fresh);
            for (ExistingFile.StoredLink link : links) {
                editor.add(6, 0, link.message());
            }
            for (int at = tracked ? 10 : 2; at < body.length; at += 8) {
                putU64(body, at, UNDEFINED);
            }
            editor.replace(info, body);
            return;
        }
        links.addAll(fresh);
        editor.replace(info, writeLinkStorage(buf, links, flags, nextOrder));
        if (!dense) {
            for (ObjectHeaderEditor.Message message : editor.messages(6)) {
                editor.remove(message);
            }
        }
    }

    /**
     * Changes an object's attributes in the file: those deleted or replaced go, those added come. In a
     * version-1 header (no dense storage) they are messages in it; in version 2, messages while they stay
     * within the header's compact limit (its own, or 8), else all written anew densely (with a creation-order
     * index if the object keeps one) and its Attribute Info message points at them. In an object that tracks
     * creation order, an added attribute takes the next one.
     */
    private void rewriteAttributes(GrowBuffer buf, ObjectHeaderEditor editor, ObjectSpec spec) {
        if (spec.attributes.isEmpty() && spec.deletedAttributes.isEmpty()) {
            return;
        }
        Set<String> gone = spec.deletedAttributes;
        if (editor.version() == 1) {
            for (ObjectHeaderEditor.Message message : editor.messages(12)) {
                if (gone.contains(storedAttribute(message).name())) {
                    removeAttribute(editor, message);
                }
            }
            for (AttributeSpec attribute : spec.attributes) {
                editor.add(12, 0, attributeBody(attribute, legacy));
            }
            return;
        }
        ObjectHeaderEditor.Message info = editor.find(21);
        int infoFlags = 0;
        int nextOrder = -1; // libhdf5's "maximum creation index" is the next one to give
        long heap = UNDEFINED;
        long nameIndex = UNDEFINED;
        byte[] infoBody = null;
        if (info != null) {
            infoBody = info.body();
            infoFlags = infoBody[1] & 0xff;
            int p = 2;
            if ((infoFlags & 0x01) != 0) {
                nextOrder = (infoBody[2] & 0xff) | (infoBody[3] & 0xff) << 8;
                p += 2;
            }
            heap = u64(infoBody, p);
            nameIndex = u64(infoBody, p + 8);
        } else if ((editor.headerFlags() & 0x04) != 0) {
            infoFlags = (editor.headerFlags() >> 2) & 0x03; // tracked, and indexed, as the header says
        }
        boolean tracked = (infoFlags & 0x01) != 0;
        boolean dense = heap != UNDEFINED;
        List<ExistingFile.StoredAttribute> attributes = new ArrayList<>();
        if (dense) {
            for (ExistingFile.StoredAttribute attribute : existing.denseAttributes(heap, nameIndex)) {
                if (!gone.contains(attribute.name())) {
                    attributes.add(attribute);
                } else if (attribute.sharedId() != null) {
                    sharedReleases.add(new SharedMessages.Release(12, attribute.sharedId()));
                }
            }
        } else {
            for (ObjectHeaderEditor.Message message : editor.messages(12)) {
                ExistingFile.StoredAttribute attribute = storedAttribute(message);
                if (gone.contains(attribute.name())) {
                    removeAttribute(editor, message);
                } else {
                    attributes.add(attribute);
                }
            }
        }
        if (tracked && nextOrder < 0) {
            nextOrder = 0;
            for (ExistingFile.StoredAttribute attribute : attributes) {
                nextOrder = Math.max(nextOrder, attribute.creationOrder() + 1);
            }
        }
        List<ExistingFile.StoredAttribute> fresh = new ArrayList<>();
        for (AttributeSpec attribute : spec.attributes) {
            int order = tracked ? nextOrder++ : 0;
            fresh.add(new ExistingFile.StoredAttribute(attribute.name(), 0, order, attributeBody(attribute, legacy)));
        }
        int total = attributes.size() + fresh.size();
        if (!dense && total <= editor.maxCompactAttributes()) {
            for (ExistingFile.StoredAttribute attribute : fresh) {
                editor.add(12, 0, attribute.message(), attribute.creationOrder());
            }
            if (tracked && info != null && (infoFlags & 0x01) != 0) {
                infoBody[2] = (byte) nextOrder;
                infoBody[3] = (byte) (nextOrder >>> 8);
                editor.replace(info, infoBody);
            }
            return;
        }
        if (dense && total < editor.minDenseAttributes()) {
            // Fewer than the object's minimum for dense storage: its attributes go back into its header, as
            // libhdf5 moves them (H5O__attr_remove_update), and its Attribute Info message points at no heap or
            // index.
            attributes.addAll(fresh);
            for (ExistingFile.StoredAttribute attribute : attributes) {
                editor.add(12, attribute.flags(), attribute.message(), attribute.creationOrder());
            }
            if ((infoFlags & 0x01) != 0) {
                infoBody[2] = (byte) nextOrder;
                infoBody[3] = (byte) (nextOrder >>> 8);
            }
            for (int at = (infoFlags & 0x01) != 0 ? 4 : 2; at < infoBody.length; at += 8) {
                putU64(infoBody, at, UNDEFINED);
            }
            editor.replace(info, infoBody);
            return;
        }
        attributes.addAll(fresh);
        byte[] storage = writeAttributeStorage(buf, attributes, infoFlags, nextOrder);
        if (info != null) {
            editor.replace(info, storage);
        } else {
            editor.add(21, 0, storage);
        }
        if (!dense) {
            for (ObjectHeaderEditor.Message message : editor.messages(12)) {
                editor.remove(message);
            }
        }
    }

    /**
     * An attribute message of an object's header: its name, flags, creation order and body; for one kept in
     * the shared-message table (flagged shared), its heap ID, and the name from the heap's copy.
     */
    private ExistingFile.StoredAttribute storedAttribute(ObjectHeaderEditor.Message message) {
        byte[] body = message.body();
        if ((message.flags() & 0x02) == 0) {
            return new ExistingFile.StoredAttribute(ExistingFile.attributeName(body), message.flags(),
                    message.creationOrder(), body, null);
        }
        byte[] heapId = ExistingFile.sharedId(body);
        if (heapId == null) {
            throw new HdfUnsupportedException("an attribute shared otherwise than in the shared-message table");
        }
        return new ExistingFile.StoredAttribute(ExistingFile.attributeName(existing.sharedMessage(heapId, 12)),
                message.flags(), message.creationOrder(), java.util.Arrays.copyOf(body, 10), heapId);
    }

    /** Removes an attribute message; one kept in the shared-message table is released there. */
    private void removeAttribute(ObjectHeaderEditor editor, ObjectHeaderEditor.Message message) {
        if ((message.flags() & 0x02) != 0) {
            sharedReleases.add(new SharedMessages.Release(12, storedAttribute(message).sharedId()));
        }
        editor.remove(message);
    }

    /**
     * Lays out the changes to a dataset of the file: if data was written to it, its layout (a new chunk
     * index, a block allocated, compact data) and its shape (grown); and its attributes.
     */
    private void writeExistingDataset(GrowBuffer buf, DatasetSpec dataset) {
        ObjectHeaderEditor editor = editor(dataset.address);
        if (dataset.storage != null) {
            byte[] layout = dataset.storage.layout(buf);
            ObjectHeaderEditor.Message message = editor.find(8);
            if (message == null) {
                throw new HdfFormatException("dataset at " + dataset.address + " has no layout message");
            }
            byte[] old = message.body();
            if (layout != null && (!java.util.Arrays.equals(java.util.Arrays.copyOf(old, layout.length), layout)
                    || old.length - layout.length >= 8)) {
                editor.replace(message, layout);
            }
            if (!java.util.Arrays.equals(dataset.shape, dataset.fileShape)) {
                ObjectHeaderEditor.Message space = editor.find(1);
                if ((space.flags() & 0x02) != 0) {
                    // Shared in the shared-message table with datasets of the old shape: this one's own now, as
                    // libhdf5 stops sharing a message it changes, and released there.
                    byte[] heapId = ExistingFile.sharedId(space.body());
                    if (heapId == null) {
                        throw new HdfUnsupportedException("dataset at " + dataset.address + " shares its dataspace with"
                                + " another object's header, which Falcon does not change");
                    }
                    int flags = space.flags() & ~0x02;
                    editor.remove(space);
                    editor.add(1, flags, dataspaceBody(dataset.shape, dataset.maxShape, editor.version() == 1));
                    sharedReleases.add(new SharedMessages.Release(1, heapId));
                } else {
                    byte[] body = space.body();
                    int dimensions = body[0] == 1 ? 8 : 4; // after version 1's reserved bytes, or version 2's type
                    if ((space.flags() & 0x40) != 0) {
                        // Kept here, marked shareable: counted in the shared-message table by its encoding, which
                        // changes, so released there and no longer shareable (libhdf5 would share it anew).
                        int encoded = dimensions + 8 * dataset.shape.length * ((body[2] & 0x01) != 0 ? 2 : 1);
                        sharedReleases.add(new SharedMessages.Release(1, null,
                                java.util.Arrays.copyOf(body, encoded), dataset.address));
                        int flags = space.flags() & ~0x40;
                        for (int d = 0; d < dataset.shape.length; d++) {
                            putU64(body, dimensions + 8 * d, dataset.shape[d]);
                        }
                        editor.remove(space);
                        editor.add(1, flags, java.util.Arrays.copyOf(body, encoded));
                    } else {
                        for (int d = 0; d < dataset.shape.length; d++) {
                            putU64(body, dimensions + 8 * d, dataset.shape[d]);
                        }
                        editor.replace(space, body);
                    }
                }
            }
        }
        rewriteAttributes(buf, editor, dataset);
    }

    private static long u64(byte[] b, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (b[at + i] & 0xffL) << (8 * i);
        }
        return value;
    }

    // --------------------------------------------------------------- serialization

    /**
     * A laid-out object: its object-header address; a group of the original format's symbol table (B-tree and
     * heap, else undefined); and, for a group of the file, whether that changed in this session.
     */
    private record GroupResult(long headerAddress, long btreeAddress, long heapAddress, boolean changed) {
    }

    /** One child of a legacy (symbol-table) group. */
    private record SymbolChild(String name, long headerAddress, int cacheType, long btree, long heap,
                               String linkValue) {
    }

    private GroupResult writeGroup(GrowBuffer buf, GroupSpec group) {
        List<NewLink> added = addedLinks(buf, group);
        int references = referenceCounts.getOrDefault(group, 1);
        if (legacy && added.stream().noneMatch(NewLink::external)) {
            List<SymbolChild> children = new ArrayList<>();
            for (NewLink link : added) {
                children.add(link.entry());
            }
            return writeSymbolTableGroup(buf, children, group.attributes, references);
        }
        // The modern format; or in the earliest, a group with an external link, which a symbol table cannot
        // hold: libhdf5 gives such a group the new format's link messages, in its version-1 header.
        List<NamedLink> links = new ArrayList<>();
        for (NewLink link : added) {
            links.add(new NamedLink(link.name(), link.message(-1)));
        }
        byte[] linkInfo = links.size() > MAX_COMPACT ? writeDenseLinks(buf, links) : null;
        byte[] attributeInfo = !legacy && group.attributes.size() > MAX_COMPACT
                ? writeDenseAttributes(buf, group.attributes) : null;
        buf.align(8);
        long headerAddress = buf.position();
        writeGroupHeader(buf, links, group.attributes, linkInfo, attributeInfo, references);
        return new GroupResult(headerAddress, UNDEFINED, UNDEFINED, false);
    }

    /** A link of a group: its name and its Link message body. */
    private record NamedLink(String name, byte[] body) {
    }

    private long writeDataset(GrowBuffer buf, DatasetSpec dataset) {
        byte[] layout = dataset.storage.layout(buf);
        // Dense attribute structures are written before the object header so it can reference them. The
        // earliest format has no dense storage: its version-1 headers hold every attribute.
        byte[] attributeInfo = !legacy && dataset.attributes.size() > MAX_COMPACT
                ? writeDenseAttributes(buf, dataset.attributes) : null;
        buf.align(8);
        long headerAddress = buf.position();

        byte[] datatype = dataset.nbitPrecision >= 0
                ? nbitDatatype(dataset.elementSize, dataset.nbitPrecision) : dataset.datatype;
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(1, 0x00, dataspaceBody(dataset.shape, dataset.maxShape, legacy)));
        messages.add(new Message(3, 0x01, datatype));
        messages.add(new Message(5, 0x01, fillValueBody(dataset.fillValue, legacy)));
        messages.add(new Message(8, 0x00, layout));
        if (!dataset.filters.isEmpty()) {
            messages.add(new Message(11, 0x00, filterPipelineBody(dataset, legacy)));
        }
        if (attributeInfo != null) {
            messages.add(new Message(21, 0x00, attributeInfo));
        } else {
            for (AttributeSpec attribute : dataset.attributes) {
                messages.add(new Message(12, 0x00, attributeBody(attribute, legacy)));
            }
        }
        writeObjectHeader(buf, messages, referenceCounts.getOrDefault(dataset, 1));
        return headerAddress;
    }

    /** The bit of an szip filter's parameter (its pixels per block) that selects nearest-neighbour coding. */
    private static final int SZIP_NN_PARAMETER = 1 << 16;
    // Filter flags in the pipeline message: libhdf5 marks every filter optional except fletcher32.
    private static final int FILTER_OPTIONAL = 1;
    private static final int FILTER_MANDATORY = 0;

    /** One encoded chunk: its stored bytes, and the mask of filters that were skipped for it. */
    private record EncodedChunk(byte[] bytes, int filterMask) {
    }

    // --------------------------------------------------------------- storage

    /**
     * The dataset's storage, made when data is first written to it (or it grows), or when the file is
     * completed. A dataset given its data when it was made writes it now.
     */
    private Storage storage(DatasetSpec spec, String path) {
        if (spec.storage == null && spec.inFile()) {
            spec.storage = existingStorage(spec, path);
        }
        if (spec.storage == null) {
            boolean grows = spec.maxShape != null && !java.util.Arrays.equals(spec.maxShape, spec.shape);
            if (grows && spec.chunkShape == null) {
                throw new IllegalStateException("dataset " + path + " can grow, so it must be chunked");
            }
            Storage storage = new Storage(spec);
            spec.storage = storage;
            if (spec.type == null && elementCount(spec.shape) > 0) {
                storage.write(new long[spec.shape.length], spec.shape.clone(), givenData(spec));
            }
            spec.data = null; // in the file now (or, compact, in the storage)
            spec.vlenStrings = null;
            spec.referenceTargets = null;
        }
        return spec.storage;
    }

    /**
     * Writes the data of the dataset last given its data whole, if any (P2 WF9): its filters and layout
     * may be set until the next dataset or group is added, so only that one dataset's data is held.
     */
    private void writeGivenData() {
        DatasetSpec dataset = unwritten;
        if (dataset != null) {
            unwritten = null;
            storage(dataset, unwrittenPath);
        }
    }

    /** The data a dataset was given when it was made, as encoded elements. */
    private ValueEncoder.Encoded givenData(DatasetSpec spec) {
        if (spec.vlenStrings != null) {
            int n = spec.vlenStrings.size();
            byte[] ids = new byte[n * 16];
            List<ValueEncoder.IdPatch> patches = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                byte[] payload = spec.vlenStrings.get(i);
                // A vlen ID's length field is the string's byte length or the sequence's element count.
                writeU32(ids, i * 16, spec.vlenElementCounts != null ? spec.vlenElementCounts[i] : payload.length);
                if (payload.length > 0) {
                    long[] slot = heaps.add(payload);
                    patches.add(new ValueEncoder.IdPatch(i * 16L + 4, (int) slot[0]));
                    writeU32(ids, i * 16 + 12, slot[1]);
                }
            }
            return new ValueEncoder.Encoded(ids, patches, List.of());
        }
        if (spec.referenceTargets != null) {
            List<ValueEncoder.RefPatch> refs = new ArrayList<>();
            for (int i = 0; i < spec.referenceTargets.size(); i++) {
                if (spec.referenceTargets.get(i) != null) { // a null reference stays all zeros, as h5py writes it
                    refs.add(new ValueEncoder.RefPatch(i * 8L, spec.referenceTargets.get(i)));
                }
            }
            return new ValueEncoder.Encoded(new byte[spec.referenceTargets.size() * 8], List.of(), refs);
        }
        return new ValueEncoder.Encoded(spec.data, List.of(), List.of());
    }

    private static void writeU32(byte[] out, int at, long value) {
        for (int b = 0; b < 4; b++) {
            out[at + b] = (byte) (value >>> (8 * b));
        }
    }

    /** A cell of a chunk grid, by its grid coordinates. */
    private record Cell(long[] scaled) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Cell c && java.util.Arrays.equals(scaled, c.scaled);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(scaled);
        }
    }

    /** A byte within the chunk at {@code cell}. */
    private record ChunkByte(Cell cell, int offset) {
    }

    /**
     * A chunk being written: its elements so far (the rest the fill value), and its ids and object
     * references still to fill in.
     */
    private static final class Pending {
        final byte[] data;
        final java.util.BitSet written;
        final long needed;                                       // the elements it holds within the extent's bounds
        final Map<Integer, Integer> ids = new HashMap<>();       // byte offset of an id's address -> collection
        final Map<Integer, String> refs = new HashMap<>();       // byte offset of an object reference -> its target

        Pending(byte[] data, java.util.BitSet written, long needed) {
            this.data = data;
            this.written = written;
            this.needed = needed;
        }
    }

    /**
     * Where a dataset's data goes as it is written: a contiguous block, allocated in the file at the first
     * write; data held for the object header (compact); or chunks, each written and filtered once all of
     * its elements within the dataset's bounds are (its maximum shape, or its shape if it cannot grow),
     * held in memory until then. A chunk written again is read back, decoded, and written anew.
     */
    private final class Storage {
        private final DatasetSpec spec;
        private final int size;
        // Chunks holding object references are kept until close(), when their targets' addresses are known
        // (P2 WF8): a filtered chunk cannot be patched in the file afterwards.
        private final boolean holdsAddresses;
        private long address = UNDEFINED;                                   // contiguous: the block
        private com.ebremer.falcon.hdf5.message.ExternalFileList external;   // ... or external raw files
        private ExternalFileAccess externalAccess;
        private Path externalDirectory;
        private byte[] compact;                                             // compact: the data
        private final Map<Long, Integer> compactIds = new HashMap<>();
        private final Map<Long, String> compactRefs = new HashMap<>();
        private final Map<Cell, ChunkIndexWriter.Entry> stored = new HashMap<>();
        private final Map<Cell, Pending> pending = new HashMap<>();
        private FilterPipeline decoder;
        // A dataset of the file whose partial edge chunks are stored unfiltered (P2 WF10), and the chunks
        // stored so: chunks past its extent in some dimension, as libhdf5 tells them.
        private boolean partialEdgesUnfiltered;
        private final Set<Cell> unfiltered = new HashSet<>();

        Storage(DatasetSpec spec) {
            this.spec = spec;
            this.size = spec.elementSize;
            this.holdsAddresses = spec.type != null ? ValueEncoder.holdsObjectReferences(spec.type)
                    : spec.referenceTargets != null;
        }

        /** True if this dataset's chunks or compact data wait for object addresses, known only on close. */
        boolean waitsForAddresses() {
            return holdsAddresses && (spec.compact ? !compactRefs.isEmpty() : !pending.isEmpty());
        }

        /** Writes the box {@code [offset, offset + count)} of encoded elements. */
        void write(long[] offset, long[] count, ValueEncoder.Encoded data) {
            if (spec.nbitPrecision >= 0) {
                for (int i = 0; i < data.bytes().length / size; i++) {
                    long value = littleEndianSigned(data.bytes(), i * size, size);
                    if (!fitsUnsigned(value, spec.nbitPrecision)) {
                        throw new IllegalArgumentException("n-bit(" + spec.nbitPrecision + ") stores unsigned "
                                + spec.nbitPrecision + "-bit values, but element " + i + " is " + value);
                    }
                }
            }
            if (elementCount(count) == 0) {
                return;
            }
            if (spec.compact) {
                writeCompact(offset, count, data);
            } else if (spec.chunkShape == null) {
                writeContiguous(offset, count, data);
            } else {
                writeChunks(offset, count, data);
            }
        }

        private void writeCompact(long[] offset, long[] count, ValueEncoder.Encoded data) {
            if (compact == null) {
                compact = new byte[Math.toIntExact(elementCount(spec.shape) * size)];
                tile(compact, spec.fillValue, size);
            }
            long[] stride = rowMajorStride(spec.shape);
            forEachRow(offset, count, (at, index, run) -> System.arraycopy(data.bytes(), (int) (index * size),
                    compact, (int) (dot(at, stride) * size), (int) (run * size)));
            for (ValueEncoder.IdPatch id : data.ids()) {
                compactIds.put(datasetByte(id.offset(), offset, count, stride), id.collection());
            }
            for (ValueEncoder.RefPatch ref : data.refs()) {
                compactRefs.put(datasetByte(ref.offset(), offset, count, stride), ref.path());
            }
        }

        private void writeContiguous(long[] offset, long[] count, ValueEncoder.Encoded data) {
            if (external != null) {
                writeExternal(offset, count, data);
                return;
            }
            if (address == UNDEFINED) {
                long bytes = elementCount(spec.shape) * size;
                address = output().allocate(bytes);
                if (spec.fillValue != null && !allZero(spec.fillValue)) {
                    output().fill(address, bytes, spec.fillValue);
                }
            }
            long[] stride = rowMajorStride(spec.shape);
            forEachRun(offset, count, spec.shape, (at, index, run) -> output().write(address + dot(at, stride) * size,
                    data.bytes(), (int) (index * size), (int) (run * size)));
            for (ValueEncoder.IdPatch id : data.ids()) {
                heaps.idAt(address + datasetByte(id.offset(), offset, count, stride), id.collection());
            }
            for (ValueEncoder.RefPatch ref : data.refs()) {
                fileReferences.add(new FileReference(address + datasetByte(ref.offset(), offset, count, stride), ref.path()));
            }
        }

        /**
         * Writes a box of a dataset whose data is in external raw files, into their slots, now. Variable-length
         * ids are filled in first (placing their heap collection); object references, whose addresses are
         * known only on close, are not written there.
         */
        private void writeExternal(long[] offset, long[] count, ValueEncoder.Encoded data) {
            if (!data.refs().isEmpty()) {
                throw new HdfUnsupportedException("dataset " + spec.name + " keeps its data in external files, where"
                        + " Falcon does not write object references");
            }
            byte[] bytes = data.bytes();
            if (!data.ids().isEmpty()) {
                bytes = bytes.clone();
                for (ValueEncoder.IdPatch id : data.ids()) {
                    putU64(bytes, (int) id.offset(), heaps.address(id.collection()));
                }
            }
            long[] stride = rowMajorStride(spec.shape);
            byte[] source = bytes;
            forEachRun(offset, count, spec.shape, (at, index, run) ->
                    writeExternal(dot(at, stride) * size, source, (int) (index * size), (int) (run * size)));
        }

        /** Writes {@code length} bytes at byte {@code position} of the data, into the slots that hold it. */
        private void writeExternal(long position, byte[] bytes, int from, int length) {
            long start = 0;
            for (int i = 0; i < external.slots() && length > 0; i++) {
                long end = external.size(i) < 0 ? Long.MAX_VALUE : start + external.size(i);
                if (position < end) {
                    int n = (int) Math.min(length, end - position);
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes, from, n);
                    long at = external.fileOffset(i) + position - start;
                    java.nio.channels.FileChannel channel = externalFile(external.name(i));
                    try {
                        while (buffer.hasRemaining()) {
                            at += channel.write(buffer, at);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException("cannot write external raw data file " + external.name(i), e);
                    }
                    position += n;
                    from += n;
                    length -= n;
                }
                start = end;
            }
            if (length > 0) {
                throw new HdfFormatException("dataset " + spec.name + ": its external files hold fewer bytes than its data");
            }
        }

        /** The external raw data file {@code name}, opened to write (created, as libhdf5 creates it, if missing). */
        private java.nio.channels.FileChannel externalFile(String name) {
            Path file = externalAccess.resolve(name, externalDirectory, "external raw data file");
            return externalFiles.computeIfAbsent(file, f -> {
                try {
                    return java.nio.channels.FileChannel.open(f, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE);
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot open external raw data file " + f, e);
                }
            });
        }

        /** The byte, from the dataset's start, of byte {@code boxByte} of a box's encoded elements. */
        private long datasetByte(long boxByte, long[] offset, long[] count, long[] stride) {
            long element = boxByte / size;
            long flat = 0;
            for (int d = count.length - 1; d >= 0; d--) {
                flat += (offset[d] + element % count[d]) * stride[d];
                element /= count[d];
            }
            return flat * size + boxByte % size;
        }

        private void writeChunks(long[] offset, long[] count, ValueEncoder.Encoded data) {
            long[] chunk = spec.chunkShape;
            int rank = chunk.length;
            long[] first = new long[rank];
            long[] last = new long[rank];
            for (int d = 0; d < rank; d++) {
                first[d] = offset[d] / chunk[d];
                last[d] = (offset[d] + count[d] - 1) / chunk[d];
            }
            long[] countStride = rowMajorStride(count);
            long[] chunkStride = rowMajorStride(chunk);
            List<Cell> touched = new ArrayList<>();
            long[] cell = first.clone();
            while (true) {
                long[] origin = new long[rank];
                long[] lo = new long[rank];
                long[] extent = new long[rank];
                for (int d = 0; d < rank; d++) {
                    origin[d] = cell[d] * chunk[d];
                    lo[d] = Math.max(offset[d], origin[d]);
                    extent[d] = Math.min(offset[d] + count[d], origin[d] + chunk[d]) - lo[d];
                }
                Cell key = new Cell(cell.clone());
                Pending p = pending(key, origin);
                forEachRow(lo, extent, (at, index, run) -> {
                    long src = 0;
                    long dst = 0;
                    for (int d = 0; d < rank; d++) {
                        src += (at[d] - offset[d]) * countStride[d];
                        dst += (at[d] - origin[d]) * chunkStride[d];
                    }
                    System.arraycopy(data.bytes(), (int) (src * size), p.data, (int) (dst * size), (int) (run * size));
                    p.written.set((int) dst, (int) (dst + run));
                });
                touched.add(key);
                int d = rank - 1;
                while (d >= 0) {
                    if (++cell[d] <= last[d]) {
                        break;
                    }
                    cell[d] = first[d];
                    d--;
                }
                if (d < 0) {
                    break;
                }
            }
            for (ValueEncoder.IdPatch id : data.ids()) {
                ChunkByte at = chunkByte(id.offset(), offset, count, chunkStride);
                pending.get(at.cell()).ids.put(at.offset(), id.collection());
            }
            for (ValueEncoder.RefPatch ref : data.refs()) {
                ChunkByte at = chunkByte(ref.offset(), offset, count, chunkStride);
                pending.get(at.cell()).refs.put(at.offset(), ref.path());
            }
            for (Cell key : touched) {
                Pending p = pending.get(key);
                if (p != null && !holdsAddresses && p.written.cardinality() >= p.needed) {
                    flush(key, p);
                }
            }
        }

        /** The chunk, and the byte within it, of byte {@code boxByte} of a box's encoded elements. */
        private ChunkByte chunkByte(long boxByte, long[] offset, long[] count, long[] chunkStride) {
            long[] chunk = spec.chunkShape;
            int rank = chunk.length;
            long element = boxByte / size;
            long[] scaled = new long[rank];
            long[] at = new long[rank];
            for (int d = rank - 1; d >= 0; d--) {
                at[d] = offset[d] + element % count[d];
                element /= count[d];
            }
            long dst = 0;
            for (int d = 0; d < rank; d++) {
                scaled[d] = at[d] / chunk[d];
                dst += (at[d] - scaled[d] * chunk[d]) * chunkStride[d];
            }
            return new ChunkByte(new Cell(scaled), (int) (dst * size + boxByte % size));
        }

        /** The chunk being written at {@code cell}: one already pending, or a stored one read back, or a new one. */
        private Pending pending(Cell cell, long[] origin) {
            Pending p = pending.get(cell);
            if (p == null) {
                int elements = Math.toIntExact(elementCount(spec.chunkShape));
                java.util.BitSet written = new java.util.BitSet(elements);
                ChunkIndexWriter.Entry entry = stored.remove(cell);
                byte[] data;
                if (entry != null) {
                    data = readBack(entry, elements * size, unfiltered.remove(cell));
                    written.set(0, elements);
                } else {
                    data = new byte[elements * size];
                    tile(data, spec.fillValue, size);
                }
                long needed = 1;
                for (int d = 0; d < origin.length; d++) {
                    long bound = spec.maxShape == null ? spec.shape[d]
                            : spec.maxShape[d] == UNLIMITED ? Long.MAX_VALUE : spec.maxShape[d];
                    needed *= Math.max(0, Math.min(spec.chunkShape[d], bound - origin[d]));
                }
                p = new Pending(data, written, needed);
                pending.put(cell, p);
            }
            return p;
        }

        private byte[] readBack(ChunkIndexWriter.Entry entry, int chunkBytes, boolean storedUnfiltered) {
            byte[] stored = output().read(entry.address(), entry.size());
            if (spec.filters.isEmpty() || storedUnfiltered) {
                return storedUnfiltered ? java.util.Arrays.copyOf(stored, chunkBytes) : stored;
            }
            if (decoder == null) {
                decoder = FilterPipelineMessage.parse(HdfBuffer.of(filterPipelineBody(spec, legacy)), 0);
            }
            return java.util.Arrays.copyOf(decoder.decode(stored, entry.filterMask(), size, chunkBytes), chunkBytes);
        }

        /** Writes a chunk: its ids filled in (placing their heap collections), filtered, at the end of the file. */
        private void flush(Cell cell, Pending p) {
            for (Map.Entry<Integer, Integer> id : p.ids.entrySet()) {
                putU64(p.data, id.getKey(), heaps.address(id.getValue()));
            }
            for (Map.Entry<Integer, String> ref : p.refs.entrySet()) {
                putU64(p.data, ref.getKey(), addressOf(ref.getValue()));
            }
            store(cell, p.data);
            pending.remove(cell);
        }

        /**
         * Writes a chunk's elements at the end of the file: filtered, or as they are for a partial edge chunk
         * of a dataset that keeps those unfiltered.
         */
        private void store(Cell cell, byte[] data) {
            boolean raw = keepsPartialEdgesUnfiltered() && partial(cell);
            EncodedChunk encoded = spec.filters.isEmpty() || raw ? new EncodedChunk(data, 0) : encodeChunk(data, spec);
            long at = output().allocate(encoded.bytes().length);
            output().write(at, encoded.bytes());
            stored.put(cell, new ChunkIndexWriter.Entry(cell.scaled(), at, encoded.bytes().length, encoded.filterMask()));
            if (raw) {
                unfiltered.add(cell);
            } else {
                unfiltered.remove(cell);
            }
        }

        /** True if the chunk at {@code cell} reaches past the dataset's extent ({@code H5D__chunk_is_partial_edge_chunk}). */
        boolean partial(Cell cell) {
            for (int d = 0; d < spec.chunkShape.length; d++) {
                if ((cell.scaled()[d] + 1) * spec.chunkShape[d] > spec.shape[d]) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the chunks are indexed by a version-1 B-tree (a dataset that can grow, or the earliest format). */
        private boolean usesBTree() {
            return legacy || (spec.maxShape != null && !java.util.Arrays.equals(spec.maxShape, spec.shape));
        }

        /**
         * Whether partial edge chunks stay unfiltered: in a dataset of the file that stores them so, while its
         * chunks are indexed by a fixed array, whose layout message (version 4) records it. A version-1
         * B-tree's layout (version 3) cannot, so there every chunk is filtered.
         */
        private boolean keepsPartialEdgesUnfiltered() {
            return partialEdgesUnfiltered && !usesBTree();
        }

        /**
         * Writes the chunks still pending, partly written or not (their unwritten elements are the fill
         * value), but those waiting for object addresses: see {@link #writeHeld}.
         */
        void finish() {
            if (!holdsAddresses) {
                for (Cell cell : new ArrayList<>(pending.keySet())) {
                    flush(cell, pending.get(cell));
                }
            }
            // Chunks stored unfiltered that are no longer partial edge chunks (the dataset grew), or all of
            // them under a B-tree index, are filtered now, as libhdf5 does when a dataset grows.
            for (Cell cell : new ArrayList<>(unfiltered)) {
                if (!keepsPartialEdgesUnfiltered() || !partial(cell)) {
                    ChunkIndexWriter.Entry entry = stored.get(cell);
                    int chunkBytes = Math.toIntExact(elementCount(spec.chunkShape) * size);
                    store(cell, readBack(entry, chunkBytes, true));
                }
            }
            if (compact != null) {
                for (Map.Entry<Long, Integer> id : compactIds.entrySet()) {
                    putU64(compact, (int) (long) id.getKey(), heaps.address(id.getValue()));
                }
            }
        }

        /** The object references this dataset waits for: their targets' paths. */
        void heldTargets(Set<String> targets) {
            if (holdsAddresses) {
                targets.addAll(compactRefs.values());
                for (Pending p : pending.values()) {
                    targets.addAll(p.refs.values());
                }
            }
        }

        /**
         * Once the objects' addresses are known: fills them in to compact data, and writes the chunks
         * that waited for them.
         */
        void writeHeld() {
            if (!holdsAddresses) {
                return;
            }
            if (compact != null) {
                for (Map.Entry<Long, String> ref : compactRefs.entrySet()) {
                    putU64(compact, (int) (long) ref.getKey(), addressOf(ref.getValue()));
                }
            }
            for (Cell cell : new ArrayList<>(pending.keySet())) {
                flush(cell, pending.get(cell));
            }
        }

        /** The Data Layout message body, writing the chunk index (if any) into {@code buf}. */
        byte[] layout(GrowBuffer buf) {
            if (external != null) {
                return null; // in its external files, as its layout and file list say
            }
            if (spec.compact) {
                if (compact == null) {
                    compact = new byte[Math.toIntExact(elementCount(spec.shape) * size)];
                    tile(compact, spec.fillValue, size);
                }
                return compactLayoutBody(compact); // data stored inline in the header, no data block
            }
            if (spec.chunkShape == null) {
                long bytes = elementCount(spec.shape) * size;
                // Never written: no block yet (libhdf5's late allocation); an empty dataset never has one.
                return contiguousLayoutBody(bytes == 0 ? UNDEFINED : address, bytes);
            }
            long[] chunk = spec.chunkShape;
            boolean btree = usesBTree();
            int flags = keepsPartialEdgesUnfiltered() ? DONT_FILTER_PARTIAL_CHUNKS : 0;
            // Chunks still waiting for object addresses are laid out as placeholders of the same size in
            // the index: the first of complete()'s two layouts, which finds those addresses.
            Map<Cell, ChunkIndexWriter.Entry> entriesByCell = new HashMap<>(stored);
            if (holdsAddresses) {
                for (Cell cell : pending.keySet()) {
                    entriesByCell.put(cell, new ChunkIndexWriter.Entry(cell.scaled(), 0, 0, 0));
                }
            }
            if (entriesByCell.isEmpty()) {
                // No chunk written: no index is allocated (libhdf5's own form), and every element is the fill value.
                return btree ? btreeLayoutBody(chunk, size, UNDEFINED) : chunkedLayoutBody(chunk, size, UNDEFINED, flags);
            }
            if (btree) {
                List<ChunkIndexWriter.Entry> entries = new ArrayList<>(entriesByCell.values());
                entries.sort((a, b) -> java.util.Arrays.compare(a.scaled(), b.scaled()));
                return btreeLayoutBody(chunk, size, ChunkIndexWriter.writeBTreeV1(buf, entries, chunk, chunkK));
            }
            long[] grid = new long[chunk.length];
            long cells = 1;
            for (int d = 0; d < chunk.length; d++) {
                grid[d] = (spec.shape[d] + chunk[d] - 1) / chunk[d];
                cells *= grid[d];
            }
            Map<Long, ChunkIndexWriter.Entry> byCell = new HashMap<>();
            for (ChunkIndexWriter.Entry entry : entriesByCell.values()) {
                long n = 0;
                for (int d = 0; d < chunk.length; d++) {
                    n = n * grid[d] + entry.scaled()[d];
                }
                byCell.put(n, entry);
            }
            int chunkBytes = Math.toIntExact(elementCount(chunk) * size);
            return chunkedLayoutBody(chunk, size,
                    ChunkIndexWriter.writeFixedArray(buf, cells, byCell, !spec.filters.isEmpty(), chunkBytes), flags);
        }
    }

    /** Receives a run of a box's last dimension: its first element's coordinates, its index in the box, and its length. */
    @FunctionalInterface
    private interface RunVisitor {
        void run(long[] at, long index, long length);
    }

    /** Calls {@code visitor} for each row (run of the last dimension) of the box {@code [offset, offset + count)}. */
    private static void forEachRow(long[] offset, long[] count, RunVisitor visitor) {
        int rank = count.length;
        if (rank == 0) {
            visitor.run(new long[0], 0, 1);
            return;
        }
        for (long c : count) {
            if (c == 0) {
                return;
            }
        }
        long[] at = offset.clone();
        long run = count[rank - 1];
        long index = 0;
        while (true) {
            visitor.run(at, index, run);
            index += run;
            int d = rank - 2;
            while (d >= 0) {
                if (++at[d] < offset[d] + count[d]) {
                    break;
                }
                at[d] = offset[d];
                d--;
            }
            if (d < 0) {
                return;
            }
        }
    }

    /**
     * As {@link #forEachRow}, but a run spans every trailing dimension the box covers whole in
     * {@code shape}, so rows that lie next to each other in the dataset are one run.
     */
    private static void forEachRun(long[] offset, long[] count, long[] shape, RunVisitor visitor) {
        int rank = count.length;
        int whole = rank;
        while (whole > 1 && count[whole - 1] == shape[whole - 1]) {
            whole--;
        }
        if (whole == rank) {
            forEachRow(offset, count, visitor);
            return;
        }
        long inner = 1;
        for (int d = whole; d < rank; d++) {
            inner *= shape[d];
        }
        long[] full = new long[rank];
        long innerElements = inner;
        int folded = whole;
        forEachRow(java.util.Arrays.copyOf(offset, folded), java.util.Arrays.copyOf(count, folded), (at, index, run) -> {
            System.arraycopy(at, 0, full, 0, folded);
            visitor.run(full, index * innerElements, run * innerElements);
        });
    }

    private static long dot(long[] at, long[] stride) {
        long flat = 0;
        for (int d = 0; d < at.length; d++) {
            flat += at[d] * stride[d];
        }
        return flat;
    }

    /** Fills {@code out} with copies of {@code fill} (left zero for the default fill value). */
    private static void tile(byte[] out, byte[] fill, int size) {
        if (fill == null || allZero(fill)) {
            return;
        }
        for (int at = 0; at + size <= out.length; at += size) {
            System.arraycopy(fill, 0, out, at, Math.min(size, fill.length));
        }
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** The Data Layout message (version 3) of a chunked dataset indexed by a version-1 B-tree. */
    private static byte[] btreeLayoutBody(long[] chunkShape, int elementSize, long btreeAddress) {
        GrowBuffer b = new GrowBuffer();
        b.u8(3);                     // version 3: what every HDF5 version reads
        b.u8(2);                     // layout class: chunked
        b.u8(chunkShape.length + 1); // dimensionality (chunk dims + element size)
        b.u64(btreeAddress);
        for (long c : chunkShape) {
            b.u32(c);
        }
        b.u32(elementSize);
        return b.toByteArray();
    }

    /**
     * Runs a chunk through the dataset's filters in pipeline order. A filter that cannot help a chunk
     * (szip on incompressible data) is skipped for it, recorded in the chunk's filter mask, as libhdf5
     * does for optional filters.
     */
    private static EncodedChunk encodeChunk(byte[] chunk, DatasetSpec dataset) {
        byte[] block = chunk;
        int mask = 0;
        for (int i = 0; i < dataset.filters.size(); i++) {
            FilterSpec filter = dataset.filters.get(i);
            byte[] next = switch (filter.id()) {
                case Filters.SHUFFLE -> shuffle(block, dataset.elementSize);
                case Filters.DEFLATE -> deflate(block, filter.parameter());
                case Filters.FLETCHER32 -> appendFletcher32(block);
                // at full precision, libhdf5 flags "no compression needed" and stores the chunk as it is
                case Filters.NBIT -> Nbit.encode(block, nbitClientData(dataset, filter));
                case Filters.SCALEOFFSET -> ScaleOffset.encode(block, scaleOffsetClientData(dataset, filter));
                case Filters.SZIP -> Szip.encode(block, szipClientData(dataset));
                // optional filters that return 0 for a chunk (LZF, Blosc that cannot shrink it) skip it
                case Filter.LZF, Filter.BLOSC, Filter.LZ4, Filter.BITSHUFFLE, Filter.ZSTD, Filter.BZIP2 ->
                        ThirdPartyFilters.encode(filter.id(), thirdPartyClientData(dataset, filter), block);
                default -> throw new IllegalStateException("unknown filter " + filter.id());
            };
            if (next == null) {
                mask |= 1 << i;
            } else {
                block = next;
            }
        }
        return new EncodedChunk(block, mask);
    }

    /** True if the dataset's integer type is signed (bit 3 of a fixed-point type's class bits). */
    private static boolean signed(DatasetSpec dataset) {
        return (dataset.datatype[1] & 0x08) != 0;
    }

    /** The dataset's fill value bits (its custom fill value, or the default 0), little-endian. */
    private static long fillBits(DatasetSpec dataset) {
        long bits = 0;
        if (dataset.fillValue != null) {
            for (int b = 0; b < Math.min(8, dataset.fillValue.length); b++) {
                bits |= (long) (dataset.fillValue[b] & 0xff) << (8 * b);
            }
        }
        return bits;
    }

    /**
     * The szip client data: a dataset of the file's own, or libhdf5's for the dataset's coding, pixels per
     * block, element size, byte order, and chunk shape ({@code H5Z__set_local_szip}).
     */
    private static int[] szipClientData(DatasetSpec dataset) {
        int parameter = 0;
        for (FilterSpec filter : dataset.filters) {
            if (filter.id() == Filters.SZIP) {
                if (filter.clientData() != null) {
                    return filter.clientData();
                }
                parameter = filter.parameter();
            }
        }
        boolean bigEndian = (dataset.datatype[1] & 0x01) != 0;
        return Szip.clientData((parameter & SZIP_NN_PARAMETER) != 0 ? Szip.NN : Szip.EC, parameter & 0xFF,
                dataset.elementSize * 8, bigEndian, dataset.chunkShape);
    }

    /**
     * The n-bit client data: a dataset of the file's own, or for a new dataset's unsigned little-endian
     * integers of {@code precision} bits: total, "no compression needed" (at full precision), elements per
     * chunk, then the atomic type (class 1, size, byte order 0, precision, offset 0).
     */
    private static int[] nbitClientData(DatasetSpec dataset, FilterSpec filter) {
        if (filter.clientData() != null) {
            return filter.clientData();
        }
        int chunkElements = Math.toIntExact(elementCount(dataset.chunkShape));
        return new int[] {8, filter.parameter() == dataset.elementSize * 8 ? 1 : 0, chunkElements, 1,
            dataset.elementSize, 0, filter.parameter(), 0};
    }

    /** The scale-offset client data: a dataset of the file's own, or libhdf5's for a new integer dataset. */
    private static int[] scaleOffsetClientData(DatasetSpec dataset, FilterSpec filter) {
        if (filter.clientData() != null) {
            return filter.clientData();
        }
        return ScaleOffset.integerClientData(Math.toIntExact(elementCount(dataset.chunkShape)), dataset.elementSize,
                signed(dataset), false, fillBits(dataset));
    }

    /**
     * The filter-pipeline message, listing the filters in the order they are applied on write, each with
     * the flags and client data libhdf5 stores for it: version 2, or for the earliest format version 1
     * (with reserved bytes, and each filter's client data padded to 8 bytes).
     */
    private static byte[] filterPipelineBody(DatasetSpec dataset, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 1 : 2); // version
        b.u8(dataset.filters.size());
        if (legacy) {
            for (int i = 0; i < 6; i++) {
                b.u8(0); // reserved
            }
        }
        for (FilterSpec filter : dataset.filters) {
            switch (filter.id()) {
                case Filters.DEFLATE -> writeFilter(b, legacy, Filters.DEFLATE, FILTER_OPTIONAL, filter.parameter());
                case Filters.SHUFFLE -> writeFilter(b, legacy, Filters.SHUFFLE, FILTER_OPTIONAL, dataset.elementSize);
                case Filters.FLETCHER32 -> writeFilter(b, legacy, Filters.FLETCHER32, FILTER_MANDATORY);
                case Filters.NBIT -> writeFilter(b, legacy, Filters.NBIT, FILTER_OPTIONAL, nbitClientData(dataset, filter));
                case Filters.SCALEOFFSET -> writeFilter(b, legacy, Filters.SCALEOFFSET, FILTER_OPTIONAL,
                        scaleOffsetClientData(dataset, filter));
                case Filters.SZIP -> writeFilter(b, legacy, Filters.SZIP, FILTER_OPTIONAL, szipClientData(dataset));
                case Filter.LZF, Filter.BLOSC, Filter.LZ4, Filter.BITSHUFFLE, Filter.ZSTD, Filter.BZIP2 ->
                        writeFilter(b, legacy, filter.id(), FILTER_OPTIONAL, ThirdPartyFilters.pluginName(filter.id()),
                        thirdPartyClientData(dataset, filter));
                default -> throw new IllegalStateException("unknown filter " + filter.id());
            }
        }
        return b.toByteArray();
    }

    /** bitshuffle's {@code BSHUF_H5_COMPRESS_LZ4} and {@code BSHUF_H5_COMPRESS_ZSTD}. */
    private static final int BITSHUFFLE_LZ4 = 2;
    private static final int BITSHUFFLE_ZSTD = 3;

    /**
     * A third-party filter's client data: a dataset of the file's own, or for a new dataset what the plugin's
     * {@code set_local} makes of hdf5plugin's values, from the datatype and chunk shape:
     *
     * <ul>
     *   <li>LZF ({@code lzf_filter.c}): h5py's filter version 4, liblzf's API version 0x0105, the chunk's
     *       bytes;</li>
     *   <li>Blosc ({@code blosc_filter.c}): hdf5-blosc's version 2, Blosc's format 2, the type size (an array's
     *       base type; above 255, 1), the chunk's bytes, then clevel, shuffle, and compressor;</li>
     *   <li>bitshuffle ({@code bshuf_h5filter.c}): bitshuffle's version 0.4, the element size, then the block
     *       size, compression, and (for zstd) level;</li>
     *   <li>LZ4, Zstandard and bzip2 (no {@code set_local}): the block size, the level, the block size.</li>
     * </ul>
     *
     * The chunk's bytes are an unsigned 32-bit product, as the plugins compute them.
     */
    private static int[] thirdPartyClientData(DatasetSpec dataset, FilterSpec filter) {
        if (filter.clientData() != null) {
            return filter.clientData();
        }
        int[] options = filter.options();
        int chunkBytes = (int) (dataset.elementSize * elementCount(dataset.chunkShape));
        return switch (filter.id()) {
            case Filter.LZF -> new int[] {4, 0x0105, chunkBytes};
            case Filter.BLOSC -> {
                int typeSize = dataset.elementSize;
                if (com.ebremer.falcon.hdf5.message.DatatypeMessage.parse(HdfBuffer.of(dataset.datatype), 0)
                        instanceof Datatype.Array array) {
                    typeSize = array.base().size();
                }
                yield new int[] {2, 2, typeSize > 255 ? 1 : typeSize, chunkBytes, options[0], options[1], options[2]};
            }
            case Filter.BITSHUFFLE -> {
                int[] data = new int[3 + options.length];
                data[0] = 0;
                data[1] = 4;
                data[2] = dataset.elementSize;
                System.arraycopy(options, 0, data, 3, options.length);
                yield data;
            }
            default -> options.clone(); // LZ4's block size, Zstandard's level, bzip2's block size
        };
    }

    /** An unsigned little-endian integer datatype of {@code precision} significant bits (for n-bit). */
    private static byte[] nbitDatatype(int size, int precision) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x10); // version 1, class 0 (fixed point)
        b.u8(0);    // class bit field: little-endian, unsigned
        b.u8(0);
        b.u8(0);
        b.u32(size);
        b.u16(0);         // bit offset
        b.u16(precision); // bit precision
        return b.toByteArray();
    }

    private static void writeFilter(GrowBuffer b, boolean legacy, int id, int flags, int... clientData) {
        writeFilter(b, legacy, id, flags, null, clientData);
    }

    /**
     * One filter of the pipeline message, as {@code H5O__pline_encode} writes it: a name (null terminated) for
     * a filter numbered 256 or above, or in version 1 for any that has one, padded there to 8 bytes.
     */
    private static void writeFilter(GrowBuffer b, boolean legacy, int id, int flags, String name, int[] clientData) {
        byte[] nameBytes = name == null ? new byte[0] : (name + "\0").getBytes(StandardCharsets.US_ASCII);
        int nameLength = legacy ? (nameBytes.length + 7) / 8 * 8 : nameBytes.length;
        b.u16(id);
        if (legacy || id >= 256) {
            b.u16(nameLength);
        }
        b.u16(flags);
        b.u16(clientData.length);
        b.bytes(nameBytes);
        for (int i = nameBytes.length; i < nameLength; i++) {
            b.u8(0);
        }
        for (int value : clientData) {
            b.u32(value);
        }
        if (legacy && clientData.length % 2 != 0) {
            b.u32(0); // version 1 pads the client data to a multiple of 8 bytes
        }
    }

    /** Groups the {@code j}-th byte of every element together (the shuffle filter's forward transform). */
    private static byte[] shuffle(byte[] data, int elementSize) {
        if (elementSize <= 1) {
            return data;
        }
        int elements = data.length / elementSize;
        byte[] out = new byte[data.length];
        int p = 0;
        for (int b = 0; b < elementSize; b++) {
            for (int i = 0; i < elements; i++) {
                out[p++] = data[i * elementSize + b];
            }
        }
        return out;
    }

    /** Appends the 4-byte (little-endian) Fletcher-32 checksum HDF5 uses. */
    private static byte[] appendFletcher32(byte[] data) {
        int checksum = Fletcher32.checksum(data, data.length);
        byte[] out = java.util.Arrays.copyOf(data, data.length + 4);
        out[data.length] = (byte) checksum;
        out[data.length + 1] = (byte) (checksum >>> 8);
        out[data.length + 2] = (byte) (checksum >>> 16);
        out[data.length + 3] = (byte) (checksum >>> 24);
        return out;
    }

    private static byte[] deflate(byte[] data, int level) {
        Deflater deflater = new Deflater(level);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] scratch = new byte[1024];
        while (!deflater.finished()) {
            out.write(scratch, 0, deflater.deflate(scratch));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** Layout flag (version 4): partial edge chunks are stored unfiltered. */
    private static final int DONT_FILTER_PARTIAL_CHUNKS = 0x01;

    /** The version-4 chunked data-layout message: chunk dimensions and a fixed-array chunk index. */
    private static byte[] chunkedLayoutBody(long[] chunkShape, int elementSize, long fixedArrayHeaderAddress, int flags) {
        int rank = chunkShape.length;
        long maxDim = elementSize;
        for (long c : chunkShape) {
            maxDim = Math.max(maxDim, c);
        }
        int encodedLength = (63 - Long.numberOfLeadingZeros(maxDim)) / 8 + 1;
        GrowBuffer b = new GrowBuffer();
        b.u8(4);                     // version 4 (HDF5 1.10+; version 5 is HDF5 2.0-only)
        b.u8(2);                     // layout class: chunked
        b.u8(flags);                 // flags: partial edge chunks unfiltered
        b.u8(rank + 1);              // dimensionality (chunk dims + element size)
        b.u8(encodedLength);
        for (long c : chunkShape) {
            b.uvar(c, encodedLength);
        }
        b.uvar(elementSize, encodedLength);
        b.u8(3);                     // index type: fixed array
        b.u8(ChunkIndexWriter.FA_PAGE_BITS); // page bits
        b.u64(fixedArrayHeaderAddress);
        return b.toByteArray();
    }

    private static long[] rowMajorStride(long[] dims) {
        long[] stride = new long[dims.length];
        long s = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            stride[i] = s;
            s *= dims[i];
        }
        return stride;
    }

    private static void increment(int[] coord, long[] extent) {
        for (int d = coord.length - 1; d >= 0; d--) {
            if (++coord[d] < extent[d]) {
                return;
            }
            coord[d] = 0;
        }
    }

    private static void increment(int[] coord, int[] extent) {
        for (int d = coord.length - 1; d >= 0; d--) {
            if (++coord[d] < extent[d]) {
                return;
            }
            coord[d] = 0;
        }
    }

    private static long align8(long n) {
        return (n + 7) & ~7L;
    }

    private void writeGroupHeader(GrowBuffer buf, List<NamedLink> links, List<AttributeSpec> attributes,
                                  byte[] linkInfo, byte[] attributeInfo, int referenceCount) {
        List<Message> messages = new ArrayList<>();
        // Links: a Link Info message pointing at dense storage, or an empty one plus compact Link messages.
        messages.add(new Message(2, 0x00, linkInfo != null ? linkInfo : linkInfoBody()));
        messages.add(new Message(10, 0x01, new byte[] {0, 0}));
        if (linkInfo == null) {
            for (NamedLink link : links) {
                messages.add(new Message(6, 0x00, link.body()));
            }
        }
        if (attributeInfo != null) {
            messages.add(new Message(21, 0x00, attributeInfo));
        } else {
            for (AttributeSpec attribute : attributes) {
                messages.add(new Message(12, 0x00, attributeBody(attribute, legacy)));
            }
        }
        writeObjectHeader(buf, messages, referenceCount);
    }

    /** One object-header message (type, flags, and body), framed by the version-specific header writer. */
    private record Message(int type, int flags, byte[] body) {
    }

    /**
     * Writes an object header around {@code messages}: version-2 (checksummed) or version-1 by format, with
     * the object's hard-link count (in version 2, a Reference Count message when it is more than 1).
     */
    private void writeObjectHeader(GrowBuffer buf, List<Message> messages, int referenceCount) {
        if (legacy) {
            writeObjectHeaderV1(buf, messages, referenceCount);
        } else {
            List<Message> all = messages;
            if (referenceCount > 1) {
                all = new ArrayList<>(messages);
                all.add(new Message(0x16, 0x00, new byte[] {0, (byte) referenceCount, (byte) (referenceCount >>> 8),
                    (byte) (referenceCount >>> 16), (byte) (referenceCount >>> 24)}));
            }
            writeObjectHeaderV2(buf, all);
        }
    }

    private static void writeObjectHeaderV2(GrowBuffer buf, List<Message> messages) {
        GrowBuffer framed = new GrowBuffer();
        for (Message message : messages) {
            checkMessageSize(message);
            framed.u8(message.type());
            framed.u16(message.body().length);
            framed.u8(message.flags());
            framed.bytes(message.body());
        }
        byte[] body = framed.toByteArray();
        int sizeBits = body.length <= 0xFF ? 0 : body.length <= 0xFFFF ? 1 : 2;
        long start = buf.position();
        buf.bytes(OHDR_SIGNATURE);
        buf.u8(2);
        buf.u8(sizeBits);
        buf.uvar(body.length, 1 << sizeBits);
        buf.bytes(body);
        buf.u32(buf.checksum(start, buf.position()));
    }

    /** A message body that would overflow its header's 16-bit size field would be silently truncated. */
    private static void checkMessageSize(Message message) {
        if (message.body().length > MAX_MESSAGE_BODY) {
            throw new HdfUnsupportedException("object-header message (type " + message.type() + ") of "
                    + message.body().length + " bytes exceeds the " + MAX_MESSAGE_BODY + "-byte limit");
        }
    }

    /** Version-1 object header: a 12-byte prefix padded to 16, then 8-byte-aligned messages. */
    private static void writeObjectHeaderV1(GrowBuffer buf, List<Message> messages, int referenceCount) {
        int chunk0 = 0;
        for (Message message : messages) {
            checkMessageSize(message);
            chunk0 += 8 + align8(message.body().length); // 8-byte message header + padded body
        }
        buf.u8(1);                 // version
        buf.u8(0);                 // reserved
        buf.u16(messages.size());  // total number of messages
        buf.u32(referenceCount);
        buf.u32(chunk0);           // size of chunk 0's message data
        buf.u32(0);                // pad the 12-byte prefix to 16 bytes
        for (Message message : messages) {
            int padded = align8(message.body().length);
            buf.u16(message.type());
            buf.u16(padded);
            buf.u8(message.flags());
            buf.u8(0);
            buf.u8(0);
            buf.u8(0);             // reserved (3)
            buf.bytes(message.body());
            for (int i = message.body().length; i < padded; i++) {
                buf.u8(0);         // pad the body to an 8-byte boundary
            }
        }
    }

    private static final int GLOBAL_HEAP_MIN_SIZE = 4096; // HDF5 requires collections to be at least this large
    private static final int GLOBAL_HEAP_MAX_OBJECTS = 0xFFFF; // object indices are 16-bit; 0 is free space
    private static final int GLOBAL_HEAP_TARGET_SIZE = 1 << 20; // start a new collection beyond this

    /**
     * The file's global-heap collections, which hold variable-length data and region references' objects,
     * shared by every dataset and attribute, as libhdf5 shares them. Objects go into the current
     * collection, which is written to the file (placed) when it is full (65,535 objects, or about 1 MiB),
     * when data referring to it must be written in its final form (a chunk being filtered), or when the
     * file is completed. A variable-length id written before its collection is placed has its collection
     * address filled in when it is.
     */
    private final class GlobalHeaps implements ValueEncoder.Heap {
        private final List<byte[]> current = new ArrayList<>();
        private int currentBytes;
        private final List<Long> addresses = new ArrayList<>();        // each placed collection's address
        private final List<Long> waiting = new ArrayList<>();          // ids in the file waiting for the current one
        private final List<String> regionTargets = new ArrayList<>();  // the current collection's region objects'
        private final List<Integer> regionObjects = new ArrayList<>(); // ... targets, and their indices

        @Override
        public long[] add(byte[] object) {
            int footprint = 16 + align8(object.length);
            if (current.size() == GLOBAL_HEAP_MAX_OBJECTS
                    || (!current.isEmpty() && (long) currentBytes + footprint > GLOBAL_HEAP_TARGET_SIZE)) {
                seal();
            }
            current.add(object);
            currentBytes += footprint;
            return new long[] {addresses.size(), current.size()};
        }

        @Override
        public long[] addRegion(String datasetPath, byte[] selection) {
            byte[] object = new byte[8 + selection.length]; // the dataset's address (filled in later), the selection
            System.arraycopy(selection, 0, object, 8, selection.length);
            long[] slot = add(object);
            regionTargets.add(datasetPath);
            regionObjects.add((int) slot[1]);
            return slot;
        }

        /** The address of collection {@code collection}, placing it first if it is the current one. */
        long address(int collection) {
            if (collection == addresses.size()) {
                seal();
            }
            return addresses.get(collection);
        }

        /** Fills in the collection address of the id whose address field is at file offset {@code position}. */
        void idAt(long position, int collection) {
            if (collection < addresses.size()) {
                output().writeU64(position, addresses.get(collection));
            } else {
                waiting.add(position);
            }
        }

        /** Places the current collection, if it holds anything. */
        void sealAll() {
            if (!current.isEmpty()) {
                seal();
            }
        }

        private void seal() {
            GrowBuffer collection = new GrowBuffer();
            int[] dataOffsets = writeGlobalHeap(collection, current);
            long at = output().allocate(collection.size());
            output().write(at, collection.toByteArray());
            addresses.add(at);
            for (long position : waiting) {
                output().writeU64(position, at);
            }
            for (int i = 0; i < regionTargets.size(); i++) {
                fileReferences.add(new FileReference(at + dataOffsets[regionObjects.get(i) - 1], regionTargets.get(i)));
            }
            current.clear();
            currentBytes = 0;
            waiting.clear();
            regionTargets.clear();
            regionObjects.clear();
        }
    }

    /**
     * Writes a global-heap collection holding {@code objects} at indices 1, 2, ..., and returns where each
     * object's data starts, from the collection's start.
     */
    private static int[] writeGlobalHeap(GrowBuffer buf, List<byte[]> objects) {
        long start = buf.position();
        int usedExtents = 0;
        for (byte[] object : objects) {
            usedExtents += 16 + align8(object.length); // object header + padded data
        }
        int total = Math.max(16 + usedExtents + 16, GLOBAL_HEAP_MIN_SIZE);
        int freeExtent = total - 16 - usedExtents; // the trailing free-space object's extent
        int[] dataOffsets = new int[objects.size()];

        buf.bytes(GCOL_SIGNATURE);
        buf.u8(1);
        buf.u8(0);
        buf.u8(0);
        buf.u8(0);
        buf.u64(total);
        for (int i = 0; i < objects.size(); i++) {
            buf.u16(i + 1);   // object index (1-based; 0 marks free space)
            buf.u16(1);       // reference count
            buf.u32(0);       // reserved
            buf.u64(objects.get(i).length);
            dataOffsets[i] = (int) (buf.position() - start);
            buf.bytes(objects.get(i));
            while ((buf.position() - start) % 8 != 0) {
                buf.u8(0);    // pad object data to an 8-byte boundary
            }
        }
        buf.u16(0);           // free-space object: index 0
        buf.u16(0);
        buf.u32(0);
        buf.u64(freeExtent);  // its extent (this header plus the remaining free bytes)
        while (buf.position() - start < total) {
            buf.u8(0);        // materialize the free space
        }
        return dataOffsets;
    }

    /**
     * Writes an object's attributes densely (a fractal heap of attribute messages, indexed by name in a
     * version-2 B-tree of type 8) and returns the Attribute Info (message 21) body pointing at them.
     */
    private static byte[] writeDenseAttributes(GrowBuffer buf, List<AttributeSpec> attributes) {
        List<ExistingFile.StoredAttribute> stored = new ArrayList<>();
        for (AttributeSpec attribute : attributes) {
            stored.add(new ExistingFile.StoredAttribute(attribute.name(), 0, 0, attributeBody(attribute, false)));
        }
        return writeAttributeStorage(buf, stored, 0, -1);
    }

    /**
     * Writes attributes densely: a fractal heap of their messages, indexed by name (a version-2 B-tree of
     * type 8: heap id, message flags, creation order, name hash) and, if {@code infoFlags} bit 1 says so, by
     * creation order (type 9); returns the Attribute Info body (version 0, its flags, the maximum creation
     * index (the next to give, as libhdf5 keeps it) if bit 0 says it is tracked, and the addresses).
     */
    private static byte[] writeAttributeStorage(GrowBuffer buf, List<ExistingFile.StoredAttribute> attributes,
                                                int infoFlags, int maxCreationIndex) {
        List<byte[]> objects = new ArrayList<>();
        for (ExistingFile.StoredAttribute attribute : attributes) {
            if (attribute.sharedId() == null) {
                objects.add(attribute.message());
            }
        }
        FractalHeapWriter.Heap heap = FractalHeapWriter.write(buf, objects, ATTR_HEAP_ID, ATTR_HEAP_BITS);
        List<NameRecord> records = new ArrayList<>();
        List<OrderRecord> ordered = new ArrayList<>();
        int next = 0;
        for (ExistingFile.StoredAttribute attribute : attributes) {
            // An attribute in the shared-message table is named by its ID there (its record flagged shared).
            byte[] id = attribute.sharedId() != null ? attribute.sharedId() : heap.ids().get(next++);
            byte[] name = attribute.name().getBytes(StandardCharsets.UTF_8);
            int hash = Lookup3.hashLittle(name);
            GrowBuffer r = new GrowBuffer();
            r.bytes(id);                            // heap id (8)
            r.u8(attribute.flags());                // message flags
            r.u32((infoFlags & 0x01) != 0 ? attribute.creationOrder() : 0x0000FFFF); // creation order
            r.u32(hash);                            // name hash
            records.add(new NameRecord(hash, name, r.toByteArray()));
            GrowBuffer o = new GrowBuffer();
            o.bytes(id);
            o.u8(attribute.flags());
            o.u32(attribute.creationOrder());
            ordered.add(new OrderRecord(attribute.creationOrder(), o.toByteArray()));
        }
        long names = BTreeV2Writer.write(buf, BT2_ATTR_NAME, 17, sortedByName(records));
        long orders = (infoFlags & 0x02) != 0 ? BTreeV2Writer.write(buf, BT2_ATTR_ORDER, 13, sortedByOrder(ordered)) : UNDEFINED;

        GrowBuffer b = new GrowBuffer();
        b.u8(0);       // version
        b.u8(infoFlags);
        if ((infoFlags & 0x01) != 0) {
            b.u16(maxCreationIndex);
        }
        b.u64(heap.headerAddress());
        b.u64(names);
        if ((infoFlags & 0x02) != 0) {
            b.u64(orders);
        }
        return b.toByteArray();
    }

    /** A name index record, with the name's hash and bytes, which order the index. */
    private record NameRecord(int hash, byte[] name, byte[] record) {
    }

    /** A creation-order index record, with its creation order. */
    private record OrderRecord(long order, byte[] record) {
    }

    /**
     * The records of a name index in its order: by the name's hash, and names of equal hash by their bytes,
     * as libhdf5 compares them ({@code H5G__dense_btree2_name_compare}, {@code H5A__dense_btree2_name_compare}).
     */
    private static List<byte[]> sortedByName(List<NameRecord> records) {
        List<NameRecord> sorted = new ArrayList<>(records);
        sorted.sort((x, y) -> x.hash() != y.hash() ? Integer.compareUnsigned(x.hash(), y.hash())
                : java.util.Arrays.compareUnsigned(x.name(), y.name()));
        List<byte[]> result = new ArrayList<>();
        for (NameRecord r : sorted) {
            result.add(r.record());
        }
        return result;
    }

    private static List<byte[]> sortedByOrder(List<OrderRecord> records) {
        List<OrderRecord> sorted = new ArrayList<>(records);
        sorted.sort((x, y) -> Long.compare(x.order(), y.order()));
        List<byte[]> result = new ArrayList<>();
        for (OrderRecord r : sorted) {
            result.add(r.record());
        }
        return result;
    }

    /**
     * Writes a group's links densely (a fractal heap of Link messages, indexed by name in a version-2
     * B-tree of type 5) and returns the Link Info (message 2) body pointing at them.
     */
    private static byte[] writeDenseLinks(GrowBuffer buf, List<NamedLink> links) {
        List<ExistingFile.StoredLink> stored = new ArrayList<>();
        for (NamedLink link : links) {
            stored.add(new ExistingFile.StoredLink(link.name(), link.body(), -1));
        }
        return writeLinkStorage(buf, stored, 0, -1);
    }

    /**
     * Writes links densely: a fractal heap of their Link messages, indexed by name (a version-2 B-tree of
     * type 5: name hash, heap id) and, if {@code infoFlags} bit 1 says so, by creation order (type 6:
     * creation order, heap id); returns the Link Info body (version 0, its flags, the maximum creation index
     * (the next to give, as libhdf5 keeps it) if bit 0 says it is tracked, and the addresses).
     */
    private static byte[] writeLinkStorage(GrowBuffer buf, List<ExistingFile.StoredLink> links, int infoFlags,
                                           long maxCreationIndex) {
        List<byte[]> objects = new ArrayList<>();
        for (ExistingFile.StoredLink link : links) {
            objects.add(link.message());
        }
        FractalHeapWriter.Heap heap = FractalHeapWriter.write(buf, objects, LINK_HEAP_ID, LINK_HEAP_BITS);
        List<NameRecord> records = new ArrayList<>();
        List<OrderRecord> ordered = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            byte[] name = links.get(i).name().getBytes(StandardCharsets.UTF_8);
            int hash = Lookup3.hashLittle(name);
            GrowBuffer r = new GrowBuffer();
            r.u32(hash);                 // name hash (type-5 record leads with the hash)
            r.bytes(heap.ids().get(i));  // heap id
            records.add(new NameRecord(hash, name, r.toByteArray()));
            GrowBuffer o = new GrowBuffer();
            o.u64(links.get(i).creationOrder());
            o.bytes(heap.ids().get(i));
            ordered.add(new OrderRecord(links.get(i).creationOrder(), o.toByteArray()));
        }
        long names = BTreeV2Writer.write(buf, BT2_LINK_NAME, 4 + LINK_HEAP_ID, sortedByName(records));
        long orders = (infoFlags & 0x02) != 0
                ? BTreeV2Writer.write(buf, BT2_LINK_ORDER, 8 + LINK_HEAP_ID, sortedByOrder(ordered)) : UNDEFINED;

        GrowBuffer b = new GrowBuffer();
        b.u8(0);       // version
        b.u8(infoFlags);
        if ((infoFlags & 0x01) != 0) {
            b.u64(maxCreationIndex);
        }
        b.u64(heap.headerAddress());
        b.u64(names);
        if ((infoFlags & 0x02) != 0) {
            b.u64(orders);
        }
        return b.toByteArray();
    }

    /**
     * The Attribute message: version 3 (with a character-set field, UTF-8 for a non-ASCII name), or for
     * the earliest format version 1, whose name, datatype, and dataspace are each padded to 8 bytes.
     */
    private static byte[] attributeBody(AttributeSpec attribute, boolean legacy) {
        byte[] name = (attribute.name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] dataspace = dataspaceBody(attribute.shape, null, legacy);
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 1 : 3);
        b.u8(0x00);
        b.u16(name.length);
        b.u16(attribute.datatype.length);
        b.u16(dataspace.length);
        if (legacy) {
            padded(b, name);
            padded(b, attribute.datatype);
            padded(b, dataspace);
        } else {
            b.u8(isAscii(name) ? 0 : 1);
            b.bytes(name);
            b.bytes(attribute.datatype);
            b.bytes(dataspace);
        }
        b.bytes(attribute.data);
        return b.toByteArray();
    }

    /** Appends {@code bytes} zero-padded to a multiple of 8. */
    private static void padded(GrowBuffer b, byte[] bytes) {
        b.bytes(bytes);
        for (int i = bytes.length; i < align8(bytes.length); i++) {
            b.u8(0);
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte x : bytes) {
            if (x < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Dataspace message: version 2, or version 1 (with its reserved bytes) for the earliest format;
     * with the maximum dimensions ({@link #UNLIMITED} as all ones) if they are not the current ones.
     */
    private static byte[] dataspaceBody(long[] shape, long[] maxShape, boolean legacy) {
        boolean max = maxShape != null && !java.util.Arrays.equals(maxShape, shape);
        GrowBuffer b = new GrowBuffer();
        if (legacy) {
            b.u8(1);
            b.u8(shape.length);
            b.u8(max ? 0x01 : 0x00); // flags: maximum dimensions present
            b.u8(0);      // reserved
            b.u32(0);     // reserved
        } else {
            b.u8(2);
            b.u8(shape.length);
            b.u8(max ? 0x01 : 0x00);
            b.u8(shape.length == 0 ? 0 : 1);
        }
        for (long dimension : shape) {
            b.u64(dimension);
        }
        if (max) {
            for (long dimension : maxShape) {
                b.u64(dimension);
            }
        }
        return b.toByteArray();
    }

    /**
     * The Fill Value message body: the default (reads back as zero) or a defined custom value. Version 3,
     * or for the earliest format version 2, written as libhdf5 writes it (late allocation, fill written
     * if set; the default fill is "defined" with no value).
     */
    private static byte[] fillValueBody(byte[] fill, boolean legacy) {
        if (legacy) {
            GrowBuffer b = new GrowBuffer();
            b.u8(2);        // version
            b.u8(2);        // space allocation time: late
            b.u8(2);        // fill value write time: if set
            b.u8(1);        // fill value defined
            b.u32(fill == null ? 0 : fill.length);
            if (fill != null) {
                b.bytes(fill);
            }
            return b.toByteArray();
        }
        if (fill == null) {
            return new byte[] {0x03, 0x0a}; // version 3; fill value not defined -> reads back as zero
        }
        GrowBuffer b = new GrowBuffer();
        b.u8(3);        // version
        b.u8(0x2a);     // flags: alloc/fill time + fill-value-defined (bit 5)
        b.u32(fill.length);
        b.bytes(fill);
        return b.toByteArray();
    }

    /** Compact data-layout message: the element data stored inline (version 3, class 0). */
    private static byte[] compactLayoutBody(byte[] data) {
        GrowBuffer b = new GrowBuffer();
        b.u8(3);       // version
        b.u8(0);       // layout class: compact
        b.u16(data.length);
        b.bytes(data);
        return b.toByteArray();
    }

    private static byte[] contiguousLayoutBody(long address, long size) {
        GrowBuffer b = new GrowBuffer();
        b.u8(3);
        b.u8(1);
        b.u64(address);
        b.u64(size);
        return b.toByteArray();
    }

    private static byte[] linkInfoBody() {
        GrowBuffer b = new GrowBuffer();
        b.u8(0);
        b.u8(0);
        b.u64(UNDEFINED);
        b.u64(UNDEFINED);
        return b.toByteArray();
    }

    /**
     * A hard-link message. The name's length field is 1, 2, or 4 bytes as its length needs (flag bits
     * 0-1), and a non-ASCII name carries the UTF-8 character set (flag bit 4).
     */
    private static byte[] linkBody(String name, long targetHeaderAddress) {
        return linkBody(name, targetHeaderAddress, -1);
    }

    /** A hard-link message, with its creation order unless that is negative. */
    private static byte[] linkBody(String name, long targetHeaderAddress, long creationOrder) {
        GrowBuffer b = linkHeader(name, 0, creationOrder);
        b.u64(targetHeaderAddress);
        return b.toByteArray();
    }

    /** A soft-link message: link type 1, then the target path (its length in 2 bytes, no terminator). */
    private static byte[] softLinkBody(String name, String targetPath) {
        return softLinkBody(name, targetPath, -1);
    }

    private static byte[] softLinkBody(String name, String targetPath, long creationOrder) {
        byte[] target = targetPath.getBytes(StandardCharsets.UTF_8);
        if (target.length > 0xFFFF) {
            throw new IllegalArgumentException("a soft link's target is at most 65535 bytes");
        }
        GrowBuffer b = linkHeader(name, 1, creationOrder);
        b.u16(target.length);
        b.bytes(target);
        return b.toByteArray();
    }

    /**
     * An external-link message: link type 64, then its information (its length in 2 bytes): a version and
     * flags byte (0), the file name and the object path, each null-terminated.
     */
    private static byte[] externalLinkBody(String name, String fileName, String objectPath) {
        return externalLinkBody(name, fileName, objectPath, -1);
    }

    private static byte[] externalLinkBody(String name, String fileName, String objectPath, long creationOrder) {
        byte[] file = (fileName + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] object = (objectPath + "\0").getBytes(StandardCharsets.UTF_8);
        if (1 + file.length + object.length > 0xFFFF) {
            throw new IllegalArgumentException("an external link's names are at most 65534 bytes together");
        }
        GrowBuffer b = linkHeader(name, 64, creationOrder);
        b.u16(1 + file.length + object.length);
        b.u8(0);
        b.bytes(file);
        b.bytes(object);
        return b.toByteArray();
    }

    /**
     * A Link message's start: version 1, flags (the name length's width, link type, creation order and
     * character set present), the link type unless hard (0), the creation order if not negative (in a group
     * that tracks it), the character set for a non-ASCII name, and the name.
     */
    private static GrowBuffer linkHeader(String name, int linkType, long creationOrder) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int widthCode = nameBytes.length <= 0xFF ? 0 : nameBytes.length <= 0xFFFF ? 1 : 2;
        boolean utf8 = !isAscii(nameBytes);
        GrowBuffer b = new GrowBuffer();
        b.u8(1);
        b.u8(widthCode | (linkType != 0 ? 0x08 : 0) | (creationOrder >= 0 ? 0x04 : 0) | (utf8 ? 0x10 : 0));
        if (linkType != 0) {
            b.u8(linkType);
        }
        if (creationOrder >= 0) {
            b.u64(creationOrder);
        }
        if (utf8) {
            b.u8(1);  // character set: UTF-8
        }
        b.uvar(nameBytes.length, 1 << widthCode);
        b.bytes(nameBytes);
        return b;
    }

    /**
     * Writes a legacy (symbol-table) group: its symbol table (see {@link #writeSymbolTable}) and a version-1
     * object header carrying the Symbol Table message. Returns the group's header plus its B-tree and heap
     * (for the parent's scratch-pad cache and the superblock's root entry).
     */
    private GroupResult writeSymbolTableGroup(GrowBuffer buf, List<SymbolChild> children,
                                              List<AttributeSpec> attributes, int referenceCount) {
        SymbolTable table = writeSymbolTable(buf, children);
        buf.align(8);
        long headerAddress = buf.position();
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(17, 0x00, symbolTableBody(table)));
        for (AttributeSpec attribute : attributes) {
            messages.add(new Message(12, 0x00, attributeBody(attribute, true)));
        }
        writeObjectHeader(buf, messages, referenceCount);
        return new GroupResult(headerAddress, table.btree(), table.heap(), false);
    }

    /** A written symbol table: its group B-tree's root and its local heap. */
    private record SymbolTable(long btree, long heap) {
    }

    /** The Symbol Table message: the group B-tree's and the local heap's addresses. */
    private static byte[] symbolTableBody(SymbolTable table) {
        GrowBuffer b = new GrowBuffer();
        b.u64(table.btree());
        b.u64(table.heap());
        return b.toByteArray();
    }

    /**
     * Writes a symbol table: a local heap of the link names (and soft links' values), the children's
     * entries in symbol-table nodes of {@code 2 * groupLeafK} entries, sorted by name, and a version-1
     * group B-tree over the nodes, of as many levels as they need ({@code 2 * groupInternalK} entries a
     * node), every node full-size as libhdf5 allocates it.
     */
    private SymbolTable writeSymbolTable(GrowBuffer buf, List<SymbolChild> children) {
        int perNode = 2 * groupLeafK;        // max symbols per symbol-table node
        // Local heap: 8 reserved bytes (offset 0 = the empty name), then each name, null-terminated and
        // padded to an 8-byte boundary.
        Map<String, Long> nameOffsets = new HashMap<>();
        Map<String, Long> valueOffsets = new HashMap<>(); // a soft link's target path
        List<String> strings = new ArrayList<>();
        long dataSize = 8;
        for (SymbolChild child : children) {
            nameOffsets.put(child.name(), dataSize);
            strings.add(child.name());
            dataSize += align8(child.name().getBytes(StandardCharsets.UTF_8).length + 1);
            if (child.linkValue() != null) {
                valueOffsets.put(child.name(), dataSize);
                strings.add(child.linkValue());
                dataSize += align8(child.linkValue().getBytes(StandardCharsets.UTF_8).length + 1);
            }
        }
        buf.align(8);
        long heapDataAddress = buf.position();
        for (int i = 0; i < 8; i++) {
            buf.u8(0);
        }
        for (String string : strings) {
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            buf.bytes(bytes);
            for (int i = bytes.length; i < align8(bytes.length + 1); i++) {
                buf.u8(0);
            }
        }
        buf.align(8);
        long heapHeaderAddress = buf.position();
        buf.bytes(HEAP_SIGNATURE);
        buf.u8(0);            // version
        buf.u8(0);
        buf.u8(0);
        buf.u8(0);            // reserved (3)
        buf.u64(dataSize);            // data segment size
        buf.u64(1);                  // free-list head offset (1 = no free blocks)
        buf.u64(heapDataAddress);    // data segment address

        // Symbol-table nodes: name-sorted entries distributed across nodes of <= perNode symbols each,
        // each padded to its fixed allocated size.
        List<SymbolChild> sorted = new ArrayList<>(children);
        // libhdf5 orders and looks up names with strcmp: by UTF-8 bytes, not by Java's UTF-16 order.
        sorted.sort((a, b) -> java.util.Arrays.compareUnsigned(
                a.name().getBytes(StandardCharsets.UTF_8), b.name().getBytes(StandardCharsets.UTF_8)));
        int nodeCount = Math.max(1, (sorted.size() + perNode - 1) / perNode);
        int snodSize = 8 + perNode * SYMBOL_ENTRY_SIZE;
        List<Long> nodes = new ArrayList<>();
        List<Long> maxNames = new ArrayList<>(); // heap offset of each node's greatest-by-name entry
        for (int n = 0; n < nodeCount; n++) {
            int from = n * perNode;
            int to = Math.min(from + perNode, sorted.size());
            buf.align(8);
            long node = buf.position();
            buf.bytes(SNOD_SIGNATURE);
            buf.u8(1);            // version
            buf.u8(0);            // reserved
            buf.u16(to - from);
            for (int e = from; e < to; e++) {
                SymbolChild child = sorted.get(e);
                buf.u64(nameOffsets.get(child.name()));  // link name offset
                buf.u64(child.headerAddress());          // object header address
                buf.u32(child.cacheType());
                buf.u32(0);                              // reserved
                if (child.cacheType() == 2) {            // scratch pad: a soft link's value in the heap
                    buf.u32(valueOffsets.get(child.name()));
                    buf.u32(0);
                    buf.u64(0);
                } else {                                 // scratch pad: B-tree + heap for a group
                    buf.u64(child.cacheType() == 1 ? child.btree() : 0);
                    buf.u64(child.cacheType() == 1 ? child.heap() : 0);
                }
            }
            buf.reserve((int) (snodSize - (buf.position() - node)));
            nodes.add(node);
            maxNames.add(to == 0 ? 0 : nameOffsets.get(sorted.get(to - 1).name()));
        }
        return new SymbolTable(writeGroupBTree(buf, nodes, maxNames), heapHeaderAddress);
    }

    /**
     * Writes a version-1 group B-tree (node type 0) over symbol-table nodes and returns its root. Level 0
     * points at the symbol-table nodes, each higher level at the nodes below it. A node's key {@code i + 1}
     * is the heap offset of the greatest name under child {@code i}, and its key 0 the greatest name before
     * the node (the empty name, offset 0, for the first node of a level), so a name lies under the child
     * whose keys bound it.
     */
    private long writeGroupBTree(GrowBuffer buf, List<Long> children, List<Long> maxNames) {
        int perNode = 2 * groupInternalK;
        int nodeSize = 8 + 2 * 8 + (perNode + 1) * 8 + perNode * 8;
        int level = 0;
        while (true) {
            int nodes = (children.size() + perNode - 1) / perNode;
            buf.align(8);
            long first = buf.position();
            List<Long> parents = new ArrayList<>();
            List<Long> parentMaxNames = new ArrayList<>();
            for (int n = 0; n < nodes; n++) {
                int from = n * perNode;
                int to = Math.min(children.size(), from + perNode);
                long at = first + (long) n * nodeSize;
                buf.bytes(TREE_SIGNATURE);
                buf.u8(0);                                   // node type: group
                buf.u8(level);
                buf.u16(to - from);                          // entries used
                buf.u64(n == 0 ? UNDEFINED : at - nodeSize); // left sibling
                buf.u64(n == nodes - 1 ? UNDEFINED : at + nodeSize); // right sibling
                buf.u64(from == 0 ? 0 : maxNames.get(from - 1)); // key 0
                for (int i = from; i < to; i++) {
                    buf.u64(children.get(i));
                    buf.u64(maxNames.get(i));
                }
                buf.reserve((int) (nodeSize - (buf.position() - at))); // the node is allocated full-size
                parents.add(at);
                parentMaxNames.add(maxNames.get(to - 1));
            }
            if (nodes == 1) {
                return first;
            }
            children = parents;
            maxNames = parentMaxNames;
            level++;
        }
    }

    /** The original version-0 superblock: the root group is reached through a symbol-table entry. */
    private byte[] superblockV0(long rootAddress, long base, long endOfFile) {
        GrowBuffer sb = new GrowBuffer();
        sb.bytes(HDF5_SIGNATURE);
        sb.u8(0);   // superblock version
        sb.u8(0);   // free-space storage version
        sb.u8(0);   // root group symbol-table entry version
        sb.u8(0);   // reserved
        sb.u8(0);   // shared header message format version
        sb.u8(8);   // size of offsets
        sb.u8(8);   // size of lengths
        sb.u8(0);   // reserved
        sb.u16(groupLeafK);
        sb.u16(groupInternalK);
        sb.u32(0);  // file consistency flags
        sb.u64(base);       // base address: the superblock's, after a user block
        sb.u64(UNDEFINED);  // free-space info address
        sb.u64(base + endOfFile); // end-of-file address (absolute)
        sb.u64(UNDEFINED);  // driver info block address
        sb.u64(0);              // root symbol-table entry: link name offset
        sb.u64(rootAddress);    // root symbol-table entry: object header address
        boolean cached = legacyRootBtree != UNDEFINED; // no symbol table to cache in a root of the new format
        sb.u32(cached ? 1 : 0); // cache type: group
        sb.u32(0);              // reserved
        sb.u64(cached ? legacyRootBtree : 0); // scratch pad: root B-tree
        sb.u64(cached ? legacyRootHeap : 0);  // scratch pad: root local heap
        return sb.toByteArray();
    }

    private static byte[] superblock(long rootAddress, long base, long endOfFile) {
        GrowBuffer sb = new GrowBuffer();
        sb.bytes(HDF5_SIGNATURE);
        sb.u8(3);
        sb.u8(8);
        sb.u8(8);
        sb.u8(0);
        sb.u64(base);
        sb.u64(UNDEFINED);
        sb.u64(base + endOfFile);
        sb.u64(rootAddress);
        sb.u32(Lookup3.hashLittle(sb.toByteArray()));
        return sb.toByteArray();
    }

    private static void requireElementCount(long[] shape, int length) {
        if (elementCount(shape) != length) {
            throw new IllegalArgumentException(
                    "shape implies " + elementCount(shape) + " elements but data has " + length);
        }
    }

    private static long elementCount(long[] shape) {
        long count = 1;
        for (long d : shape) {
            count *= d;
        }
        return count;
    }

    /** Minimum number of bytes needed to hold an unsigned value up to {@code size} (member offsets). */
    private static int byteWidthFor(int size) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, size));
        return Math.max(1, (bits + 7) / 8);
    }

    /**
     * Builds a compound (class 6) datatype message: members packed at the given offsets, names UTF-8.
     * Version 3 (HDF5 1.8+), or for the earliest format version 1 (padded names and the legacy member
     * dimension block).
     */
    private static byte[] compoundDatatype(CompoundField[] fields, int[] offsets, int recordSize, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 0x16 : 0x36);
        b.u8(fields.length & 0xFF);
        b.u8((fields.length >>> 8) & 0xFF);
        b.u8(0);
        b.u32(recordSize);
        int offsetWidth = byteWidthFor(recordSize);
        for (int i = 0; i < fields.length; i++) {
            byte[] name = (fields[i].name + "\0").getBytes(StandardCharsets.UTF_8); // null-terminated name
            if (legacy) {
                padded(b, name);
                b.u32(offsets[i]);
                b.u8(0);      // dimensionality (a scalar member)
                b.u8(0);
                b.u8(0);
                b.u8(0);      // reserved (3)
                b.u32(0);     // dimension permutation
                b.u32(0);     // reserved
                for (int d = 0; d < 4; d++) {
                    b.u32(0); // dimension sizes
                }
            } else {
                b.bytes(name);
                b.uvar(offsets[i], offsetWidth);
            }
            b.bytes(fields[i].datatype);
        }
        return b.toByteArray();
    }

    /** Builds a variable-length sequence (class 9, version 1) datatype message over the given base. */
    private static byte[] vlenSequenceDatatype(byte[] base) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x19); // version 1, class 9 (variable-length)
        b.u8(0x00); // bit field: vlen type 0 = sequence (1 would be string)
        b.u8(0x00);
        b.u8(0x00);
        b.u32(16); // size = the 16-byte global-heap id
        b.bytes(base);
        return b.toByteArray();
    }

    /**
     * Builds an array (class 10) datatype message with the given element shape and base: version 3
     * (HDF5 1.8+), or for the earliest format version 2, the first that has the array class.
     */
    private static byte[] arrayDatatype(int[] arrayDims, int baseSize, byte[] base, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 0x2A : 0x3A);
        b.u8(0);
        b.u8(0);
        b.u8(0);
        b.u32(product(arrayDims) * baseSize);
        b.u8(arrayDims.length); // rank
        if (legacy) {
            b.u8(0);
            b.u8(0);
            b.u8(0);            // reserved (3)
        }
        for (int dimension : arrayDims) {
            b.u32(dimension);
        }
        if (legacy) {
            for (int d = 0; d < arrayDims.length; d++) {
                b.u32(d);       // permutation index (the identity)
            }
        }
        b.bytes(base);
        return b.toByteArray();
    }

    /** Builds a native complex (class 11, version 5) datatype message over a {@code float64} base. */
    private static byte[] complex128Datatype() {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x5B); // version 5, class 11 (complex)
        b.u8(0x01);
        b.u8(0);
        b.u8(0);
        b.u32(16);
        b.bytes(DATATYPE_FLOAT64);
        return b.toByteArray();
    }

    private static int product(int[] values) {
        int p = 1;
        for (int v : values) {
            p *= v;
        }
        return p;
    }

    private static void requireArrayData(long[] shape, int perElement, int dataLength) {
        long expected = elementCount(shape) * perElement;
        if (dataLength != expected) {
            throw new IllegalArgumentException(
                    "array dataset expects " + expected + " values but data has " + dataLength);
        }
    }

    private static void putDoubleLittleEndian(byte[] out, int offset, double value) {
        long v = Double.doubleToLongBits(value);
        for (int b = 0; b < 8; b++) {
            out[offset + b] = (byte) (v >>> (8 * b));
        }
    }

    private static byte[] float32Bytes(float[] data) {
        byte[] out = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            int v = Float.floatToIntBits(data[i]);
            out[i * 4] = (byte) v;
            out[i * 4 + 1] = (byte) (v >>> 8);
            out[i * 4 + 2] = (byte) (v >>> 16);
            out[i * 4 + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    /**
     * Builds an enumerated (class 8) datatype message over a 32-bit base type, member names UTF-8:
     * version 3 (HDF5 1.8+), or for the earliest format version 1 (names padded to 8 bytes).
     */
    private static byte[] enumDatatype(EnumType type, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        int members = type.names.size();
        b.u8(legacy ? 0x18 : 0x38);
        b.u8(members & 0xFF);
        b.u8((members >>> 8) & 0xFF);
        b.u8(0);
        b.u32(4); // size = base type size
        b.bytes(DATATYPE_INT32);
        for (String name : type.names) {
            byte[] bytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
            if (legacy) {
                padded(b, bytes);
            } else {
                b.bytes(bytes);
            }
        }
        for (int value : type.values) {
            b.u32(value);
        }
        return b.toByteArray();
    }

    private static int align8(int n) {
        return (n + 7) & ~7;
    }

    private static byte[] intBytes(int[] data) {
        byte[] out = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            int v = data[i];
            out[i * 4] = (byte) v;
            out[i * 4 + 1] = (byte) (v >>> 8);
            out[i * 4 + 2] = (byte) (v >>> 16);
            out[i * 4 + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    private static byte[] shortBytes(short[] data) {
        byte[] out = new byte[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = (byte) data[i];
            out[i * 2 + 1] = (byte) (data[i] >>> 8);
        }
        return out;
    }

    private static byte[] longBytes(long[] data) {
        byte[] out = new byte[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (data[i] >>> (8 * b));
            }
        }
        return out;
    }

    /** A fixed-length string (class 3) datatype message: null-padded, ASCII or UTF-8, of the given byte size. */
    private static byte[] fixedStringDatatype(int size, boolean utf8) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x13); // version 1, class 3 (string)
        b.u8(utf8 ? 0x11 : 0x01); // bit field: null-pad; character set ASCII or UTF-8 (bits 4-7)
        b.u8(0);
        b.u8(0);
        b.u32(size);
        return b.toByteArray();
    }

    private static byte[] doubleBytes(double[] data) {
        byte[] out = new byte[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            long v = Double.doubleToLongBits(data[i]);
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (v >>> (8 * b));
            }
        }
        return out;
    }

    // --------------------------------------------------------------- spec tree

    /**
     * What is written for a group or dataset: its attributes, and for one already in a file being changed
     * (P2 WF6), where it is and which of the file's attributes go.
     */
    private abstract static class ObjectSpec {
        long address = UNDEFINED;          // in the file being changed: the object header; else undefined
        Hdf5Object object;                 // ... and the reader's view of it
        final List<AttributeSpec> attributes = new ArrayList<>();
        final Set<String> attributeNames = new HashSet<>();     // added (or replacing the file's) this session
        final Set<String> deletedAttributes = new HashSet<>();  // the file's, deleted or replaced
        int hardLinks;                     // an object added: the hard links to it (besides its own)
        private Set<String> fileAttributeNames;

        boolean inFile() {
            return address != UNDEFINED;
        }

        /** True if the object has the attribute {@code name} in the file, not deleted. */
        boolean hasFileAttribute(String name) {
            if (!inFile() || deletedAttributes.contains(name)) {
                return false;
            }
            if (fileAttributeNames == null) {
                fileAttributeNames = new HashSet<>();
                for (Attribute attribute : object.attributes()) {
                    fileAttributeNames.add(attribute.name());
                }
            }
            return fileAttributeNames.contains(name);
        }
    }

    private static final class GroupSpec extends ObjectSpec {
        String name = "";
        final List<GroupSpec> groups = new ArrayList<>();       // added, or (in the file) opened
        final List<DatasetSpec> datasets = new ArrayList<>();   // added, or (in the file) opened
        final List<LinkSpec> links = new ArrayList<>();        // soft, external, and hard links
        final Set<String> linkNames = new HashSet<>();      // groups and datasets share one namespace
        final Set<String> deletedLinks = new HashSet<>();   // the file's links, deleted
        private Set<String> fileLinkNames;

        /** True if the group has the link {@code name} in the file, not deleted. */
        boolean hasFileLink(String name) {
            if (!inFile() || deletedLinks.contains(name)) {
                return false;
            }
            if (fileLinkNames == null) {
                fileLinkNames = new HashSet<>();
                for (Link link : ((Group) object).links()) {
                    fileLinkNames.add(link.name());
                }
            }
            return fileLinkNames.contains(name);
        }
    }

    private static final class DatasetSpec extends ObjectSpec {
        String name;
        final byte[] datatype;
        final int elementSize;
        long[] shape;                   // grows, for a dataset with a larger maximum shape
        long[] maxShape;                // null: the shape cannot change
        long[] chunkShape;              // null for contiguous storage
        Datatype type;                  // a dataset made by createDataset, written by DatasetWriter.write; else null
        Storage storage;                // made when data is first written
        byte[] data;                    // inline element bytes until written, or null for vlen strings/sequences
        List<byte[]> vlenStrings;       // vlen payloads (string bytes or sequence element bytes), or null
        int[] vlenElementCounts;        // per-element sequence lengths (element counts); null for strings
        List<String> referenceTargets;  // object-reference target paths, or null
        byte[] fillValue;                // custom fill value (datatype-order bytes), or null for the default 0
        boolean compact;                 // store the element data inline in the object header
        final List<FilterSpec> filters = new ArrayList<>(); // the chunk filter pipeline, in write order
        int nbitPrecision = -1;         // -1 = no n-bit filter
        long[] fileShape;               // a dataset of the file: its shape there
        boolean virtual;                // a virtual dataset of the file: written into its sources (P2 WF11)

        DatasetSpec(String name, byte[] datatype, int elementSize, long[] shape, long[] chunkShape,
                    byte[] data, List<byte[]> vlenStrings) {
            this.name = name;
            this.datatype = datatype;
            this.elementSize = elementSize;
            this.shape = shape;
            this.chunkShape = chunkShape;
            this.data = data;
            this.vlenStrings = vlenStrings;
        }
    }

    /**
     * An attribute: its value's bytes, the variable-length ids in them to fill in once their collections
     * are placed, and the object references once their targets' addresses are known.
     */
    private record AttributeSpec(String name, byte[] datatype, long[] shape, byte[] data, List<ValueEncoder.IdPatch> ids,
                                 List<ValueEncoder.RefPatch> refs) {
        AttributeSpec(String name, byte[] datatype, long[] shape, byte[] data) {
            this(name, datatype, shape, data, List.of(), List.of());
        }
    }

    /**
     * A link added: soft (to {@code target}, no file), external (to {@code target} in {@code file}), or hard,
     * to an object added in the session ({@code object}) or to one of the file (its header {@code address}).
     */
    private record LinkSpec(String name, String target, String file, ObjectSpec object, long address) {
    }

    /**
     * One filter of a dataset's pipeline: its id and its parameter (deflate level, n-bit precision, szip
     * pixels per block and coding); for a dataset of the file, the client data the file stored for it; for a
     * new dataset's third-party filter, the values hdf5plugin passes to {@code H5Pset_filter}, from which
     * {@link #thirdPartyClientData} makes the client data.
     */
    private record FilterSpec(int id, int parameter, int[] clientData, int[] options) {
        FilterSpec(int id, int parameter) {
            this(id, parameter, null, null);
        }

        FilterSpec(int id, int parameter, int[] clientData) {
            this(id, parameter, clientData, null);
        }
    }
}
