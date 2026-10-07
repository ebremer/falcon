package com.ebremer.falcon.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Selection;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@link S3Store} against {@link FakeS3}, an in-process S3 that checks every signature the AWS SDK sends
 * from the request it received.
 */
class S3StoreTest {

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

    private S3Store.Builder builder() {
        return S3Store.builder(clients.signed(), "bucket");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aZarrHierarchyRoundTripsUnderAPrefix() {
        S3Store store = builder().prefix("/data/image.zarr/").build();
        assertTrue(store.isWritable());
        ZarrGroup root = Zarr.createGroup(store);
        int[] values = IntStream.range(0, 64).toArray();
        root.createArray("plain", ArraySpec.builder(new long[] {64}, DataType.INT32).chunkShape(16).gzip(5).build())
                .writeInts(values);
        root.createArray("sharded", ArraySpec.builder(new long[] {64}, DataType.INT32).chunkShape(32).sharding(8).build())
                .writeInts(values);
        root.createGroup("sub").createArray("a b", ArraySpec.builder(new long[] {4}, DataType.INT32).build())
                .writeInts(new int[] {4, 3, 2, 1});

        assertTrue(s3.objects.containsKey("data/image.zarr/zarr.json"), s3.objects.keySet().toString());
        assertTrue(s3.objects.containsKey("data/image.zarr/sub/a b/c/0"), s3.objects.keySet().toString());

        ZarrGroup read = Zarr.openGroup(store);
        assertEquals(List.of("plain", "sharded", "sub"), read.childNames());
        assertArrayEquals(values, read.array("plain").readInts());
        assertArrayEquals(values, read.array("sharded").readInts());
        assertArrayEquals(new int[] {4, 3, 2, 1}, read.group("sub").array("a b").readInts());

        s3.requests.clear();
        Selection part = read.array("sharded").select(new long[] {10}, new long[] {4});
        assertArrayEquals(new int[] {10, 11, 12, 13}, part.readInts());
        // the shard index from the end of the shard, then one sub-chunk: no whole shard
        List<String> shard = clients.gets(s3, "/bucket/data/image.zarr/sharded/c/0");
        assertTrue(shard.contains("bytes=-68"), s3.requests.toString());
        assertTrue(shard.stream().anyMatch(r -> r.startsWith("bytes=") && !r.equals("bytes=-68")), s3.requests.toString());
        assertFalse(shard.contains(""), s3.requests.toString());
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void readsWritesRangesAndDeletes() {
        S3Store store = builder().build();
        String[] keys = {"a b", "50%", "x+y=z", "café/中", "q?x#y", "t~i_l-d.e"};
        for (String key : keys) {
            store.set(key, bytes("value of " + key));
        }
        for (String key : keys) {
            assertArrayEquals(bytes("value of " + key), s3.objects.get(key), key);
            assertArrayEquals(bytes("value of " + key), store.get(key).orElseThrow(), key);
            assertEquals(OptionalLong.of(bytes("value of " + key).length), store.size(key));
            assertTrue(store.exists(key));
            assertArrayEquals(bytes("value"), store.getRange(key, 0, 5).orElseThrow());
            assertArrayEquals(bytes(key), store.getSuffix(key, bytes(key).length).orElseThrow());
        }
        assertArrayEquals(new byte[0], store.getRange("a b", 1000, 5).orElseThrow());
        assertArrayEquals(new byte[0], store.getRange("a b", 2, 0).orElseThrow());
        assertArrayEquals(bytes("value of a b"), store.getSuffix("a b", 1000).orElseThrow());

        store.set("empty", new byte[0]);
        assertArrayEquals(new byte[0], store.get("empty").orElseThrow());
        assertArrayEquals(new byte[0], store.getSuffix("empty", 5).orElseThrow());
        assertArrayEquals(new byte[0], store.getRange("empty", 0, 5).orElseThrow());
        assertEquals(OptionalLong.of(0), store.size("empty"));

        byte[] large = new byte[3 << 20];
        new Random(7).nextBytes(large);
        store.set("large", large);
        assertArrayEquals(large, s3.objects.get("large"));
        assertArrayEquals(large, store.get("large").orElseThrow());
        assertArrayEquals(Arrays.copyOfRange(large, 1 << 20, (1 << 20) + 1000),
                store.getRange("large", 1 << 20, 1000).orElseThrow());

        assertTrue(store.get("missing").isEmpty());
        assertTrue(store.getRange("missing", 0, 4).isEmpty());
        assertTrue(store.getRange("missing", 0, 0).isEmpty());
        assertTrue(store.getSuffix("missing", 4).isEmpty());
        assertTrue(store.size("missing").isEmpty());
        assertFalse(store.exists("missing"));

        store.delete("a b");
        store.delete("a b"); // deleting what is absent is not an error
        assertTrue(store.get("a b").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.get("/bad"));
        assertThrows(IllegalArgumentException.class, () -> store.getRange("k", -1, 4));
        assertThrows(IllegalArgumentException.class, () -> store.getSuffix("k", -1));
        assertEquals(List.of(), s3.refusals);
    }

    /**
     * Listings come a page at a time (here three entries a page), in S3's order (UTF-8 bytes), with keys
     * URL-encoded or not; the store returns every valid key, in Java's order, relative to its prefix.
     */
    @Test
    void listingsPageDecodeAndSort() {
        // S3 sorts by UTF-8 bytes, so U+FF5E (EF BD 9E) comes before an emoji (F0 9F ...); Java sorts by
        // UTF-16 units, the emoji (D83D) first
        List<String> keys = List.of("a b", "x+y", "50%", "café", "～", "😀", "emoji😀",
                "q&r<s>\"'", "dir/x", "dir/y", "dir/sub/z", "folder/", "bad//key", "zarr.json");
        for (String key : keys) {
            s3.objects.put("root/" + key, bytes(key));
        }
        s3.objects.put("rootless", bytes("not under the prefix"));
        s3.objects.put("other/k", bytes("x"));
        s3.pageSize = 3;
        S3Store store = builder().prefix("root").build();

        TreeSet<String> valid = new TreeSet<>(List.of("a b", "x+y", "50%", "café", "～", "😀",
                "emoji😀", "q&r<s>\"'", "dir/x", "dir/y", "dir/sub/z", "zarr.json"));
        assertEquals("～", valid.last());
        for (boolean encode : new boolean[] {true, false}) {
            s3.encodeKeys = encode;
            s3.requests.clear();
            assertEquals(List.copyOf(valid), store.list(), "encoded " + encode);
            assertTrue(s3.requests.size() >= 4, "pages: " + s3.requests);
            assertEquals(List.of("dir/sub/z", "dir/x", "dir/y"), store.listPrefix("dir/"));
            assertEquals(List.of("dir/sub/z"), store.listPrefix("dir/s"));
            assertEquals(List.of("50%", "a b", "bad/", "café", "dir/", "emoji😀", "folder/",
                    "q&r<s>\"'", "x+y", "zarr.json", "😀", "～"), store.listDir(""));
            assertEquals(List.of("dir/sub/", "dir/x", "dir/y"), store.listDir("dir"));
            assertEquals(List.of("dir/sub/z"), store.listDir("dir/sub/"));
            assertEquals(List.of(), store.listDir("nothing/"));
        }
        assertTrue(s3.requests.get(0).contains("list-type=2"), s3.requests.get(0));
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void aBucketThatDeniesAbsentKeysIsReadWithMissingStatuses() {
        s3.absentStatus = 403;
        S3Store strict = builder().build();
        ZarrException e = assertThrows(ZarrException.class, () -> strict.get("missing"));
        assertTrue(e.getMessage().contains("HTTP 403 AccessDenied: Access Denied"), e.getMessage());
        assertThrows(ZarrException.class, () -> strict.exists("missing"));

        S3Store lenient = builder().missingStatuses(404, 403).build();
        assertTrue(lenient.get("missing").isEmpty());
        assertFalse(lenient.exists("missing"));
        assertTrue(lenient.getSuffix("missing", 8).isEmpty());
    }

    @Test
    void anAnonymousStoreSendsNoSignatureAndIsReadOnly() {
        s3.objects.put("zarr.json", bytes("{}"));
        s3.allowAnonymous = true;
        S3Store store = S3Store.builder(clients.client(AnonymousCredentialsProvider.create(), "us-east-1", 3), "bucket")
                .build();
        assertFalse(store.isWritable());
        assertArrayEquals(bytes("{}"), store.get("zarr.json").orElseThrow());
        assertEquals(List.of("-"), s3.authorizations);
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> store.set("k", new byte[1]))
                .getMessage().contains("anonymous"));
        assertThrows(UnsupportedOperationException.class, () -> store.delete("k"));

        s3.allowAnonymous = false;
        ZarrException e = assertThrows(ZarrException.class, () -> store.get("zarr.json"));
        assertTrue(e.getMessage().contains("anonymous"), e.getMessage());

        S3Store readOnly = builder().readOnly().build();
        assertFalse(readOnly.isWritable());
        assertThrows(UnsupportedOperationException.class, () -> readOnly.set("k", new byte[1]));
        assertTrue(s3.objects.keySet().equals(java.util.Set.of("zarr.json")), s3.objects.keySet().toString());
    }

    @Test
    void aWrongSecretOrRegionIsRefused() {
        S3Store wrongSecret = S3Store.builder(clients.client(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(FakeS3.ACCESS_KEY, "not the secret")), "us-east-1", 1), "bucket").build();
        ZarrException e = assertThrows(ZarrException.class, () -> wrongSecret.get("k"));
        assertTrue(e.getMessage().contains("SignatureDoesNotMatch"), e.getMessage());

        S3Store wrongRegion = S3Store.builder(clients.client(Clients.CREDENTIALS, "eu-west-1", 1), "bucket").build();
        assertThrows(ZarrException.class, () -> wrongRegion.get("k"));
    }

