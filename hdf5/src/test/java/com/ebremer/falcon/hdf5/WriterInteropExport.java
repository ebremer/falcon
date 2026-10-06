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
