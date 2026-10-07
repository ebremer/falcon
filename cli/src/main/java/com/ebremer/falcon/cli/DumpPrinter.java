package com.ebremer.falcon.cli;

import java.io.PrintStream;

/**
 * Prints the elements of a selection as they arrive, in C order: as text, CSV, or JSON.
 * <ul>
 *   <li><b>text:</b> rows of values, each line starting with the coordinates of its first value in the
 *       array, wrapped at 80 columns: {@code (0, 0): 1, 2, 3}.</li>
 *   <li><b>csv:</b> a value per line for one dimension, a row per line for two; for more, each row starts
 *       with its indices in the result's leading dimensions.</li>
 *   <li><b>json:</b> nested arrays of the result's shape, a row per line.</li>
 * </ul>
 * A result of no dimensions (a scalar, or every dimension given as an index) prints its one value.
 */
abstract class DumpPrinter {

    protected final PrintStream out;
    protected final Slices slices;
    protected final long[] shape;      // the result's shape
    protected final long[] index;      // the next element's index in the result
    private final int[] resultDims;    // the source dimension of each result dimension

    DumpPrinter(PrintStream out, Slices slices) {
        this.out = out;
        this.slices = slices;
        this.shape = slices.resultShape();
        this.index = new long[shape.length];
        this.resultDims = new int[shape.length];
        int r = 0;
        for (int d = 0; d < slices.squeeze().length; d++) {
            if (!slices.squeeze()[d]) {
                resultDims[r++] = d;
            }
        }
    }

    static DumpPrinter of(String format, PrintStream out, Slices slices) {
        return switch (format) {
            case "text" -> new Text(out, slices);
            case "csv" -> new Csv(out, slices);
            case "json" -> new Json(out, slices);
            default -> throw new UsageException("--format is text, csv, or json, not '" + format + "'");
        };
    }

    /**
     * Prints the next element.
     *
     * @param values the elements it is among
     * @param i      its index in them
     */
    final void element(Values values, int i) {
        print(values, i);
        for (int d = shape.length - 1; d >= 0; d--) {
            if (++index[d] < shape[d]) {
                break;
            }
            if (d > 0) {
                index[d] = 0;
            }
        }
    }

    abstract void print(Values values, int i);

    /** Ends the output, or for a result with no elements, prints what stands for it. */
    abstract void end(boolean empty);

    /** {@return the coordinate in the source array of the next element, in source dimension {@code d}} */
    long sourceCoordinate(int d) {
        long resultIndex = 0;
        for (int r = 0; r < resultDims.length; r++) {
            if (resultDims[r] == d) {
                resultIndex = index[r];
            }
        }
        return slices.start()[d] + resultIndex * slices.step()[d];
    }

    /** Text: lines labelled with their first value's coordinates in the array. */
    static final class Text extends DumpPrinter {
        private static final int WIDTH = 80;
        private final StringBuilder line = new StringBuilder();
        private boolean values;

        Text(PrintStream out, Slices slices) {
            super(out, slices);
        }

        @Override
        void print(Values v, int i) {
            String text = v.text(i);
            if (shape.length == 0) {
                out.println(text);
                return;
            }
            boolean rowStart = index[shape.length - 1] == 0;
            if (rowStart || (values && line.length() + 2 + text.length() > WIDTH)) {
                flush();
                line.append('(');
                int rank = slices.start().length;
                for (int d = 0; d < rank; d++) {
                    line.append(d == 0 ? "" : ", ").append(sourceCoordinate(d));
                }
                line.append("): ");
            } else {
                line.append(", ");
            }
            line.append(text);
            values = true;
        }

        private void flush() {
            if (values) {
                out.println(line);
            }
            line.setLength(0);
            values = false;
        }

        @Override
        void end(boolean empty) {
            flush();
        }
    }

    /** CSV, RFC 4180: a field is quoted when it holds a comma, a quote, a line break, or edge spaces. */
    static final class Csv extends DumpPrinter {
        private final StringBuilder line = new StringBuilder();

        Csv(PrintStream out, Slices slices) {
            super(out, slices);
        }

        @Override
        void print(Values v, int i) {
            String field = quote(v.csv(i));
            if (shape.length <= 1) {
                out.println(field);
                return;
            }
            int last = shape.length - 1;
            if (index[last] == 0) {
                line.setLength(0);
                if (shape.length > 2) {
                    for (int d = 0; d < last; d++) {
                        line.append(index[d]).append(',');
                    }
                }
            } else {
                line.append(',');
            }
            line.append(field);
            if (index[last] == shape[last] - 1) {
                out.println(line);
            }
        }

        static String quote(String field) {
            boolean quote = field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0
                    || field.indexOf('\r') >= 0 || (!field.isEmpty() && (field.charAt(0) == ' '
                    || field.charAt(field.length() - 1) == ' '));
            return quote ? '"' + field.replace("\"", "\"\"") + '"' : field;
        }

        @Override
        void end(boolean empty) {
        }
    }

    /** JSON: nested arrays, each innermost one on a line. */
    static final class Json extends DumpPrinter {
        private long remaining;

        Json(PrintStream out, Slices slices) {
            super(out, slices);
            remaining = 1;
            for (long n : shape) {
                remaining *= n;
            }
        }

        @Override
        void print(Values v, int i) {
            int rank = shape.length;
            if (rank == 0) {
                out.println(v.json(i).toJson());
                return;
            }
            // open the arrays this element starts
            int opens = 0;
            for (int d = rank - 1; d >= 0 && index[d] == 0; d--) {
                opens++;
            }
            if (opens > 0) {
                out.print("[".repeat(opens));
            }
            out.print(v.json(i).toJson());
            int closes = 0;
            for (int d = rank - 1; d >= 0 && index[d] == shape[d] - 1; d--) {
                closes++;
            }
            out.print("]".repeat(closes));
            remaining--;
            if (remaining == 0) {
                out.println();
            } else if (closes > 0) {
                out.println(",");
                out.print(" ".repeat(rank - closes));
            } else {
                out.print(", ");
            }
        }

        @Override
        void end(boolean empty) {
            if (empty) {
                out.println(emptyArrays(0));
            }
        }

        private String emptyArrays(int dim) {
            if (dim == shape.length) {
                return "null";
            }
            if (shape[dim] == 0) {
                return "[]";
            }
            String inner = emptyArrays(dim + 1);
            StringBuilder s = new StringBuilder("[");
            for (long i = 0; i < shape[dim]; i++) {
                s.append(i == 0 ? "" : ", ").append(inner);
            }
            return s.append(']').toString();
        }
    }
}
