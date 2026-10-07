package com.ebremer.falcon.cli;

import java.io.PrintStream;
import java.util.List;

/** Rows printed in aligned columns, each but the last padded to its widest cell. */
final class Table {

    private Table() {
    }

    static void print(PrintStream out, List<String[]> rows) {
        int columns = 0;
        for (String[] row : rows) {
            columns = Math.max(columns, row.length);
        }
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int c = 0; c < row.length - 1; c++) {
                widths[c] = Math.max(widths[c], row[c].length());
            }
        }
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < row.length; c++) {
                if (c > 0) {
                    line.append("  ");
                }
                line.append(row[c]);
                if (c < row.length - 1) {
                    line.repeat(' ', widths[c] - row[c].length());
                }
            }
            out.println(line.toString().stripTrailing());
        }
    }

    /**
     * {@return a value shortened to {@code max} characters, ending in {@code ...} if it was cut}
     *
     * @param value the text
     * @param max   the most characters to keep
     */
    static String clip(String value, int max) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }
}
