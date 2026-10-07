package com.ebremer.falcon.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.ExternalFileAccess;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.Hdf5Writer;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.OpenOptions;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

/** {@link S3RangeReader} against {@link FakeS3}, reading HDF5 files Falcon wrote. */
class S3RangeReaderTest {

    @TempDir
    Path dir;

    private FakeS3 s3;
    private Clients clients;

    @BeforeEach
    void start() throws IOException {
        s3 = new FakeS3("bucket");
        clients = new Clients(s3);
    }

    @AfterEach
    void stop() {
        clients.close();
        s3.close();
    }

    /** Writes an HDF5 file with {@code build} and puts it in the fake bucket under {@code key}. */
    private byte[] upload(String key, Consumer<Hdf5Writer> build) throws IOException {
        Path file = dir.resolve(key.replace('/', '_'));
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            build.accept(w);
        }
        byte[] bytes = Files.readAllBytes(file);
        s3.objects.put(key, bytes);
        return bytes;
    }

    @Test
    void anHdf5FileIsReadARangeAtATime() throws IOException {
        int[] big = IntStream.range(0, 1 << 20).map(i -> i * 7).toArray(); // 4 MiB in 64 KiB chunks
        byte[] file = upload("data/scan.h5", w -> {
            w.intDataset("counts", new int[] {10, 20, 30}, new long[] {3}).intAttribute("scale", new int[] {100}, new long[] {});
            w.group("run").intChunkedDataset("big", big, new long[] {big.length}, new long[] {1 << 14});
        });
        assertTrue(file.length > 4 << 20, "file of " + file.length);
        S3Client client = clients.signed();

        S3RangeReader reader = S3RangeReader.open(client, "s3://bucket/data/scan.h5");
        assertEquals(file.length, reader.size());
        try (Hdf5File h5 = Hdf5File.open(reader)) {
            assertArrayEquals(new int[] {10, 20, 30}, h5.root().dataset("counts").readInts());
            assertArrayEquals(new int[] {100}, h5.root().dataset("counts").attribute("scale").orElseThrow().readInts());
            int[] part = h5.root().dataset("run/big").select(new long[] {500_000}, new long[] {10}).readInts();
            assertArrayEquals(Arrays.copyOfRange(big, 500_000, 500_010), part);
        }

        // one HEAD, then only ranges, each pinned to the object's ETag; far less than the file
        assertEquals(1, s3.requests.stream().filter(r -> r.startsWith("HEAD ")).count(), s3.requests.toString());
        List<String> ranges = clients.gets(s3, "/bucket/data/scan.h5");
        assertTrue(!ranges.isEmpty() && ranges.stream().allMatch(r -> r.startsWith("bytes=")), ranges.toString());
        long fetched = ranges.stream().mapToLong(r -> {
            String[] span = r.substring("bytes=".length()).split("-");
            return Long.parseLong(span[1]) - Long.parseLong(span[0]) + 1;
        }).sum();
        assertTrue(fetched < file.length / 8, fetched + " of " + file.length + " bytes: " + ranges);
        assertTrue(s3.ifMatches.stream().allMatch(FakeS3.eTag(file)::equals), s3.ifMatches.toString());
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void anObjectReplacedWhileOpenFailsItsNextRead() throws IOException {
        upload("f.h5", w -> w.intDataset("x", new int[] {1, 2, 3}, new long[] {3}));
        S3RangeReader reader = S3RangeReader.open(clients.signed(), "bucket", "f.h5");
        ByteBuffer first = ByteBuffer.allocate(8);
        reader.read(0, first);
        assertArrayEquals(Arrays.copyOf(s3.objects.get("f.h5"), 8), first.array());

        upload("f.h5", w -> w.intDataset("x", new int[] {4, 5, 6}, new long[] {3}));
        IOException e = assertThrows(IOException.class, () -> reader.read(0, ByteBuffer.allocate(8)));
        assertTrue(e.getMessage().contains("changed since it was opened"), e.getMessage());
    }

    @Test
    void readsCheckTheirBoundsAndFailuresSayWhy() throws IOException {
        s3.objects.put("ten", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        S3Client client = clients.signed();
        S3RangeReader reader = S3RangeReader.open(client, "bucket", "ten");
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        reader.read(6, direct);
        assertEquals(List.of((byte) 6, (byte) 7, (byte) 8, (byte) 9),
                List.of(direct.get(0), direct.get(1), direct.get(2), direct.get(3)));
        ByteBuffer slice = ByteBuffer.allocate(10).position(3).limit(5);
        reader.read(1, slice);
        assertArrayEquals(new byte[] {0, 0, 0, 1, 2, 0, 0, 0, 0, 0}, slice.array());

        s3.requests.clear();
        reader.read(10, ByteBuffer.allocate(0));
        assertThrows(EOFException.class, () -> reader.read(8, ByteBuffer.allocate(3)));
        assertThrows(IllegalArgumentException.class, () -> reader.read(-1, ByteBuffer.allocate(1)));
        assertEquals(List.of(), s3.requests); // none of these needs a request

        assertThrows(FileNotFoundException.class, () -> S3RangeReader.open(client, "bucket", "missing"));
        assertThrows(IllegalArgumentException.class, () -> S3RangeReader.open(client, "s3://bucket"));
        assertThrows(IllegalArgumentException.class, () -> S3RangeReader.open(client, "s3://bucket/"));
        assertThrows(IllegalArgumentException.class, () -> S3RangeReader.open(client, "bad bucket", "k"));
        assertThrows(IllegalArgumentException.class, () -> S3RangeReader.open(client, "bucket", ""));
        assertTrue(reader.toString().startsWith("s3://bucket/ten ("), reader.toString());

        s3.allowAnonymous = true;
        S3Client anonymous = clients.client(AnonymousCredentialsProvider.create(), "us-east-1", 1);
        ByteBuffer two = ByteBuffer.allocate(2);
        S3RangeReader.open(anonymous, "s3://bucket/ten").read(0, two);
        assertArrayEquals(new byte[] {0, 1}, two.array());
        s3.allowAnonymous = false;
        IOException denied = assertThrows(IOException.class, () -> S3RangeReader.open(anonymous, "bucket", "ten"));
        assertTrue(denied.getMessage().contains("403"), denied.getMessage());
    }

    @Test
    void siblingsOpenTheFilesAnHdf5FileNamesFromBesideIt() throws IOException {
        upload("data/main.h5", w -> {
            w.externalLink("cal", "sub/cal.h5", "/gain");
            w.externalLink("moved", "/old/machine/path/other.h5", "/v");
            w.externalLink("escape", "../secret.h5", "/x");
            w.externalLink("absent", "nothing.h5", "/x");
        });
        upload("data/sub/cal.h5", w -> w.intDataset("gain", new int[] {1, 2, 3}, new long[] {3}));
        upload("data/other.h5", w -> w.intDataset("v", new int[] {9}, new long[] {1}));
        upload("secret.h5", w -> w.intDataset("x", new int[] {-1}, new long[] {1}));
        S3Client client = clients.signed();

        S3RangeReader reader = S3RangeReader.open(client, "s3://bucket/data/main.h5");
        OpenOptions options = OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(reader.siblings()));
        try (Hdf5File h5 = Hdf5File.open(reader, options)) {
            assertArrayEquals(new int[] {1, 2, 3}, h5.root().dataset("cal").readInts());
            assertArrayEquals(new int[] {9}, h5.root().dataset("moved").readInts());
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("escape"));
            assertTrue(h5.root().child("absent").isEmpty());
        }
        try (Hdf5File h5 = Hdf5File.open(S3RangeReader.open(client, "s3://bucket/data/main.h5"))) {
            // by default, a file read from S3 opens no other, and says how to let it
            HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class, () -> h5.root().child("cal"));
            assertTrue(e.getMessage().contains("resolvedBy"), e.getMessage());
        }
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void siblingNamesResolveInsideTheDirectory() {
        assertEquals("d/b.h5", S3RangeReader.resolve("d/", "b.h5"));
        assertEquals("d/s/b.h5", S3RangeReader.resolve("d/", "./s//b.h5"));
        assertEquals("d/b.h5", S3RangeReader.resolve("d/", "s/../b.h5"));
        assertEquals("d/b.h5", S3RangeReader.resolve("d/", "/abs/path/b.h5"));
        assertEquals("d/b.h5", S3RangeReader.resolve("d/", "C:\\data\\b.h5"));
        assertEquals("d/s/b.h5", S3RangeReader.resolve("d/", "s\\b.h5"));
        assertEquals("b.h5", S3RangeReader.resolve("", "b.h5"));
        assertNull(S3RangeReader.resolve("d/", "../b.h5"));
        assertNull(S3RangeReader.resolve("d/", "s/../../b.h5"));
        assertNull(S3RangeReader.resolve("d/", ""));
        assertNull(S3RangeReader.resolve("d/", "s/"));
        assertNull(S3RangeReader.resolve("d/", "/"));
    }
}
