package de.schladebeck.fx.grid.render;

/** Picks a style per cell. Called for every visible cell on every repaint, so it must be cheap. */
@FunctionalInterface
public interface CellStyleProvider {

    /** Returns a shared style instance, or {@code null} for the defaults. */
    CellStyle styleFor(int modelRow, int modelColumn, Object value);
}
