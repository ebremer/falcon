package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Runs the {@code falcon} command in this JVM, capturing what it prints. */
final class Cli {

    private Cli() {
    }

    /**
     * What a run printed, and its status.
     *
     * @param status the exit status
     * @param out    the output, with {@code \n} line ends
     * @param err    the messages
     */
    record Result(int status, String out, String err) {

        /** {@return this result, after checking it succeeded} */
        Result ok() {
            assertEquals(0, status, () -> "status " + status + "\nout:\n" + out + "\nerr:\n" + err);
            return this;
        }
    }

    static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Falcon.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(status, text(out), text(err));
    }

    /** Runs a command line that must succeed, and {@return its output} */
    static String ok(String... args) {
        return run(args).ok().out();
    }

    private static String text(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
