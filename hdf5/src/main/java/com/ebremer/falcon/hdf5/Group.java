package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.hdf5.btree.GroupBTreeV1;
import com.ebremer.falcon.hdf5.group.SymbolTableEntry;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.heap.FractalHeap;
import com.ebremer.falcon.hdf5.heap.LocalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.message.LinkInfoMessage;
import com.ebremer.falcon.hdf5.message.LinkMessage;
import com.ebremer.falcon.hdf5.message.SymbolTableMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * A group in the HDF5 hierarchy: a named collection of {@linkplain Link links} to other objects.
 *
 * <p>{@link #links()} lists every link: hard links, soft links (paths within this file), and external
 * links (objects in other files). {@link #children()} and {@link #child(String)} follow hard and soft
 * links to the objects they reach; external links are listed but not followed, and a soft link that does
 * not resolve (a missing target, or a cycle) reaches nothing. Links are read lazily on first access and
 * cached, for old-style (symbol-table) and new-style (compact or dense link storage) groups alike. A
 * lookup by name before then reads only what it needs: the group's name index (a dense group's name-hash
 * B-tree, or an old-style group's B-tree of names), as libhdf5 does.
 *
 * <p><b>Paths.</b> {@link #child(String)}, {@link #link(String)}, {@link #group(String)},
 * {@link #dataset(String)} and {@link #committedType(String)} take a path, as libhdf5's functions do: link
 * names separated by {@code /}, relative to this group or, starting with {@code /}, to the root group.
 * Repeated slashes and a trailing slash are ignored, and {@code .} names the group it is in. Soft links
 * along the way are followed. An object is named by the path it was reached through:
 *
 * <pre>{@code
 * Dataset temperature = h5.root().dataset("climate/2024/temperature"); // path() "/climate/2024/temperature"
 * Group climate = anyGroup.group("/climate");                           // absolute: from the root
 * }</pre>
 */
public final class Group extends Hdf5Object {

    /** Soft links followed while resolving one path before giving up (libhdf5's default H5L_NUM_LINKS). */
    private static final int MAX_SOFT_LINKS = 16;

    private volatile List<Link> links;          // loaded lazily, then cached (immutable)
    private volatile Map<String, Link> linksByName; // built from links when a lookup finds them loaded
    private volatile List<Hdf5Object> children;

    private Group(FileContext ctx, String name, String path, long objectHeaderAddress) {
        super(ctx, name, path, objectHeaderAddress);
    }

    static Group root(FileContext ctx, long objectHeaderAddress) {
        return new Group(ctx, "", "/", objectHeaderAddress);
    }

    static Group child(FileContext ctx, String name, String parentPath, long objectHeaderAddress) {
        return new Group(ctx, name, childPath(parentPath, name), objectHeaderAddress);
    }

    @Override
    public boolean isGroup() {
        return true;
    }

    /** Every link in this group, of every kind, in the order the group indexes them. */
    public List<Link> links() {
        ctx.checkOpen();
        List<Link> result = links;
        if (result == null) {
            result = List.copyOf(loadLinks());
            links = result;
        }
        return result;
    }

    /**
     * The objects this group's hard and soft links reach, in link order. External links, and soft links
     * that do not resolve, are left out (see {@link #links()}). An object reached through a soft link is
     * named by the link.
     */
    public List<Hdf5Object> children() {
        ctx.checkOpen();
        List<Hdf5Object> result = children;
        if (result == null) {
            List<Hdf5Object> reached = new ArrayList<>();
            for (Link link : links()) {
                follow(link, 0).ifPresent(reached::add);
            }
            result = List.copyOf(reached);
            children = result;
        }
        return result;
    }

    /** The name of every link in this group (including external and unresolved soft links). */
    public List<String> childNames() {
        return links().stream().map(Link::name).toList();
    }

    /**
     * The link at {@code path}, if present: the link named by the path's last component, in the group the
     * rest of the path reaches (see <b>Paths</b> above). For a plain name, this group's link of that name.
     * Empty if there is no such link, or if the path ends in {@code .} or is {@code /}, which no link
     * names.
     */
    public Optional<Link> link(String path) {
        String trimmed = trimTrailingSlashes(path);
        int slash = trimmed.lastIndexOf('/');
        String name = trimmed.substring(slash + 1);
        if (name.isEmpty() || name.equals(".")) {
            return Optional.empty();
        }
        if (slash >= 0) {
            return resolvePath(slash == 0 ? "/" : trimmed.substring(0, slash), 0)
                    .flatMap(parent -> parent instanceof Group group ? group.link(name) : Optional.empty());
        }
        return named(name);
    }

    /** This group's link called {@code name}: from the loaded links, or else through the name index. */
    private Optional<Link> named(String name) {
        ctx.checkOpen();
        List<Link> loaded = links;
        if (loaded != null) {
            Map<String, Link> byName = linksByName;
            if (byName == null) {
                Map<String, Link> map = new HashMap<>();
                for (Link link : loaded) {
                    map.putIfAbsent(link.name(), link);
                }
                byName = Map.copyOf(map);
                linksByName = byName;
            }
            return Optional.ofNullable(byName.get(name));
        }
        ObjectHeader header = header();
        HeaderMessage symbolTable = header.find(MessageType.SYMBOL_TABLE);
        if (symbolTable != null) {
            SymbolTableMessage message = SymbolTableMessage.parse(ctx, symbolTable);
            LocalHeap heap = LocalHeap.parse(ctx, message.localHeapAddress());
            SymbolTableEntry entry = GroupBTreeV1.find(ctx, message.btreeAddress(), heap,
                    name.getBytes(StandardCharsets.UTF_8));
            return Optional.ofNullable(entry == null ? null : oldStyleLink(heap, name, entry));
        }
        HeaderMessage linkInfo = header.find(MessageType.LINK_INFO);
        if (linkInfo != null && LinkInfoMessage.fractalHeapAddress(ctx, linkInfo) != HdfBuffer.UNDEFINED_ADDRESS) {
            FractalHeap heap = FractalHeap.parse(ctx, LinkInfoMessage.fractalHeapAddress(ctx, linkInfo));
            int hash = nameHash(name);
            // A link-name-index record (type 5) is the name's hash, which orders the index, and the heap ID.
            for (byte[] record : BTreeV2.find(ctx, LinkInfoMessage.nameBTreeAddress(ctx, linkInfo),
                    record -> Integer.compareUnsigned(hashAt(record, 0), hash))) {
                Link link = denseLink(heap, record);
                if (link.name().equals(name)) {
                    return Optional.of(link);
                }
            }
            return Optional.empty();
        }
        for (HeaderMessage message : header.messages()) {
            if (message.type() == MessageType.LINK) {
                Link link = LinkMessage.parse(ctx, message);
                if (link.name().equals(name)) {
                    return Optional.of(link);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The object at {@code path}, following soft links within this file (see <b>Paths</b> above). For a
     * plain name, the object this group's link of that name reaches. Empty if a component is missing, is
     * an external or user-defined link or a soft link that does not resolve, or is not a group but is
     * followed by more of the path; and for an empty path.
     */
    public Optional<Hdf5Object> child(String path) {
        return path.isEmpty() ? Optional.empty() : resolvePath(path, 0);
    }

    /**
     * The group at {@code path} (see <b>Paths</b> above).
     *
     * @throws NoSuchElementException if no object is there
     * @throws HdfUnsupportedException if the path crosses an external or user-defined link
     * @throws IllegalArgumentException if the object there is not a group
     */
    public Group group(String path) {
        return requireChild(path, Group.class);
    }

    /**
     * The dataset at {@code path} (see <b>Paths</b> above).
     *
     * @throws NoSuchElementException if no object is there
     * @throws HdfUnsupportedException if the path crosses an external or user-defined link
     * @throws IllegalArgumentException if the object there is not a dataset
     */
    public Dataset dataset(String path) {
        return requireChild(path, Dataset.class);
    }

    /**
     * The committed (named) datatype at {@code path} (see <b>Paths</b> above).
     *
     * @throws NoSuchElementException if no object is there
     * @throws HdfUnsupportedException if the path crosses an external or user-defined link
     * @throws IllegalArgumentException if the object there is not a committed datatype
     */
    public CommittedDatatype committedType(String path) {
        return requireChild(path, CommittedDatatype.class);
    }

    private <T extends Hdf5Object> T requireChild(String path, Class<T> kind) {
        Hdf5Object object = child(path).orElseThrow(() -> whyNot(path));
        if (!kind.isInstance(object)) {
            throw new IllegalArgumentException(
                    "'" + path + "' in " + displayPath() + " is not a " + kind.getSimpleName().toLowerCase());
        }
        return kind.cast(object);
    }

    /** Why {@code path} reaches no object: walks it again, a component at a time, to the one that fails. */
    private RuntimeException whyNot(String path) {
        String context = path.indexOf('/') < 0 ? "" : " (resolving '" + path + "' in " + displayPath() + ")";
        Hdf5Object current = path.startsWith("/") ? Group.root(ctx, ctx.rootAddress()) : this;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (!(current instanceof Group group)) {
                return new NoSuchElementException(current.path() + " is not a group" + context);
            }
            Optional<Link> link = group.link(part);
            if (link.isEmpty()) {
                return new NoSuchElementException("no child '" + part + "' in " + group.displayPath() + context);
            }
            Optional<Hdf5Object> reached = group.follow(link.get(), 0);
            if (reached.isEmpty()) {
                return group.unfollowed(link.get(), context);
            }
            current = reached.get();
        }
        return new NoSuchElementException(path.isEmpty() ? "an empty path names no object"
                : "'" + path + "' in " + displayPath() + " follows more than " + MAX_SOFT_LINKS + " soft links");
    }

    /** Why a link of this group reaches no object. */
    private RuntimeException unfollowed(Link link, String context) {
        String name = "'" + link.name() + "' in " + displayPath();
        return switch (link) {
            case Link.External e -> new HdfUnsupportedException(name + " is an external link to " + e.fileName()
                    + ":" + e.objectPath() + "; Falcon does not follow external links (open that file instead)"
                    + context);
            case Link.Soft s -> new NoSuchElementException(
                    name + " is a soft link to " + s.targetPath() + ", which does not resolve" + context);
            default -> new HdfUnsupportedException(
                    name + " is a user-defined link, which Falcon does not follow" + context);
        };
    }

    private static String trimTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    /** The object a link reaches, named by the link; empty for external, user-defined, or dangling links. */
    private Optional<Hdf5Object> follow(Link link, int softLinks) {
        return switch (link) {
            case Link.Hard hard -> Optional.of(classify(ctx, hard.name(), path(), hard.objectHeaderAddress()));
            case Link.Soft soft -> resolvePath(soft.targetPath(), softLinks + 1)
                    .map(target -> rename(target, soft.name()));
            default -> Optional.empty();
        };
    }

    /** {@code target} as reached through a link named {@code name} in this group. */
    private Hdf5Object rename(Hdf5Object target, String name) {
        return target.name().equals(name) && target.path().equals(childPath(path(), name))
                ? target : classify(ctx, name, path(), target.objectHeaderAddress());
    }

    /**
     * Resolves a path, a soft link's or a caller's: absolute from the root, otherwise relative to this
     * group, following soft links along the way. Empty if a component is missing, crosses a non-group, is
     * an external link, or if more than {@link #MAX_SOFT_LINKS} soft links are followed (a cycle).
     */
    private Optional<Hdf5Object> resolvePath(String targetPath, int softLinks) {
        if (softLinks > MAX_SOFT_LINKS) {
            return Optional.empty();
        }
        Hdf5Object current = targetPath.startsWith("/") ? Group.root(ctx, ctx.rootAddress()) : this;
        for (String part : targetPath.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (!(current instanceof Group group)) {
                return Optional.empty();
            }
            Optional<Link> next = group.link(part);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            Optional<Hdf5Object> reached = group.follow(next.get(), softLinks);
            if (reached.isEmpty()) {
                return Optional.empty();
            }
            current = reached.get();
            softLinks += next.get() instanceof Link.Soft ? 1 : 0;
        }
        return Optional.of(current);
    }

    private List<Link> loadLinks() {
        ObjectHeader header = header();
        HeaderMessage symbolTable = header.find(MessageType.SYMBOL_TABLE);
        if (symbolTable != null) {
            return loadOldStyleLinks(symbolTable);
        }
        HeaderMessage linkInfo = header.find(MessageType.LINK_INFO);
        if (linkInfo != null && LinkInfoMessage.fractalHeapAddress(ctx, linkInfo) != HdfBuffer.UNDEFINED_ADDRESS) {
            return loadDenseLinks(linkInfo);
        }
        // Compact storage: the links are Link messages in this object header.
        List<Link> result = new ArrayList<>();
        for (HeaderMessage message : header.messages()) {
            if (message.type() == MessageType.LINK) {
                result.add(LinkMessage.parse(ctx, message));
            }
        }
        return result;
    }

    /** Dense storage: links live in a fractal heap indexed by a v2 B-tree (name index). */
    private List<Link> loadDenseLinks(HeaderMessage linkInfo) {
        FractalHeap heap = FractalHeap.parse(ctx, LinkInfoMessage.fractalHeapAddress(ctx, linkInfo));
        long nameBTree = LinkInfoMessage.nameBTreeAddress(ctx, linkInfo);
        List<Link> result = new ArrayList<>();
        for (byte[] record : BTreeV2.readRecords(ctx, nameBTree)) {
            result.add(denseLink(heap, record));
        }
        return result;
    }

    /** The link a link-name-index record points at: the record is the name hash (4 bytes), then the heap ID. */
    private Link denseLink(FractalHeap heap, byte[] record) {
        byte[] heapId = Arrays.copyOfRange(record, 4, 4 + heap.idLength());
        return LinkMessage.parse(HdfBuffer.of(heap.readObject(heapId)), 0, ctx.sizeOfOffsets());
    }

    /** Old-style storage: symbol-table entries, whose names (and soft-link paths) live in a local heap. */
    private List<Link> loadOldStyleLinks(HeaderMessage symbolTable) {
        SymbolTableMessage message = SymbolTableMessage.parse(ctx, symbolTable);
        LocalHeap heap = LocalHeap.parse(ctx, message.localHeapAddress());
        List<SymbolTableEntry> entries = GroupBTreeV1.readEntries(ctx, message.btreeAddress());
        List<Link> result = new ArrayList<>(entries.size());
        for (SymbolTableEntry entry : entries) {
            result.add(oldStyleLink(heap, heap.name(ctx, entry.linkNameOffset()), entry));
        }
        return result;
    }

    /** The link a symbol-table entry named {@code name} stands for: soft (its target in the heap) or hard. */
    private Link oldStyleLink(LocalHeap heap, String name, SymbolTableEntry entry) {
        return entry.isSoftLink()
                ? new Link.Soft(name, heap.name(ctx, entry.linkValueOffset()))
                : new Link.Hard(name, entry.objectHeaderAddress());
    }

    private String displayPath() {
        return path().isEmpty() ? "/" : path();
    }
}
