package com.ebremer.falcon.ome;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What {@link OmeValidator} found: every issue, errors and warnings. The metadata is valid when there are no
 * errors.
 *
 * @param issues the issues, in the order they were found
 */
public record ValidationReport(List<Issue> issues) {

    /**
     * Creates the report.
     *
     * @throws NullPointerException if {@code issues} is null
     */
    public ValidationReport {
        issues = List.copyOf(issues);
    }

    /** How much an issue matters. */
    public enum Severity {
        /** The specification says MUST (or MUST NOT), and the metadata does not comply. */
        ERROR,
        /** The specification says SHOULD (or SHOULD NOT), and the metadata does not comply. */
        WARNING
    }

    /**
     * One issue.
     *
     * @param severity how much it matters
     * @param node     the Zarr node it is in: {@code "/"} for the root, else the node's path
     * @param pointer  where in the node's attributes, as a JSON pointer; empty for the node itself
     * @param message  what is wrong
     */
    public record Issue(Severity severity, String node, String pointer, String message) {

        /**
         * Creates the issue.
         *
         * @throws NullPointerException if an argument is null
         */
        public Issue {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(pointer, "pointer");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return severity.name().toLowerCase(java.util.Locale.ROOT) + ": " + node
                    + (pointer.isEmpty() ? "" : "#" + pointer) + ": " + message;
        }
    }

    /** {@return whether the metadata is valid: whether there are no errors} */
    public boolean isValid() {
        return errors().isEmpty();
    }

    /** {@return the errors} */
    public List<Issue> errors() {
        return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
    }

    /** {@return the warnings} */
    public List<Issue> warnings() {
        return issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
    }

    @Override
    public String toString() {
        return issues.isEmpty() ? "valid" : issues.stream().map(Issue::toString).collect(Collectors.joining("\n"));
    }
}
