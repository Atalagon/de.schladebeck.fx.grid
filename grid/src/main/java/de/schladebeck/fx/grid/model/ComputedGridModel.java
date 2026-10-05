package de.schladebeck.fx.grid.model;

import java.util.function.IntFunction;

/// A model whose values come from a function, so any size costs no memory:
/// ```java
/// GridModel times = new ComputedGridModel(1_000, 1_000, (row, column) -> (row + 1) * (column + 1))
///         .columnNames(column -> "x " + (column + 1))
///         .rowNames(row -> "y " + (row + 1));
/// ```
///
/// The function runs on the JavaFX application thread for every visible cell on every repaint, so it must be
/// fast. Call [#refresh()] when its results change, e.g. because they depend on time or on other data.
///
/// The changing methods and the builders may be called from any thread, see [AbstractGridModel#applyChange].
public class ComputedGridModel extends AbstractGridModel {

    /** Computes the value of a cell. */
    @FunctionalInterface
    public interface Values {

        /// Computes one cell.
        ///
        /// @param row    model row
        /// @param column model column
        /// @return the value of the cell, may be `null`
        Object valueAt(int row, int column);
    }

    private final Values values;
    private int rows;
    private int columns;
    private IntFunction<String> columnNames;
    private IntFunction<String> rowNames;
    private IntFunction<Class<?>> columnTypes;

    /// A model of the given size whose values come from `values`.
    ///
    /// @param rows    number of rows
    /// @param columns number of columns
    /// @param values  computes each cell's value
    public ComputedGridModel(int rows, int columns, Values values) {
        this.rows = rows;
        this.columns = columns;
        this.values = values;
    }

    /** Header text per column, instead of A, B, C, ... Returns this model for chaining. Any thread. */
    public ComputedGridModel columnNames(IntFunction<String> names) {
        applySetup(() -> {
            columnNames = names;
            fireStructureChanged();
        });
        return this;
    }

    /** Row header text per row, instead of row numbers. Returns this model for chaining. Any thread. */
    public ComputedGridModel rowNames(IntFunction<String> names) {
        applySetup(() -> {
            rowNames = names;
            fireStructureChanged();
        });
        return this;
    }

    /// The type of each column's values, to pick renderers without looking at each value. Without it, renderers
    /// are picked by each value's class. Returns this model for chaining. Any thread.
    public ComputedGridModel columnTypes(IntFunction<Class<?>> types) {
        applySetup(() -> {
            columnTypes = types;
            fireStructureChanged();
        });
        return this;
    }

    /** Changes the size. May be called from any thread. */
    public void setSize(int rows, int columns) {
        applyChange(() -> {
            boolean sameColumns = columns == this.columns;
            this.rows = rows;
            this.columns = columns;
            if (sameColumns) {
                fireDataChanged();
            } else {
                fireStructureChanged();
            }
        });
    }

    /** The function's results changed; repaints whatever is visible. May be called from any thread. */
    public void refresh() {
        applyChange(() -> {
            if (rows > 0) {
                fireRowsUpdated(0, rows - 1);
            }
        });
    }

    @Override
    public int getRowCount() {
        return rows;
    }

    @Override
    public int getColumnCount() {
        return columns;
    }

    @Override
    public Object getValueAt(int row, int column) {
        return values.valueAt(row, column);
    }

    @Override
    public String getColumnName(int column) {
        return columnNames != null ? columnNames.apply(column) : super.getColumnName(column);
    }

    @Override
    public String getRowName(int row) {
        return rowNames != null ? rowNames.apply(row) : null;
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return columnTypes != null ? columnTypes.apply(column) : Object.class;
    }
}
