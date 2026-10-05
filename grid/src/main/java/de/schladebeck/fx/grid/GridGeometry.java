package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.internal.ColumnLayout;
import de.schladebeck.fx.grid.internal.Spans;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;

/// The viewport math: which rows and columns are visible, where they are, what is under the mouse, how far to
/// scroll. Captured once per layout pass and shared by painting, input and the public API, so they agree with
/// what is on screen.
///
/// Two coordinate systems: canvas pixels (mouse events, [Bounds], zoom applied) and unzoomed pixels
/// (what the painter draws in; zoom is a canvas transform). Methods with "canvas" in a parameter name take
/// canvas pixels, everything else is unzoomed. Rows and columns are view rows and columns. Frozen rows stay at the
/// top and frozen columns at the left; the others scroll, and their positions start after the frozen ones.
final class GridGeometry {

    private static final double RESIZE_MARGIN = 4;

    private ColumnLayout columns = new ColumnLayout();
    private Spans rows = new Spans();
    private int frozen;
    private int frozenRows;
    private double headerHeight;
    private double rowHeaderWidth;
    private double frozenWidth;
    private double frozenHeight;
    private double zoom = 1;
    private double width;
    private double height;
    private double snapScale = 1;
    private double line = 1;
    private double scrollX;
    private double scrollY;

    void update(ColumnLayout columns, Spans rows, int frozenColumns, int frozenRows, double headerHeight,
                double rowHeaderWidth, double zoom, double canvasWidth, double canvasHeight, double renderScale) {
        this.columns = columns;
        this.rows = rows;
        this.frozen = Math.clamp(frozenColumns, 0, columns.count());
        this.frozenRows = Math.clamp(frozenRows, 0, rows.count());
        this.headerHeight = headerHeight;
        this.rowHeaderWidth = rowHeaderWidth;
        this.frozenWidth = columns.start(frozen);
        this.frozenHeight = rows.start(this.frozenRows);
        this.zoom = zoom;
        this.width = canvasWidth / zoom;
        this.height = canvasHeight / zoom;
        this.snapScale = renderScale * zoom;
        this.line = Math.max(1, Math.round(renderScale)) / snapScale;
    }

    /** Sets the scroll offsets, clamped to what can be scrolled. */
    void setScroll(double x, double y) {
        scrollX = Double.isNaN(x) ? 0 : Math.clamp(x, 0, maxScrollX());
        scrollY = Double.isNaN(y) ? 0 : Math.clamp(y, 0, maxScrollY());
    }

    // ---- sizes

    double zoom() {
        return zoom;
    }

    double width() {
        return width;
    }

    double height() {
        return height;
    }

    /** One device pixel line width, at least one logical pixel. */
    double line() {
        return line;
    }

    int rowCount() {
        return rows.count();
    }

    double headerHeight() {
        return headerHeight;
    }

    double rowHeaderWidth() {
        return rowHeaderWidth;
    }

    int frozenColumns() {
        return frozen;
    }

    double frozenWidth() {
        return frozenWidth;
    }

    int frozenRows() {
        return frozenRows;
    }

    double frozenHeight() {
        return frozenHeight;
    }

    double scrollX() {
        return scrollX;
    }

    double scrollY() {
        return scrollY;
    }

    double bodyHeight() {
        return Math.max(0, height - headerHeight);
    }

    /** Height available to the scrolling (non-frozen) rows. */
    double scrollableHeight() {
        return Math.max(0, bodyHeight() - frozenHeight);
    }

    /** Width available to the scrolling (non-frozen) columns. */
    double scrollableWidth() {
        return Math.max(0, width - rowHeaderWidth - frozenWidth);
    }

    double maxScrollX() {
        return Math.max(0, columns.total() - frozenWidth - scrollableWidth());
    }

    double maxScrollY() {
        return Math.max(0, rows.total() - bodyHeight());
    }

    // ---- visible range

    /** First non-frozen row at least partly visible below the frozen rows. */
    int firstScrolledRow() {
        return Math.max(frozenRows, rows.indexAt(frozenHeight + scrollY));
    }

    /** Last non-frozen row at least partly visible, or {@code firstScrolledRow() - 1} if none. */
    int lastScrolledRow() {
        if (scrollableHeight() <= 0 || frozenRows >= rows.count()) {
            return firstScrolledRow() - 1;
        }
        int last = rows.indexAt(scrollY + bodyHeight());
        return last < 0 ? rows.count() - 1 : last;
    }

    /** Last frozen row at least partly visible, or -1 if none. */
    int lastFrozenRow() {
        if (frozenRows == 0 || bodyHeight() <= 0) {
            return -1;
        }
        int last = rows.indexAt(bodyHeight());
        return last < 0 ? frozenRows - 1 : Math.min(last, frozenRows - 1);
    }

    /** Whether a row is on screen, frozen or scrolled. */
    boolean showsRow(int row) {
        return row <= lastFrozenRow() || row >= firstScrolledRow() && row <= lastScrolledRow();
    }

