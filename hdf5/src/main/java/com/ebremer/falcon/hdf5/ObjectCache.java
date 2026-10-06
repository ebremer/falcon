package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.ChunkIndex;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a file's objects have read of themselves, shared by every handle of the same object (P2 PF6): its
 * header, attributes, and links, and a dataset's datatype, shape, layout, filters, fill value, chunk index,
 * and virtual mappings. Handles are cheap and many (each {@code group.dataset("x")} makes one, and a path
 * makes one per group it passes), so each reads what it needs once per file, not once per handle.
 *
 * <p>Kept per file, by object-header address, up to {@link OpenOptions#objectCacheSize} bytes (as
 * estimated: a few hundred bytes an object, plus its header's messages, its attributes and links, and its
 * chunk index once read whole), least recently used first out. A handle keeps what it has, evicted or not,
 * so the bound applies to objects no handle holds. A size of 0 shares nothing: each handle keeps its own.
 *
 * <p>Thread-safe: a {@link State}'s parts are each read once into a volatile field, as a handle's were, and
 * the cache's map is guarded by the cache.
 */
final class ObjectCache implements AutoCloseable {

    /** What every object is reckoned to take, before its parts: the state and its small parts. */
    static final long BASE_BYTES = 512;

    private final long maxBytes;
    private final LinkedHashMap<Long, State> states = new LinkedHashMap<>(16, 0.75f, true); // access order
    private long bytes;

    private ObjectCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** The object cache of the file of {@code ctx}. */
    static ObjectCache of(FileContext ctx) {
        return ctx.resource(ObjectCache.class, () -> new ObjectCache(ctx.options().objectCacheSize()));
    }

    /** The state of the object whose header is at {@code address}: the one kept, or a new one, kept if it fits. */
    synchronized State state(long address) {
        State state = states.get(address);
        if (state != null) {
            return state;
        }
        if (maxBytes <= 0) {
            return new State(address, null); // nothing shared: the handle keeps its own
        }
        state = new State(address, this);
        state.bytes = BASE_BYTES;
        state.kept = true;
        states.put(address, state);
        bytes += BASE_BYTES;
        evict(state);
        return state;
    }

    /** Counts {@code more} bytes that {@code state} keeps now, if it is kept here. */
    synchronized void charge(State state, long more) {
        if (!state.kept) {
            return;
        }
        state.bytes += more;
        bytes += more;
        evict(state);
    }

    /** Drops the least recently used states, but {@code keep}, until the cache is within its bound. */
    private void evict(State keep) {
        Iterator<Map.Entry<Long, State>> it = states.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            State eldest = it.next().getValue();
            if (eldest == keep) {
                continue;
            }
            eldest.kept = false;
            bytes -= eldest.bytes;
            it.remove();
        }
    }

    /** The number of objects kept, and their bytes as estimated (for tests). */
    synchronized long[] usage() {
        return new long[] {states.size(), bytes};
    }

    @Override
    public synchronized void close() {
        for (State state : states.values()) {
            state.kept = false;
        }
        states.clear();
        bytes = 0;
    }

    /**
     * One object's parts, each read when a handle first needs it ({@code null} until then; an
     * {@link Optional} where "none" must differ from "not read yet").
     */
    static final class State {
        final long address;
        private final ObjectCache cache;  // null if not shared
        private long bytes;               // guarded by the cache
        private boolean kept;             // guarded by the cache
        volatile ObjectHeader header;
        volatile List<Attribute> attributes;
        volatile Datatype datatype;       // a dataset's, or a committed datatype's
        volatile Dataspace dataspace;
        volatile DataLayout layout;
        volatile Optional<FilterPipeline> filterPipeline;
        volatile Optional<byte[]> fillValue;
        volatile ChunkIndex chunkIndex;
        volatile VirtualDataset virtual;
        volatile List<Link> links;
        volatile Map<String, Link> linksByName;

        private State(long address, ObjectCache cache) {
            this.address = address;
            this.cache = cache;
        }

        /** Counts {@code more} bytes this object now keeps (a part read), against the cache's bound. */
        void charge(long more) {
            if (cache != null) {
                cache.charge(this, more);
            }
        }

        /** This object's header, parsed on first use. */
        ObjectHeader header(FileContext ctx) {
            ObjectHeader result = header;
            if (result == null) {
                result = ObjectHeader.parse(ctx, address);
                header = result;
                charge(96 + 48L * result.messages().size());
            }
            return result;
        }
    }
}
