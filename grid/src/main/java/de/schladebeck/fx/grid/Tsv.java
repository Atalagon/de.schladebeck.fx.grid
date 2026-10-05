package de.schladebeck.fx.grid;

/** Tab-separated text for the clipboard. Tabs and line breaks inside values become spaces. */
final class Tsv {

    /** The text of a view cell. */
    @FunctionalInterface
    interface CellText {
        String of(int row, int column);
    }

    private Tsv() {
    }

    /** The cells at the given view rows and columns, row by row, each as {@code text} gives it. */
    static String of(int[] rows, int[] columns, CellText text) {
        StringBuilder result = new StringBuilder();
        for (int row : rows) {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    result.append('\t');
                }
                String value = text.of(row, columns[i]);
                if (value != null) {
                    appendClean(result, value);
                }
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static void appendClean(StringBuilder text, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
        }
    }
}
