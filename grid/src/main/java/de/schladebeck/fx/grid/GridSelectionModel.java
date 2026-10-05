package de.schladebeck.fx.grid;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/// Rectangular ranges of view cells plus an anchor and a focused cell, which is always one cell. Selecting a
/// million rows costs one range. What a click selects depends on the [SelectionMode].
///
/// Listeners ([Observable]) hear about every change. The grid keeps the selection on its rows when they are
/// sorted, filtered, inserted or deleted (see [Grid]); a row inserted inside a selected range is selected too.
/// Whole columns ([#selectColumn], [#selectAll]) stay whole when rows are added, whole rows when columns are moved
/// or hidden.
public final class GridSelectionModel implements Observable {

    /// A selected range, and whether it stands for whole columns (all rows, also rows added later) or whole rows
    /// (all columns).
    record Area(CellRange cells, boolean allRows, boolean allColumns) {

        Area withRows(int firstRow, int lastRow) {
            return new Area(new CellRange(firstRow, cells.firstColumn(), lastRow, cells.lastColumn()), allRows,
                    allColumns);
        }
    }

    /// The selection with model rows instead of view rows, taken before the view changes and put back after it,
    /// see [#capture] and [#restore]. `modelRows.get(i)` holds the model rows of area `i`, `null` for whole
    /// columns, which stay whole.
    record Snapshot(List<Area> areas, List<int[]> modelRows, int focusRow, int anchorRow) {
    }

    /** Shape of the active range, kept when Shift extends it. */
    private enum Span { CELLS, ROWS, COLUMNS }

    private final List<Area> areas = new ArrayList<>();
    private final List<CellRange> ranges = new AbstractList<>() {
        @Override
        public CellRange get(int index) {
            return areas.get(index).cells();
        }

        @Override
        public int size() {
            return areas.size();
        }
    };
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();
    private final ObjectProperty<SelectionMode> mode = new SimpleObjectProperty<>(this, "mode", SelectionMode.AREA) {
        @Override
        protected void invalidated() {
            clear();
        }
    };
    private IntSupplier rowCount = () -> 0;
    private IntSupplier columnCount = () -> 0;
    private Span span = Span.CELLS;
    private int anchorRow = -1;
    private int anchorColumn = -1;
    private int focusedRow = -1;
    private int focusedColumn = -1;

    /** Each grid creates its own, see {@link Grid#getSelectionModel()}. */
    GridSelectionModel() {
    }

    /** Changing the mode clears the selection. */
    public ObjectProperty<SelectionMode> modeProperty() {
        return mode;
    }

    public SelectionMode getMode() {
        return mode.get();
    }

    public void setMode(SelectionMode value) {
        mode.set(value);
    }

    /** Selected ranges, the last one is the active range. A read-only view that follows the selection. */
    public List<CellRange> getRanges() {
        return ranges;
    }

    /** View row of the focused cell, -1 for none. */
    public int getFocusedRow() {
        return focusedRow;
    }

    /** View column of the focused cell, -1 for none. */
    public int getFocusedColumn() {
        return focusedColumn;
    }

    /** View row where Shift extends from, -1 for none. */
    public int getAnchorRow() {
        return anchorRow;
    }

    /** View column where Shift extends from, -1 for none. */
    public int getAnchorColumn() {
        return anchorColumn;
    }

    /** Whether no cell is selected. */
    public boolean isEmpty() {
        return areas.isEmpty();
    }

    /** Whether a view cell is selected. */
    public boolean isSelected(int row, int column) {
        for (Area area : areas) {
            if (area.cells().contains(row, column)) {
                return true;
            }
        }
        return false;
    }

    /** Click: selects the cell, its row or its column, depending on the mode. The cell becomes anchor and focus. */
    public void select(int row, int column) {
        check(row, column);
        start(row, column, defaultSpan(), false);
    }

    /** Ctrl+click: adds a range like {@link #select} would create. In single cell mode the same as select. */
    public void add(int row, int column) {
        check(row, column);
        start(row, column, defaultSpan(), getMode() != SelectionMode.SINGLE_CELL);
    }

    /// Shift+click: replaces the active range with one from the anchor to the given cell, keeping its shape
    /// (cells, whole rows or whole columns). In single cell mode the same as select.
    public void extend(int row, int column) {
        check(row, column);
        if (anchorRow < 0 || getMode() == SelectionMode.SINGLE_CELL) {
            select(row, column);
            return;
        }
        Area area = area(anchorRow, anchorColumn, row, column);
        if (areas.isEmpty()) {
            areas.add(area);
        } else {
            areas.set(areas.size() - 1, area);
        }
        focusedRow = row;
        focusedColumn = column;
        fire();
    }

    /// Row header click: selects the whole row in area and row mode, with `add` (Ctrl+click) in addition to the
    /// other ranges; otherwise acts like a click on the row's cell in the focused column.
    public void selectRow(int row, boolean add) {
        check(row, 0);
        int column = Math.max(focusedColumn, 0);
        if (getMode() == SelectionMode.AREA || getMode() == SelectionMode.ROW) {
            start(row, column, Span.ROWS, add);
        } else {
            select(row, column);
        }
    }

