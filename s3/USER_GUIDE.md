# Falcon S3 — User Guide

Falcon S3 reads Falcon's formats from **Amazon S3** and S3-compatible object storage, through the
[AWS SDK for Java 2.x](https://github.com/aws/aws-sdk-java-v2):

- **`S3Store`**, a Zarr `Store` that reads, lists, and writes the objects under a prefix of a bucket;
- **`S3RangeReader`**, an HDF5 `RangeReader` that reads an object a byte range at a time, so opening a
  large file fetches only the metadata and the chunks a read needs.

Everything below is the public API in `com.ebremer.falcon.s3`.

- [Dependencies](#dependencies)
- [The client](#the-client)
- [Zarr: `S3Store`](#zarr-s3store)
- [HDF5: `S3RangeReader`](#hdf5-s3rangereader)
- [Errors, logging, and threads](#errors-logging-and-threads)
- [Moving from Zarr's old `S3Store`](#moving-from-zarrs-old-s3store)

## Dependencies

This is Falcon's one module with runtime dependencies beyond `java.base`; `core`, `hdf5`, and `zarr`
have none, and S3 lives here so that they keep none. It brings:

| Library | Version | License |
|---|---|---|
| `software.amazon.awssdk:s3` and the SDK modules it needs | 2.55.12 | Apache-2.0 |
| `software.amazon.awssdk:url-connection-client` | 2.55.12 | Apache-2.0 |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 |
| `org.slf4j:slf4j-api` | 1.7.36 | MIT |
| `software.amazon.eventstream:eventstream` | 1.0.1 | Apache-2.0 |

That is 32 jars, about 8.5 MB. The SDK's two default HTTP clients (Netty, and Apache HttpClient 5) are
left out: `url-connection-client`, over the JDK's `HttpURLConnection`, takes their place.

The formats are optional dependencies, so an application adds the one it uses beside this module:

```xml
<dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>s3</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.ebremer</groupId>
    <artifactId>zarr</artifactId>   <!-- or hdf5, or both -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

As a JPMS module it is `com.ebremer.falcon.s3`; requiring it lets an application read the SDK's S3 module
(and, through it, the SDK's other modules, which are automatic modules) and the format modules it has.

## The client

Both classes take an `S3Client` that the application builds and closes; Falcon never builds or closes
one. So the SDK's settings apply unchanged: its region and credential chains, an endpoint for another
service, its retries and timeouts.

```java
try (S3Client s3 = S3Client.create()) {
    ...                    // open stores and HDF5 files with it; close them before the client
}
```

- **Region:** `S3Client.create()` takes it from the SDK's chain (`AWS_REGION`, the `aws.region` system
  property, `~/.aws/config`, instance metadata), and fails if none gives one. Name it with
  `S3Client.builder().region(Region.EU_WEST_1)`. A bucket in another region fails each request, and the
  error names the bucket's region; `crossRegionAccessEnabled(true)` follows it instead.
- **Credentials:** by default the SDK's chain: environment variables, system properties, web identity,
  `~/.aws` profiles and SSO, and container and instance roles. Any `AwsCredentialsProvider` may be given
  with `credentialsProvider(...)`. A public bucket is read with `AnonymousCredentialsProvider.create()`,
  which signs nothing; an `S3Store` over such a client is read-only.
- **S3-compatible storage:** name the endpoint and address the bucket in the path. Some services refuse
  the CRC-32 trailers the SDK adds to each upload by default, so compute checksums only where S3 requires
  them:

  ```java
  S3Client minio = S3Client.builder()
          .endpointOverride(URI.create("http://localhost:9000"))  // R2: https://<account>.r2.cloudflarestorage.com
          .forcePathStyle(true)                                   // GCS (HMAC keys): https://storage.googleapis.com
          .region(Region.of("us-east-1"))                         // R2 and GCS: Region.of("auto")
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
          .build();
  ```

- **Retries and timeouts:** the SDK's standard retry strategy (3 attempts, with backoff, for throttling,
  server errors, and failed connections), and its timeouts, set through
  `overrideConfiguration(...)` and `httpClientBuilder(UrlConnectionHttpClient.builder().socketTimeout(...))`.
- **Another HTTP client:** an application that adds the SDK's `apache5-client` (or Netty) beside this
  module has two clients on its class path; give the one to use with `httpClient(...)` or
  `httpClientBuilder(...)`, or the SDK refuses to choose.

## Zarr: `S3Store`

```java
try (S3Client s3 = S3Client.create()) {
    Store store = S3Store.fromUrl(s3, "s3://my-bucket/data/image.zarr").build();
    ZarrGroup root = Zarr.openGroup(store);
    double[] values = root.array("image").readDoubles();
}
```

- `S3Store.builder(client, bucket)` or `S3Store.fromUrl(client, "s3://bucket/prefix")`, then:
  - `prefix("data/image.zarr")` roots the store inside the bucket (leading and trailing `/` ignored);
  - `readOnly()` refuses writes whatever the client's credentials allow;
  - `missingStatuses(404, 403)` reads a key S3 answers 403 for as absent: a bucket that may be read but
    not listed answers 403 for a key it does not hold. A key truly denied then reads as fill values, so do
    this only for such buckets.
- **Requests:** `get` is a `GetObject`, `getRange` and `getSuffix` a `GetObject` with a `Range` (so a
  sharded array reads its shard index and the sub-chunks it needs, never whole shards), `size` and
  `exists` a `HeadObject`, `set` a `PutObject` (one request; S3 takes at most 5 GB in one), and `delete` a
  `DeleteObject`.
- **Listings** use `ListObjectsV2`, every page, `listDir` with the `/` delimiter, so `childNames()` lists
  a group's children without listing its arrays' chunks. A listing leaves out objects whose names are
  not store keys, such as the "folder" objects ending in `/` that consoles create.
- **Consolidated metadata** makes a remote hierarchy one request to walk: see the Zarr guide's
  *Consolidated metadata*.

## HDF5: `S3RangeReader`

```java
try (S3Client s3 = S3Client.create();
     Hdf5File h5 = Hdf5File.open(S3RangeReader.open(s3, "s3://my-bucket/data/scan.h5"))) {
    double[] slab = h5.root().dataset("image").select(new long[]{100, 0}, new long[]{50, 200}).readDoubles();
}
```

- `S3RangeReader.open(client, "s3://bucket/key")` or `open(client, bucket, key)` sends one `HeadObject`,
  for the object's size and ETag; a missing object is a `FileNotFoundException`.
- Each read is one `GetObject` with a `Range`. HDF5 reads metadata in cached pages of 64 KiB and each
  chunk with one read; `OpenOptions.readerPageSize(...)` and `readerCacheSize(...)` tune the pages.
- Every read carries `If-Match` with the ETag the object had when opened, so an object replaced while the
  file is open fails the next read (an `IOException`, surfacing as `UncheckedIOException` from the read
  that needed it) instead of mixing two files' bytes.
- **Other files.** A file read this way has no directory, so by default it opens no other file, and a read
  through an external link, a virtual dataset, external raw data, or a reference into another file fails,
  saying so. `siblings()` opens them from beside the object, as `ExternalFileAccess.sameDirectory()`
  does for a local file: a relative name from the object's "directory" (its key up to the last `/`), and an
  absolute name by its file name alone there, as libhdf5 looks for a file moved with the files it names. A
  name that climbs out (`../x.h5`) is refused.

  ```java
  S3RangeReader reader = S3RangeReader.open(s3, "s3://my-bucket/data/vds.h5");
  OpenOptions options = OpenOptions.defaults()
          .externalFileAccess(ExternalFileAccess.resolvedBy(reader.siblings()));
  try (Hdf5File h5 = Hdf5File.open(reader, options)) { ... }
  ```

- **Writing.** S3 cannot change part of an object, so HDF5 files are not written to S3 in place: write the
  file locally with `Hdf5Writer` and upload it, `s3.putObject(r -> r.bucket(b).key(k), RequestBody.fromFile(path))`
  (one request up to 5 GB; the SDK's multipart calls for more).

## Errors, logging, and threads

- `S3Store` reports failures as `ZarrException`, `S3RangeReader` as `IOException`, each with S3's status,
  error code, and message, and hints: a bucket in another region names its region, a 403 from an
  anonymous client says so. Credentials never appear in messages or in `toString()`, which gives the
  `s3://` URL, the endpoint if one was set, and the region.
- The SDK logs through SLF4J 1.7. Without a binding on the class path, SLF4J prints a three-line notice
  once and logs nothing; add a binding (`slf4j-simple`, Logback 1.2, `log4j-slf4j-impl`) to see the
  SDK's logs.
- Both classes may be used from several threads at once, as `S3Client` may.

## Moving from Zarr's old `S3Store`

Until 2026-10-07 the `zarr` module had its own `S3Store` (`com.ebremer.falcon.zarr.store.S3Store`), which
signed requests itself. It is gone; this module's `S3Store` replaces it, and the client takes the settings
the old builder had:

| Old (`zarr`) | Now |
|---|---|
| `S3Store.fromUrl(url)` / `builder(bucket)` | `S3Store.fromUrl(client, url)` / `builder(client, bucket)` |
| `prefix`, `readOnly`, `missingStatuses` | the same |
| `region("eu-west-1")` | `S3Client.builder().region(Region.EU_WEST_1)` |
| `endpoint(url)`, `pathStyle(b)` | `endpointOverride(URI)`, `forcePathStyle(b)` |
| `credentials(id, secret[, token])` | `credentialsProvider(StaticCredentialsProvider.create(...))` |
| `fromEnvironment()` | the default chain (which reads the environment, and much more) |
| no credentials (anonymous by default) | `credentialsProvider(AnonymousCredentialsProvider.create())`; the default is now the SDK's chain |
| `timeoutMillis`, `maxRetries` | `overrideConfiguration(...)` and the HTTP client's builder |
