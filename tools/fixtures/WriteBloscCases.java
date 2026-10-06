import com.ebremer.falcon.core.compress.blosc.BloscDecoder;
import com.ebremer.falcon.core.compress.blosc.BloscEncoder;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Blosc buffers and LZ4 blocks with Falcon's encoders, for check_blosc_encoder.py to compare with
 * c-blosc's and liblz4's (numcodecs). Dev-time tool, run by that script with the JDK's source launcher
 * from the repo root, after {@code mvn -pl core compile}:
 *
 * <pre>
 *     java -cp core/target/classes tools/fixtures/WriteBloscCases.java OUT_DIR
 * </pre>
 *
 * It reads {@code OUT_DIR/cases.txt}, one case a line ({@code blosc <id> <input> <typesize> <shuffle>
 * <blocksize> <clevel> <cname>}, {@code lz4 <id> <input> <acceleration>}, or {@code lz4hc <id> <input>
 * <level>}), and writes {@code OUT_DIR/falcon.out}: per case, its id and length (big-endian ints) and its
 * bytes. Every Blosc buffer is first decoded by Falcon's own decoder, which must give the input back.
 */
public class WriteBloscCases {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Map<String, byte[]> inputs = new HashMap<>();
        List<String> lines = Files.readAllLines(dir.resolve("cases.txt"));
        long start = System.nanoTime();
        try (OutputStream file = Files.newOutputStream(dir.resolve("falcon.out"));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file))) {
            for (String line : lines) {
                String[] f = line.trim().split("\\s+");
                int id = Integer.parseInt(f[1]);
                byte[] data = inputs.computeIfAbsent(f[2], p -> read(dir.resolve(p)));
                byte[] result = switch (f[0]) {
                    case "blosc" -> {
                        byte[] buffer = BloscEncoder.compress(data, Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                                Integer.parseInt(f[5]), Integer.parseInt(f[6]), BloscEncoder.compressor(f[7]));
                        if (!Arrays.equals(data, BloscDecoder.decompress(buffer))) {
                            throw new AssertionError("Falcon's decoder does not read back case " + id);
                        }
                        yield buffer;
                    }
                    case "lz4" -> Lz4.compress(data, 0, data.length, Integer.parseInt(f[3]));
                    case "lz4hc" -> Lz4.compressHc(data, 0, data.length, Integer.parseInt(f[3]));
                    default -> throw new IllegalArgumentException(line);
                };
                out.writeInt(id);
                out.writeInt(result.length);
                out.write(result);
            }
        }
        System.err.printf("Falcon wrote %d cases in %.1f s%n", lines.size(), (System.nanoTime() - start) / 1e9);
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
