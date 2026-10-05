package de.schladebeck.fx.grid.render;

import javafx.scene.canvas.GraphicsContext;

/// Paints the content of one cell, like Swing's `TableCellRenderer`, but draws directly instead of returning a
/// component. One instance serves all cells, so it must be stateless.
///
/// When it runs, the background and the selection are painted; grid lines, selection outlines and the focus
/// border come after all cells. The `GraphicsContext` starts in the same state for every cell: font,
/// fill and stroke from the context, text aligned left and centered on its baseline (`VPos.CENTER`), full
/// opacity, line width 1, no effect. A renderer may change these freely; anything it pushes with `save()` it
/// must `restore()`.
///
/// A renderer that throws is reported once and its cell stays empty; the other cells are still painted.
///
/// ## What costs time
///
/// A renderer runs for every visible cell on every frame. Measured with one renderer for all of about 850 cells
/// on screen (1280×800, narrow columns), vsync off, on the laptop the README describes, where the grid itself
/// takes 1.1 ms per frame; `RendererBenchmark` in the showcase module measures it on any machine:
///
/// | Drawing in every cell | Time per frame |
/// |---|---:|
/// | rectangles, lines, ovals, rounded rectangles, an image made once | 1.2 ms, as much as nothing |
/// | text (`fillText`, [CellRenderers#drawText]) | 1.6 ms |
/// | text in three fonts taking turns | 1.3 to 1.8 ms |
/// | text inside a clip (`clip()`) | 1.7 to 2.2 ms |
/// | a triangle (`fillPolygon`) | 2.4 ms |
/// | two lines as a path (`beginPath`, `lineTo`, `stroke`) | 4 ms |
/// | a polyline of 10 points (`strokePolyline`) | 5.5 ms |
/// | text with an effect (`setEffect(new DropShadow(...))`) | 34 to 37 ms |
/// | a snapshot of a `Label`, taken per cell | 720 ms |
///
/// The graphics card draws rectangles, lines, ovals, rounded rectangles, images and text. Paths, polygons and
/// polylines are rasterized on the CPU, and an effect renders each cell into a texture of its own. Prefer the first
/// group for anything in every cell: the check box of [CellRenderers#BOOLEAN] draws its mark as two lines because
/// as one path it doubled the time of a frame. Make colors, fonts and formats once, in fields, rather than per
/// cell.
///
/// ## Nodes in cells
///
/// A renderer only paints: the grid is one canvas, not a node per cell, so it cannot hold a `Button`, a
/// `ComboBox` or any other node. For the look of a node, draw a picture of it: take `node.snapshot(null, null)`
/// once per distinct value, keep the images in a map and draw them with `gc.drawImage`. Drawing a kept image costs
/// nothing measurable; a snapshot per cell and frame makes the grid unusable. For interaction there are other
/// ways: in-place editing ([de.schladebeck.fx.grid.Grid#setEditable(boolean)]), the cell action
/// ([de.schladebeck.fx.grid.Grid#setOnCellAction]), a cell's context menu
/// ([de.schladebeck.fx.grid.Grid#cellContextMenuFactoryProperty()]) and a node in its tooltip
/// ([de.schladebeck.fx.grid.Grid#tooltipGraphicProviderProperty()]); a form next to the grid suits anything bigger.
@FunctionalInterface
public interface CellRenderer {

    /** {@code cell} is reused for the next cell, do not keep a reference to it. */
    void paint(GraphicsContext gc, CellContext cell);
}
