package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellStyle;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javafx.util.StringConverter;

/// What single columns have of their own, besides their place and width (`ColumnLayout`) and their renderer
/// (`RendererRegistry`, which caches the renderer choice). Only columns with a setting have an entry, so a model
/// with millions of columns costs nothing here.
final class ColumnSettings {

    /** One column's settings; `null` fields mean the grid's default. */
    static final class Column {
        /** Set by the user or in code, so fitting leaves the width alone. */
        boolean explicitWidth;
        CellStyle headerStyle;
        CellRenderer headerRenderer;
        Comparator<Object> comparator;
        StringConverter<Object> converter;
        Object prototypeValue;

        private boolean isEmpty() {
            return !explicitWidth && headerStyle == null && headerRenderer == null && comparator == null
                    && converter == null && prototypeValue == null;
        }
    }

    private final Map<Integer, Column> columns = new HashMap<>();

    /** One setting of a column, or {@code null} if the column does not have it. */
    <T> T get(int modelColumn, Function<Column, T> setting) {
        Column column = columns.isEmpty() ? null : columns.get(modelColumn);
        return column == null ? null : setting.apply(column);
    }

    /** Changes one column's settings; an entry that ends up empty is removed. */
    void edit(int modelColumn, Consumer<Column> change) {
        Column column = columns.computeIfAbsent(modelColumn, c -> new Column());
        change.accept(column);
        if (column.isEmpty()) {
            columns.remove(modelColumn);
        }
    }

    boolean hasExplicitWidth(int modelColumn) {
        return get(modelColumn, c -> c.explicitWidth) == Boolean.TRUE;
    }

    /** The model columns whose width was set by the user or in code. */
    void forEachExplicitWidth(IntConsumer action) {
        columns.forEach((column, settings) -> {
            if (settings.explicitWidth) {
                action.accept(column);
            }
        });
    }

    /** Forgets columns that no longer exist. */
    void prune(int columnCount) {
        columns.keySet().removeIf(column -> column >= columnCount);
    }

    void clear() {
        columns.clear();
    }
}