    /// Selects the whole column in area and column mode, with `add` in addition to the other ranges; otherwise
    /// acts like a click on the column's cell in the focused row.
    public void selectColumn(int column, boolean add) {
        check(0, column);
        int row = Math.max(focusedRow, 0);
        if (getMode() == SelectionMode.AREA || getMode() == SelectionMode.COLUMN) {
            start(row, column, Span.COLUMNS, add);
        } else {
            select(row, column);
        }
    }

    /** Selects every cell, also rows added later, except in single cell mode. Anchor and focus are kept. */
    public void selectAll() {
        int rows = rowCount.getAsInt();
        int columns = columnCount.getAsInt();
        if (getMode() != SelectionMode.SINGLE_CELL && rows > 0 && columns > 0) {
            replace(new Area(new CellRange(0, 0, rows - 1, columns - 1), true, true));
        }
    }

    /** Replaces the selection with one range regardless of the mode. Anchor and focus are kept. */
    public void selectRange(CellRange range) {
        check(range.firstRow(), range.firstColumn());
        check(range.lastRow(), range.lastColumn());
        replace(new Area(range, false, false));
    }

    /** Deselects everything and removes the focus. */
    public void clear() {
        areas.clear();
        anchorRow = -1;
        anchorColumn = -1;
        focusedRow = -1;
        focusedColumn = -1;
        fire();
    }

    @Override
    public void addListener(InvalidationListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(InvalidationListener listener) {
        listeners.remove(listener);
    }

    /** Shift+click in the row header: whole rows from the anchor row, where the mode allows rows. */
    void extendRows(int row) {
        if (getMode() == SelectionMode.AREA || getMode() == SelectionMode.ROW) {
            span = Span.ROWS;
        }
        extend(row, Math.max(focusedColumn, 0));
    }

    /** Grid size, needed for whole-row and whole-column ranges. Suppliers, so it is only computed when needed. */
    void setSize(IntSupplier rows, IntSupplier columns) {
        rowCount = rows;
        columnCount = columns;
    }

    /// Rows were inserted at `first`, the row count already includes them: everything from there moves down, a
    /// range spanning it grows, and whole columns cover the new rows too.
    void rowsInserted(int first, int count) {
        if (areas.isEmpty() && focusedRow < 0) {
            return;
        }
        int rows = rowCount.getAsInt();
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            CellRange r = area.cells();
            if (area.allRows()) {
                areas.set(i, area.withRows(0, rows - 1));
            } else if (r.firstRow() >= first) {
                areas.set(i, area.withRows(r.firstRow() + count, r.lastRow() + count));
            } else if (r.lastRow() >= first) {
                areas.set(i, area.withRows(r.firstRow(), r.lastRow() + count));
            }
        }
        anchorRow = anchorRow >= first ? anchorRow + count : anchorRow;
        focusedRow = focusedRow >= first ? focusedRow + count : focusedRow;
        fire();
    }

    /** Rows {@code first..first+count-1} were deleted: ranges shrink or vanish, everything below moves up. */
    void rowsDeleted(int first, int count) {
        if (areas.isEmpty() && focusedRow < 0) {
            return;
        }
        int last = first + count - 1;
        for (int i = areas.size() - 1; i >= 0; i--) {
            CellRange r = areas.get(i).cells();
            int top = r.firstRow() > last ? r.firstRow() - count : Math.min(r.firstRow(), first);
            int bottom = r.lastRow() > last ? r.lastRow() - count : Math.min(r.lastRow(), first - 1);
            if (bottom < top) {
                areas.remove(i);
            } else {
                areas.set(i, areas.get(i).withRows(top, bottom));
            }
        }
        anchorRow = rowAfterDelete(anchorRow, first, last);
        focusedRow = rowAfterDelete(focusedRow, first, last);
        if (focusedRow < 0) {
            focusedColumn = -1;
            anchorColumn = -1;
        }
        fire();
    }

    /** All rows may have changed: keep the selection where it still fits, whole columns over all rows. */
    void clipToRowCount() {
        int rows = rowCount.getAsInt();
        for (int i = areas.size() - 1; i >= 0; i--) {
            Area area = areas.get(i);
            CellRange r = area.cells();
            if (r.firstRow() >= rows || rows == 0) {
                areas.remove(i);
            } else if (area.allRows() || r.lastRow() >= rows) {
                areas.set(i, area.withRows(area.allRows() ? 0 : r.firstRow(), rows - 1));
            }
        }
        anchorRow = Math.min(anchorRow, rows - 1);
        focusedRow = Math.min(focusedRow, rows - 1);
        if (focusedRow < 0) {
            focusedColumn = -1;
            anchorColumn = -1;
        }
        fire();
    }

