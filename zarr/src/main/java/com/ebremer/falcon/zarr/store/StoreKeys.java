package com.ebremer.falcon.zarr.store;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Key validation and listing helpers shared by {@link Store} implementations, so every store agrees on
 * what a key is and how {@link Store#listPrefix} / {@link Store#listDir} behave.
 */
public final class StoreKeys {

    private StoreKeys() {
    }

    /**
     * Checks that {@code key} is a legal store key.
     *
     * @param key the key to check
     * @throws IllegalArgumentException if the key is null, empty, begins or ends with {@code '/'}, or has
     *                                  an empty, {@code "."}, or {@code ".."} segment
     */
    public static void validate(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("store key must be non-empty");
        }
        if (key.charAt(0) == '/' || key.charAt(key.length() - 1) == '/') {
            throw new IllegalArgumentException("store key must not begin or end with '/': " + key);
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("store key must not contain an empty segment: " + key);
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "store key must not contain a '.' or '..' segment: " + key);
            }
        }
    }

    /** Treats {@code prefix} as a directory boundary: empty stays empty, otherwise ensure a trailing '/'. */
    static String asDirPrefix(String prefix) {
        if (prefix.isEmpty() || prefix.endsWith("/")) {
            return prefix;
        }
        return prefix + "/";
    }

    /**
     * All of {@code keys} beginning with the raw string {@code prefix}, sorted and de-duplicated, as
     * {@link Store#listPrefix} gives them.
     *
     * @param keys   the store's keys
     * @param prefix the start every listed key has
     * @return the matching keys
     */
    public static List<String> listPrefix(Collection<String> keys, String prefix) {
        return keys.stream().filter(k -> k.startsWith(prefix)).distinct().sorted().toList();
    }

    /**
     * The immediate children of {@code prefix}: leaf keys directly under it (returned as full keys) and
     * child directories (returned as the full prefix with a trailing {@code '/'}). Sorted, de-duplicated, as
     * {@link Store#listDir} gives them.
     *
     * @param keys   the store's keys
     * @param prefix the directory to list; a trailing {@code '/'} is added if missing, and empty lists the
     *               top level
     * @return the keys and child prefixes
     */
    public static List<String> listDir(Collection<String> keys, String prefix) {
        String dir = asDirPrefix(prefix);
        TreeSet<String> out = new TreeSet<>();
        for (String key : keys) {
            if (!key.startsWith(dir)) {
                continue;
            }
            String rest = key.substring(dir.length());
            if (rest.isEmpty()) {
                continue;
            }
            int slash = rest.indexOf('/');
            if (slash < 0) {
                out.add(key);                                   // leaf child
            } else {
                out.add(dir + rest.substring(0, slash) + "/");  // child directory
            }
        }
        return List.copyOf(out);
    }
}
