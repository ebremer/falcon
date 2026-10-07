package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Attribute;
import com.ebremer.falcon.hdf5.CommittedDatatype;
import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.Group;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.hdf5.Link;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/** {@code ls} and {@code info} of an HDF5 file. */
final class Hdf5Inspect {

    private final PrintStream out;

    Hdf5Inspect(Context context) {
        this.out = context.out;
    }

    /**
     * {@return the object at {@code path}, following soft and external links}
     *
     * @param file the file
     * @param path an absolute path, or {@code /} for the root group
     * @throws NoSuchElementException if no object is there
     */
    static Hdf5Object resolve(Hdf5File file, String path) {
        String p = path.strip();
        if (p.isEmpty() || p.chars().allMatch(c -> c == '/')) {
            return file.root();
        }
        return file.root().child(p).orElseThrow(() -> new NoSuchElementException("no object at " + path));
    }

    static String join(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    // ---- ls -----------------------------------------------------------------------------------------------

    void ls(Hdf5File file, String path, boolean recursive) {
        Hdf5Object target = resolve(file, path);
        List<String[]> rows = new ArrayList<>();
        if (!(target instanceof Group group)) {
            rows.add(row(target.path(), target));
        } else if (recursive) {
            rows.add(row(group.path(), group));
            Set<Long> listed = new HashSet<>();
            listed.add(group.objectHeaderAddress());
            walk(group, group.path(), rows, listed);
        } else {
            for (Link link : sorted(group.links())) {
                rows.add(linkRow(group, link, link.name(), null));
            }
        }
        Table.print(out, rows);
    }

    /** {@return a group's links by name, as h5ls lists them} */
    static List<Link> sorted(List<Link> links) {
        List<Link> byName = new ArrayList<>(links);
        byName.sort(java.util.Comparator.comparing(Link::name));
        return byName;
    }

    private void walk(Group group, String path, List<String[]> rows, Set<Long> listed) {
        for (Link link : sorted(group.links())) {
            String childPath = join(path, link.name());
            List<Group> into = new ArrayList<>(1);
            rows.add(linkRow(group, link, childPath, link instanceof Link.Hard ? into : null));
            for (Group child : into) {
                if (listed.add(child.objectHeaderAddress())) {
                    walk(child, childPath, rows, listed);
                } else {
                    rows.add(new String[] {join(childPath, "..."), "(a group listed above, by another path)"});
                }
            }
        }
    }

    /** A link's row; a hard link's group is added to {@code into}, when given, to be walked. */
    private static String[] linkRow(Group group, Link link, String name, List<Group> into) {
        return switch (link) {
            case Link.Soft s -> new String[] {name, "soft link", "-> " + s.targetPath()};
            case Link.External e -> new String[] {name, "external link", "-> " + e.fileName() + ":" + e.objectPath()};
            case Link.UserDefined u -> new String[] {name, "user-defined link", "(type " + u.type() + ")"};
            case Link.Hard h -> {
                try {
                    Hdf5Object child = group.child(h.name()).orElse(null);
                    if (child == null) {
                        yield new String[] {name, "(nothing)"};
                    }
                    if (into != null && child instanceof Group g) {
                        into.add(g);
                    }
                    yield row(name, child);
                } catch (RuntimeException e) {
                    yield new String[] {name, "error: " + Errors.describe(e)};
                }
            }
        };
    }

    private static String[] row(String name, Hdf5Object object) {
        return switch (object) {
            case Group g -> new String[] {name, "group"};
            case Dataset d -> {
                try {
                    String layout = d.chunkShape().map(c -> "chunks " + Describe.shape(c))
                            .orElse(d.layout().name().toLowerCase(java.util.Locale.ROOT));
                    yield new String[] {name, "dataset", shape(d.dataspace()), Describe.type(d.datatype()), layout,
                            d.filters().isEmpty() ? "" : Describe.filters(d.filters())};
                } catch (RuntimeException e) {
                    yield new String[] {name, "dataset", "error: " + Errors.describe(e)};
                }
            }
            case CommittedDatatype t -> new String[] {name, "datatype", Describe.type(t.datatype())};
            default -> new String[] {name, object.getClass().getSimpleName()};
        };
    }

    static String shape(Dataspace space) {
        return switch (space.kind()) {
            case NULL -> "null";
            case SCALAR -> "scalar";
            default -> Describe.shape(space.dimensions());
        };
    }

    // ---- info ---------------------------------------------------------------------------------------------

    void info(Hdf5File file, String path) {
        Hdf5Object object = resolve(file, path);
        out.println(object.path());
        List<String[]> rows = new ArrayList<>();
        switch (object) {
            case Group g -> {
                rows.add(field("object", "group (HDF5)"));
                rows.add(field("members", Integer.toString(g.links().size())));
                if (g.path().equals("/")) {
                    rows.add(field("superblock", "version " + file.superblockVersion()));
                    file.driverInfo().ifPresent(d -> rows.add(field("driver", d.driverId())));
                    file.fileSpaceInfo().ifPresent(s -> rows.add(field("file space",
                            s.strategy().name().toLowerCase(java.util.Locale.ROOT))));
                }
            }
            case Dataset d -> dataset(d, rows);
            case CommittedDatatype t -> {
                rows.add(field("object", "committed datatype (HDF5)"));
                rows.add(field("type", Describe.type(t.datatype())));
            }
            default -> rows.add(field("object", object.getClass().getSimpleName()));
        }
        object.comment().ifPresent(c -> rows.add(field("comment", c)));
        object.modificationTime().ifPresent(t -> rows.add(field("modified", t.toString())));
        List<Attribute> attributes = object.attributes();
        rows.add(field("attributes", Integer.toString(attributes.size())));
        Table.print(out, rows);
        attributes(attributes);
    }

    private static void dataset(Dataset d, List<String[]> rows) {
        Dataspace space = d.dataspace();
        Datatype type = d.datatype();
        rows.add(field("object", "dataset (HDF5)"));
        String shape = shape(space);
        long[] max = space.maxDimensions();
        if (max != null && !java.util.Arrays.equals(max, space.dimensions())) {
            shape += ", at most " + Describe.shape(max);
        }
        rows.add(field("shape", shape));
        rows.add(field("type", Describe.type(type)));
        rows.add(field("layout", d.layout().name().toLowerCase(java.util.Locale.ROOT)
                + d.chunkShape().map(c -> ", chunks " + Describe.shape(c)).orElse("")));
        rows.add(field("filters", Describe.filters(d.filters())));
        rows.add(field("fill value", d.fillValueBytes().map(b -> fill(type, b)).orElse("0 (the default)")));
        long stored = d.storageSize();
        String storage = Describe.bytes(stored);
        long logical = space.elementCount() * type.size();
        if (stored > 0 && fixedSize(type) && !d.filters().isEmpty()) {
            storage += String.format(", for %,d bytes of elements (compression ratio %.2f)", logical,
                    (double) logical / stored);
        }
        rows.add(field("storage", storage));
    }

    private static boolean fixedSize(Datatype type) {
        return switch (type) {
            case Datatype.VariableLength v -> false;
            case Datatype.Reference r -> false;
            case Datatype.Compound c -> c.members().stream().allMatch(m -> fixedSize(m.type()));
            case Datatype.Array a -> fixedSize(a.base());
            default -> true;
        };
    }

    /** A fill value's bytes, as a number where the type is a plain one, else in hex. */
    private static String fill(Datatype type, byte[] bytes) {
        try {
            switch (type) {
                case Datatype.FixedPoint fp when Describe.standardInteger(fp) -> {
                    ByteBuffer b = ByteBuffer.wrap(bytes).order(fp.byteOrder());
                    long v = switch (fp.size()) {
                        case 1 -> fp.signed() ? b.get(0) : b.get(0) & 0xff;
                        case 2 -> fp.signed() ? b.getShort(0) : b.getShort(0) & 0xffff;
                        case 4 -> fp.signed() ? b.getInt(0) : b.getInt(0) & 0xffffffffL;
                        default -> b.getLong(0);
                    };
                    return fp.size() == 8 && !fp.signed() ? Long.toUnsignedString(v) : Long.toString(v);
                }
                case Datatype.FloatingPoint fp when Describe.standardFloat(fp) -> {
                    ByteBuffer b = ByteBuffer.wrap(bytes).order(fp.byteOrder());
                    return switch (fp.size()) {
                        case 2 -> Values.number(Float.float16ToFloat(b.getShort(0))).literal();
                        case 4 -> Values.number(b.getFloat(0)).literal();
                        default -> Values.number(b.getDouble(0)).literal();
                    };
                }
                default -> {
                    return "0x" + HexFormat.of().formatHex(bytes);
                }
            }
        } catch (RuntimeException e) {
            return "0x" + HexFormat.of().formatHex(bytes);
        }
    }

    private void attributes(List<Attribute> attributes) {
        List<String[]> rows = new ArrayList<>();
        for (Attribute attribute : attributes) {
            String value;
            try {
                value = Table.clip(Hdf5Values.json(attribute).toJson(), 100);
            } catch (RuntimeException e) {
                value = "error: " + Errors.describe(e);
            }
            String about = Describe.type(attribute.datatype());
            if (attribute.dataspace().kind() == Dataspace.Kind.SIMPLE) {
                about += " " + Describe.shape(attribute.dataspace().dimensions());
            }
            rows.add(new String[] {"  " + attribute.name(), "=", value, "[" + about + "]"});
        }
        Table.print(out, rows);
    }

    static String[] field(String name, String value) {
        return new String[] {"  " + name, value};
    }

    static ByteOrder order(Datatype type) {
        return switch (type) {
            case Datatype.FixedPoint fp -> fp.byteOrder();
            case Datatype.FloatingPoint fp -> fp.byteOrder();
            case Datatype.BitField b -> b.byteOrder();
            case Datatype.Time t -> t.byteOrder();
            case Datatype.Enumeration e -> order(e.base());
            case Datatype.Complex c -> order(c.base());
            case Datatype.Array a -> order(a.base());
            default -> ByteOrder.LITTLE_ENDIAN;
        };
    }
}
