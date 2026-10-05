package de.schladebeck.fx.grid;

/** What a click selects and how Shift and Ctrl extend it. */
public enum SelectionMode {

    /** One cell at a time. Shift and Ctrl just move the selection. */
    SINGLE_CELL,

    /** Whole rows. Shift extends, Ctrl adds further rows. */
    ROW,

    /** Whole columns. Shift extends, Ctrl adds further columns. */
    COLUMN,

    /** Rectangular cell ranges like a spreadsheet. Shift extends, Ctrl adds further ranges. */
    AREA
}
