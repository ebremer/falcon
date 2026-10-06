package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.datatype.Datatype;
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

        // --- P2 WF1: data streamed as it is written; datasets that grow (version-1 B-tree index)
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String name = format == Hdf5Writer.Format.LATEST ? "streaming.h5" : "streaming_earliest.h5";
            try (Hdf5Writer w = Hdf5Writer.create(begin(dir, name, format.name().toLowerCase()), format)) {
                writeStreaming(w);
            }
        }

        // --- P2 WF2/WF3: every datatype through createDataset, and typed attributes
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "datatypes.h5", "latest"))) {
            writeDatatypes(w, false);
        }
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "datatypes_earliest.h5", "earliest"), Hdf5Writer.Format.EARLIEST)) {
            writeDatatypes(w, true);
        }

        // --- P2 WF4: soft and external links (compact and dense), region references
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "links.h5", "latest"))) {
            w.intDataset("data", range(12), new long[] {3, 4});
            dataset("/data", range(12));
            w.group("g").intDataset("x", new int[] {7, 8}, new long[] {2});
            w.softLink("abs", "/g/x").softLink("rel", "g/x").softLink("dangling", "/nowhere")
                    .externalLink("ext", "basic.h5", "/counts");
            Hdf5Writer.GroupWriter many = w.group("many");
            for (int i = 0; i < 6; i++) {
                many.intDataset("d" + i, new int[] {i}, new long[] {1});
            }
            many.softLink("s", "/data").softLink("t", "/g").externalLink("e", "basic.h5", "/grid").softLink("u", "d3");
            group("/").put("links", Map.of("abs", Map.of("soft", "/g/x"), "rel", Map.of("soft", "g/x"),
                    "dangling", Map.of("soft", "/nowhere"), "ext", Map.of("external", List.of("basic.h5", "/counts"))));
            group("/").put("follow", Map.of("abs", new int[] {7, 8}, "rel", new int[] {7, 8},
                    "ext", new int[] {10, 20, 30, 40, 50}));
            group("/many").put("links", Map.of("s", Map.of("soft", "/data"), "t", Map.of("soft", "/g"),
                    "e", Map.of("external", List.of("basic.h5", "/grid")), "u", Map.of("soft", "d3")));
            group("/many").put("follow", Map.of("s", range(12), "e", range(6), "u", new int[] {3}));
            w.regionReferenceDataset("regions", new long[] {5}, new Hdf5Writer.Region[] {
                Hdf5Writer.Region.block("/data", new long[] {1, 1}, new long[] {2, 2}),
                Hdf5Writer.Region.points("/data", new long[][] {{2, 3}, {0, 0}, {1, 2}}),
                Hdf5Writer.Region.hyperslab("/data", new long[] {0, 0}, new long[] {2, 2}, new long[] {2, 2}, null),
                Hdf5Writer.Region.all("/g/x"),
                null});
            dataset("/regions", null).put("regions", java.util.Arrays.asList(
                    Map.of("target", "/data", "values", new int[] {5, 6, 9, 10}),
                    Map.of("target", "/data", "values", new int[] {11, 0, 6}),
                    Map.of("target", "/data", "values", new int[] {0, 2, 8, 10}),
                    Map.of("target", "/g/x", "values", new int[] {7, 8}),
                    null));
        }
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "links_earliest.h5", "earliest"), Hdf5Writer.Format.EARLIEST)) {
            w.group("g").intDataset("x", new int[] {7, 8}, new long[] {2});
            w.softLink("abs", "/g/x").softLink("rel", "g/x").softLink("dangling", "/nowhere");
            group("/").put("links", Map.of("abs", Map.of("soft", "/g/x"), "rel", Map.of("soft", "g/x"),
                    "dangling", Map.of("soft", "/nowhere")));
            group("/").put("follow", Map.of("abs", new int[] {7, 8}, "rel", new int[] {7, 8}));
        }

        // --- P2 WF8: references in chunks, compact data and attributes
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String name = format == Hdf5Writer.Format.LATEST ? "references.h5" : "references_earliest.h5";
            try (Hdf5Writer w = Hdf5Writer.create(begin(dir, name, format.name().toLowerCase()), format)) {
                writeReferences(w);
            }
        }

        // --- P2 WF5: dense storage of any size; old-style groups of several B-tree levels
        writeLargeGroups(dir);

        // --- P2 WF6: files changed in place, written by Falcon and by libhdf5
        writeEdits(dir);

        // --- P2 WF7, WF10: szip's nearest-neighbour coding, user blocks, links, and more files changed
        writeWf7(dir);
        writeLinks(dir);
        writeWf10Edits(dir);

        Files.writeString(dir.resolve("manifest.json"), json(Map.of("files", files)), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ P2 WF8, WF5, WF6

    /** References in chunked, filtered, compound and compact datasets, and in attributes (P2 WF8). */
    private void writeReferences(Hdf5Writer w) {
        Map<String, Datatype> members = new LinkedHashMap<>();
        members.put("id", Datatype.int32());
        members.put("target", Datatype.objectReference());
        w.createDataset("refs", Datatype.objectReference(), 0).chunked(2).maxShape(Hdf5Writer.UNLIMITED).deflate(4)
                .append(new String[] {"/a", "/g", null, "/g/b", "/"});
        dataset("/refs", null).put("refs", java.util.Arrays.asList("/a", "/g", null, "/g/b", "/"));
        w.createDataset("records", Datatype.compound(members), 3).chunked(2).shuffle().write(Map.of(
                "id", new int[] {1, 2, 3}, "target", new String[] {"/g/b", null, "/a"}));
        dataset("/records", null).put("field_refs", Map.of("target", java.util.Arrays.asList("/g/b", null, "/a")));
        w.createDataset("regions", Datatype.regionReference(), 2).chunked(1).deflate(1).write(new Hdf5Writer.Region[] {
            Hdf5Writer.Region.block("/a", new long[] {1}, new long[] {2}), Hdf5Writer.Region.all("/g/b")});
        dataset("/regions", null).put("regions", List.of(Map.of("target", "/a", "values", new int[] {11, 12}),
                Map.of("target", "/g/b", "values", new int[] {7, 8})));
        w.createDataset("compact", Datatype.objectReference(), 2).compact().write(new String[] {"/a", "/g/b"});
        dataset("/compact", null).put("refs", List.of("/a", "/g/b"));
        w.root().attribute("self", Datatype.objectReference(), new long[0], new String[] {"/"})
                .attribute("targets", Datatype.objectReference(), new long[] {2}, new String[] {"/a", null})
                .attribute("region", Datatype.regionReference(), new long[] {1},
                        new Hdf5Writer.Region[] {Hdf5Writer.Region.points("/a", new long[][] {{3}})});
        group("/").put("attr_refs", Map.of("self", List.of("/"), "targets", java.util.Arrays.asList("/a", null)));
        group("/").put("attr_regions", Map.of("region", List.of(Map.of("target", "/a", "values", new int[] {13}))));
        Hdf5Writer.GroupWriter g = w.group("g");
        Map<String, Object> refs = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) { // dense attribute storage in the modern format
            String target = i % 2 == 0 ? "/a" : "/g";
            g.attribute("r" + i, Datatype.objectReference(), new long[0], new String[] {target});
            refs.put("r" + i, List.of(target));
        }
        group("/g").put("attr_refs", refs);
        g.intDataset("b", new int[] {7, 8}, new long[] {2});
        dataset("/g/b", new int[] {7, 8});
        w.intDataset("a", new int[] {10, 11, 12, 13}, new long[] {4});
        dataset("/a", new int[] {10, 11, 12, 13});
    }

    /** Dense storage beyond one heap block and one B-tree node (P2 WF5); old-style groups of many levels. */
    private void writeLargeGroups(Path dir) throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "dense_big.h5", "latest"))) {
            Hdf5Writer.GroupWriter many = w.group("many");
            for (int i = 0; i < 20; i++) {
                many.intDataset(String.format("d%06d", i), new int[] {i}, new long[] {1});
            }
            for (int i = 20; i < 100_000; i++) {
                many.softLink(String.format("l%06d", i), String.format("d%06d", i % 20));
            }
            group("/many").put("count", 100_000);
            group("/many").put("links", Map.of("l050000", Map.of("soft", "d000000"), "l099999", Map.of("soft", "d000019")));
            group("/many").put("follow", Map.of("l004321", new int[] {1}, "l099999", new int[] {19}));
            Hdf5Writer.DatasetWriter attrs = w.intDataset("attrs", new int[] {0}, new long[] {1});
            for (int i = 0; i < 3000; i++) {
                attrs.intAttribute(String.format("a%04d", i), new int[] {i, -i}, new long[] {2});
            }
            dataset("/attrs", new int[] {0}).put("attr_count", 3000);
            objects.getLast().put("attrs", Map.of("a0000", new int[] {0, 0}, "a1234", new int[] {1234, -1234},
                    "a2999", new int[] {2999, -2999}));
            Hdf5Writer.GroupWriter large = w.group("large");
            Map<String, Object> expected = new LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                double[] values = new double[1 + i * 187]; // 8 bytes to 58 KB: large blocks, and blocks skipped
                java.util.Arrays.fill(values, i);
                large.doubleAttribute("x" + i, values, new long[] {values.length});
                if (i % 13 == 0 || i == 39) {
                    expected.put("x" + i, values);
                }
            }
            group("/large").put("attrs", expected);
        }
        try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "dense_big_earliest.h5", "earliest"), Hdf5Writer.Format.EARLIEST)) {
            Hdf5Writer.GroupWriter g = w.group("g");
            for (int i = 0; i < 300; i++) {
                g.intDataset("x" + i, new int[] {i}, new long[] {1});
            }
            for (int i = 300; i < 9000; i++) {
                g.softLink("x" + i, "x" + (i % 300));
            }
            group("/g").put("count", 9000);
            group("/g").put("follow", Map.of("x8999", new int[] {299}, "x4567", new int[] {67}));
            Hdf5Writer.GroupWriter big = w.group("big");
            for (int i = 0; i < 40_000; i++) {
                big.softLink(String.format("é%05d", i), "/g/x1");
            }
            group("/big").put("count", 40_000);
            group("/big").put("follow", Map.of("é31234", new int[] {1}));
        }
    }

    /** A fixture libhdf5 wrote, copied into the export to be changed. */
    private static Path fixture(Path dir, String fixture, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.copy(Fixtures.path(fixture), file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    /** Files changed in place (P2 WF6): files Falcon wrote, and files libhdf5 wrote. */
    private void writeEdits(Path dir) throws IOException {
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String name = format == Hdf5Writer.Format.LATEST ? "edit_falcon.h5" : "edit_falcon_earliest.h5";
            Path file = begin(dir, name, format.name().toLowerCase());
            try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
                w.intDataset("ints", range(12), new long[] {3, 4});
                w.createDataset("rows", Datatype.float64(), 0, 2).chunked(2, 2).maxShape(Hdf5Writer.UNLIMITED, 2)
                        .deflate(4).append(new double[] {0, 0.5, 1, 1.5, 2, 2.5});
                w.shortDataset("small", new short[] {1, 2, 3}, new long[] {3}).compact();
                Hdf5Writer.GroupWriter g = w.group("g");
                g.intDataset("old", new int[] {9}, new long[] {1});
                g.intDataset("kept", new int[] {8}, new long[] {1});
                g.stringAttribute("a1", "one").stringAttribute("a2", "two").stringAttribute("a3", "three");
            }
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                w.intDataset("added", new int[] {1, 2}, new long[] {2});
                w.root().stringAttribute("title", "changed");
                Hdf5Writer.GroupWriter g = w.group("g");
                g.delete("old");
                for (int i = 0; i < 20; i++) {
                    g.softLink("s" + i, "/ints");
                }
                g.stringAttribute("a1", "uno").deleteAttribute("a2")
                        .attribute("a4", Datatype.int32(), new long[] {2}, new int[] {4, 44});
                g.group("sub").intDataset("deep", new int[] {7}, new long[] {1});
                w.dataset("ints").write(new long[] {1, 1}, new long[] {2, 2}, new int[] {-1, -2, -3, -4})
                        .stringAttribute("units", "m");
                w.dataset("rows").append(new double[] {3, 3.5}).write(new long[] {0, 1}, new long[] {1, 1}, new double[] {-0.5});
                w.dataset("small").write(new long[] {2}, new long[] {1}, new short[] {30});
                w.createDataset("refs", Datatype.objectReference(), 3).write(new String[] {"/g/kept", "/added", "/g/sub/deep"});
            }
            group("/").put("children", List.of("added", "g", "ints", "refs", "rows", "small"));
            group("/").put("attrs", Map.of("title", List.of("changed")));
            dataset("/ints", new int[] {0, 1, 2, 3, 4, -1, -2, 7, 8, -3, -4, 11}).put("attrs", Map.of("units", List.of("m")));
            dataset("/rows", new double[] {0, -0.5, 1, 1.5, 2, 2.5, 3, 3.5}).put("shape", new long[] {4, 2});
            dataset("/small", new int[] {1, 2, 30});
            dataset("/added", new int[] {1, 2});
            dataset("/refs", null).put("refs", List.of("/g/kept", "/added", "/g/sub/deep"));
            group("/g").put("count", 22);
            objects.getLast().put("absent", List.of("old"));
            objects.getLast().put("attrs", Map.of("a1", List.of("uno"), "a3", List.of("three"), "a4", new int[] {4, 44}));
            objects.getLast().put("attr_absent", List.of("a2"));
            objects.getLast().put("follow", Map.of("s7", new int[] {0, 1, 2, 3, 4, -1, -2, 7, 8, -3, -4, 11},
                    "kept", new int[] {8}));
            dataset("/g/sub/deep", new int[] {7});
        }

        // Groups libhdf5 wrote, in both formats: links added (past the compact limit) and deleted.
        for (String source : new String[] {"new_style_groups.h5", "old_style_groups.h5"}) {
            boolean latest = source.startsWith("new");
            Path file = fixture(dir, source, "edit_" + source);
            begin(dir, "edit_" + source, latest ? "latest" : "earliest");
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                Hdf5Writer.GroupWriter alpha = w.group("alpha");
                for (int i = 0; i < 300; i++) {
                    alpha.intDataset(String.format("n%03d", i), new int[] {i}, new long[] {1});
                }
                alpha.delete("delta");
                alpha.group("beta").delete("gamma").softLink("up", "/root_ds");
                w.group("empty").stringAttribute("now", "not empty");
                for (int i = 0; i < 12; i++) {
                    w.root().attribute("r" + i, Datatype.int16(), new long[0], new int[] {i});
                }
            }
            group("/alpha").put("count", 301);
            objects.getLast().put("absent", List.of("delta"));
            objects.getLast().put("follow", Map.of("n123", new int[] {123}, "n000", new int[] {0}));
            group("/alpha/beta").put("children", List.of("up"));
            objects.getLast().put("links", Map.of("up", Map.of("soft", "/root_ds")));
            objects.getLast().put("follow", Map.of("up", range(4)));
            group("/empty").put("attrs", Map.of("now", List.of("not empty")));
            group("/").put("attr_count", 12);
            objects.getLast().put("attrs", Map.of("r0", new int[] {0}, "r11", new int[] {11}));
            dataset("/root_ds", range(4));
        }

        // Dense links and attributes libhdf5 wrote.
        Path links = fixture(dir, "dense_links.h5", "edit_dense_links.h5");
        begin(dir, "edit_dense_links.h5", "latest");
        try (Hdf5Writer w = Hdf5Writer.open(links)) {
            Hdf5Writer.GroupWriter dense = w.group("dense");
            dense.delete("link03").delete("link17").softLink("soft", "/dense/link05");
            dense.intDataset("link03", new int[] {-3}, new long[] {1});
        }
        group("/dense").put("count", 20);
        objects.getLast().put("absent", List.of("link17"));
        objects.getLast().put("follow", Map.of("link03", new int[] {-3}, "link05", new int[] {5}, "soft", new int[] {5}));
        Path attributes = fixture(dir, "dense_attrs.h5", "edit_dense_attrs.h5");
        begin(dir, "edit_dense_attrs.h5", "latest");
        try (Hdf5Writer w = Hdf5Writer.open(attributes)) {
            w.dataset("d").deleteAttribute("attr00").intAttribute("attr05", new int[] {-5}, new long[0])
                    .stringAttribute("added", "yes");
        }
        dataset("/d", range(3)).put("attr_count", 20);
        objects.getLast().put("attr_absent", List.of("attr00"));
        objects.getLast().put("attrs", Map.of("attr05", new int[] {-5}, "attr19", new int[] {190}, "added", List.of("yes")));

        // Every chunk index libhdf5 writes, rewritten as Falcon's once chunks are written.
        Path chunks = fixture(dir, "chunk_indexes.h5", "edit_chunk_indexes.h5");
        begin(dir, "edit_chunk_indexes.h5", "latest");
        files.getLast().put("min_hdf5", "2.0"); // libhdf5 2.0 wrote it with version-5 layouts, which 1.14 cannot read
        try (Hdf5Writer w = Hdf5Writer.open(chunks)) {
            w.dataset("single").write(new long[] {1}, new long[] {2}, new int[] {-1, -2});
            w.dataset("implicit").write(new long[] {8}, new long[] {2}, new int[] {-8, -9});
            w.dataset("fixed").write(new long[] {4}, new long[] {2}, new int[] {-4, -5});
            w.dataset("extensible").append(new int[] {12, 13, 14, 15, 16});
            w.dataset("extensible_gz").append(range(7));
            w.dataset("btree2").extend(5, 6).write(new long[] {4, 4}, new long[] {1, 2}, new int[] {44, 45});
            w.dataset("btree2_deep").write(new long[] {39, 39}, new long[] {1, 1}, new int[] {-1});
        }
        dataset("/single", new int[] {0, -1, -2, 3, 4});
        int[] implicit = range(10);
        implicit[8] = -8;
        implicit[9] = -9;
        dataset("/implicit", implicit);
        int[] fixed = range(20);
        fixed[4] = -4;
        fixed[5] = -5;
        dataset("/fixed", fixed);
        dataset("/extensible", range(17)).put("maxshape", java.util.Arrays.asList((Object) null));
        int[] gz = java.util.Arrays.copyOf(range(200), 207);
        System.arraycopy(range(7), 0, gz, 200, 7);
        dataset("/extensible_gz", gz);
        int[] btree2 = new int[30];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                btree2[r * 6 + c] = r * 4 + c;
            }
        }
        btree2[4 * 6 + 4] = 44;
        btree2[4 * 6 + 5] = 45;
        dataset("/btree2", btree2).put("shape", new long[] {5, 6});
        int[] deep = range(1600);
        deep[1599] = -1;
        dataset("/btree2_deep", deep);
        dataset("/extensible_big", range(1200));

        // The earliest format's filtered chunks (version-1 B-tree): gzip, shuffle, fletcher32.
        Path filtered = fixture(dir, "chunked_data.h5", "edit_chunked_data.h5");
        begin(dir, "edit_chunked_data.h5", "earliest");
        try (Hdf5Writer w = Hdf5Writer.open(filtered)) {
            w.dataset("gzip_i4").write(new long[] {3}, new long[] {4}, new int[] {-3, -4, -5, -6});
            w.dataset("shuffle_i4").write(new long[] {19}, new long[] {1}, new int[] {-19});
            w.dataset("fletcher_i4").write(new long[] {0}, new long[] {20}, range(20, 40));
            w.dataset("chunk_2d").write(new long[] {3, 5}, new long[] {1, 1}, new int[] {-23});
        }
        int[] gzip = range(20);
        gzip[3] = -3;
        gzip[4] = -4;
        gzip[5] = -5;
        gzip[6] = -6;
        dataset("/gzip_i4", gzip);
        int[] shuffled = range(20);
        shuffled[19] = -19;
        dataset("/shuffle_i4", shuffled);
        dataset("/fletcher_i4", range(20, 40));
        int[] grid = range(24);
        grid[23] = -23;
        dataset("/chunk_2d", grid);

        // Creation order tracked (h5py's track_order): links and attributes, compact and dense, with their
        // creation-order indexes, which h5py iterates by.
        for (String source : new String[] {"tracked_order.h5", "tracked_order_old.h5"}) {
            Path file = fixture(dir, source, "edit_" + source);
            begin(dir, "edit_" + source, source.contains("old") ? "earliest" : "latest");
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                w.group("small").delete("a").intDataset("d", new int[] {100}, new long[] {1});
                w.group("big").delete("z05").softLink("new", "/d");
                w.dataset("d").intAttribute("x", new int[] {-1}, new long[0]).deleteAttribute("y")
                        .intAttribute("v", new int[] {118}, new long[0]);
                w.dataset("dd").deleteAttribute("q00").intAttribute("p", new int[] {7}, new long[0]);
                for (int i = 0; i < 9; i++) {
                    w.dataset("d").intAttribute("m" + i, new int[] {i}, new long[0]);
                }
            }
            group("/small").put("order", List.of("c", "b", "d"));
            objects.getLast().put("follow", Map.of("d", new int[] {100}, "c", new int[] {'c'}));
            List<String> big = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                if (i != 14) { // z05, the 15th made
                    big.add(String.format("z%02d", 19 - i));
                }
            }
            big.add("new");
            group("/big").put("order", big);
            objects.getLast().put("follow", Map.of("new", range(3)));
            List<String> attrs = new ArrayList<>(List.of("w", "x", "v"));
            for (int i = 0; i < 9; i++) {
                attrs.add("m" + i);
            }
            dataset("/d", range(3)).put("attr_order", attrs);
            objects.getLast().put("attrs", Map.of("x", new int[] {-1}, "w", new int[] {'w'}, "m8", new int[] {8}));
            List<String> dd = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                if (i != 19) { // q00, the last made
                    dd.add(String.format("q%02d", 19 - i));
                }
            }
            dd.add("p");
            dataset("/dd", range(3)).put("attr_order", dd);
        }

        // Shared object header messages (SOHM): data written under a shared filter pipeline, objects added.
        for (String source : new String[] {"sohm.h5", "sohm_latest.h5"}) {
            Path file = fixture(dir, source, "edit_" + source);
            begin(dir, "edit_" + source, source.contains("latest") ? "latest" : "earliest");
            if (source.contains("latest")) {
                files.getLast().put("min_hdf5", "2.0"); // written by libhdf5 2.0 with version-5 layouts
            }
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                w.dataset("b").write(new long[] {4}, new long[] {2}, new int[] {-4, -5});
                w.intDataset("added", new int[] {1}, new long[] {1});
                w.group("group").intDataset("inner", new int[] {2}, new long[] {1});
            }
            dataset("/b", new int[] {0, 1, 2, 3, -4, -5}).put("attrs", Map.of("units", new int[] {7}));
            dataset("/a", range(6)).put("attrs", Map.of("units", new int[] {7}));
            dataset("/added", new int[] {1});
            group("/group").put("attrs", Map.of("title", List.of("shared")));
            objects.getLast().put("follow", Map.of("inner", new int[] {2}));
        }

        // Contiguous and compact data libhdf5 wrote, written in place; a block allocated for one never written.
        Path contiguous = fixture(dir, "data_contiguous.h5", "edit_data_contiguous.h5");
        begin(dir, "edit_data_contiguous.h5", "earliest");
        try (Hdf5Writer w = Hdf5Writer.open(contiguous)) {
            w.dataset("c_i4").write(new long[] {1}, new long[] {2}, new int[] {-1, -2});
            w.dataset("c_be_i4").write(new long[] {4}, new long[] {1}, new int[] {-4});
            w.dataset("c_str").write(new long[] {3}, new long[] {1}, new String[] {"xyz"});
            w.dataset("compact_i4").write(new long[] {2}, new long[] {1}, new int[] {33});
            w.dataset("unwritten").write(new long[] {1}, new long[] {1}, new int[] {1});
        }
        dataset("/c_i4", new int[] {0, -1, -2, 3, 4});
        dataset("/c_be_i4", new int[] {0, 1, 2, 3, -4});
        dataset("/c_str", new String[] {"abc", "de", "fghij", "xyz"});
        dataset("/compact_i4", new int[] {10, 20, 33});
        dataset("/unwritten", new int[] {7, 1, 7, 7});

        // A second hard link deleted: its object's reference count lowered.
        Path metadata = fixture(dir, "metadata.h5", "edit_metadata.h5");
        begin(dir, "edit_metadata.h5", "latest");
        try (Hdf5Writer w = Hdf5Writer.open(metadata)) {
            w.delete("hardlink");
        }
        dataset("/plain", range(3)).put("refcount", 1);
        group("/").put("absent", List.of("hardlink"));

        // A user block before the superblock; B-tree 'K' values other than libhdf5's defaults.
        for (String source : new String[] {"userblock_v0.h5", "userblock_v3.h5", "btree_k_earliest.h5", "btree_k_latest.h5"}) {
            Path file = fixture(dir, source, "edit_" + source);
            begin(dir, "edit_" + source, source.contains("v0") || source.contains("earliest") ? "earliest" : "latest");
            if (source.equals("userblock_v3.h5")) {
                files.getLast().put("min_hdf5", "2.0"); // written by libhdf5 2.0 with a version-5 layout
            }
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                Hdf5Writer.GroupWriter g = w.group("added");
                for (int i = 0; i < 200; i++) {
                    g.intDataset("l" + i, new int[] {i}, new long[] {1});
                }
                w.createDataset("chunks", Datatype.int32(), 0).chunked(1).maxShape(Hdf5Writer.UNLIMITED).append(range(300));
            }
            group("/added").put("count", 200);
            objects.getLast().put("follow", Map.of("l0", new int[] {0}, "l199", new int[] {199}));
            dataset("/chunks", range(300));
        }
    }

    /** szip's nearest-neighbour coding (each chunk also checked against libaec's encoder), and user blocks. */
    private void writeWf7(Path dir) throws IOException {
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String suffix = format == Hdf5Writer.Format.LATEST ? "" : "_earliest";
            try (Hdf5Writer w = Hdf5Writer.create(begin(dir, "szip_nn" + suffix + ".h5", format.name().toLowerCase()), format)) {
                int[] smooth = new int[1000];
                short[] wave = new short[600];
                double[] signal = doubles(512);
                byte[] bytes = new byte[300];
                for (int i = 0; i < smooth.length; i++) {
                    smooth[i] = 100_000 + i * 3 + (i % 7);
                }
                for (int i = 0; i < wave.length; i++) {
                    wave[i] = (short) (Math.sin(i / 20.0) * 30000);
                    bytes[i % 300] = (byte) (i / 3);
                }
                w.createDataset("i32", Datatype.int32(), 1000).chunked(256)
                        .szip(Hdf5Writer.SzipCoding.NEAREST_NEIGHBOUR, 32).write(smooth);
                szipDataset("/i32", smooth);
                w.createDataset("i16_be", Datatype.int16().withByteOrder(java.nio.ByteOrder.BIG_ENDIAN), 20, 30)
                        .chunked(20, 30).szip(Hdf5Writer.SzipCoding.NEAREST_NEIGHBOUR, 16).write(wave);
                szipDataset("/i16_be", toInts(wave));
                w.createDataset("f64", Datatype.float64(), 512).chunked(128).shuffle()
                        .szip(Hdf5Writer.SzipCoding.NEAREST_NEIGHBOUR, 8).write(signal);
                szipDataset("/f64", signal);
                int[] unsigned = new int[300];
                for (int i = 0; i < 300; i++) {
                    unsigned[i] = bytes[i] & 0xff;
                }
                w.createDataset("u8_pad", Datatype.uint8(), 300).chunked(300)
                        .szip(Hdf5Writer.SzipCoding.NEAREST_NEIGHBOUR, 16).write(unsigned);
                szipDataset("/u8_pad", unsigned);
                w.createDataset("zeros", Datatype.int32(), 4096).chunked(4096)
                        .szip(Hdf5Writer.SzipCoding.ENTROPY, 32).write(new int[4096]); // zero blocks, runs to the segment end
                szipDataset("/zeros", new int[4096]);
            }
        }
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String name = format == Hdf5Writer.Format.LATEST ? "userblock.h5" : "userblock_earliest.h5";
            byte[] block = new byte[600];
            byte[] text = "Falcon user block".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, block, 0, text.length);
            block[599] = 0x7f;
            try (Hdf5Writer w = Hdf5Writer.create(begin(dir, name, format.name().toLowerCase()), format, block)) {
                w.intDataset("x", range(5), new long[] {5});
                w.createDataset("grows", Datatype.int32(), 0).chunked(4).maxShape(Hdf5Writer.UNLIMITED).append(range(9));
                w.group("g").stringAttribute("a", "b");
            }
            files.getLast().put("userblock", Map.of("size", 1024, "hex", java.util.HexFormat.of().formatHex(block)));
            dataset("/x", range(5));
            dataset("/grows", range(9));
            group("/g").put("attrs", Map.of("a", List.of("b")));
        }
    }

    private void szipDataset(String path, Object values) {
        dataset(path, values).put("szip", true);
        objects.getLast().put("szip_exact", true);
    }

    private static int[] toInts(short[] values) {
        int[] ints = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            ints[i] = values[i];
        }
        return ints;
    }

    /**
     * Hard links (a cycle among them), moves and renames, and a link deleted while another reaches its object;
     * and in the earliest format, external links, in groups libhdf5 then writes in the new format.
     */
    private void writeLinks(Path dir) throws IOException {
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            String name = format == Hdf5Writer.Format.LATEST ? "hard_links.h5" : "hard_links_earliest.h5";
            try (Hdf5Writer w = Hdf5Writer.create(begin(dir, name, format.name().toLowerCase()), format)) {
                w.group("a").intDataset("x", new int[] {1, 2, 3}, new long[] {3});
                Hdf5Writer.GroupWriter b = w.group("b");
                b.hardLink("y", "/a/x").hardLink("up", "/a").hardLink("root", "/");
                w.group("c").intDataset("only", new int[] {7}, new long[] {1});
                w.hardLink("kept", "/c/only");
                w.delete("c");
                w.group("d").group("inner").intDataset("deep", new int[] {9}, new long[] {1});
                w.move("d", "/b/d2");
                b.move("d2/inner/deep", "/deep_moved");
                w.intDataset("plain", new int[] {5}, new long[] {1});
                w.move("plain", "renamed");
                w.externalLink("ext", "other.h5", "/elsewhere");
                w.group("e").externalLink("ext2", "other.h5", "/x").intDataset("v", new int[] {4}, new long[] {1});
                w.referenceDataset("refs", new long[] {3}, new String[] {"/b/y", "/kept", "/deep_moved"});
            }
            group("/").put("children", List.of("a", "b", "deep_moved", "e", "ext", "kept", "refs", "renamed"));
            objects.getLast().put("links", Map.of("ext", Map.of("external", List.of("other.h5", "/elsewhere"))));
            objects.getLast().put("refcount", 2);
            dataset("/a/x", new int[] {1, 2, 3}).put("refcount", 2);
            dataset("/b/y", new int[] {1, 2, 3}).put("same_as", "/a/x");
            group("/a").put("refcount", 2);
            group("/b/up").put("same_as", "/a");
            group("/b/root").put("same_as", "/");
            group("/b").put("children", List.of("d2", "root", "up", "y"));
            group("/b/d2/inner").put("children", List.of());
            dataset("/kept", new int[] {7}).put("refcount", 1);
            dataset("/deep_moved", new int[] {9});
            dataset("/renamed", new int[] {5});
            group("/e").put("children", List.of("ext2", "v"));
            objects.getLast().put("links", Map.of("ext2", Map.of("external", List.of("other.h5", "/x"))));
            dataset("/refs", null).put("refs", List.of("/a/x", "/kept", "/deep_moved"));
        }
    }

    /** Files libhdf5 wrote, changed in ways P2 WF10 opened: filters with parameters, links, dense storage shrunk. */
    private void writeWf10Edits(Path dir) throws IOException {
        // szip datasets of either coding (written with libaec's own coding, each chunk checked against it)
        Path szip = fixture(dir, "szip.h5", "edit_szip.h5");
        begin(dir, "edit_szip.h5", "latest");
        files.getLast().put("min_hdf5", "2.0"); // written by libhdf5 2.0 with version-5 layouts
        float[] f32;
        int[] multi;
        int[] be;
        try (Hdf5File h5 = Hdf5File.open(szip)) {
            f32 = h5.root().dataset("f32_nn").readFloats();
            multi = h5.root().dataset("i32_nn_multi").readInts();
            be = h5.root().dataset("be_i16_nn").readInts();
        }
        try (Hdf5Writer w = Hdf5Writer.open(szip)) {
            w.dataset("f32_nn").write(new long[] {10}, new long[] {3}, new float[] {-1.5f, 2.25f, 1e9f});
            w.dataset("i32_nn_multi").write(new long[] {250}, new long[] {10}, range(-5000, -4990));
            w.dataset("be_i16_nn").write(new long[] {0}, new long[] {2}, new short[] {-32768, 32767});
        }
        f32[10] = -1.5f;
        f32[11] = 2.25f;
        f32[12] = 1e9f;
        double[] f32d = new double[f32.length];
        for (int i = 0; i < f32.length; i++) {
            f32d[i] = f32[i];
        }
        szipDataset("/f32_nn", f32d);
        System.arraycopy(range(-5000, -4990), 0, multi, 250, 10);
        szipDataset("/i32_nn_multi", multi);
        be[0] = -32768;
        be[1] = 32767;
        szipDataset("/be_i16_nn", be);

        // scale-offset: integers (fill value, big-endian, 64-bit) and decimal-scaled floats
        Path scaleOffset = fixture(dir, "scaleoffset.h5", "edit_scaleoffset.h5");
        begin(dir, "edit_scaleoffset.h5", "latest");
        files.getLast().put("min_hdf5", "2.0");
        int[] fill;
        int[] bigEndian;
        long[] neg;
        try (Hdf5File h5 = Hdf5File.open(scaleOffset)) {
            fill = h5.root().dataset("i4_fill").readInts();
            bigEndian = h5.root().dataset("be_i4").readInts();
            neg = h5.root().dataset("i8_neg").readLongs();
        }
        try (Hdf5Writer w = Hdf5Writer.open(scaleOffset)) {
            w.dataset("i4_fill").write(new long[] {3}, new long[] {4}, new int[] {-1, 1_000_000, 5, -1});
            w.dataset("be_i4").write(new long[] {60}, new long[] {8}, range(-4, 4));
            w.dataset("i8_neg").write(new long[] {18}, new long[] {2}, new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
            w.dataset("f4_d2").write(new double[] {0.25, 1.25, -2.75, 3.5, 100.5, -0.25, 7.75, 0});
            w.dataset("f8_d3_fill").write(new double[] {2.125, -7.5, 0.5, 1.25, -0.75, 3, 4.5});
        }
        System.arraycopy(new int[] {-1, 1_000_000, 5, -1}, 0, fill, 3, 4);
        dataset("/i4_fill", fill);
        System.arraycopy(range(-4, 4), 0, bigEndian, 60, 8);
        dataset("/be_i4", bigEndian);
        neg[18] = Long.MIN_VALUE;
        neg[19] = Long.MAX_VALUE;
        dataset("/i8_neg", neg);
        dataset("/f4_d2", new double[] {0.25, 1.25, -2.75, 3.5, 100.5, -0.25, 7.75, 0});
        dataset("/f8_d3_fill", new double[] {2.125, -7.5, 0.5, 1.25, -0.75, 3, 4.5});

        // n-bit: an atomic type, a compound of two, one at full precision
        Path nbit = fixture(dir, "nbit_data.h5", "edit_nbit_data.h5");
        begin(dir, "edit_nbit_data.h5", "latest");
        long[] nbitValues;
        try (Hdf5File h5 = Hdf5File.open(nbit)) {
            nbitValues = h5.root().dataset("nbit_u").readLongs();
        }
        try (Hdf5Writer w = Hdf5Writer.open(nbit)) {
            w.dataset("nbit_u").write(new long[] {17}, new long[] {3}, new long[] {65535, 0, 1234});
        }
        nbitValues[17] = 65535;
        nbitValues[18] = 0;
        nbitValues[19] = 1234;
        dataset("/nbit_u", nbitValues);
        Path compound = fixture(dir, "compound_nbit.h5", "edit_compound_nbit.h5");
        begin(dir, "edit_compound_nbit.h5", "latest");
        int[] a;
        long[] bMember;
        try (Hdf5File h5 = Hdf5File.open(compound)) {
            a = h5.root().dataset("c").member("a").readInts();
            bMember = h5.root().dataset("c").member("b").readLongs();
        }
        try (Hdf5Writer w = Hdf5Writer.open(compound)) {
            w.dataset("c").write(new long[] {1}, new long[] {2},
                    Map.of("a", new short[] {-2048, 2047}, "b", new long[] {1_048_575, 3}));
        }
        a[1] = -2048;
        a[2] = 2047;
        bMember[1] = 1_048_575;
        bMember[2] = 3;
        dataset("/c", null).put("fields", Map.of("a", a, "b", bMember));

        // partial edge chunks stored unfiltered, kept so (a fixed array) or filtered (a dataset that grows)
        Path edges = fixture(dir, "partial_edges.h5", "edit_partial_edges.h5");
        begin(dir, "edit_partial_edges.h5", "latest");
        files.getLast().put("min_hdf5", "2.0");
        try (Hdf5Writer w = Hdf5Writer.open(edges)) {
            w.dataset("fixed").write(new long[] {0}, new long[] {1}, new int[] {-100});
            w.dataset("fixed").write(new long[] {9}, new long[] {1}, new int[] {-9});
            w.dataset("plane").write(new long[] {4, 6}, new long[] {1, 1}, new int[] {-34});
            w.dataset("grows").append(new int[] {10, 11, 12});
        }
        int[] fixedEdge = range(10);
        fixedEdge[0] = -100;
        fixedEdge[9] = -9;
        dataset("/fixed", fixedEdge);
        int[] plane = range(35);
        plane[34] = -34;
        dataset("/plane", plane).put("shape", new long[] {5, 7});
        dataset("/grows", range(13));

        // hard links and moves in files libhdf5 wrote, both group formats
        for (String source : new String[] {"new_style_groups.h5", "old_style_groups.h5"}) {
            String name = "edit_links_" + source;
            Path file = fixture(dir, source, name);
            begin(dir, name, source.startsWith("new") ? "latest" : "earliest");
            int[] gamma;
            try (Hdf5File h5 = Hdf5File.open(file)) {
                gamma = h5.root().dataset("alpha/beta/gamma").readInts();
            }
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                w.hardLink("g2", "/alpha/beta/gamma");
                w.group("empty").hardLink("back", "/alpha");
                w.group("alpha").move("beta", "/empty/beta2");
                w.move("root_ds", "renamed");
                w.group("added").intDataset("n", new int[] {4}, new long[] {1});
                w.group("empty").move("beta2/gamma", "/added/gamma");
                w.referenceDataset("refs", new long[] {2}, new String[] {"/added/gamma", "/renamed"});
            }
            group("/").put("children", List.of("added", "alpha", "empty", "g2", "refs", "renamed"));
            group("/alpha").put("children", List.of("delta"));
            objects.getLast().put("refcount", 2);
            group("/empty").put("children", List.of("back", "beta2"));
            group("/empty/back").put("same_as", "/alpha");
            group("/empty/beta2").put("children", List.of());
            dataset("/added/gamma", gamma).put("refcount", 2);
            dataset("/g2", gamma).put("same_as", "/added/gamma");
            dataset("/refs", null).put("refs", List.of("/added/gamma", "/renamed"));
        }

        // external links added to groups of the original format, which convert (the root among them)
        Path converted = fixture(dir, "old_style_groups.h5", "edit_external_old_style.h5");
        begin(dir, "edit_external_old_style.h5", "earliest");
        try (Hdf5Writer w = Hdf5Writer.open(converted)) {
            w.group("alpha").externalLink("ext", "other.h5", "/x");
            w.externalLink("rootext", "other.h5", "/y");
            w.group("alpha").delete("delta");
        }
        group("/").put("children", List.of("alpha", "empty", "root_ds", "rootext"));
        objects.getLast().put("links", Map.of("rootext", Map.of("external", List.of("other.h5", "/y"))));
        group("/alpha").put("children", List.of("beta", "ext"));
        objects.getLast().put("links", Map.of("ext", Map.of("external", List.of("other.h5", "/x"))));
        dataset("/root_ds", range(4));

        // attributes in the shared-message table (SOHM): deleted, replaced, added to, compact and dense; libhdf5
        // then deletes the attributes still shared, through the index Falcon left
        for (String source : new String[] {"sohm.h5", "sohm_latest.h5"}) {
            String name = "edit_shared_" + source;
            Path file = fixture(dir, source, name);
            begin(dir, name, source.contains("latest") ? "latest" : "earliest");
            if (source.contains("latest")) {
                files.getLast().put("min_hdf5", "2.0");
            }
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                w.dataset("a").deleteAttribute("units");
                w.group("group").stringAttribute("title", "unshared now").stringAttribute("t", "x");
                w.dataset("many").deleteAttribute("attr00").deleteAttribute("attr01")
                        .intAttribute("attr05", new int[] {-5}, new long[0]).stringAttribute("added", "yes");
            }
            dataset("/a", range(6)).put("attr_absent", List.of("units"));
            dataset("/b", range(6)).put("libhdf5_delete_attrs", List.of("units"));
            group("/group").put("attrs", Map.of("title", List.of("unshared now"), "t", List.of("x")));
            dataset("/many", null).put("attr_count", 12);
            objects.getLast().put("attr_absent", List.of("attr00", "attr01"));
            objects.getLast().put("attrs", Map.of("attr05", new int[] {-5}, "attr11", new int[] {11}, "added", List.of("yes")));
            objects.getLast().put("libhdf5_delete_attrs", List.of("attr02", "big"));
        }
        Path btree = fixture(dir, "sohm_btree.h5", "edit_shared_btree.h5");
        begin(dir, "edit_shared_btree.h5", "latest");
        try (Hdf5Writer w = Hdf5Writer.open(btree)) {
            w.dataset("d1").deleteAttribute("same");
            w.group("g").deleteAttribute("u0").intAttribute("u1", new int[] {-1}, new long[0]);
            w.dataset("e2").append(new int[] {4, 5, 6});
            w.dataset("e1").append(new int[] {9});
        }
        dataset("/d1", range(3)).put("attr_absent", List.of("same"));
        dataset("/d2", range(3)).put("libhdf5_delete_attrs", List.of("same"));
        group("/g").put("attrs", Map.of("u1", new int[] {-1}, "u2", new int[] {102}));
        objects.getLast().put("attr_absent", List.of("u0"));
        objects.getLast().put("libhdf5_delete_attrs", List.of("u3", "u4"));
        dataset("/e1", new int[] {0, 1, 2, 3, 9}).put("maxshape", java.util.Arrays.asList((Object) null));
        dataset("/e2", new int[] {0, 1, 2, 3, 4, 5, 6});
        // libhdf5 itself corrupts a header when it resizes a dataset whose dataspace is still shared (libhdf5
        // 1.14 and 2.0, with no Falcon in the file: "message size exceeds buffer end"), so it does not grow e3.
        dataset("/e3", range(4)).put("libhdf5_no_grow", true);

        // data in external raw files, written into the slots of two files
        Path external = fixture(dir, "external.h5", "edit_external.h5");
        fixture(dir, "external_a.bin", "external_a.bin");
        fixture(dir, "external_b.bin", "external_b.bin");
        begin(dir, "edit_external.h5", "latest");
        files.getLast().put("companions", List.of("external_a.bin", "external_b.bin"));
        try (Hdf5Writer w = Hdf5Writer.open(external)) {
            w.dataset("ext").write(new long[] {4}, new long[] {4}, new int[] {-4, -5, -6, -7});
        }
        dataset("/ext", new int[] {0, 1, 2, 3, -4, -5, -6, -7, 8, 9, 10, 11});

        // the journal: a change interrupted while it was written over the file (a version-3 superblock, marked
        // as open by a writer: libhdf5 refuses it), and the same change redone by the next open
        for (String name : new String[] {"interrupted.h5", "recovered.h5"}) {
            Path file = begin(dir, name, "latest");
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("x", range(3), new long[] {3});
                w.group("g").intDataset("y", new int[] {1}, new long[] {1});
            }
            Hdf5Writer w = Hdf5Writer.open(file);
            w.group("g").intDataset("z", new int[] {2}, new long[] {1}).stringAttribute("a", "b");
            w.dataset("x").stringAttribute("units", "m");
            Hdf5Writer.interruptAfter = 0;
            try {
                w.close();
                throw new IllegalStateException("not interrupted");
            } catch (IOException expected) {
                w.abort();
            } finally {
                Hdf5Writer.interruptAfter = -1;
            }
            if (name.equals("interrupted.h5")) {
                files.getLast().put("refused", true);
                continue;
            }
            Hdf5Writer.open(file).close();
            dataset("/x", range(3)).put("attrs", Map.of("units", List.of("m")));
            group("/g").put("children", List.of("y", "z"));
            dataset("/g/z", new int[] {2}).put("attrs", Map.of("a", List.of("b")));
        }

        // dense storage shrunk below its minimum: back to compact messages
        Path shrunk = fixture(dir, "dense_links.h5", "edit_dense_shrunk_links.h5");
        begin(dir, "edit_dense_shrunk_links.h5", "latest");
        try (Hdf5Writer w = Hdf5Writer.open(shrunk)) {
            Hdf5Writer.GroupWriter dense = w.group("dense");
            for (int i = 0; i < 16; i++) {
                dense.delete(String.format("link%02d", i));
            }
        }
        group("/dense").put("children", List.of("link16", "link17", "link18", "link19"));
        objects.getLast().put("follow", Map.of("link19", new int[] {19}));
        Path shrunkAttributes = fixture(dir, "dense_attrs.h5", "edit_dense_shrunk_attrs.h5");
        begin(dir, "edit_dense_shrunk_attrs.h5", "latest");
        try (Hdf5Writer w = Hdf5Writer.open(shrunkAttributes)) {
            Hdf5Writer.DatasetWriter d = w.dataset("d");
            for (int i = 0; i < 16; i++) {
                d.deleteAttribute(String.format("attr%02d", i));
            }
            d.stringAttribute("new", "one");
        }
        dataset("/d", range(3)).put("attr_count", 5);
        objects.getLast().put("attrs", Map.of("new", List.of("one"), "attr19", new int[] {190}));
    }

    private static int[] range(int from, int to) {
        int[] a = new int[to - from];
        for (int i = 0; i < a.length; i++) {
            a[i] = from + i;
        }
        return a;
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
        // Many attributes: dense storage in the latest format, version-1 header messages in the earliest.
        Hdf5Writer.DatasetWriter manyAttributes = w.intDataset("many_attrs", new int[] {1}, new long[] {1});
        Map<String, Object> manyAttrs = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            manyAttributes.intAttribute("a" + i, new int[] {i}, new long[] {});
            manyAttrs.put("a" + i, new int[] {i});
        }
        dataset("/many_attrs", new int[] {1}).put("attrs", manyAttrs);
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

    /** Datasets written piece by piece, grown, rewritten in part, and left partly unwritten. */
    private void writeStreaming(Hdf5Writer w) {
        // 2-D uint16, chunked and deflated, written a block of rows at a time (chunks completed as they fill).
        Hdf5Writer.DatasetWriter image = w.createDataset("image", Datatype.uint16(), 40, 30).chunked(8, 16).deflate(4);
        int[] pixels = new int[1200];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (i * 53) % 65536;
        }
        for (int row = 0; row < 40; row += 5) {
            image.write(new long[] {row, 0}, new long[] {5, 30}, java.util.Arrays.copyOfRange(pixels, row * 30, row * 30 + 150));
        }
        dataset("/image", pixels).put("dtype", "<u2");
        objects.getLast().put("chunks", new long[] {8, 16});
        // Appended rows of an unlimited dataset, with a fill value and one chunk written twice.
        Hdf5Writer.DatasetWriter rows = w.createDataset("rows", Datatype.int32(), 0, 4).chunked(3, 4)
                .maxShape(Hdf5Writer.UNLIMITED, 4).fillValue(-1).shuffle().deflate(1);
        rows.append(range(8));             // rows 0-1
        rows.append(new int[] {8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19}); // rows 2-4
        rows.write(new long[] {1, 1}, new long[] {1, 2}, new int[] {-5, -6}); // into the first, stored chunk
        rows.extend(7, 4);                 // rows 5-6 never written: the fill value
        int[] expected = new int[28];
        for (int i = 0; i < 20; i++) {
            expected[i] = i;
        }
        expected[5] = -5;
        expected[6] = -6;
        java.util.Arrays.fill(expected, 20, 28, -1);
        dataset("/rows", expected).put("shape", new long[] {7, 4});
        objects.getLast().put("maxshape", java.util.Arrays.asList(null, 4));
        objects.getLast().put("fill", -1);
        // Contiguous float64 with a fill value, written in two pieces, one gap.
        Hdf5Writer.DatasetWriter line = w.createDataset("line", Datatype.float64(), 10).fillValue(2.5);
        line.write(new long[] {0}, new long[] {3}, new double[] {0.5, 1.5, -1});
        line.write(new long[] {7}, new long[] {3}, new double[] {7, 8, 9});
        dataset("/line", new double[] {0.5, 1.5, -1, 2.5, 2.5, 2.5, 2.5, 7, 8, 9});
        // Variable-length strings appended to a chunked dataset; their heap collections placed as chunks fill.
        Hdf5Writer.DatasetWriter names = w.createDataset("names", Datatype.variableString(), 0).chunked(4)
                .maxShape(Hdf5Writer.UNLIMITED);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String[] batch = {"n" + i, "é" + i};
            names.append(batch);
            all.addAll(List.of(batch));
        }
        dataset("/names", all.toArray(new String[0])).put("maxshape", java.util.Arrays.asList((Object) null));
        // A 3-D dataset that grows in two dimensions, written in boxes.
        Hdf5Writer.DatasetWriter cube = w.createDataset("cube", Datatype.int64(), 2, 2, 3).chunked(2, 2, 2)
                .maxShape(Hdf5Writer.UNLIMITED, Hdf5Writer.UNLIMITED, 3);
        cube.extend(3, 5, 3);
        long[] cubeValues = new long[45];
        for (int i = 0; i < 45; i++) {
            cubeValues[i] = i * 1_000_000_007L;
        }
        cube.write(cubeValues);
        dataset("/cube", cubeValues).put("shape", new long[] {3, 5, 3});
        objects.getLast().put("maxshape", java.util.Arrays.asList(null, null, 3));
        // Multi-level B-tree indexes: 300 chunks (2 levels of 64-entry nodes) and 5000 (3 levels).
        for (int chunk : new int[] {10, 1}) {
            int n = chunk == 10 ? 3000 : 5000;
            Hdf5Writer.DatasetWriter many = w.createDataset("chunks_" + n / chunk, Datatype.int32(), 0).chunked(chunk)
                    .maxShape(Hdf5Writer.UNLIMITED);
            for (int start = 0; start < n; start += 500) {
                many.append(java.util.Arrays.copyOfRange(range(n), start, start + 500));
            }
            dataset("/chunks_" + n / chunk, range(n)).put("maxshape", java.util.Arrays.asList((Object) null));
        }
    }

    /** One dataset per datatype class (and order and sign), through createDataset, plus typed attributes. */
    private void writeDatatypes(Hdf5Writer w, boolean earliest) {
        w.createDataset("u8", Datatype.uint8(), 3).write(new int[] {0, 128, 255});
        dataset("/u8", new int[] {0, 128, 255}).put("dtype", "|u1");
        w.createDataset("u16", Datatype.uint16(), 2).write(new int[] {0, 65535});
        dataset("/u16", new int[] {0, 65535}).put("dtype", "<u2");
        w.createDataset("u32", Datatype.uint32(), 2).write(new long[] {0, 4294967295L});
        dataset("/u32", new long[] {0, 4294967295L}).put("dtype", "<u4");
        w.createDataset("u64", Datatype.uint64(), 2).write(new java.math.BigInteger[] {
            java.math.BigInteger.ZERO, new java.math.BigInteger("18446744073709551615")});
        dataset("/u64", List.of(0, new java.math.BigInteger("18446744073709551615"))).put("dtype", "<u8");
        w.createDataset("i8", Datatype.int8(), 2).write(new byte[] {-128, 127});
        dataset("/i8", new int[] {-128, 127}).put("dtype", "|i1");
        w.createDataset("be_i32", Datatype.int32().withByteOrder(java.nio.ByteOrder.BIG_ENDIAN), 3).write(new int[] {-2, 0, 70000});
        dataset("/be_i32", new int[] {-2, 0, 70000}).put("dtype", ">i4");
        w.createDataset("be_f64", Datatype.float64().withByteOrder(java.nio.ByteOrder.BIG_ENDIAN), 2).write(new double[] {1.25, -3e300});
        dataset("/be_f64", new double[] {1.25, -3e300}).put("dtype", ">f8");
        w.createDataset("f16", Datatype.float16(), 3).write(new float[] {0.5f, -2f, 65504f});
        dataset("/f16", new double[] {0.5, -2, 65504}).put("dtype", "<f2");
        w.createDataset("f32", Datatype.float32(), 2).write(new int[] {3, -16777217});
        dataset("/f32", new double[] {3, -16777216}).put("dtype", "<f4");
        w.createDataset("bool", Datatype.bool(), 3).write(new boolean[] {true, false, true});
        dataset("/bool", new int[] {1, 0, 1}).put("dtype", "|b1");
        w.createDataset("bits", Datatype.bitField(2), 2).write(new int[] {0x8001, 7});
        dataset("/bits", new int[] {0x8001, 7}).put("dtype", "<u2");
        w.createDataset("opaque", Datatype.opaque(3, "falcon"), 2).write(new byte[][] {{1, 2, 3}, {-1, 0, 9}});
        dataset("/opaque", null).put("opaque_hex", "010203ff0009");
        w.createDataset("time", Datatype.unixTime(8), 2).write(new java.time.Instant[] {
            java.time.Instant.EPOCH, java.time.Instant.parse("2023-11-14T22:13:20Z")});
        dataset("/time", null).put("time", new long[] {0, 1_700_000_000L});
        w.createDataset("fixed", Datatype.string(6), 2).write(new String[] {"abc", "été"});
        dataset("/fixed", new String[] {"abc", "été"});
        w.createDataset("vstr", Datatype.variableString(), 2).write(new String[] {"one", ""});
        dataset("/vstr", new String[] {"one", ""});
        Datatype.Enumeration color = new Datatype.Enumeration(2, Datatype.int16(), List.of(
                new Datatype.Enumeration.Member("RED", -1), new Datatype.Enumeration.Member("BLUE", 300)));
        w.createDataset("enum", color, 3).write(new String[] {"BLUE", "RED", "BLUE"});
        dataset("/enum", new int[] {300, -1, 300}).put("enum", Map.of("RED", -1, "BLUE", 300));
        Map<String, Datatype> members = new LinkedHashMap<>();
        members.put("id", Datatype.uint32());
        members.put("pos", Datatype.arrayOf(Datatype.float32(), 2));
        members.put("name", Datatype.string(4));
        w.createDataset("records", Datatype.compound(members), 2).write(Map.of(
                "id", new long[] {1, 4000000000L}, "pos", new float[] {0.5f, 1.5f, -2, 3}, "name", new String[] {"ab", "cdef"}));
        dataset("/records", null).put("fields", Map.of("id", new long[] {1, 4000000000L}));
        w.createDataset("seq", Datatype.sequenceOf(Datatype.int16()), 2).write(new short[][] {{1, -2}, {}});
        dataset("/seq", null).put("rows", List.of(new int[] {1, -2}, new int[0]));
        w.createDataset("chunked_strings", Datatype.string(3), 5).chunked(2).deflate(2)
                .write(new String[] {"a", "bb", "ccc", "", "e"});
        dataset("/chunked_strings", new String[] {"a", "bb", "ccc", "", "e"});
        if (!earliest) {
            w.createDataset("complex64", Datatype.complexOf(Datatype.float32()), 2).write(new double[] {1, -2, 0.5, 4});
            dataset("/complex64", new double[] {1, -2, 0.5, 4}).put("complex", true);
            objects.getLast().put("min_hdf5", "2.0");
        }
        Hdf5Writer.GroupWriter root = w.root();
        root.stringAttribute("title", "Falcon").stringAttribute("units", "m/s²")
                .attribute("u8s", Datatype.uint8(), new long[] {3}, new int[] {1, 200, 255})
                .attribute("vlen", Datatype.variableString(), new long[] {2}, new String[] {"x", "yz"})
                .attribute("f32", Datatype.float32(), new long[0], new double[] {0.25});
        group("/").put("attrs", Map.of("title", new String[] {"Falcon"}, "units", new String[] {"m/s²"},
                "u8s", new int[] {1, 200, 255}, "vlen", new String[] {"x", "yz"}, "f32", new double[] {0.25}));
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