    @Test
    void aSessionTokenIsSentAndSigned() {
        s3.sessionToken = "FQoGZXIvYXdzEXAMPLETOKEN//+=";
        s3.objects.put("k", bytes("v"));
        S3Store withToken = S3Store.builder(clients.client(StaticCredentialsProvider.create(
                AwsSessionCredentials.create(FakeS3.ACCESS_KEY, FakeS3.SECRET, s3.sessionToken)), "us-east-1", 1),
                "bucket").build();
        assertArrayEquals(bytes("v"), withToken.get("k").orElseThrow());
        assertTrue(s3.authorizations.get(0).contains("x-amz-security-token"), s3.authorizations.get(0));
        assertThrows(ZarrException.class, () -> builder().build().get("k"));
    }

    @Test
    void aBucketInAnotherRegionIsReportedNotFollowed() {
        s3.movedTo = "eu-west-1";
        S3Store store = builder().build();
        ZarrException e = assertThrows(ZarrException.class, () -> store.get("k"));
        assertTrue(e.getMessage().contains("PermanentRedirect"), e.getMessage());
        assertTrue(e.getMessage().contains("region 'eu-west-1'"), e.getMessage());
        assertEquals(1, s3.requests.size(), s3.requests.toString());
        assertTrue(assertThrows(ZarrException.class, () -> store.size("k")).getMessage().contains("eu-west-1"));
        assertTrue(assertThrows(ZarrException.class, store::list).getMessage().contains("eu-west-1"));
    }

