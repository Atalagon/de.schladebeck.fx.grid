/// Painting cells and column headers. A [CellRenderer] draws one cell onto the grid's canvas, given a
/// [CellContext] with the cell's bounds, value, font and colors. [CellRenderers] has the built-in renderers and the
/// text helpers that shorten or wrap text and feed tooltips and column fitting.
///
/// Which renderer and which style a cell gets is decided per cell: a [CellRendererProvider] or the column's
/// renderer, and a [CellStyleProvider] returning shared [CellStyle] constants. Styles set colors, a font, or only
/// its weight and posture, which keeps the grid's font size so bold text follows the theme.
///
/// ```java
/// CellStyle negative = CellStyle.DEFAULT.withTextFill(Color.web("#cf222e")).withFontWeight(FontWeight.BOLD);
/// grid.setCellStyleProvider((row, column, value) -> value instanceof Double d && d < 0 ? negative : null);
/// grid.getColumn(3).setRenderer(CellRenderers.number("#,##0.00"))
///         .setHeaderStyle(CellStyle.DEFAULT.withAlignment(HPos.RIGHT));
/// ```
///
/// Renderers run for every visible cell in every frame. They must not allocate much, must not keep the context,
/// and should leave cutting text to [CellContext#fitText(String)] or, for text in another font,
/// [CellContext#fitText(String, javafx.scene.text.Font)], so tooltips and fitting keep working. [TextMetrics]
/// measures text with the same rules, for example to compute row heights. [CellContext#indent] makes room for
/// something painted before the content, such as an icon, so the text is fitted into the rest.
///
/// ## Implementation notes
///
/// [CellContext] is abstract so the painter can fill in one instance for every cell without setters in the API.
/// Fonts with a style's weight or posture are derived once per style instance and base font and then cached; that
/// is why styles should be constants and not built per cell.
package de.schladebeck.fx.grid.render;

