package de.schladebeck.fx.grid.showcase;

import de.schladebeck.fx.grid.model.ComputedGridModel;

/** Data computed from row and column for the benchmark, so 100 billion cells need no memory. */
final class SyntheticModel extends ComputedGridModel {

    private static final String[] FIRST = {"Anna", "Ben", "Clara", "David", "Eva", "Felix", "Greta", "Hannes",
            "Ida", "Jonas", "Klara", "Lukas", "Mia", "Noah", "Olivia", "Paul"};
    private static final String[] LAST = {"Becker", "Fischer", "Hoffmann", "Koch", "Meyer", "Müller", "Richter",
            "Schäfer", "Schmidt", "Schneider", "Schulz", "Wagner", "Weber", "Wolf", "Zimmermann", "Neumann"};
    private static final String[] WORDS = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
            "a rather long text that will not fit into its column", "hotel", "india", "juliett", "kilo"};

    SyntheticModel(int rows, int columns) {
        super(rows, columns, SyntheticModel::value);
        columnNames(column -> switch (column) {
            case 0 -> "ID";
            case 1 -> "Name";
            case 2 -> "Amount";
            case 3 -> "Active";
            default -> "Column " + column;
        });
        columnTypes(column -> switch (kind(column)) {
            case 0 -> Integer.class;
            case 1 -> String.class;
            case 2 -> Double.class;
            default -> Boolean.class;
        });
    }

    /** Fires an update without changing data, to measure the cost of live updates. */
    void touch(int row) {
        fireRowsUpdated(row, row);
    }

    private static Object value(int row, int column) {
        int h = hash(row, column);
        if (column == 0) {
            return row + 1;
        }
        if (column == 1) {
            return FIRST[h & 15] + " " + LAST[(h >>> 4) & 15];
        }
        return switch (kind(column)) {
            case 0 -> (h >>> 1) % 100_000;
            case 1 -> WORDS[(h >>> 3) % WORDS.length];
            case 2 -> (h % 1_000_000) / 100.0;
            default -> (h & 8) == 0;
        };
    }

    private static int kind(int column) {
        return column < 4 ? column : column % 4;
    }

    private static int hash(int row, int column) {
        int h = row * 31 + column * 1_000_003;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return h;
    }
}