    /** First non-frozen column at least partly visible, or -1. */
    int firstScrolledColumn() {
        if (frozen >= columns.count() || rowHeaderWidth + frozenWidth >= width) {
            return -1;
        }
        return columns.columnAt(frozenWidth + scrollX);
    }

    int lastScrolledColumn() {
        double right = width - rowHeaderWidth + scrollX;
        return right >= columns.total() ? columns.count() - 1 : columns.columnAt(right);
    }

    // ---- positions, unzoomed

    private double columnX(int column) {
        return rowHeaderWidth + columns.start(column) - (column < frozen ? 0 : scrollX);
    }

    private double rowY(int row) {
        return headerHeight + rows.start(row) - (row < frozenRows ? 0 : scrollY);
    }

    /** Snapped left edge of a column. */
    double left(int column) {
        return snap(columnX(column));
    }

    /** Snapped right edge of a column, including its grid line. */
    double right(int column) {
        return snap(columnX(column) + columns.width(column));
    }

    /** Snapped top edge of a row; {@code top(row + 1)} is its bottom, unless {@code row} is the last frozen row. */
    double top(int row) {
        return snap(rowY(row));
    }

    /** Snapped bottom edge of a row, including its grid line. */
    double bottom(int row) {
        return snap(rowY(row) + rows.size(row));
    }

    double snap(double value) {
        return Math.round(value * snapScale) / snapScale;
    }

    // ---- hit testing, canvas pixels

    boolean inColumnHeader(double canvasY) {
        return canvasY / zoom < headerHeight;
    }

    boolean inRowHeader(double canvasX) {
        return canvasX / zoom < rowHeaderWidth;
    }

    /** Row under a canvas y, or -1 in the header and below the last row. */
    int rowAt(double canvasY) {
        double y = canvasY / zoom - headerHeight;
        if (y < 0) {
            return -1;
        }
        return y < frozenHeight ? rows.indexAt(y) : rows.indexAt(y + scrollY);
    }

    /** Column under a canvas x, or -1 in the row header and right of the last column. */
    int columnAt(double canvasX) {
        double x = canvasX / zoom - rowHeaderWidth;
        if (x < 0) {
            return -1;
        }
        return x < frozenWidth ? columns.columnAt(x) : columns.columnAt(x + scrollX);
    }

    /** Column whose right border is within the resize margin of a canvas x, or -1. */
    int resizeBorderAt(double canvasX) {
        double x = canvasX / zoom;
        double margin = RESIZE_MARGIN / zoom;
        for (int candidate : new int[] {columnAt(canvasX), columnAt(canvasX - RESIZE_MARGIN)}) {
            if (candidate >= 0 && Math.abs(columnX(candidate) + columns.width(candidate) - x) <= margin) {
                return candidate;
            }
        }
        return -1;
    }

    /** Gap between columns nearest to a canvas x, 0 to count, for dropping a moved column. */
    int dropGapAt(double canvasX) {
        int column = columnAt(canvasX);
        if (column < 0) {
            return canvasX / zoom < rowHeaderWidth ? 0 : columns.count();
        }
        double middle = columnX(column) + columns.width(column) / 2;
        return canvasX / zoom < middle ? column : column + 1;
    }

    /** Canvas pixel bounds of a cell, also when scrolled out of view. */
    Bounds cellBounds(int row, int column) {
        return new BoundingBox(columnX(column) * zoom, rowY(row) * zoom,
                columns.width(column) * zoom, rows.size(row) * zoom);
    }

    // ---- scrolling

    /** The row about one screen below {@code row}, by the real row heights: Page Down. */
    int pageDown(int row) {
        int target = rows.indexAt(rows.start(row) + scrollableHeight());
        return target < 0 ? rows.count() - 1 : Math.max(row + 1, target - 1);
    }

    /** The row about one screen above {@code row}, by the real row heights: Page Up. */
    int pageUp(int row) {
        double position = rows.start(row) - scrollableHeight();
        return position <= 0 ? 0 : Math.max(0, Math.min(row - 1, rows.indexAt(position) + 1));
    }

    /** Vertical offset that shows {@code row} fully with the least scrolling; frozen rows are always visible. */
    double scrollYToShow(int row, double current) {
        if (row < frozenRows) {
            return current;
        }
        double top = rows.start(row) - frozenHeight;
        double bottom = top + rows.size(row);
        double result = current;
        if (bottom > result + scrollableHeight()) {
            result = bottom - scrollableHeight();
        }
        return Math.min(result, top);
    }

    /** Horizontal offset that shows {@code column}; frozen columns are always visible. */
    double scrollXToShow(int column, double current) {
        if (column < frozen) {
            return current;
        }
        double left = columns.start(column) - frozenWidth;
        double result = current;
        if (left + columns.width(column) > result + scrollableWidth()) {
            result = left + columns.width(column) - scrollableWidth();
        }
        return Math.min(result, left);
    }
}
