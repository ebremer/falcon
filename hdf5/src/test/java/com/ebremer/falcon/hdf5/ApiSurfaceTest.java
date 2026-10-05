package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The public API (P2 A8): no internal type in a public signature, and objects reached through
 * references named by the paths libhdf5 gives them.
 */
class ApiSurfaceTest {

    /** The packages module-info exports. */
    private static final Set<String> EXPORTED = Set.of("com.ebremer.falcon.hdf5", "com.ebremer.falcon.hdf5.datatype");

    @Test
    void exportedPackagesAreTheOnesChecked() {
        Module module = Hdf5File.class.getModule();
        if (module.isNamed()) {
            assertEquals(EXPORTED, module.getDescriptor().exports().stream()
                    .map(java.lang.module.ModuleDescriptor.Exports::source).collect(java.util.stream.Collectors.toSet()));
        }
    }

    /** Every public or protected member of every public class names only exported or JDK types. */
    @Test
    void publicSignaturesUseOnlyExportedTypes() throws Exception {
        List<String> leaks = new ArrayList<>();
        int checked = 0;
        for (Class<?> type : apiClasses()) {
            checked++;
            check(type, type.getGenericSuperclass(), "superclass", leaks);
            for (Type implemented : type.getGenericInterfaces()) {
                check(type, implemented, "interface", leaks);
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (visible(constructor)) {
                    checkExecutable(type, constructor, leaks);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (visible(method) && !method.isSynthetic()) {
                    checkExecutable(type, method, leaks);
                    check(type, method.getGenericReturnType(), method.getName() + " returns", leaks);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (visible(field)) {
                    check(type, field.getGenericType(), "field " + field.getName(), leaks);
                }
            }
        }
        assertTrue(checked > 30, "found only " + checked + " API classes");
        assertTrue(leaks.isEmpty(), "internal types in the public API:\n  " + String.join("\n  ", leaks));
    }

    @Test
    void attributesHaveNoPublicConstructor() {
        assertEquals(0, Attribute.class.getConstructors().length);
    }

    private static boolean visible(Member member) {
        return Modifier.isPublic(member.getModifiers())
                || (Modifier.isProtected(member.getModifiers()) && !Modifier.isFinal(member.getDeclaringClass().getModifiers()));
    }

    private static void checkExecutable(Class<?> owner, Executable executable, List<String> leaks) {
        String what = executable.getName() + Arrays.toString(executable.getParameterTypes());
        for (Type parameter : executable.getGenericParameterTypes()) {
            check(owner, parameter, what + " takes", leaks);
        }
        for (Type thrown : executable.getGenericExceptionTypes()) {
            check(owner, thrown, what + " throws", leaks);
        }
    }

    private static void check(Class<?> owner, Type type, String where, List<String> leaks) {
        switch (type) {
            case null -> { }
            case Class<?> c when c.isArray() -> check(owner, c.getComponentType(), where, leaks);
            case Class<?> c -> {
                String pkg = c.getPackageName();
                boolean ok = c.isPrimitive() || pkg.startsWith("java.") || (EXPORTED.contains(pkg) && isPublic(c));
                if (!ok) {
                    leaks.add(owner.getName() + ": " + where + " " + c.getName());
                }
            }
            case ParameterizedType p -> {
                check(owner, p.getRawType(), where, leaks);
                for (Type argument : p.getActualTypeArguments()) {
                    check(owner, argument, where, leaks);
                }
            }
            case WildcardType w -> {
                Stream.of(w.getUpperBounds()).forEach(b -> check(owner, b, where, leaks));
                Stream.of(w.getLowerBounds()).forEach(b -> check(owner, b, where, leaks));
            }
            case GenericArrayType g -> check(owner, g.getGenericComponentType(), where, leaks);
            case TypeVariable<?> v -> { } // its bounds are checked where it is declared
            default -> leaks.add(owner.getName() + ": " + where + " unknown type " + type);
        }
    }

    /** True if {@code c} and every class enclosing it are public. */
    private static boolean isPublic(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getEnclosingClass()) {
            if (!Modifier.isPublic(k.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /** The public classes (nested ones too) of the exported packages, from the compiled classes. */
    private static List<Class<?>> apiClasses() throws URISyntaxException, IOException {
        Path classes = Path.of(Hdf5File.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> out = new ArrayList<>();
        for (String pkg : EXPORTED) {
            Path dir = classes.resolve(pkg.replace('.', '/'));
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = pkg + "." + file.getFileName().toString().replace(".class", "");
                    Class<?> c;
                    try {
                        c = Class.forName(name, false, Hdf5File.class.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        throw new UncheckedIOException(new IOException(e));
                    }
                    if (isPublic(c) && !c.isAnonymousClass() && !c.isLocalClass()) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ paths of referenced objects

    /** Each reference's target is named by the path h5py (libhdf5's H5Iget_name) gives it. */
    @ParameterizedTest
    @ValueSource(strings = {"paths.h5", "paths_latest.h5"})
    void referencedObjectsAreNamedAsLibhdf5NamesThem(String fixture) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            Dataset refs = h5.root().dataset("refs");
            String[] expected = refs.attribute("names").orElseThrow().readStrings();
            Hdf5Object[] objects = refs.readObjectReferences();
            for (int i = 0; i < objects.length; i++) {
                Hdf5Object object = objects[i];
                assertTrue(object.toString().contains("@" + object.objectHeaderAddress()), object.toString());
                assertEquals(expected[i], object.path(), "reference " + i);
                String name = expected[i].substring(expected[i].lastIndexOf('/') + 1);
                assertEquals(name, object.name(), "reference " + i);
                assertTrue(object.toString().endsWith("[" + expected[i] + "]"), object.toString());
            }
            // A referenced group's children are named under the path found for it.
            Group a = (Group) objects[1];
            assertEquals("/a/b", a.children().getFirst().path());
        }
    }

    @Test
    void referencedObjectsOfOtherFixturesAreNamed() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("references.h5"))) {
            Hdf5Object[] refs = h5.root().dataset("refs").readObjectReferences();
            assertEquals(List.of("/target_a", "/target_g", "/target_g/inner"),
                    Stream.of(refs).map(Hdf5Object::path).toList());
            assertEquals("/target_a", h5.root().dataset("rrefs").readRegionReferences()[0].dataset().path());
        }
    }
}
