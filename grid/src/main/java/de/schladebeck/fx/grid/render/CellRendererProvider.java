package de.schladebeck.fx.grid.render;

/// Picks the renderer per cell, for example by row or by value. Called for every visible cell on every repaint,
/// so return shared renderer instances.
///
/// @see de.schladebeck.fx.grid.Grid#setCellRendererProvider
@FunctionalInterface
public interface CellRendererProvider {

    /// Picks the renderer for one cell.
    ///
    /// @param modelRow    model row
    /// @param modelColumn model column
    /// @param value       the cell's value
    /// @return the renderer for this cell, or `null` for the grid's default choice
    CellRenderer rendererFor(int modelRow, int modelColumn, Object value);
}
