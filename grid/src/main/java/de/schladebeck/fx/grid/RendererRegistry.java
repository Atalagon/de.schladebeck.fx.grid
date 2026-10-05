package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The grid's default renderer choice for a cell: the column's own renderer, else the default renderer for the
/// column class, else (for columns of mixed types) the default renderer for the value's class, else text.
///
/// Columns with their own renderer are kept in a map and the per-column choice in a small direct-mapped cache, so
/// the cost does not grow with the number of columns and the lookup in the paint loop does not allocate.
final class RendererRegistry {

    /** Marks columns whose renderer depends on each value's class. */
    private static final CellRenderer BY_VALUE = (gc, cell) -> {
    };
    private static final int CACHE_SIZE = 1024;

    private final Map<Class<?>, CellRenderer> defaults = new HashMap<>(Map.of(
            Object.class, CellRenderers.TEXT,
            Number.class, CellRenderers.NUMBER,
            Boolean.class, CellRenderers.BOOLEAN));
    private final Map<Class<?>, CellRenderer> byValueClass = new HashMap<>();
    private final Map<Integer, CellRenderer> byColumn = new HashMap<>();
    private final int[] cachedColumns = new int[CACHE_SIZE];
    private final CellRenderer[] cachedRenderers = new CellRenderer[CACHE_SIZE];

    RendererRegistry() {
        invalidate();
    }

    void setDefault(Class<?> type, CellRenderer renderer) {
        defaults.put(type, renderer);
        byValueClass.clear();
        invalidate();
    }

    void setColumn(int modelColumn, CellRenderer renderer) {
        if (renderer == null) {
            byColumn.remove(modelColumn);
        } else {
            byColumn.put(modelColumn, renderer);
        }
        invalidate();
    }

    /** The column's own renderer, or {@code null}. */
    CellRenderer column(int modelColumn) {
        return byColumn.get(modelColumn);
    }

    void resize(int columnCount) {
        byColumn.keySet().removeIf(column -> column >= columnCount);
        invalidate();
    }

    /** Column classes or renderers changed, e.g. a list model that was empty now has values. */
    void invalidate() {
        Arrays.fill(cachedColumns, -1);
    }

    CellRenderer get(GridModel model, int modelColumn, Object value) {
        int slot = modelColumn & (CACHE_SIZE - 1);
        CellRenderer renderer;
        if (cachedColumns[slot] == modelColumn) {
            renderer = cachedRenderers[slot];
        } else {
            renderer = byColumn.get(modelColumn);
            if (renderer == null) {
                Class<?> type = model.getColumnClass(modelColumn);
                renderer = type == null || type == Object.class ? BY_VALUE : forClass(type);
            }
            cachedColumns[slot] = modelColumn;
            cachedRenderers[slot] = renderer;
        }
        if (renderer != BY_VALUE) {
            return renderer;
        }
        return value == null ? CellRenderers.TEXT : byValueClass.computeIfAbsent(value.getClass(), this::forClass);
    }

    /// The default renderer for a class: the nearest superclass with one, then its interfaces (nearest first), then
    /// the one for `Object`. Primitive types count as their wrappers, so a `double.class` column is a number column.
    private CellRenderer forClass(Class<?> type) {
        Class<?> boxed = ValueParser.boxed(type);
        for (Class<?> t = boxed; t != null && t != Object.class; t = t.getSuperclass()) {
            CellRenderer renderer = defaults.get(t);
            if (renderer != null) {
                return renderer;
            }
        }
        Deque<Class<?>> interfaces = new ArrayDeque<>();
        for (Class<?> t = boxed; t != null; t = t.getSuperclass()) {
            interfaces.addAll(List.of(t.getInterfaces()));
        }
        while (!interfaces.isEmpty()) {
            Class<?> next = interfaces.poll();
            CellRenderer renderer = defaults.get(next);
            if (renderer != null) {
                return renderer;
            }
            interfaces.addAll(List.of(next.getInterfaces()));
        }
        return defaults.getOrDefault(Object.class, CellRenderers.TEXT);
    }
}
