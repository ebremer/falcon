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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * A group in the HDF5 hierarchy: a named collection of {@linkplain Link links} to other objects.
 *
 * <p>{@link #links()} lists every link: hard links, soft links (paths within this file), and external
 * links (objects in other files). {@link #children()} and {@link #child(String)} follow hard and soft
 * links to the objects they reach; external links are listed but not followed, and a soft link that does
 * not resolve (a missing target, or a cycle) reaches nothing. Links are read lazily on first access and
 * cached, for old-style (symbol-table) and new-style (compact or dense link storage) groups alike.
 */
public final class Group extends Hdf5Object {

    /** Soft links followed while resolving one path before giving up (libhdf5's default H5L_NUM_LINKS). */
    private static final int MAX_SOFT_LINKS = 16;

    private volatile List<Link> links;          // loaded lazily, then cached (immutable)
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

    /** The link with the given name, if present. */
    public Optional<Link> link(String name) {
        for (Link link : links()) {
            if (link.name().equals(name)) {
                return Optional.of(link);
            }
        }
        return Optional.empty();
    }

    /**
     * The object the named link reaches, following soft links within this file. Empty if there is no
     * such link, or it is an external link, or a soft link that does not resolve.
     */
    public Optional<Hdf5Object> child(String name) {
        return link(name).flatMap(link -> follow(link, 0));
    }

    /** The direct child group with the given name. */
    public Group group(String name) {
        return requireChild(name, Group.class);
    }

    /** The direct child dataset with the given name. */
    public Dataset dataset(String name) {
        return requireChild(name, Dataset.class);
    }

    /** The direct child committed (named) datatype with the given name. */
    public CommittedDatatype committedType(String name) {
        return requireChild(name, CommittedDatatype.class);
    }

    private <T extends Hdf5Object> T requireChild(String name, Class<T> kind) {
        Link link = link(name).orElseThrow(
                () -> new NoSuchElementException("no child '" + name + "' in " + displayPath()));
        Hdf5Object object = follow(link, 0).orElseThrow(() -> switch (link) {
            case Link.External e -> new HdfUnsupportedException("'" + name + "' in " + displayPath()
                    + " is an external link to " + e.fileName() + ":" + e.objectPath()
                    + "; Falcon does not follow external links (open that file instead)");
            case Link.Soft s -> new NoSuchElementException("'" + name + "' in " + displayPath()
                    + " is a soft link to " + s.targetPath() + ", which does not resolve");
            default -> new HdfUnsupportedException("'" + name + "' in " + displayPath()
                    + " is a user-defined link, which Falcon does not follow");
        });
        if (!kind.isInstance(object)) {
            throw new IllegalArgumentException(
                    "'" + name + "' in " + displayPath() + " is not a " + kind.getSimpleName().toLowerCase());
        }
        return kind.cast(object);
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
     * Resolves a soft link's path: absolute from the root, otherwise relative to this group, following
     * soft links along the way. Empty if a component is missing, crosses a non-group, is an external
     * link, or if more than {@link #MAX_SOFT_LINKS} soft links are followed (a cycle).
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
            // link-name-index record: name hash (4 bytes) followed by the heap ID.
            byte[] heapId = Arrays.copyOfRange(record, 4, 4 + heap.idLength());
            result.add(LinkMessage.parse(HdfBuffer.of(heap.readObject(heapId)), 0, ctx.sizeOfOffsets()));
        }
        return result;
    }

    /** Old-style storage: symbol-table entries, whose names (and soft-link paths) live in a local heap. */
    private List<Link> loadOldStyleLinks(HeaderMessage symbolTable) {
        SymbolTableMessage message = SymbolTableMessage.parse(ctx, symbolTable);
        LocalHeap heap = LocalHeap.parse(ctx, message.localHeapAddress());
        List<SymbolTableEntry> entries = GroupBTreeV1.readEntries(ctx, message.btreeAddress());
        List<Link> result = new ArrayList<>(entries.size());
        for (SymbolTableEntry entry : entries) {
            String name = heap.name(ctx, entry.linkNameOffset());
            result.add(entry.isSoftLink()
                    ? new Link.Soft(name, heap.name(ctx, entry.linkValueOffset()))
                    : new Link.Hard(name, entry.objectHeaderAddress()));
        }
        return result;
    }

    private String displayPath() {
        return path().isEmpty() ? "/" : path();
    }
}
