import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Writes Zstandard frames with Falcon's encoder, for check_zstd_encoder.py to decode with libzstd (one-shot
 * and streaming) and to compare with libzstd's own ratios. Dev-time tool, run with the JDK's source launcher
 * from the repo root, after {@code mvn -pl core compile}:
 *
 * <pre>
 *     java -cp core/target/classes tools/fixtures/WriteZstdCases.java OUT_DIR [--huge]
 *     python tools/fixtures/check_zstd_encoder.py OUT_DIR
 * </pre>
 *
 * Each input is written as {@code orig_<name>.bin}, and each frame as {@code frame_<k>.zst}, listed in
 * {@code manifest.tsv} (frame, input, level, checksum). Inputs: the edge sizes (empty, 1 byte, tiny, exactly
 * 128 KiB, multi-block), all-equal and random bytes, noisy and smooth float32/float64, text (the repo's own
 * Java sources), records with a repeated field, and a sharded chunk's bytes. Every input is written at
 * levels -5, 1, 3, 9, 19, and 22, and the text at every level; odd frames carry the content checksum.
 * {@code --huge} adds a 150 MB frame, over libzstd's default streaming window limit, at the default level.
 */
public class WriteZstdCases {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        boolean huge = args.length > 1 && args[1].equals("--huge");
        Map<String, byte[]> inputs = inputs(huge);
        List<String> manifest = new ArrayList<>();
        int k = 0;
        for (Map.Entry<String, byte[]> e : inputs.entrySet()) {
            Files.write(out.resolve("orig_" + e.getKey() + ".bin"), e.getValue());
            int[] levels = e.getKey().equals("text") ? java.util.stream.IntStream.rangeClosed(-5, 22).toArray()
                    : e.getKey().equals("huge") ? new int[] {3} : new int[] {-5, 1, 3, 9, 19, 22};
            for (int level : levels) {
                boolean checksum = k % 2 == 1;
                Files.write(out.resolve("frame_" + k + ".zst"), ZstdEncoder.compress(e.getValue(), level, checksum));
                manifest.add(k + "\t" + e.getKey() + "\t" + level + "\t" + checksum);
                k++;
            }
        }
        Files.write(out.resolve("manifest.tsv"), manifest);
        System.out.println(k + " frames of " + inputs.size() + " inputs written to " + out);
    }

    static Map<String, byte[]> inputs(boolean huge) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        Random r = new Random(99);
        m.put("empty", new byte[0]);
        m.put("one", new byte[] {42});
        m.put("tiny", "hello world".getBytes());
        m.put("zeros_128k", new byte[1 << 17]);
        byte[] equal = new byte[300_000];
        Arrays.fill(equal, (byte) 0x5A);
        m.put("all_equal", equal);
        byte[] random = new byte[200_000];
        r.nextBytes(random);
        m.put("random", random);
        int n = 1 << 20;
        ByteBuffer f32 = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            f32.putFloat((float) (Math.sin(i * 0.001) * 100 + r.nextGaussian()));
        }
        m.put("noisy_float32", f32.array());
        ByteBuffer f64 = ByteBuffer.allocate((n / 2) * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n / 2; i++) {
            f64.putDouble(Math.sin(i * 0.0005) * Math.cos(i * 0.00007) * 1000);
        }
        m.put("smooth_float64", f64.array());
        byte[] text = sources(Path.of("zarr/src/main/java"), 2 << 20);
        m.put("text", text);
        m.put("text_128k", Arrays.copyOf(text, 1 << 17));
        m.put("text_128k_plus_1", Arrays.copyOf(text, (1 << 17) + 1));
        byte[] records = new byte[400_000];
        for (int i = 0; i < records.length; i += 24) {
            for (int j = 0; j < 24 && i + j < records.length; j++) {
                records[i + j] = j < 4 ? (byte) r.nextInt(256) : (byte) (j * 7 + (i / 24000));
            }
        }
        m.put("records", records);
        // A shard of float32 sub-chunks (64 x 64 each, of a smooth 512 x 512 field), then its index.
        ByteBuffer shard = ByteBuffer.allocate(512 * 512 * 4 + 64 * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 64; x++) {
                        int gy = by * 64 + y;
                        int gx = bx * 64 + x;
                        shard.putFloat(Math.round((Math.sin(gx * 0.02) + Math.cos(gy * 0.03)) * 1000) / 10f);
                    }
                }
            }
        }
        for (int i = 0; i < 64; i++) {
            shard.putLong(i * 16384L).putLong(16384);
        }
        m.put("shard_float32", shard.array());
        if (huge) {
            byte[] big = new byte[150_000_000];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i / 1000);
            }
            m.put("huge", big);
        }
        return m;
    }

    /** Up to {@code max} bytes of the Java sources under {@code root}, concatenated in path order. */
    static byte[] sources(Path root, int max) throws Exception {
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                text.write(Files.readAllBytes(p));
                if (text.size() >= max) {
                    break;
                }
            }
        }
        return Arrays.copyOf(text.toByteArray(), Math.min(max, text.size()));
    }
}
