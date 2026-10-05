package de.schladebeck.fx.grid.render;

import java.util.List;
import javafx.geometry.HPos;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/// Everything a [CellRenderer] needs to paint one cell or one column header. The grid fills in one instance and
/// reuses it for every cell, so painting does not allocate. Bounds exclude the grid lines. Font, text fill and
/// alignment already include the cell style.
///
/// The grid provides the implementation; a test of a renderer can provide its own.
public abstract class CellContext {

    /** For implementations; renderers get their context from the grid. */
    protected CellContext() {
    }

    /// View row, as shown (after filtering and sorting), or -1 for the column header and for a prototype value
    /// measured when fitting the column.
    public abstract int getViewRow();

    /** View column, as shown (after reordering). */
    public abstract int getViewColumn();

    /** Row in the model, -1 like {@link #getViewRow()}. */
    public abstract int getModelRow();

    /** Column in the model. */
    public abstract int getModelColumn();

    /** The cell's value, may be {@code null}; the column name for the column header. */
    public abstract Object getValue();

    /** Left edge of the cell, in canvas coordinates without zoom. */
    public abstract double getX();

    /** Top edge of the cell, in canvas coordinates without zoom. */
    public abstract double getY();

    /** Width of the cell without its grid line, and without the sort arrow in a header. */
    public abstract double getWidth();

    /** Height of the cell without its grid line. */
    public abstract double getHeight();

    /** Horizontal padding on each side of the content. */
    public abstract double getPadding();

    /// Whether the cell is painted as selected; its background is already painted. While the grid has the focus,
    /// the focused cell of a selection of several cells is painted as not selected (except in row mode), so its
    /// border stands out.
    public abstract boolean isSelected();

    /** Whether the cell has the keyboard focus; its border is painted afterwards. */
    public abstract boolean isFocused();

    /// Whether the user can edit the cell in place: the grid is editable and the model allows it. A context that
    /// does not override this, such as one in a renderer test, says `true`.
    public boolean isEditable() {
        return true;
    }

    /// The fraction digits [CellRenderers#NUMBER] shows at least, so that decimal separators line up: the most
    /// any decimal number on screen in this column needs. 0 for headers and for a context that does not override
    /// this.
    public int getFractionDigits() {
        return 0;
    }

    /** Font from the cell style or the grid. */
    public abstract Font getFont();

    /** Text color from the cell style or the grid. */
    public abstract Color getTextFill();

    /** Alignment from the cell style, or {@code null} to let the renderer decide. */
    public abstract HPos getAlignment();

    /// Shortens `text` with an ellipsis so it fits between the paddings in the cell's font. The grid remembers the
    /// full text for the tooltip and its width for fitting the column.
    public String fitText(String text) {
        return fitText(text, getFont());
    }

    /// Like [#fitText(String)], for text drawn in another font than the cell's, such as a smaller second line in
    /// a header. Fitting the column counts this text too. If several texts of a cell are cut off, the tooltip shows
    /// the first one.
    ///
    /// @param text the text to fit
    /// @param font the font the renderer draws `text` with
    /// @return `text`, or a prefix plus an ellipsis, or "" if not even the ellipsis fits
    public abstract String fitText(String text, Font font);

    /// Wraps `text` into as many lines as fit the cell's height, each fitting between the paddings. If the
    /// text needs more lines, the last one ends in an ellipsis and the grid remembers the full text for the tooltip.
    public abstract List<String> wrapText(String text);

    /** Height of one line of text in the current font. */
    public abstract double getLineHeight();

    /// Moves the start of the cell's content right by `width`, for something a renderer paints before it, such as
    /// an icon or the indentation of a tree. [#getX()] and [#getWidth()] change accordingly, [#fitText(String)]
    /// fits into what is left, and fitting the column to its content counts the width.
    ///
    /// @param width the space taken at the left
    public abstract void indent(double width);

    /// The nearest position on the screen's pixel grid, taking zoom and display scaling into account. Lines and
    /// boxes drawn between snapped positions stay sharp. Without a grid (a test context) positions are rounded to
    /// whole numbers.
    ///
    /// @param position an x or y coordinate in the cell's coordinate system
    /// @return the snapped coordinate
    public double snap(double position) {
        return Math.round(position);
    }
}
