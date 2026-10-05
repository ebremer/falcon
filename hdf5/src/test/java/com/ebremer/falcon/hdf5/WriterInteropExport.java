package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Exports the writer's feature matrix for checking by the reference library: writes one file per area
 * into the directory named by {@code -Dfalcon.interop.dir}, plus a {@code manifest.json} of what each
 * object must read back as. {@code tools/fixtures/check_hdf5_writer.py} runs this and reads every file
 * with h5py (HDF5 2.0 and, optionally, 1.14), and decodes szip chunks with libaec &mdash; the oracle the
 * Falcon-only round trips in {@link WriteTest} cannot provide. Skipped unless the property is set.
 *
 * <pre>
 * mvn -pl hdf5 test -Dtest=WriterInteropExport -Dfalcon.interop.dir=/tmp/falcon-interop
 * </pre>
 */
@EnabledIfSystemProperty(named = "falcon.interop.dir", matches = ".+")
class WriterInteropExport {

    private final List<Map<String, Object>> files = new ArrayList<>();
    private List<Map<String, Object>> objects;

    @Test
    void export() throws IOException {
        Path dir = Path.of(System.getProperty("falcon.interop.dir"));
        Files.createDirectories(dir);

        // --- basic objects, atomics, layouts, attributes
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "basic.h5", "latest"))) {
            int[] counts = {10, 20, 30, 40, 50};
            w.intDataset("counts", counts, new long[] {5})
                    .intAttribute("scale", new int[] {100}, new long[] {})
                    .doubleAttribute("range", new double[] {0.5, 9.5}, new long[] {2});
            dataset("/counts", counts).put("attrs", Map.of("scale", new int[] {100}, "range", new double[] {0.5, 9.5}));
            w.intDataset("grid", range(6), new long[] {2, 3});
            dataset("/grid", range(6)).put("shape", new long[] {2, 3});
            Hdf5Writer.GroupWriter run = w.group("run");
            run.doubleDataset("signal", new double[] {0.5, 1.5, 2.5}, new long[] {3});
            dataset("/run/signal", new double[] {0.5, 1.5, 2.5});
            run.stringDataset("labels", new String[] {"alpha", "beta", "été"}, new long[] {3});
            dataset("/run/labels", new String[] {"alpha", "beta", "été"});
            run.group("nested").intAttribute("depth", new int[] {2}, new long[] {});
            group("/run/nested").put("attrs", Map.of("depth", new int[] {2}));
            w.byteDataset("i8", new byte[] {-128, 0, 127}, new long[] {3});
            dataset("/i8", new int[] {-128, 0, 127});
            w.shortDataset("i16", new short[] {-32768, 1, 32767}, new long[] {3});
            dataset("/i16", new int[] {-32768, 1, 32767});
            w.longDataset("i64", new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE}, new long[] {3});
            dataset("/i64", new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE});
            w.floatDataset("f32", new float[] {0.25f, -1.5f, 1048576.5f}, new long[] {3});
            dataset("/f32", new double[] {0.25, -1.5, 1048576.5});
            w.fixedStringDataset("fixed", new String[] {"ab", "cde", ""}, new long[] {3});
            dataset("/fixed", new String[] {"ab", "cde", ""});
            w.intDataset("compact", new int[] {7, 8, 9}, new long[] {3}).compact();
            dataset("/compact", new int[] {7, 8, 9});
            w.intChunkedDataset("filled", range(10), new long[] {10}, new long[] {4}).fillValue(-1);
            dataset("/filled", range(10));
            w.doubleDataset("scalar", new double[] {6.25}, new long[] {});
            dataset("/scalar", new double[] {6.25}).put("shape", new long[] {});
        }

        // --- chunked storage and every filter (including > 1024 chunks: a paged fixed array)
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "chunked.h5", "latest"))) {
            int[] edge = range(1000);
            w.intChunkedDataset("plain", edge, new long[] {1000}, new long[] {64});
            dataset("/plain", edge);
            w.intChunkedDataset("grid2d", range(35), new long[] {5, 7}, new long[] {2, 3});
            dataset("/grid2d", range(35)).put("shape", new long[] {5, 7});
            w.intChunkedDataset("deflate", edge, new long[] {1000}, new long[] {64}).deflate(6);
            dataset("/deflate", edge);
            w.intChunkedDataset("shuffle_deflate_fletcher", edge, new long[] {1000}, new long[] {64})
                    .shuffle().deflate(4).fletcher32();
            dataset("/shuffle_deflate_fletcher", edge);
            w.doubleChunkedDataset("f64_deflate", doubles(500), new long[] {500}, new long[] {100}).deflate(1);
            dataset("/f64_deflate", doubles(500));
            int[] mixed = new int[64];
            for (int i = 0; i < 64; i++) {
                mixed[i] = i < 16 ? 7 : i < 32 ? 0 : i < 40 ? -2000000000 + i : (i * 37) % 1000;
            }
            w.intChunkedDataset("scaleoffset", mixed, new long[] {64}, new long[] {16}).scaleOffset();
            dataset("/scaleoffset", mixed);
            w.intChunkedDataset("scaleoffset_fill", new int[] {-1, 5, -1, 9, 100, -1, 3, 3}, new long[] {8},
                    new long[] {4}).fillValue(-1).scaleOffset();
            dataset("/scaleoffset_fill", new int[] {-1, 5, -1, 9, 100, -1, 3, 3});
            w.intChunkedDataset("scaleoffset_deflate", edge, new long[] {1000}, new long[] {128}).scaleOffset().deflate(6);
            dataset("/scaleoffset_deflate", edge);
            w.intChunkedDataset("nbit", range(100), new long[] {100}, new long[] {32}).nbit(12);
            dataset("/nbit", range(100));
            int[] many = range(100_000);
            w.intChunkedDataset("paged", many, new long[] {100_000}, new long[] {64});
            dataset("/paged", many);
            w.intChunkedDataset("paged_deflate", many, new long[] {100_000}, new long[] {64}).deflate(1);
            dataset("/paged_deflate", many);
        }

        // --- szip (h5py ships it disabled: the checker decodes these chunks with libaec)
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "szip.h5", "latest"))) {
            int[] ints = new int[300];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = (i * 7) % 113 - 50;
            }
            w.intChunkedDataset("i32", ints, new long[] {300}, new long[] {64}).szip();
            dataset("/i32", ints).put("szip", true);
            w.intChunkedDataset("i32_shuffle", ints, new long[] {300}, new long[] {64}).shuffle().szip();
            dataset("/i32_shuffle", ints).put("szip", true);
            w.doubleChunkedDataset("f64", doubles(256), new long[] {256}, new long[] {128}).szip();
            dataset("/f64", doubles(256)).put("szip", true);
            int[] noise = new int[64];
            java.util.Random random = new java.util.Random(5);
            for (int i = 0; i < noise.length; i++) {
                noise[i] = random.nextInt();
            }
            w.intChunkedDataset("incompressible", noise, new long[] {64}, new long[] {32}).szip();
            dataset("/incompressible", noise).put("szip", true);
        }

        // --- dense storage: many links and many attributes
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "dense.h5", "latest"))) {
            for (int n : new int[] {50, 2000}) {
                Hdf5Writer.GroupWriter g = w.group("links" + n);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String name = String.format("item%04d", i);
                    g.intDataset(name, new int[] {i}, new long[] {1});
                    names.add(name);
                }
                group("/links" + n).put("children", names);
            }
            for (int n : new int[] {40, 300}) {
                Hdf5Writer.DatasetWriter d = w.intDataset("attrs" + n, new int[] {n}, new long[] {1});
                Map<String, Object> attrs = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    String name = String.format("attr%03d", i);
                    d.intAttribute(name, new int[] {i}, new long[] {});
                    attrs.put(name, new int[] {i});
                }
                dataset("/attrs" + n, new int[] {n}).put("attrs", attrs);
            }
        }

        // --- compound, enum, array, vlen, references (HDF5 1.8+ datatype encodings) and complex (2.0)
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "types.h5", "latest"))) {
            writeTypes(w);
            w.complexDataset("complex", new long[] {3}, new double[] {1, 3, -5}, new double[] {2, -4, 0});
            dataset("/complex", new double[] {1, 2, 3, -4, -5, 0}).put("complex", true);
            objects.getLast().put("min_hdf5", "2.0");
        }

        // --- the earliest on-disk format
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "earliest.h5", "earliest"), Hdf5Writer.Format.EARLIEST)) {
            Hdf5Writer.GroupWriter g = w.group("g");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String name = String.format("item%02d", i);
                g.intDataset(name, new int[] {i}, new long[] {1});
                names.add(name);
            }
            group("/g").put("children", names);
            w.stringDataset("strings", new String[] {"x", "yy"}, new long[] {2});
            dataset("/strings", new String[] {"x", "yy"});
            w.doubleDataset("values", new double[] {1.25, 2.5}, new long[] {2})
                    .intAttribute("unit", new int[] {3}, new long[] {});
            dataset("/values", new double[] {1.25, 2.5}).put("attrs", Map.of("unit", new int[] {3}));
            writeTypes(w);
        }

        // --- edge cases libhdf5 once rejected or misread (both formats)
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String name = format == Hdf5Writer.Format.LATEST ? "edges.h5" : "edges_earliest.h5";
            try (Hdf5Writer w = Hdf5Writer.create(begin(dir, name, format.name().toLowerCase()), format)) {
                writeEdges(w, format);
            }
        }

        Files.writeString(dir.resolve("manifest.json"), json(Map.of("files", files)), StandardCharsets.UTF_8);
    }

    private void writeEdges(Hdf5Writer w, Hdf5Writer.Format format) {
        // Empty datasets: no storage is allocated.
        w.intDataset("empty", new int[0], new long[] {0});
        dataset("/empty", new int[0]).put("shape", new long[] {0});
        w.intDataset("empty_rows", new int[0], new long[] {3, 0});
        dataset("/empty_rows", new int[0]).put("shape", new long[] {3, 0});
        w.stringDataset("empty_strings", new String[0], new long[] {0});
        dataset("/empty_strings", new String[0]).put("shape", new long[] {0});
        w.intDataset("empty_compact", new int[0], new long[] {0}).compact();
        dataset("/empty_compact", new int[0]).put("shape", new long[] {0});
        // Variable-length data beyond one heap collection (65,535 objects), and one object over 1 MiB.
        String[] many = new String[70_000];
        for (int i = 0; i < many.length; i++) {
            many[i] = "s" + i;
        }
        w.stringDataset("many_strings", many, new long[] {many.length});
        dataset("/many_strings", many);
        String big = "x".repeat(1_500_000);
        w.stringDataset("big_string", new String[] {"a", big, ""}, new long[] {3});
        dataset("/big_string", new String[] {"a", big, ""});
        // Messages near the 64 KiB limit.
        int[] compactMax = new int[16381]; // 65524 bytes
        for (int i = 0; i < compactMax.length; i++) {
            compactMax[i] = i * 3;
        }
        w.intDataset("compact_max", compactMax, new long[] {compactMax.length}).compact();
        dataset("/compact_max", compactMax);
        double[] wide = new double[8000];
        for (int i = 0; i < wide.length; i++) {
            wide[i] = i * 0.25;
        }
        w.intDataset("big_attr", new int[] {1}, new long[] {1}).doubleAttribute("wide", wide, new long[] {wide.length});
        dataset("/big_attr", new int[] {1}).put("attrs", Map.of("wide", wide));
        // Long and non-ASCII names.
        String longName = "n".repeat(300);
        w.intDataset(longName, new int[] {4}, new long[] {1});
        dataset("/" + longName, new int[] {4});
        w.group("café").intAttribute("été", new int[] {5}, new long[] {});
        group("/café").put("attrs", Map.of("été", new int[] {5}));
        // Fill values converted to the dataset's type.
        w.doubleDataset("fill_f64", new double[] {1}, new long[] {1}).fillValue(5);
        dataset("/fill_f64", new double[] {1}).put("fill", 5.0);
        w.floatDataset("fill_f32", new float[] {1}, new long[] {1}).fillValue(2.5);
        dataset("/fill_f32", new double[] {1}).put("fill", 2.5);
        w.intDataset("fill_i32", new int[] {1}, new long[] {1}).fillValue(-7.0);
        dataset("/fill_i32", new int[] {1}).put("fill", -7);
        // Non-ASCII fixed-length strings and datatype member names.
        w.fixedStringDataset("fixed_utf8", new String[] {"café", "日本", ""}, new long[] {3});
        dataset("/fixed_utf8", new String[] {"café", "日本", ""});
        w.compoundDataset("rec_utf8", new long[] {2}, Hdf5Writer.CompoundField.int32("é", new int[] {1, 2}));
        dataset("/rec_utf8", null).put("fields", Map.of("é", new int[] {1, 2}));
        w.enumDataset("enum_utf8", new long[] {2}, Hdf5Writer.enumType().add("ÉTÉ", 1).add("HIVER", 2), new int[] {2, 1});
        dataset("/enum_utf8", new int[] {2, 1}).put("enum", Map.of("ÉTÉ", 1, "HIVER", 2));
        if (format == Hdf5Writer.Format.LATEST) {
            // Chunked storage (not written in the earliest format): empty, and n-bit at its limit.
            w.intChunkedDataset("empty_chunked", new int[0], new long[] {0}, new long[] {4});
            dataset("/empty_chunked", new int[0]).put("shape", new long[] {0});
            w.intChunkedDataset("nbit_max", new int[] {0, 255, 128, 1}, new long[] {4}, new long[] {4}).nbit(8).fillValue(255);
            dataset("/nbit_max", new int[] {0, 255, 128, 1}).put("fill", 255);
            // A dense attribute larger than libhdf5's default 4 KiB managed-object size.
            Hdf5Writer.DatasetWriter dense = w.intDataset("dense_wide", new int[] {1}, new long[] {1});
            Map<String, Object> attrs = new LinkedHashMap<>();
            for (int i = 0; i < 8; i++) {
                dense.intAttribute("small" + i, new int[] {i}, new long[] {});
                attrs.put("small" + i, new int[] {i});
            }
            double[] mid = java.util.Arrays.copyOf(wide, 1000);
            dense.doubleAttribute("wide", mid, new long[] {mid.length});
            attrs.put("wide", mid);
            dataset("/dense_wide", new int[] {1}).put("attrs", attrs);
        }
    }

    private void writeTypes(Hdf5Writer w) {
        w.compoundDataset("compound", new long[] {3},
                Hdf5Writer.CompoundField.int32("a", new int[] {1, 2, 3}),
                Hdf5Writer.CompoundField.float64("b", new double[] {1.5, 2.5, 3.5}));
        dataset("/compound", null).put("fields", Map.of("a", new int[] {1, 2, 3}, "b", new double[] {1.5, 2.5, 3.5}));
        w.enumDataset("enum", new long[] {4}, Hdf5Writer.enumType().add("RED", 0).add("GREEN", 1).add("BLUE", 2),
                new int[] {2, 0, 1, 1});
        dataset("/enum", new int[] {2, 0, 1, 1});
        w.int32ArrayDataset("array", new long[] {2}, new int[] {2, 3}, range(12));
        dataset("/array", range(12));
        w.intSequenceDataset("vlen", new long[] {3}, new int[][] {{1}, {}, {2, 3, 4}});
        dataset("/vlen", null).put("rows", List.of(new int[] {1}, new int[0], new int[] {2, 3, 4}));
        w.referenceDataset("refs", new long[] {3}, new String[] {"/enum", "/array", null});
        dataset("/refs", null).put("refs", java.util.Arrays.asList("/enum", "/array", null));
    }

    // ------------------------------------------------------------------ manifest building

    private Path begin(Path dir, String name, String format) {
        objects = new ArrayList<>();
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file", name);
        file.put("format", format);
        file.put("objects", objects);
        files.add(file);
        return dir.resolve(name);
    }

    private Map<String, Object> dataset(String path, Object values) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("path", path);
        o.put("kind", "dataset");
        if (values != null) {
            o.put("values", values);
        }
        objects.add(o);
        return o;
    }

    private Map<String, Object> group(String path) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("path", path);
        o.put("kind", "group");
        objects.add(o);
        return o;
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    private static double[] doubles(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = Math.sin(i / 10.0) * 100;
        }
        return a;
    }

    /** A minimal JSON writer for the manifest (maps, lists, strings, numbers, primitive arrays, null). */
    private static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> {
                sb.append('"');
                for (char c : s.toCharArray()) {
                    if (c == '"' || c == '\\') {
                        sb.append('\\').append(c);
                    } else if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
                sb.append('"');
            }
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            case int[] a -> writeAll(sb, java.util.Arrays.stream(a).boxed().toList());
            case long[] a -> writeAll(sb, java.util.Arrays.stream(a).boxed().toList());
            case double[] a -> writeAll(sb, java.util.Arrays.stream(a).boxed().toList());
            case String[] a -> writeAll(sb, java.util.Arrays.asList(a));
            case List<?> list -> writeAll(sb, list);
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, e.getKey().toString());
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            default -> throw new IllegalArgumentException("cannot write " + value.getClass());
        }
    }

    private static void writeAll(StringBuilder sb, List<?> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(sb, values.get(i));
        }
        sb.append(']');
    }
}