    @Test
    void serverErrorsAreRetriedByTheClient() {
        s3.objects.put("k", bytes("v"));
        s3.failNext.set(2);
        S3Store store = builder().build();
        assertArrayEquals(bytes("v"), store.get("k").orElseThrow());
        assertEquals(3, s3.requests.size(), s3.requests.toString());

        s3.failNext.set(2);
        store.set("w", bytes("written"));
        assertArrayEquals(bytes("written"), s3.objects.get("w"));

        s3.failNext.set(3);
        S3Store once = S3Store.builder(clients.client(Clients.CREDENTIALS, "us-east-1", 2), "bucket").build();
        ZarrException e = assertThrows(ZarrException.class, () -> once.get("k"));
        assertTrue(e.getMessage().contains("503 SlowDown"), e.getMessage());

        S3Client nowhere = clients.client(Clients.CREDENTIALS, "us-east-1", 1, "http://127.0.0.1:1");
        assertThrows(ZarrException.class, () -> S3Store.builder(nowhere, "bucket").build().get("k")); // nothing listens
    }

    @Test
    void parallelBlockReadsShareTheStore() {
        S3Store store = builder().build();
        int[] values = IntStream.range(0, 4096).toArray();
        Zarr.createArray(store, ArraySpec.builder(new long[] {4096}, DataType.INT32).chunkShape(64).zstd().build())
                .writeInts(values);
        ZarrArray array = Zarr.openArray(store);
        List<int[]> blocks = array.blocks().parallel().map(Selection::readInts).toList();
        int[] all = blocks.stream().flatMapToInt(Arrays::stream).toArray();
        assertArrayEquals(values, all);
        assertEquals(List.of(), s3.refusals);
    }

