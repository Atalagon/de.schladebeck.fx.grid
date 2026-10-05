package de.schladebeck.fx.grid.model;

import java.util.Locale;
import java.util.function.IntPredicate;

/** Ready-made row filters for {@link de.schladebeck.fx.grid.Grid#setRowFilter}. */
public final class RowFilters {

    private RowFilters() {
    }

    /// Rows where any cell's text contains `text`, ignoring case. Returns `null`, which means
    /// no filter, for blank text. Looks at `toString()` of the values, not at the rendered text.
    public static IntPredicate containsText(GridModel model, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String query = text.strip().toLowerCase(Locale.ROOT);
        return row -> {
            for (int column = 0; column < model.getColumnCount(); column++) {
                Object value = model.getValueAt(row, column);
                if (value != null && value.toString().toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }
            return false;
        };
    }
}
