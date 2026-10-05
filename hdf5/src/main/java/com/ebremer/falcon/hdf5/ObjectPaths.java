package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.io.FileContext;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds a path to an object known only by its address, as libhdf5's {@code H5Iget_name} does for an
 * object opened through a reference ({@code H5G_get_name_by_addr}): a depth-first walk of the file's
 * hard links from the root group, visiting each group once, and each group's links in their native order
 * ({@code H5_ITER_NATIVE}): name order in an old-style group, the order the links were made in a compact
 * one, and name-hash order in a dense one, as {@link Group#links()} lists them. The first link that
 * reaches the object gives its path. Soft and external links are not followed. A part of the file that
 * cannot be read is skipped.
 *
 * <p>Each path found is kept for the life of the file. Thread-safe: two threads may search for the same
 * object at once, and find the same path.
 */
final class ObjectPaths implements AutoCloseable {

    private final ConcurrentHashMap<Long, String> found = new ConcurrentHashMap<>();

    /** A path to the object whose header is at {@code address}, or {@code ""} if no path reaches it. */
    String find(FileContext ctx, long address) {
        String known = found.get(address);
        if (known != null) {
            return known;
        }
        String path = address == ctx.rootAddress() ? "/" : search(ctx, address);
        String raced = found.putIfAbsent(address, path);
        return raced != null ? raced : path;
    }

    private static String search(FileContext ctx, long address) {
        Set<Long> visited = new HashSet<>();
        visited.add(ctx.rootAddress());
        Deque<Iterator<Link.Hard>> stack = new ArrayDeque<>();
        Deque<String> paths = new ArrayDeque<>();
        Group root = Group.root(ctx, ctx.rootAddress());
        stack.push(hardLinks(root));
        paths.push("/");
        while (!stack.isEmpty()) {
            Iterator<Link.Hard> links = stack.peek();
            if (!links.hasNext()) {
                stack.pop();
                paths.pop();
                continue;
            }
            Link.Hard link = links.next();
            String path = Hdf5Object.childPath(paths.peek(), link.name());
            if (link.objectHeaderAddress() == address) {
                return path;
            }
            if (!visited.add(link.objectHeaderAddress())) {
                continue;
            }
            try {
                if (Hdf5Object.classify(ctx, link.name(), paths.peek(), link.objectHeaderAddress()) instanceof Group group) {
                    stack.push(hardLinks(group));
                    paths.push(path);
                }
            } catch (HdfFormatException | HdfUnsupportedException e) {
                // an object or group that cannot be read is skipped, as are the objects only it reaches
            }
        }
        return "";
    }

    /** The group's hard links in their native order; none if the group cannot be read. */
    private static Iterator<Link.Hard> hardLinks(Group group) {
        List<Link> links;
        try {
            links = group.links();
        } catch (HdfFormatException | HdfUnsupportedException e) {
            return Collections.emptyIterator();
        }
        return links.stream().filter(Link.Hard.class::isInstance).map(Link.Hard.class::cast).iterator();
    }

    @Override
    public void close() {
        found.clear();
    }
}
