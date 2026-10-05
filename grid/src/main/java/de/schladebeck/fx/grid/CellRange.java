package de.schladebeck.fx.grid;

/// Rectangular block of cells in view coordinates, all bounds inclusive.
///
/// @param firstRow    first view row
/// @param firstColumn first view column
/// @param lastRow     last view row
/// @param lastColumn  last view column
public record CellRange(int firstRow, int firstColumn, int lastRow, int lastColumn) {

    /** Checks that the range holds at least one cell. */
    public CellRange {
        if (firstRow > lastRow || firstColumn > lastColumn) {
            throw new IllegalArgumentException("Empty range: " + firstRow + "," + firstColumn
                    + " to " + lastRow + "," + lastColumn);
        }
    }

    /** Range spanning two corner cells given in any order. */
    public static CellRange of(int row1, int column1, int row2, int column2) {
        return new CellRange(Math.min(row1, row2), Math.min(column1, column2),
                Math.max(row1, row2), Math.max(column1, column2));
    }

    /** Whether the range contains the view cell. */
    public boolean contains(int row, int column) {
        return row >= firstRow && row <= lastRow && column >= firstColumn && column <= lastColumn;
    }

    /** Number of rows in the range. */
    public int rowCount() {
        return lastRow - firstRow + 1;
    }

    /** Number of columns in the range. */
    public int columnCount() {
        return lastColumn - firstColumn + 1;
    }

    /** Number of cells in the range; a {@code long}, ranges can span billions of cells. */
    public long cellCount() {
        return (long) rowCount() * columnCount();
    }
}
