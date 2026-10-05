package com.ebremer.falcon.hdf5;

/**
 * One link in a {@link Group}: how a name in the group reaches an object. A <b>hard</b> link points at
 * an object in this file; a <b>soft</b> link holds a path in this file (absolute, or relative to the
 * link's group), which need not resolve; an <b>external</b> link names an object in another file.
 *
 * <pre>{@code
 * for (Link link : group.links()) {
 *     switch (link) {
 *         case Link.Hard h -> ...
 *         case Link.Soft s -> System.out.println(s.name() + " -> " + s.targetPath());
 *         case Link.External e -> System.out.println(e.name() + " -> " + e.fileName() + ":" + e.objectPath());
 *         case Link.UserDefined u -> ...
 *     }
 * }
 * }</pre>
 *
 * @see Group#links()
 */
public sealed interface Link permits Link.Hard, Link.Soft, Link.External, Link.UserDefined {

    /** The link's name within its group. */
    String name();

    /** A hard link to the object whose header is at {@code objectHeaderAddress} in this file. */
    record Hard(String name, long objectHeaderAddress) implements Link {
    }

    /** A soft (symbolic) link to a path in this file; the path may not exist. */
    record Soft(String name, String targetPath) implements Link {
    }

    /** An external link to the object at {@code objectPath} in the file {@code fileName}. */
    record External(String name, String fileName, String objectPath) implements Link {
    }

    /** A user-defined link class (type 65&ndash;255) that Falcon does not interpret. */
    record UserDefined(String name, int type) implements Link {
    }
}
