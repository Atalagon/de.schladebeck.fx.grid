package de.schladebeck.fx.grid.model;

/// One column of a sort order: rows are sorted by a list of these, the first one decides and the next ones break
/// its ties.
///
/// @param column    the model column
/// @param ascending the direction
public record SortKey(int column, boolean ascending) {
}
