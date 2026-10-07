package com.ebremer.falcon.cli;

/** A command line JCommander accepted but the command cannot run: the wrong number of arguments, a bad option. */
final class UsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
