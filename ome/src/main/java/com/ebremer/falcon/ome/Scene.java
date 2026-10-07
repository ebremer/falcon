package com.ebremer.falcon.ome;

import com.ebremer.falcon.ome.metadata.CoordinateSystem;
import com.ebremer.falcon.ome.metadata.CoordinateSystemRef;
import com.ebremer.falcon.ome.metadata.Multiscale;
import com.ebremer.falcon.ome.metadata.SceneMetadata;
import com.ebremer.falcon.ome.metadata.Transformation;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * (0.6) A scene: images below a group placed relative to each other by transformations between their
 * coordinate systems and the scene's own.
 *
 * <p>The transformations make a graph whose nodes are coordinate systems; {@link #transformation} finds a
 * path through it between any two, going along a transformation or against one that has an inverse, and
 * composes the transformations on the way. The images' own transformations, between their intrinsic
 * coordinate system and their others, are edges too.
 *
 * <pre>{@code
 * Scene scene = OmeZarr.open(store).asScene();
 * Transformation tileToWorld = scene.transformation(new CoordinateSystemRef("physical", "tile_1"),
 *         CoordinateSystemRef.named("world")).orElseThrow();
 * double[] world = tileToWorld.apply(new double[] {10, 20});
 * }</pre>
 */
public final class Scene {

    private final ZarrGroup group;
    private final SceneMetadata metadata;
    private final Map<String, MultiscaleImage> images = new java.util.concurrent.ConcurrentHashMap<>();

    private Scene(ZarrGroup group, SceneMetadata metadata) {
        this.group = group;
        this.metadata = new SceneMetadata(metadata.coordinateSystems(),
                Transformations.resolve(metadata.transformations(), group));
    }

    /**
     * Opens the scene a group holds.
     *
     * @param group the scene's group
     * @return the scene
     * @throws OmeFormatException if the group is not a scene
     */
    public static Scene open(ZarrGroup group) {
        OmeMetadata m = OmeMetadata.read(group.attributes()).filter(OmeMetadata::isScene).orElseThrow(() ->
                new OmeFormatException(MultiscaleImage.display(group) + " is not a scene"));
        return new Scene(group, m.scene());
    }

    /** {@return the scene's group} */
    public ZarrGroup group() {
        return group;
    }

    /** {@return the scene's metadata, with transformation parameters loaded} */
    public SceneMetadata metadata() {
        return metadata;
    }

    /** {@return the scene's own coordinate systems; the first, if any, is the one to show by default} */
    public List<CoordinateSystem> coordinateSystems() {
        return metadata.coordinateSystems();
    }

    /** {@return the paths of the images the scene's transformations refer to, in the order they first do} */
    public List<String> imagePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (Transformation t : metadata.transformations()) {
            for (CoordinateSystemRef ref : new CoordinateSystemRef[] {t.input(), t.output()}) {
                if (ref != null && ref.path() != null) {
                    paths.add(ref.path());
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Opens one of the scene's images.
     *
     * @param path the image's path in the scene's group
     * @return the image
     * @throws NoSuchElementException if it is not stored
     * @throws OmeFormatException     if it is not a multiscale image
     */
    public MultiscaleImage image(String path) {
        return images.computeIfAbsent(path, p -> {
            ZarrNode node = group.child(MultiscaleImage.trim(p)).orElseThrow(() ->
                    new NoSuchElementException("image '" + p + "' is not stored"));
            if (!(node instanceof ZarrGroup g)) {
                throw new OmeFormatException("'" + p + "' is an array, not an image");
            }
            return MultiscaleImage.open(g);
        });
    }

    /**
     * The transformation from one coordinate system to another, composed along the shortest path of
     * transformations between them. A reference's {@code path} names an image in the scene (null for the
     * scene's own systems); a reference to an image with no {@code name} is the image's intrinsic system.
     *
     * @param from the coordinate system to map from
     * @param to   the coordinate system to map to
     * @return the transformation, or empty if no path of invertible-enough transformations joins them
     */
    public Optional<Transformation> transformation(CoordinateSystemRef from, CoordinateSystemRef to) {
        String start = key(from);
        String goal = key(to);
        Map<String, List<Edge>> graph = graph();
        if (start.equals(goal)) {
            return Optional.of(new Transformation.Identity(null, from, to));
        }
        Map<String, Edge> via = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(start));
        Set<String> seen = new LinkedHashSet<>(List.of(start));
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (node.equals(goal)) {
                List<Transformation> chain = new ArrayList<>();
                for (String n = goal; !n.equals(start); n = via.get(n).from()) {
                    chain.addFirst(via.get(n).transformation());
                }
                return Optional.of(chain.size() == 1 ? chain.getFirst().with(chain.getFirst().name(), from, to)
                        : new Transformation.Sequence(chain, null, from, to));
            }
            for (Edge e : graph.getOrDefault(node, List.of())) {
                if (seen.add(e.to())) {
                    via.put(e.to(), e);
                    queue.addLast(e.to());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The transformation from a level of one of the scene's images to a coordinate system: the level's own
     * transformation to the image's intrinsic system, then the path from there.
     *
     * @param imagePath the image's path in the scene's group
     * @param level     the level, 0 for the largest
     * @param to        the coordinate system to map to
     * @return the transformation, or empty if none joins them
     */
    public Optional<Transformation> transformation(String imagePath, int level, CoordinateSystemRef to) {
        MultiscaleImage image = image(imagePath);
        Transformation toIntrinsic = image.levelTransformation(level);
        return transformation(new CoordinateSystemRef(null, imagePath), to).map(rest ->
                new Transformation.Sequence(List.of(toIntrinsic, rest), null,
                        CoordinateSystemRef.array(imagePath + "/" + image.multiscale().datasets().get(level).path()), to));
    }

    private record Edge(String from, String to, Transformation transformation) {
    }

    private Map<String, List<Edge>> graph() {
        Map<String, List<Edge>> graph = new HashMap<>();
        for (Transformation t : metadata.transformations()) {
            if (t.input() != null && t.output() != null) {
                add(graph, key(t.input()), key(t.output()), t);
            }
        }
        for (String path : imagePaths()) {
            MultiscaleImage image;
            try {
                image = image(path);
            } catch (RuntimeException e) {
                continue; // an image that cannot be opened joins nothing
            }
            Multiscale m = image.multiscale();
            String intrinsic = m.intrinsicCoordinateSystem().map(CoordinateSystem::name).orElse(null);
            if (intrinsic != null) {
                // the image's own reference, a path with no name, is its intrinsic system
                add(graph, path + "#", path + "#" + intrinsic, Transformation.Identity.of());
            }
            for (Transformation t : m.transformations()) {
                if (t.input() != null && t.output() != null) {
                    add(graph, key(prefixed(path, t.input())), key(prefixed(path, t.output())), t);
                }
            }
        }
        return graph;
    }

    private static void add(Map<String, List<Edge>> graph, String from, String to, Transformation t) {
        graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(from, to, t));
        t.inverse().ifPresent(inverse -> graph.computeIfAbsent(to, k -> new ArrayList<>()).add(new Edge(to, from,
                inverse)));
    }

    private static CoordinateSystemRef prefixed(String imagePath, CoordinateSystemRef ref) {
        String path = ref.path() == null ? imagePath : imagePath + "/" + ref.path();
        return new CoordinateSystemRef(ref.name(), path);
    }

    private static String key(CoordinateSystemRef ref) {
        String path = ref.path() == null ? "" : MultiscaleImage.trim(ref.path());
        return path + "#" + (ref.name() == null ? "" : ref.name());
    }

    @Override
    public String toString() {
        return "Scene[" + (group.path().isEmpty() ? "/" : group.path()) + ", " + imagePaths().size() + " images]";
    }
}