    /// Columns were moved, hidden or shown: only whole rows still mean the same, and they span all columns
    /// again.
    void keepWholeRows() {
        int lastColumn = columnCount.getAsInt() - 1;
        for (int i = areas.size() - 1; i >= 0; i--) {
            Area area = areas.get(i);
            CellRange r = area.cells();
            if (!area.allColumns() || lastColumn < 0) {
                areas.remove(i);
            } else {
                areas.set(i, new Area(new CellRange(r.firstRow(), 0, r.lastRow(), lastColumn), area.allRows(), true));
            }
        }
        anchorColumn = Math.min(anchorColumn, lastColumn);
        focusedColumn = Math.min(focusedColumn, lastColumn);
        fire();
    }

    /// The selected rows as model rows, or `null` if more than `limit` rows would have to be remembered (whole
    /// columns do not count, they stay whole).
    Snapshot capture(IntUnaryOperator viewToModel, int limit) {
        long total = 0;
        for (Area area : areas) {
            total += area.allRows() ? 0 : area.cells().rowCount();
        }
        if (total > limit) {
            return null;
        }
        List<int[]> modelRows = new ArrayList<>(areas.size());
        for (Area area : areas) {
            CellRange r = area.cells();
            int[] rows = null;
            if (!area.allRows()) {
                rows = new int[r.rowCount()];
                Arrays.setAll(rows, i -> viewToModel.applyAsInt(r.firstRow() + i));
            }
            modelRows.add(rows);
        }
        return new Snapshot(List.copyOf(areas), modelRows,
                focusedRow < 0 ? -1 : viewToModel.applyAsInt(focusedRow),
                anchorRow < 0 ? -1 : viewToModel.applyAsInt(anchorRow));
    }

    /// Puts a [#capture]d selection back after the view changed. Each model row goes to its new view row, or
    /// is dropped if it is no longer shown (`modelToView` returns -1); rows of a range that are no longer next to
    /// each other become separate ranges. Ranges keep their order, so the active range stays last.
    void restore(Snapshot snapshot, IntUnaryOperator modelToView) {
        areas.clear();
        int rowCountNow = rowCount.getAsInt();
        for (int i = 0; i < snapshot.areas().size(); i++) {
            Area old = snapshot.areas().get(i);
            int[] modelRows = snapshot.modelRows().get(i);
            if (modelRows == null) {
                if (rowCountNow > 0) {
                    areas.add(old.withRows(0, rowCountNow - 1));
                }
                continue;
            }
            int[] viewRows = Arrays.stream(modelRows).map(modelToView).filter(view -> view >= 0).sorted().toArray();
            for (int j = 0; j < viewRows.length; ) {
                int k = j;
                while (k + 1 < viewRows.length && viewRows[k + 1] == viewRows[k] + 1) {
                    k++;
                }
                areas.add(old.withRows(viewRows[j], viewRows[k]));
                j = k + 1;
            }
        }
        focusedRow = snapshot.focusRow() < 0 ? -1 : modelToView.applyAsInt(snapshot.focusRow());
        anchorRow = snapshot.anchorRow() < 0 ? -1 : modelToView.applyAsInt(snapshot.anchorRow());
        if (focusedRow < 0) {
            focusedColumn = -1;
        }
        if (anchorRow < 0) {
            anchorColumn = -1;
        }
        fire();
    }

    /** A deleted row's place is taken by the next row, or the last one if nothing follows. */
    private int rowAfterDelete(int row, int first, int last) {
        if (row < first) {
            return row;
        }
        if (row > last) {
            return row - (last - first + 1);
        }
        return Math.min(first, rowCount.getAsInt() - 1);
    }

    /** Rejects cells outside the grid. */
    private void check(int row, int column) {
        int rows = rowCount.getAsInt();
        int columns = columnCount.getAsInt();
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            throw new IndexOutOfBoundsException("No view cell " + row + ", " + column + ": the grid shows " + rows
                    + " rows and " + columns + " columns");
        }
    }

    private void start(int row, int column, Span span, boolean add) {
        if (!add) {
            areas.clear();
        }
        this.span = span;
        anchorRow = row;
        anchorColumn = column;
        focusedRow = row;
        focusedColumn = column;
        areas.add(area(row, column, row, column));
        fire();
    }

    private void replace(Area area) {
        areas.clear();
        areas.add(area);
        span = Span.CELLS;
        fire();
    }

    private Span defaultSpan() {
        return switch (getMode()) {
            case ROW -> Span.ROWS;
            case COLUMN -> Span.COLUMNS;
            case SINGLE_CELL, AREA -> Span.CELLS;
        };
    }

    private Area area(int row1, int column1, int row2, int column2) {
        return switch (span) {
            case CELLS -> new Area(CellRange.of(row1, column1, row2, column2), false, false);
            case ROWS -> new Area(CellRange.of(row1, 0, row2, Math.max(0, columnCount.getAsInt() - 1)), false, true);
            case COLUMNS -> new Area(CellRange.of(0, column1, Math.max(0, rowCount.getAsInt() - 1), column2), true,
                    false);
        };
    }

    private void fire() {
        for (InvalidationListener listener : listeners) {
            listener.invalidated(this);
        }
    }
}
