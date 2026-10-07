package com.ebremer.falcon.cli;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** An error, told in one line: its message, then each cause's that adds to it. */
final class Errors {

    private Errors() {
    }

    /**
     * {@return what went wrong, in one line}
     *
     * @param error the error a command failed with
     */
    static String describe(Throwable error) {
        Set<String> parts = new LinkedHashSet<>();
        for (Throwable t = unwrap(error); t != null && parts.size() < 4; t = t.getCause()) {
            String message = message(t);
            if (message != null && parts.stream().noneMatch(p -> p.contains(message))) {
                parts.add(message);
            }
        }
        return parts.isEmpty() ? unwrap(error).getClass().getSimpleName() : String.join(": ", parts);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof ExecutionException || t instanceof UncheckedIOException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String reason(java.nio.file.FileSystemException e) {
        return e.getReason() == null ? "" : " (" + e.getReason() + ")";
    }

    private static String message(Throwable t) {
        String message = t.getMessage();
        return switch (t) {
            case NoSuchFileException e -> "no such file or directory: " + e.getFile() + reason(e);
            case FileAlreadyExistsException e -> "already exists: " + e.getFile() + reason(e);
            case AccessDeniedException e -> "access denied: " + e.getFile() + reason(e);
            case FileNotFoundException e -> message;
            case StackOverflowError e -> "the structure nests too deeply";
            default -> message == null || message.isBlank() ? null : message;
        };
    }
}
