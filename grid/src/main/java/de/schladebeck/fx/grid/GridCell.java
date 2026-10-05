package de.schladebeck.fx.grid;

/// A cell in view coordinates (as shown) and model coordinates (in the data), with its value. Configuration and
/// the model take model indices; [Grid#getColumnAtView] and the selection take view indices.
///
/// @param viewRow     view row
/// @param viewColumn  view column
/// @param modelRow    model row
/// @param modelColumn model column
/// @param value       the cell's value
public record GridCell(int viewRow, int viewColumn, int modelRow, int modelColumn, Object value) {
}
