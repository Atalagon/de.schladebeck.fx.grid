package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRendererProvider;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import java.util.Comparator;
import javafx.util.StringConverter;

/// One column of a [Grid], addressed by its model index: its width and the limits for it, visibility, renderer
/// and header. Get it with [Grid#getColumn(int)]; setters return the column, so settings chain:
///
/// ```java
/// grid.getColumn(0).setWidth(80).setMaxWidth(120);
/// grid.getColumn(2).setRenderer(CellRenderers.number("#,##0.00")).setMinWidth(90)
///         .setHeaderStyle(CellStyle.DEFAULT.withAlignment(HPos.RIGHT));
/// ```
///
/// A column object belongs to the model the grid showed when it was taken; once the grid shows another model, or
/// the model no longer has the column, its methods throw `IllegalStateException`. It holds no state of its own:
/// two objects for the same column of the same model are equal.
public final class GridColumn {

    private final Grid grid;
    private final GridModel model;
    private final int modelIndex;

    GridColumn(Grid grid, int modelIndex) {
        this.grid = grid;
        this.model = grid.getModel();
        this.modelIndex = modelIndex;
    }

    /** Rejects use after the grid moved on to another model, or the model lost the column. */
    private void check() {
        if (grid.getModel() != model || modelIndex >= model.getColumnCount()) {
            throw new IllegalStateException("Column " + modelIndex + " is gone: the grid shows another model, or the"
                    + " model has fewer columns now");
        }
    }

    /** The column's index in the model; it does not change when the column is moved or hidden. */
    public int getModelIndex() {
        return modelIndex;
    }

    /** The column's position as shown, or -1 while it is hidden. */
    public int getViewIndex() {
        check();
        return grid.columns().toView(modelIndex);
    }

    /** The header text, from the model. */
    public String getName() {
        check();
        return grid.getModel().getColumnName(modelIndex);
    }

    /** Current width in pixels, also while hidden. */
    public double getWidth() {
        check();
        return grid.columns().modelWidth(modelIndex);
    }

    /// Sets the width, kept within [#getMinWidth()] and [#getMaxWidth()]. The column is no longer fitted
    /// automatically.
    ///
    /// @param width the width in pixels
    /// @return this column
    public GridColumn setWidth(double width) {
        check();
        requireWidth(width, false);
        grid.resizeColumn(modelIndex, width);
        return this;
    }

    /** Smallest width the user or the grid may give the column; 20 pixels by default. */
    public double getMinWidth() {
        check();
        return grid.columns().minWidth(modelIndex);
    }

    /** Sets the smallest width; a narrower column grows. Returns this column. */
    public GridColumn setMinWidth(double minWidth) {
        check();
        requireWidth(minWidth, false);
        grid.limitColumn(modelIndex, minWidth, Math.max(minWidth, getMaxWidth()));
        return this;
    }

    /** Largest width the user or the grid may give the column; unlimited by default. */
    public double getMaxWidth() {
        check();
        return grid.columns().maxWidth(modelIndex);
    }

    /** Sets the largest width; a wider column shrinks. Returns this column. */
    public GridColumn setMaxWidth(double maxWidth) {
        check();
        requireWidth(maxWidth, true);
        grid.limitColumn(modelIndex, Math.min(getMinWidth(), maxWidth), maxWidth);
        return this;
    }

    /** Whether the column is shown. */
    public boolean isVisible() {
        check();
        return grid.columns().isVisible(modelIndex);
    }

    /// Hides or shows the column. A hidden column keeps its width and position and comes back where it was. Keeps
    /// only whole-row selections.
    ///
    /// @param visible whether to show the column
    /// @return this column
    public GridColumn setVisible(boolean visible) {
        check();
        grid.setColumnVisible(modelIndex, visible);
        return this;
    }

    /** The column's own renderer, or {@code null} if the grid picks one. */
    public CellRenderer getRenderer() {
        check();
        return grid.renderers().column(modelIndex);
    }

    /// Sets the column's own renderer, which wins over the default renderers but not over a
    /// [CellRendererProvider]; `null` lets the grid pick again.
    ///
    /// @param renderer the renderer, or `null`
    /// @return this column
    public GridColumn setRenderer(CellRenderer renderer) {
        check();
        grid.setColumnRenderer(modelIndex, renderer);
        return this;
    }

    /** The header's own style, or {@code null} for the grid's header style. */
    public CellStyle getHeaderStyle() {
        check();
        return grid.settings().get(modelIndex, c -> c.headerStyle);
    }

