package com.ebremer.falcon.zarr.store;

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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link S3Store} against {@link FakeS3}, an in-process S3 that checks every signature from the request it
 * received. That the signer agrees with AWS's is {@link SigV4Test}'s part; this checks that what the store
 * signs is what it sends, and everything else the store does.
 */
class S3StoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    private FakeS3 s3;

    @BeforeEach
    void start() throws IOException {
        s3 = new FakeS3("bucket");
    }

    @AfterEach
    void stop() {
        s3.close();
    }

    private S3Store.Builder builder() {
        return S3Store.builder("bucket").endpoint(s3.endpoint()).credentials(FakeS3.ACCESS_KEY, FakeS3.SECRET)
                .clock(CLOCK).retryDelayMillis(1);
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
        assertTrue(s3.requests.stream().anyMatch(r -> r.endsWith("/sharded/c/0 bytes=-68")), s3.requests.toString());
        assertTrue(s3.requests.stream().anyMatch(r -> r.contains("/sharded/c/0 bytes=") && !r.endsWith("=-68")),
                s3.requests.toString());
        assertTrue(s3.requests.stream().noneMatch(r -> r.endsWith("/sharded/c/0")), s3.requests.toString());
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void readsWritesRangesAndDeletes() {
        S3Store store = builder().build();
        String[] keys = {"a b", "50%", "x+y=z", "caf\u00E9/\u4E2D", "q?x#y", "t~i_l-d.e"};
        for (String key : keys) {
            store.set(key, bytes("value of " + key));
        }
        for (String key : keys) {
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

        assertTrue(store.get("missing").isEmpty());
        assertTrue(store.getRange("missing", 0, 4).isEmpty());
        assertTrue(store.getSuffix("missing", 4).isEmpty());
        assertTrue(store.size("missing").isEmpty());
        assertFalse(store.exists("missing"));

        store.delete("a b");
        store.delete("a b"); // deleting what is absent is not an error
        assertTrue(store.get("a b").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.get("/bad"));
        assertThrows(IllegalArgumentException.class, () -> store.getRange("k", -1, 4));
        assertEquals(List.of(), s3.refusals);
    }

    /**
     * Listings come a page at a time (here three entries a page), in S3's order (UTF-8 bytes), with keys
     * URL-encoded; the store returns every valid key, in Java's order, relative to its prefix.
     */
    @Test
    void listingsPageDecodeAndSort() {
        // S3 sorts by UTF-8 bytes, so U+FF5E (EF BD 9E) comes before an emoji (F0 9F ...); Java sorts by
        // UTF-16 units, the emoji (D83D) first
        List<String> keys = List.of("a b", "x+y", "50%", "caf\u00E9", "\uFF5E", "\uD83D\uDE00", "emoji\uD83D\uDE00",
                "q&r<s>\"'", "dir/x", "dir/y", "dir/sub/z", "folder/", "bad//key", "zarr.json");
        for (String key : keys) {
            s3.objects.put("root/" + key, bytes(key));
        }
        s3.objects.put("rootless", bytes("not under the prefix"));
        s3.objects.put("other/k", bytes("x"));
        s3.pageSize = 3;
        S3Store store = builder().prefix("root").build();

        TreeSet<String> valid = new TreeSet<>(List.of("a b", "x+y", "50%", "caf\u00E9", "\uFF5E", "\uD83D\uDE00",
                "emoji\uD83D\uDE00", "q&r<s>\"'", "dir/x", "dir/y", "dir/sub/z", "zarr.json"));
        assertEquals("\uFF5E", valid.last());
        for (boolean encode : new boolean[] {true, false}) {
            s3.encodeKeys = encode;
            s3.requests.clear();
            assertEquals(List.copyOf(valid), store.list(), "encoded " + encode);
            assertTrue(s3.requests.size() >= 4, "pages: " + s3.requests);
            assertEquals(List.of("dir/sub/z", "dir/x", "dir/y"), store.listPrefix("dir/"));
            assertEquals(List.of("dir/sub/z"), store.listPrefix("dir/s"));
            assertEquals(List.of("50%", "a b", "bad/", "caf\u00E9", "dir/", "emoji\uD83D\uDE00", "folder/",
                    "q&r<s>\"'", "x+y", "zarr.json", "\uD83D\uDE00", "\uFF5E"), store.listDir(""));
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
        S3Store store = S3Store.builder("bucket").endpoint(s3.endpoint()).build();
        assertFalse(store.isWritable());
        assertArrayEquals(bytes("{}"), store.get("zarr.json").orElseThrow());
        assertEquals(List.of("-"), s3.authorizations);
        assertThrows(UnsupportedOperationException.class, () -> store.set("k", new byte[1]));
        assertThrows(UnsupportedOperationException.class, () -> store.delete("k"));

        s3.allowAnonymous = false;
        ZarrException e = assertThrows(ZarrException.class, () -> store.get("zarr.json"));
        assertTrue(e.getMessage().contains("anonymous"), e.getMessage());

        S3Store readOnly = builder().readOnly().build();
        assertFalse(readOnly.isWritable());
        assertThrows(UnsupportedOperationException.class, () -> readOnly.set("k", new byte[1]));
    }

    @Test
    void aWrongSecretOrRegionIsRefused() {
        S3Store wrongSecret = S3Store.builder("bucket").endpoint(s3.endpoint())
                .credentials(FakeS3.ACCESS_KEY, "not the secret").clock(CLOCK).build();
        ZarrException e = assertThrows(ZarrException.class, () -> wrongSecret.get("k"));
        assertTrue(e.getMessage().contains("SignatureDoesNotMatch"), e.getMessage());

        S3Store wrongRegion = builder().region("eu-west-1").build();
        assertThrows(ZarrException.class, () -> wrongRegion.get("k"));
    }

    @Test
    void aSessionTokenIsSentAndSigned() {
        s3.sessionToken = "FQoGZXIvYXdzEXAMPLETOKEN//+=";
        s3.objects.put("k", bytes("v"));
        S3Store withToken = builder().credentials(FakeS3.ACCESS_KEY, FakeS3.SECRET, s3.sessionToken).build();
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
        assertTrue(e.getMessage().contains("region(\"eu-west-1\")"), e.getMessage());
        assertEquals(1, s3.requests.size());
        assertTrue(assertThrows(ZarrException.class, () -> store.size("k")).getMessage().contains("eu-west-1"));
        assertTrue(assertThrows(ZarrException.class, store::list).getMessage().contains("eu-west-1"));
    }

    @Test
    void serverErrorsAreRetried() {
        s3.objects.put("k", bytes("v"));
        s3.failNext.set(2);
        S3Store store = builder().build();
        assertArrayEquals(bytes("v"), store.get("k").orElseThrow());
        assertEquals(3, s3.requests.size());

        s3.failNext.set(2);
        store.set("w", bytes("written"));
        assertArrayEquals(bytes("written"), s3.objects.get("w"));

        s3.failNext.set(3);
        S3Store once = builder().maxRetries(1).build();
        ZarrException e = assertThrows(ZarrException.class, () -> once.get("k"));
        assertTrue(e.getMessage().contains("503 SlowDown"), e.getMessage());

        S3Store refused = builder().endpoint("http://127.0.0.1:1").maxRetries(1).build();
        assertThrows(ZarrException.class, () -> refused.get("k")); // nothing listens there
    }

    @Test
    void parallelBlockReadsShareTheStore() {
        S3Store store = builder().build();
        int[] values = IntStream.range(0, 4096).toArray();
        Zarr.createArray(store, ArraySpec.builder(new long[] {4096}, DataType.INT32).chunkShape(64).zstd().build())
                .writeInts(values);
        ZarrArray array = Zarr.openArray(store);
        List<int[]> blocks = array.blocks().parallel().map(Selection::readInts).toList();
        int[] all = blocks.stream().flatMapToInt(java.util.Arrays::stream).toArray();
        assertArrayEquals(values, all);
        assertEquals(List.of(), s3.refusals);
    }

    @Test
    void requestsAddressTheBucketByHostOrPath() throws Exception {
        S3Store aws = S3Store.builder("my-bucket").region("eu-west-1").build();
        assertEquals("https://my-bucket.s3.eu-west-1.amazonaws.com/a%20b",
                aws.uri(aws.objectPath("a b"), "").toString());
        S3Store dotted = S3Store.builder("my.bucket").region("eu-west-1").build();
        assertEquals("https://s3.eu-west-1.amazonaws.com/my.bucket/a", dotted.uri(dotted.objectPath("a"), "").toString());
        S3Store minio = S3Store.builder("bucket").endpoint("http://127.0.0.1:9000/").prefix("p").build();
        assertEquals("http://127.0.0.1:9000/bucket/p/k", minio.uri(minio.objectPath("k"), "").toString());
        S3Store gcs = S3Store.builder("bucket").endpoint("https://storage.googleapis.com").pathStyle(false).build();
        assertEquals("https://bucket.storage.googleapis.com/k", gcs.uri(gcs.objectPath("k"), "").toString());
        S3Store gateway = S3Store.builder("bucket").endpoint("https://host/gw/").build();
        assertEquals("https://host/gw/bucket/k", gateway.uri(gateway.objectPath("k"), "").toString());
        assertThrows(IllegalArgumentException.class,
                () -> S3Store.builder("my.bucket").pathStyle(false).build());

        assertEquals("h", S3Store.hostHeader(URI.create("https://h:443/x").toURL()));
        assertEquals("h", S3Store.hostHeader(URI.create("https://h/x").toURL()));
        assertEquals("h:9000", S3Store.hostHeader(URI.create("http://h:9000/x").toURL()));
        assertEquals("[::1]:9000", S3Store.hostHeader(URI.create("http://[::1]:9000/x").toURL()));
    }

    @Test
    void theBuildersCheckTheirSettings() {
        for (String bucket : new String[] {"", "a/b", "a b", "x".repeat(256)}) {
            assertThrows(IllegalArgumentException.class, () -> S3Store.builder(bucket), bucket);
        }
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").region(""));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").region("us east"));
        for (String endpoint : new String[] {"ftp://h", "h:9000", "http://h?x=1", "http://u@h", "/path", "not a url"}) {
            assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").endpoint(endpoint), endpoint);
        }
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").prefix("a/../b"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").prefix("a//b"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").credentials("", "s"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").credentials("k", "s", "t\r\nx"));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").maxRetries(-1));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").maxRetries(11));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").timeoutMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> S3Store.builder("b").missingStatuses(200));

        S3Store fromUrl = S3Store.fromUrl("s3://my-bucket/data/image.zarr").build();
        assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com/data/image.zarr/zarr.json",
                fromUrl.uri(fromUrl.objectPath("zarr.json"), "").toString());
        assertEquals("s3://my-bucket/data/image.zarr (https://my-bucket.s3.us-east-1.amazonaws.com, us-east-1)",
                fromUrl.toString());
        S3Store top = S3Store.fromUrl("S3://my-bucket").build();
        assertEquals("/zarr.json", top.objectPath("zarr.json"));
        for (String url : new String[] {"http://b/x", "s3://", "s3:///x", "s3://b/x?y", "s3://b/a/../c"}) {
            assertThrows(IllegalArgumentException.class, () -> S3Store.fromUrl(url), url);
        }
    }

    @Test
    void credentialsComeFromTheEnvironment() {
        Map<String, String> env = Map.of("AWS_ACCESS_KEY_ID", FakeS3.ACCESS_KEY, "AWS_SECRET_ACCESS_KEY", FakeS3.SECRET,
                "AWS_REGION", "eu-west-1");
        S3Store store = S3Store.builder("bucket").endpoint(s3.endpoint()).fromEnvironment(env::get).clock(CLOCK).build();
        assertTrue(store.isWritable());
        assertTrue(store.toString().contains("eu-west-1"), store.toString());
        s3.region = "eu-west-1";
        store.set("k", bytes("v"));
        assertArrayEquals(bytes("v"), store.get("k").orElseThrow());

        // an explicit region wins; AWS_DEFAULT_REGION is the fallback
        assertTrue(S3Store.builder("b").region("ap-south-1").fromEnvironment(env::get).build().toString()
                .contains("ap-south-1"));
        Map<String, String> fallback = Map.of("AWS_ACCESS_KEY_ID", "k", "AWS_SECRET_ACCESS_KEY", "s",
                "AWS_DEFAULT_REGION", "sa-east-1");
        assertTrue(S3Store.builder("b").fromEnvironment(fallback::get).build().toString().contains("sa-east-1"));
        assertThrows(IllegalStateException.class,
                () -> S3Store.builder("b").fromEnvironment(Map.of("AWS_ACCESS_KEY_ID", "k")::get));
        assertFalse(S3Store.builder("b").fromEnvironment(env::get).anonymous().build().isWritable());
    }

    @Test
    void theDescriptionNeverHoldsTheSecret() {
        S3Store store = builder().credentials("AKIDEXAMPLE", "secret-value", "token-value").build();
        assertFalse(store.toString().contains("secret-value"));
        assertFalse(store.toString().contains("token-value"));
        ZarrException e = assertThrows(ZarrException.class, () -> store.get("k")); // the fake refuses the signature
        assertFalse(e.getMessage().contains("secret-value"), e.getMessage());
    }
}
