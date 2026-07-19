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
 * A group in the HDF5 hierarchy: a named collection of child objects.
 *
 * <p>Children are read lazily on first access and cached. Stage H1 reads <b>old-style</b> (symbol
 * table) groups; new-style (link) groups are detected and reported via {@link HdfUnsupportedException}
 * until stage H5.
 */
public final class Group extends Hdf5Object {

    private List<Hdf5Object> children;

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

    /** This group's direct children, in the order the group indexes them. */
    public List<Hdf5Object> children() {
        if (children == null) {
            children = loadChildren();
        }
        return children;
    }

    /** The direct children's names. */
    public List<String> childNames() {
        return children().stream().map(Hdf5Object::name).toList();
    }

    /** The direct child with the given name, if present. */
    public Optional<Hdf5Object> child(String name) {
        for (Hdf5Object object : children()) {
            if (object.name().equals(name)) {
                return Optional.of(object);
            }
        }
        return Optional.empty();
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
        Hdf5Object object = child(name).orElseThrow(
                () -> new NoSuchElementException("no child '" + name + "' in " + displayPath()));
        if (!kind.isInstance(object)) {
            throw new IllegalArgumentException(
                    "'" + name + "' in " + displayPath() + " is not a " + kind.getSimpleName().toLowerCase());
        }
        return kind.cast(object);
    }

    private List<Hdf5Object> loadChildren() {
        ObjectHeader header = header();
        HeaderMessage symbolTable = header.find(MessageType.SYMBOL_TABLE);
        if (symbolTable != null) {
            return loadOldStyleChildren(symbolTable);
        }
        if (header.contains(MessageType.LINK_INFO)
                || header.contains(MessageType.LINK)
                || header.contains(MessageType.GROUP_INFO)) {
            return loadNewStyleChildren(header);
        }
        return List.of();
    }

    private List<Hdf5Object> loadNewStyleChildren(ObjectHeader header) {
        HeaderMessage linkInfo = header.find(MessageType.LINK_INFO);
        if (linkInfo != null
                && LinkInfoMessage.fractalHeapAddress(ctx, linkInfo) != HdfBuffer.UNDEFINED_ADDRESS) {
            return loadDenseChildren(linkInfo);
        }
        // Compact storage: the links are Link messages in this object header.
        List<Hdf5Object> result = new ArrayList<>();
        for (HeaderMessage message : header.messages()) {
            if (message.type() != MessageType.LINK) {
                continue;
            }
            LinkMessage link = LinkMessage.parse(ctx, message);
            if (link.linkType() == LinkMessage.HARD) {
                result.add(createChild(link.name(), link.targetAddress()));
            }
            // soft/external links are resolved in a later increment
        }
        return result;
    }

    /** Dense storage: links live in a fractal heap indexed by a v2 B-tree (name index). */
    private List<Hdf5Object> loadDenseChildren(HeaderMessage linkInfo) {
        FractalHeap heap = FractalHeap.parse(ctx, LinkInfoMessage.fractalHeapAddress(ctx, linkInfo));
        long nameBTree = LinkInfoMessage.nameBTreeAddress(ctx, linkInfo);
        List<Hdf5Object> result = new ArrayList<>();
        for (byte[] record : BTreeV2.readRecords(ctx, nameBTree)) {
            // link-name-index record: name hash (4 bytes) followed by the heap ID.
            byte[] heapId = Arrays.copyOfRange(record, 4, 4 + heap.idLength());
            LinkMessage link = LinkMessage.parse(HdfBuffer.of(heap.readObject(heapId)), 0, ctx.sizeOfOffsets());
            if (link.linkType() == LinkMessage.HARD) {
                result.add(createChild(link.name(), link.targetAddress()));
            }
        }
        return result;
    }

    /** Builds a child by classifying the target object header as a group, dataset, or committed type. */
    private Hdf5Object createChild(String name, long objectHeaderAddress) {
        return classify(ctx, name, path(), objectHeaderAddress);
    }

    private List<Hdf5Object> loadOldStyleChildren(HeaderMessage symbolTable) {
        SymbolTableMessage message = SymbolTableMessage.parse(ctx, symbolTable);
        LocalHeap heap = LocalHeap.parse(ctx, message.localHeapAddress());
        List<SymbolTableEntry> entries = GroupBTreeV1.readEntries(ctx, message.btreeAddress());

        List<Hdf5Object> result = new ArrayList<>(entries.size());
        for (SymbolTableEntry entry : entries) {
            String childName = heap.name(ctx, entry.linkNameOffset());
            long childHeader = entry.objectHeaderAddress();
            // The symbol-table cache flags known groups; everything else is classified from its header
            // (a plain dataset or a committed datatype).
            result.add(entry.isGroup()
                    ? Group.child(ctx, childName, path(), childHeader)
                    : createChild(childName, childHeader));
        }
        return result;
    }

    private String displayPath() {
        return path().isEmpty() ? "/" : path();
    }
}
