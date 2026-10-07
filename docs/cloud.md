---
title: S3 and HTTP
description: Read and write HDF5 and Zarr in Amazon S3, S3-compatible storage, and over HTTP with Falcon.
---

Falcon reads remote data a byte range at a time: opening a remote HDF5 file or Zarr store and reading a small
part of it fetches the metadata and the chunks that part needs, not the whole file.

| Where | Zarr | HDF5 |
|---|---|---|
| HTTP(S), public or with a token | `HttpStore` (the `zarr` module), read-only | your own `RangeReader` (below) |
| Amazon S3 and S3-compatible storage | `S3Store` (the `s3` module), read and write | `S3RangeReader` (the `s3` module), read |
| From the shell | `falcon ... https://...` or `s3://...` | the same |

## HTTP

### Read a Zarr store over HTTP

```java
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.store.HttpStore;

ZarrGroup root = Zarr.openGroup(HttpStore.openReadOnly("https://example.org/data/scan.zarr"));
double[] tile = root.array("image").select(new long[] {0, 0}, new long[] {256, 256}).readDoubles();
```

HTTP cannot list a directory, so a group's children cannot be *listed* over plain HTTP; a named child still
opens, and a group with consolidated metadata lists its children from it. Other options, on
`HttpStore.builder(url)`:

```java
HttpStore store = HttpStore.builder("https://example.org/data/scan.zarr")
        .header("Authorization", "Bearer " + token)    // sent only to that host
        .directoryListing(true)                        // read a static server's index pages as listings
        .missingStatuses(404, 403)                     // a public bucket that answers 403 for absent keys
        .build();
```

A presigned or SAS URL keeps its query on every request.

### Read an HDF5 file over HTTP

`Hdf5File.open(RangeReader)` reads from anywhere that serves byte ranges. A `RangeReader` has two methods; one
over the JDK's HTTP client:

```java
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.RangeReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;

final class HttpRangeReader implements RangeReader {
    private final HttpClient client = HttpClient.newHttpClient();
    private final URI uri;
    private final long size;

    HttpRangeReader(URI uri) throws IOException, InterruptedException {
        this.uri = uri;
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        this.size = head.headers().firstValueAsLong("Content-Length").orElseThrow();
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void read(long position, ByteBuffer destination) throws IOException {
        long last = position + destination.remaining() - 1;
        HttpRequest request = HttpRequest.newBuilder(uri).header("Range", "bytes=" + position + "-" + last).build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 206 || response.body().length != destination.remaining()) {
                throw new IOException("HTTP " + response.statusCode() + " for bytes " + position + "-" + last);
            }
            destination.put(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}

try (Hdf5File h5 = Hdf5File.open(new HttpRangeReader(URI.create("https://example.org/data/scan.h5")))) {
    double[] slab = h5.root().dataset("image").select(new long[] {0, 0}, new long[] {64, 64}).readDoubles();
}
```

Falcon calls `read` from several threads at once, which `HttpClient` allows. The `falcon` command has such a
reader built in, which also pins the file's ETag so that a file replaced mid-read fails rather than mixing
two files' bytes.

## Amazon S3

### Add the s3 module

The `s3` module is the one library module with dependencies: the AWS SDK for Java 2.x (its S3 client and its
URL-connection HTTP client). Add it beside the format you use:

```xml
<dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>s3</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>zarr</artifactId>       <!-- and/or hdf5 -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Make a client

Falcon takes an `S3Client` you build and close, so all of the SDK's settings apply:

```java
import software.amazon.awssdk.services.s3.S3Client;

try (S3Client s3 = S3Client.create()) {   // the SDK's default region and credential chains
    // ...
}
```

- **Credentials** come from the SDK's chain: environment variables (`AWS_ACCESS_KEY_ID`, ...), `~/.aws`
  profiles and SSO, and container and instance roles.
- **Region:** `S3Client.builder().region(Region.US_EAST_1)`, or `crossRegionAccessEnabled(true)` to follow a
  bucket to its region.
- **A public bucket:** `credentialsProvider(AnonymousCredentialsProvider.create())`.
- **S3-compatible storage** (MinIO, Cloudflare R2, Google Cloud Storage with HMAC keys):

```java
S3Client minio = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:9000"))
        .forcePathStyle(true)
        .region(Region.of("us-east-1"))                 // R2 and GCS: Region.of("auto")
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
        .build();
```

### Read and write Zarr in S3

```java
import com.ebremer.falcon.s3.S3Store;

try (S3Client s3 = S3Client.create()) {
    Store store = S3Store.fromUrl(s3, "s3://my-bucket/data/image.zarr").build();
    ZarrGroup root = Zarr.openGroup(store);
    double[] values = root.array("image").readDoubles();

    ZarrArray out = Zarr.createArray(S3Store.fromUrl(s3, "s3://my-bucket/out.zarr").build(),
            ArraySpec.builder(new long[] {1000}, DataType.FLOAT64).chunkShape(100).zstd().build());
    out.writeDoubles(values2);
}
```

`S3Store` reads byte ranges (a sharded array reads only the sub-chunks it needs), lists groups with the `/`
delimiter, and writes each key with one request. `readOnly()` refuses writes; `missingStatuses(404, 403)`
reads a bucket that may be read but not listed.

### Read HDF5 from S3

```java
import com.ebremer.falcon.s3.S3RangeReader;

try (S3Client s3 = S3Client.create();
     Hdf5File h5 = Hdf5File.open(S3RangeReader.open(s3, "s3://my-bucket/data/scan.h5"))) {
    double[] slab = h5.root().dataset("image").select(new long[] {100, 0}, new long[] {50, 200}).readDoubles();
}
```

A file that names others (external links, virtual datasets) opens them from beside it with
`reader.siblings()`:

```java
S3RangeReader reader = S3RangeReader.open(s3, "s3://my-bucket/data/vds.h5");
OpenOptions options = OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(reader.siblings()));
try (Hdf5File h5 = Hdf5File.open(reader, options)) { /* ... */ }
```

S3 cannot change part of an object, so HDF5 files are not written to S3 in place: write the file locally with
`Hdf5Writer`, and upload it.

### Logging

The SDK logs through SLF4J 1.7. Without a binding on the class path SLF4J prints a short notice once; add one
(`slf4j-simple`, Logback 1.2) to see the SDK's logs. The `falcon` command silences it.

## From the shell

The `falcon` command takes `http(s)://` and `s3://` sources (and `s3://` targets) anywhere it takes a path:

```bash
falcon ls -r s3://my-bucket/data/scan.zarr
falcon info https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr
falcon convert scan.h5 s3://my-bucket/scan.zarr
falcon ls --s3-anonymous s3://public-bucket/image.zarr
falcon ls --s3-endpoint http://localhost:9000 s3://bucket/data.zarr
```

| Option | |
|---|---|
| `--s3-region R` | the region to send requests to first |
| `--s3-profile P` | the `~/.aws` profile to use |
| `--s3-endpoint URL` | S3-compatible storage |
| `--s3-anonymous`, `--no-sign-request` | a public bucket |
| `--header 'Name: value'` | an HTTP header (repeatable), such as a bearer token |
| `--http-listing` | read a static web server's directory pages as listings |
