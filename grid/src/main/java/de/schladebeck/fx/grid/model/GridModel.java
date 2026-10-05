package de.schladebeck.fx.grid.model;

import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

/// The data shown by a [de.schladebeck.fx.grid.Grid]: a rectangle of values addressed by row and column. The grid
/// asks only for the cells it shows, so the values can come from anywhere: a list of objects ([ListGridModel]), a
/// function ([ComputedGridModel]), a database cursor, or objects laid out as columns. Rows and columns can both
/// have names, and each cell may hold a value of any type.
///
/// The grid reads the model on the JavaFX application thread, so data must only change there and events must be
/// fired there. [AbstractGridModel#applyChange] takes care of this for changes from other threads.
public interface GridModel {

    /** Number of rows. */
    int getRowCount();

    /** Number of columns. */
    int getColumnCount();

    /// The value of a cell. Called for every visible cell on every repaint, so it must be fast.
    ///
    /// @param row    model row
    /// @param column model column
    /// @return the value, may be `null`
    Object getValueAt(int row, int column);

    /// Column header text. Defaults to spreadsheet-style names: A, B, ..., Z, AA, AB, ...
    ///
    /// @param column model column
    /// @return the header text
    default String getColumnName(int column) {
        StringBuilder name = new StringBuilder();
        for (int c = column; c >= 0; c = c / 26 - 1) {
            name.insert(0, (char) ('A' + c % 26));
        }
        return name.toString();
    }

    /// The first column with this name, so code does not depend on column positions.
    ///
    /// @param name the column name, as [#getColumnName] returns it
    /// @return the model column, or -1 if no column has that name
    default int findColumn(String name) {
        for (int column = 0; column < getColumnCount(); column++) {
            if (getColumnName(column).equals(name)) {
                return column;
            }
        }
        return -1;
    }

    /// Row header text, or `null` to show the row's position (1, 2, 3, ...). Names move with their row when
    /// sorting.
    ///
    /// @param row model row
    /// @return the row name, or `null` for numbers
    default String getRowName(int row) {
        return null;
    }

    /// The type of all values in a column, used to pick the default renderer. Return `Object.class`
    /// (the default) when a column holds mixed types; the renderer is then picked by each value's own class.
    ///
    /// @param column model column
    /// @return the common type of the column's values
    default Class<?> getColumnClass(int column) {
        return Object.class;
    }

    /// Whether an editable grid may edit this cell in place.
    ///
    /// @param row    model row
    /// @param column model column
    /// @return `true` if [#setValueAt] accepts a value for the cell
    default boolean isCellEditable(int row, int column) {
        return false;
    }

    /// Stores a value edited in the grid. Implementations fire an `UPDATE` event.
    ///
    /// @param value  the new value, already converted to the type of the old value
    /// @param row    model row
    /// @param column model column
    default void setValueAt(Object value, int row, int column) {
        throw new UnsupportedOperationException("This model is read-only");
    }

    /// Sorts the rows at the source, for models that do it better than the grid, such as a database query. If it
    /// returns `true`, the model has reordered its rows and fired an event, and the grid does not sort itself (it
    /// clears the selection instead of following the rows). The default returns `false`: the grid sorts.
    ///
    /// @param keys  the columns to sort by, the first one decides and the next ones break its ties; empty for the
    ///              model's natural order
    /// @param order the order of a model column's values, ascending, nulls first: the column's comparator or the
    ///              grid's default order (numbers by value, text ignoring case). Models that sort somewhere else,
    ///              such as in a database, can ignore it.
    /// @return `true` if the model sorted its rows
    default boolean sortRows(List<SortKey> keys, IntFunction<Comparator<Object>> order) {
        return false;
    }

    /** Registers a listener for changes. */
    void addGridModelListener(GridModelListener listener);

    /** Removes a listener added with {@link #addGridModelListener}. */
    void removeGridModelListener(GridModelListener listener);
}