    /// Gives the column header its own background, text color, font, font weight or posture and alignment.
    /// Components left `null` keep the grid's header style (`-grid-header-*`); `null` as a whole goes back to it.
    ///
    /// @param style the header style, or `null`
    /// @return this column
    public GridColumn setHeaderStyle(CellStyle style) {
        check();
        grid.editColumn(modelIndex, c -> c.headerStyle = style);
        return this;
    }

    /** The header's own renderer, or {@code null} for the grid's, see {@link Grid#headerRendererProperty()}. */
    public CellRenderer getHeaderRenderer() {
        check();
        return grid.settings().get(modelIndex, c -> c.headerRenderer);
    }

    /// Paints this column's header with its own renderer, for example with an icon or two lines of text;
    /// `null` goes back to the grid's header renderer. See [Grid#headerRendererProperty()] for what it gets.
    ///
    /// @param renderer the header renderer, or `null`
    /// @return this column
    public GridColumn setHeaderRenderer(CellRenderer renderer) {
        check();
        grid.editColumn(modelIndex, c -> c.headerRenderer = renderer);
        return this;
    }

    /** The column's own sort order, or {@code null} for the default. */
    @SuppressWarnings("unchecked")
    public <T> Comparator<T> getComparator() {
        check();
        return (Comparator<T>) (Comparator<?>) grid.columnComparator(modelIndex);
    }

    /// Sorts this column with its own order instead of the default (numbers by value, text ignoring case, other
    /// values by their natural order); `null` goes back to the default. Nulls come first in ascending order and
    /// last in descending order, and the comparator never sees them. If the grid is sorted by this column, it sorts
    /// again.
    ///
    /// ```java
    /// grid.getColumn("Name").setComparator(Collator.getInstance(Locale.GERMAN));
    /// ```
    ///
    /// @param comparator the order for the column's non-null values, or `null`
    /// @return this column
    @SuppressWarnings("unchecked")
    public GridColumn setComparator(Comparator<?> comparator) {
        check();
        grid.setColumnComparator(modelIndex, (Comparator<Object>) comparator);
        return this;
    }

    /** The converter used for editing this column in place, or {@code null} for the built-in one. */
    @SuppressWarnings("unchecked")
    public <T> StringConverter<T> getConverter() {
        check();
        return (StringConverter<T>) (StringConverter<?>) grid.settings().get(modelIndex, c -> c.converter);
    }

    /// Converts between values and text for editing this column in place: `toString` fills the editor,
    /// `fromString` reads what was typed, and an exception from it marks the input invalid. Without one, the
    /// grid edits text, numbers in the default locale's format or plain, booleans, enum constants by name, and
    /// ISO or localized dates and times; other types are not edited.
    ///
    /// @param converter the converter, or `null` for the built-in one
    /// @return this column
    @SuppressWarnings("unchecked")
    public GridColumn setConverter(StringConverter<?> converter) {
        check();
        grid.settings().edit(modelIndex, c -> c.converter = (StringConverter<Object>) converter);
        return this;
    }

    /** A value fitting also measures, or {@code null}. */
    public Object getPrototypeValue() {
        check();
        return grid.settings().get(modelIndex, c -> c.prototypeValue);
    }

    /// A typical widest value that fitting measures in addition to the first rows, for example the largest ID
    /// of a long list whose first rows have short ones. It is never shown.
    ///
    /// @param value the value, or `null`
    /// @return this column
    public GridColumn setPrototypeValue(Object value) {
        check();
        grid.editColumn(modelIndex, c -> c.prototypeValue = value);
        return this;
    }

    /// Fits the column to its header and the rows on screen, measured with its renderers, within its minimum and
    /// maximum width.
    ///
    /// @return this column
    public GridColumn fitToContent() {
        check();
        grid.fitColumn(modelIndex);
        return this;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GridColumn column && column.grid == grid && column.model == model
                && column.modelIndex == modelIndex;
    }

    @Override
    public int hashCode() {
        return (System.identityHashCode(grid) * 31 + System.identityHashCode(model)) * 31 + modelIndex;
    }

    @Override
    public String toString() {
        return "GridColumn[" + modelIndex + "]";
    }

    private static void requireWidth(double width, boolean infinite) {
        if (Double.isNaN(width) || width < 0 || !infinite && Double.isInfinite(width)) {
            throw new IllegalArgumentException("Not a column width: " + width);
        }
    }
}
