package com.ebremer.falcon.hdf5;

/**
 * One link in a {@link Group}: how a name in the group reaches an object. A <b>hard</b> link points at
 * an object in this file; a <b>soft</b> link holds a path in this file (absolute, or relative to the
 * link's group), which need not resolve; an <b>external</b> link names an object in another file, which
 * a group follows as the file's {@link ExternalFileAccess} policy allows.
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

    /**
     * The link's name within its group.
     *
     * @return the name
     */
    String name();

    /**
     * A hard link to the object whose header is at {@code objectHeaderAddress} in this file.
     *
     * @param name                the link's name within its group
     * @param objectHeaderAddress the address of the object's header (see {@link Hdf5Object#objectHeaderAddress()})
     */
    record Hard(String name, long objectHeaderAddress) implements Link {
    }

    /**
     * A soft (symbolic) link to a path in this file; the path may not exist.
     *
     * @param name       the link's name within its group
     * @param targetPath the path the link holds: absolute, or relative to the link's group
     */
    record Soft(String name, String targetPath) implements Link {
    }

    /**
     * An external link to the object at {@code objectPath} in the file {@code fileName}.
     *
     * @param name       the link's name within its group
     * @param fileName   the other file's name, as written (resolved as {@link ExternalFileAccess} allows)
     * @param objectPath the object's path in that file
     */
    record External(String name, String fileName, String objectPath) implements Link {
    }

    /**
     * A user-defined link class (type 65&ndash;255) that Falcon does not interpret.
     *
     * @param name the link's name within its group
     * @param type the link class, 65&ndash;255
     */
    record UserDefined(String name, int type) implements Link {
    }
}
