package com.ebremer.falcon.hdf5.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A read-only, memory-mapped HDF5 file exposed as an {@link HdfBuffer}.
 *
 * <p>Backed by the Foreign Function &amp; Memory API: the file is mapped through a shared
 * {@link Arena} whose lifetime owns the mapping, so files larger than 2&nbsp;GB are handled without
 * the {@code MappedByteBuffer} size limit and the underlying channel can be closed immediately after
 * mapping. {@link #close()} unmaps.
 */
public final class MappedHdfFile implements AutoCloseable {

    private final Arena arena;
    private final HdfBuffer buffer;
    private final Path path;

    private MappedHdfFile(Arena arena, HdfBuffer buffer, Path path) {
        this.arena = arena;
        this.buffer = buffer;
        this.path = path;
    }

    /** Maps {@code path} read-only into memory. */
    public static MappedHdfFile openReadOnly(Path path) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long byteSize = channel.size();
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, byteSize, arena);
            return new MappedHdfFile(arena, new HdfBuffer(segment), path);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** The reader over the mapped bytes. Valid until {@link #close()}. */
    public HdfBuffer buffer() {
        return buffer;
    }

    /** The file that was mapped. */
    public Path path() {
        return path;
    }

    /** The mapped file's size in bytes. */
    public long size() {
        return buffer.size();
    }

    /** Unmaps the file. Accessing the {@link #buffer()} afterwards is an error. */
    @Override
    public void close() {
        arena.close();
    }
}