    /**
     * The SDK checksums what it writes (a CRC-32 trailer on an {@code aws-chunked} body), which some
     * S3-compatible stores refuse; a client built for them computes checksums only where S3 requires them.
     */
    @Test
    void aClientForOtherStoresWritesWithoutTheChecksumTrailer() {
        S3Store checksummed = builder().build();
        checksummed.set("a", bytes("with a trailer"));
        assertEquals(List.of("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER"), s3.putPayloads);

        s3.putPayloads.clear();
        S3Client plain = clients.client(Clients.CREDENTIALS, "us-east-1", 3,
                b -> b.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED));
        S3Store store = S3Store.builder(plain, "bucket").build();
        store.set("b", bytes("without one"));
        assertTrue(s3.putPayloads.stream().noneMatch(p -> p.endsWith("TRAILER")), s3.putPayloads.toString());
        assertArrayEquals(bytes("without one"), store.get("b").orElseThrow());
        assertArrayEquals(bytes("with a trailer"), store.get("a").orElseThrow());
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void theBuildersCheckTheirSettings() {
        S3Client client = clients.signed();
        for (String bucket : new String[] {"", "a/b", "a b", "x".repeat(256)}) {
            assertThrows(IllegalArgumentException.class, () -> S3Store.builder(client, bucket), bucket);
        }
        assertThrows(NullPointerException.class, () -> S3Store.builder(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder(client, "b").prefix("a/../b"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder(client, "b").prefix("a//b"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder(client, "b").missingStatuses(200));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder(client, "b").missingStatuses());

        S3Store fromUrl = S3Store.fromUrl(client, "s3://my-bucket/data/image.zarr/").build();
        assertEquals("s3://my-bucket/data/image.zarr (" + s3.endpoint() + ", us-east-1)", fromUrl.toString());
        assertEquals("s3://my-bucket/ (" + s3.endpoint() + ", us-east-1)",
                S3Store.fromUrl(client, "S3://my-bucket").build().toString());
        for (String url : new String[] {"http://b/x", "s3://", "s3:///x", "s3://b/x?y", "s3://b/x#y", "s3://b/a/../c"}) {
            assertThrows(IllegalArgumentException.class, () -> S3Store.fromUrl(client, url), url);
        }
    }

    @Test
    void theDescriptionNeverHoldsTheSecret() {
        S3Store store = S3Store.builder(clients.client(StaticCredentialsProvider.create(
                AwsSessionCredentials.create("AKIDEXAMPLE", "secret-value", "token-value")), "us-east-1", 1),
                "bucket").build();
        assertFalse(store.toString().contains("secret-value"));
        assertFalse(store.toString().contains("token-value"));
        ZarrException e = assertThrows(ZarrException.class, () -> store.get("k")); // the fake refuses the signature
        assertFalse(e.getMessage().contains("secret-value"), e.getMessage());
        assertFalse(e.getMessage().contains("token-value"), e.getMessage());
    }
}
