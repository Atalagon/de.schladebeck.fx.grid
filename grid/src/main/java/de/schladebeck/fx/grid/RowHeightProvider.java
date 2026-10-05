package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.render.TextMetrics;

/// Gives rows their own height, e.g. room for wrapped text. Only heights that differ from the grid's row height are
/// stored, so rows the provider leaves alone cost nothing.
///
/// Up to 1,000 rows inserted or deleted at once move the stored heights along, and only new rows are asked;
/// updated rows are asked again. The grid asks for every row when more rows are inserted or deleted at once, when
/// all rows change or are sorted or filtered, when the provider, the row height or a column width changes, and on
/// [Grid#refreshRowHeights()]. For millions of rows it must be fast.
///
/// @see Grid#setRowHeightProvider
/// @see TextMetrics
@FunctionalInterface
public interface RowHeightProvider {

    /// The height of one row.
    ///
    /// @param modelRow model row
    /// @return the row's height in pixels, or 0 or less for the grid's row height
    double heightOf(int modelRow);
}
