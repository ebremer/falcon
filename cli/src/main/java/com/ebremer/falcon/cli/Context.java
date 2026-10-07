package com.ebremer.falcon.cli;

import java.io.PrintStream;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * What a command runs with: where its output and its messages go, its options, and the S3 client its
 * {@code s3://} sources and targets share, built when one is first needed and closed with the context.
 */
final class Context implements AutoCloseable {

    final PrintStream out;
    final PrintStream err;
    final CommonOptions options;
    private S3Client s3;
    private int warnings;

    Context(PrintStream out, PrintStream err, CommonOptions options) {
        this.out = out;
        this.err = err;
        this.options = options;
    }

    /** {@return the S3 client, built from the S3 options when first asked for} */
    synchronized S3Client s3() {
        if (s3 == null) {
            s3 = S3Clients.build(options);
        }
        return s3;
    }

    /**
     * Reports something the command left out or changed, and goes on.
     *
     * @param message what happened, and to what
     */
    synchronized void warn(String message) {
        err.println("falcon: warning: " + message);
        warnings++;
    }

    /** {@return how many warnings the command reported} */
    synchronized int warnings() {
        return warnings;
    }

    @Override
    public synchronized void close() {
        if (s3 != null) {
            s3.close();
            s3 = null;
        }
    }
}
