package de.schladebeck.fx.grid.model;

/// Describes a change in a [GridModel]. Rows are model rows, both bounds inclusive.
///
/// @param source   the model that changed
/// @param type     what changed
/// @param firstRow first model row affected
/// @param lastRow  last model row affected
public record GridModelEvent(GridModel source, Type type, int firstRow, int lastRow) {

    /** What changed. */
    public enum Type {
        /** Rows {@code firstRow..lastRow} were inserted. */
        INSERT,
        /** Rows {@code firstRow..lastRow} were deleted. */
        DELETE,
        /** Values in rows {@code firstRow..lastRow} changed. */
        UPDATE,
        /** Any row may have changed, including the row count. The columns are the same. */
        DATA,
        /** Anything may have changed, including the columns. */
        STRUCTURE
    }
}
