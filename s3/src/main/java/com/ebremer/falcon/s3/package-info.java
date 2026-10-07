/**
 * Amazon S3 for Falcon's formats, over the AWS SDK for Java 2.x: {@link com.ebremer.falcon.s3.S3Store}, a Zarr
 * store over the objects under a prefix of a bucket, and {@link com.ebremer.falcon.s3.S3RangeReader}, an
 * HDF5 file read from an object a byte range at a time.
 *
 * <p>Both take an {@link software.amazon.awssdk.services.s3.S3Client} the caller builds and closes, so the
 * SDK's settings apply unchanged: its region and credential chains, an endpoint for S3-compatible storage,
 * its retries and timeouts.
 */
package com.ebremer.falcon.s3;
