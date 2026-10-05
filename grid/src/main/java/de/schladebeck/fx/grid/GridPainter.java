package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.internal.ColumnLayout;
import de.schladebeck.fx.grid.internal.Numbers;
import de.schladebeck.fx.grid.internal.TextFitter;
import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.model.SortKey;
import de.schladebeck.fx.grid.render.CellContext;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRendererProvider;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import de.schladebeck.fx.grid.render.CellStyleProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/// Paints the visible part of the grid into the canvas, at most once per frame. Positions come from
/// [GridGeometry]; zoom is a canvas transform.
///
/// Each region (scrolled columns, frozen columns) is painted in three passes: backgrounds, renderers column by
/// column, then grid lines, selection outlines and the focus border. Switching between rectangles and text flushes
/// the graphics pipeline's batch, so grouping them saves up to half the render time with many cells on screen.
/// Values and styles are fetched once per cell, in the first pass.
///
/// Nothing is clipped (canvas clipping cost up to 45% of a frame in early measurements): text is shortened to
/// fit, and regions painted later (frozen columns, row header, column header) cover what scrolled under them.
///
/// [CellRenderer]s paint cells and headers through one reused [Cell]. An exception from a renderer, a style
/// provider or the model is reported once and leaves only that cell empty. The painter remembers the full text of
/// cut-off cells for tooltips. [#measure] runs the same renderers on a hidden canvas to find column widths, and
/// [#textOf] to find what a cell shows.
final class GridPainter {

    /** Width of the sort arrow and its gap at header font size 12; it grows with the font. */
    private static final double SORT_ARROW_SPACE = 12;
    /** Gap between the header text and the place in the sort order at header font size 12. */
    private static final double SORT_NUMBER_GAP = 4;
    private static final double MEASURE_WIDTH = 1e6;
    /** Fitting a column never needs more than this; measuring longer text stops early. */
    private static final double MEASURE_CAP = 10_000;
    /** Styles are meant to be shared constants; more distinct ones than this per font are not worth caching. */
    private static final int MAX_DERIVED_FONTS = 256;

    private final Grid grid;
    private final TextFitter fitter;
    private final Cell cell = new Cell();
    private final Canvas scratch = new Canvas(1, 1);
    private final double[] arrowX = new double[3];
    private final double[] arrowY = new double[3];
    private final Map<Long, String> cutOffTexts = new HashMap<>();
    /** Fonts derived with a style's weight and posture, per base font and style instance. */
    private final Map<Font, Map<CellStyle, Font>> derivedFonts = new HashMap<>();
    /** Selected ranges that touch the visible rows, collected once per frame. */
    private final List<CellRange> visibleRanges = new ArrayList<>();
    /** The built-in header renderer: the column name aligned like the column's values. */
    private final CellRenderer headerText = (gc, c) -> {
        if (c.getValue() != null) {
            CellRenderers.drawText(gc, c, c.getValue().toString(), headerAlignment(c.getModelColumn()));
        }
    };
    /** The focused cell while it is painted as not selected, see {@link #collectVisibleRanges}; -1 for none. */
    private int plainRow = -1;
    private int plainColumn = -1;
    private CellStyle lastStyle;
    private Font lastBase;
    private Font lastFont;
    private Font sortNumberFont;
    private Font sortNumberFontFor;
    private Object[] values = new Object[0];
    private CellStyle[] styles = new CellStyle[0];
    /** Snapped tops of the visible rows and the bottom of the last one, collected once per frame. */
    private double[] rowTops = new double[0];
    /** Snapped left and right edges of the columns of the region being painted. */
    private double[] columnLefts = new double[0];
    private double[] columnRights = new double[0];
    /** Group names per level of the columns of the header region being painted and of the column after them. */
    private String[][] groupNames = new String[0][];
    /** Derived from the header background once per frame. */
    private Color headerLineColor;
    private Color headerHighlight;
    private GridGeometry g;
    private int dropGap = -1;
    private int ghostColumn = -1;
    private double ghostX;

    // Measuring (column widths) and capturing (the text a cell shows) run renderers on the scratch canvas.
    private boolean measuring;
    private boolean measuredValues;
    /** Wanted widths during {@link #measure}, at the measured column's position in the request. */
    private double[] wanted;
    private int measuredSlot;
    private StringBuilder captured;

    GridPainter(Grid grid) {
        this.grid = grid;
        this.fitter = grid.textFitter();
    }

    /** Marks where a dragged column would be dropped, -1 for none. */
    void setDropGap(int gap) {
        dropGap = gap;
    }

    /** Paints a copy of a view column's header centered at a canvas x while it is dragged, -1 for none. */
    void setDragGhost(int column, double canvasX) {
        ghostColumn = column;
        ghostX = canvasX;
    }

    /// Full text of a cell cut off in the last frame, or `null`. Row -1 is the column header, column -1 the
    /// row header.
    String cutOffText(int row, int column) {
        return cutOffTexts.get(key(row, column));
    }

    void paint(GraphicsContext gc, GridGeometry geometry) {
        g = geometry;
        cutOffTexts.clear();
        double zoom = g.zoom();
        double w = g.width();
        double h = g.height();
        // Clearing the whole canvas with an identity transform also resets the canvas command buffer.
        gc.setTransform(1, 0, 0, 1, 0, 0);
        gc.clearRect(0, 0, w * zoom, h * zoom);
        gc.setTransform(zoom, 0, 0, zoom, 0, 0);
        gc.setGlobalAlpha(1);
        gc.setFill(grid.getCellBackground());
        gc.fillRect(0, 0, w, h);
        if (grid.getModel() == null) {
            return;
        }

        Color headerBackground = grid.getHeaderBackground();
        headerLineColor = stronger(headerBackground, headerBackground, 0.18);
        headerHighlight = headerBackground.interpolate(grid.getSelectionBackground(), 0.6);
        int firstRow = g.firstScrolledRow();
        int lastRow = g.lastScrolledRow();
        int lastFrozenRow = g.lastFrozenRow();
        int firstScrolled = g.firstScrolledColumn();
        int lastScrolled = g.lastScrolledColumn();
        double headerHeight = g.headerHeight();
        collectVisibleRanges(lastFrozenRow >= 0 ? 0 : firstRow, lastRow);
        // The scrolled rows first, then the frozen rows on top of what scrolled under them.
        paintRows(gc, firstRow, lastRow, firstScrolled, lastScrolled, h);
        double frozenBottom = g.snap(headerHeight + g.frozenHeight());
        if (lastFrozenRow >= 0) {
            gc.setFill(grid.getCellBackground());
            gc.fillRect(g.rowHeaderWidth(), headerHeight, w - g.rowHeaderWidth(), frozenBottom - headerHeight);
            paintRows(gc, 0, lastFrozenRow, firstScrolled, lastScrolled, frozenBottom);
        }
        if (headerHeight > 0) {
            paintColumnHeader(gc, firstScrolled, lastScrolled);
        }
        Color frozenLine = stronger(grid.getGridLineColor(), grid.getCellBackground(), 0.3);
        if (g.frozenColumns() > 0) {
            double right = g.snap(g.rowHeaderWidth() + g.frozenWidth());
            gc.setFill(frozenLine);
            gc.fillRect(right - g.line(), 0, g.line(), h);
        }
        if (g.frozenRows() > 0) {
            gc.setFill(frozenLine);
            gc.fillRect(0, frozenBottom - g.line(), w, g.line());
        }
        int columnCount = grid.columns().count();
        if (dropGap >= 0 && columnCount > 0) {
            double x = dropGap < columnCount ? g.left(dropGap) : g.right(columnCount - 1);
            gc.setFill(grid.getFocusColor());
            gc.fillRect(x - g.line(), 0, 2 * g.line(), h);
        }
        if (ghostColumn >= 0 && ghostColumn < columnCount && headerHeight > 0) {
            paintGhost(gc);
        }
        visibleRanges.clear();
        cell.value = null; // keeps no model value alive between frames
    }

    /// How wide the given model columns want to be for their header, view rows
    /// `firstRow..firstRow+rowCount-1` and their prototype value: runs the renderers against a canvas that is never
    /// shown, with room for any text. [#measuredValues()] tells whether any of the measured cells had a value.
    double[] measure(int firstRow, int rowCount, int[] modelColumns) {
        GridModel model = grid.getModel();
        ColumnLayout columns = grid.columns();
        double[] result = new double[modelColumns.length];
        measuredValues = false;
        if (model == null) {
            return result;
        }
        wanted = result;
        GraphicsContext gc = scratch.getGraphicsContext2D();
        int lastRow = Math.min(grid.getViewRowCount(), firstRow + rowCount) - 1;
        double height = grid.getRowHeight() - 1;
        measuring = true;
        try {
            for (measuredSlot = 0; measuredSlot < modelColumns.length; measuredSlot++) {
                int modelColumn = modelColumns[measuredSlot];
                int column = columns.toView(modelColumn);
                paintWith(gc, prepareHeader(column, modelColumn, model.getColumnName(modelColumn), 0, 0,
                        MEASURE_WIDTH, grid.getHeaderHeight() - 1), "header renderer");
                wanted[measuredSlot] += sortSpace(grid.getSortKeys(), modelColumn);
                int digits = 0;
                for (int row = firstRow; row <= lastRow; row++) {
                    digits = Math.max(digits, neededDigits(valueAt(model, grid.viewToModelRow(row), modelColumn)));
                }
                for (int row = firstRow; row <= lastRow; row++) {
                    int modelRow = grid.viewToModelRow(row);
                    Object value = valueAt(model, modelRow, modelColumn);
                    measuredValues |= value != null;
                    CellStyle style = styleFor(modelRow, modelColumn, value);
                    CellRenderer renderer = prepare(row, column, modelRow, modelColumn, value, style, 0, 0,
                            MEASURE_WIDTH, height, false, false);
                    cell.fractionDigits = digits;
                    paintWith(gc, renderer, "cell renderer");
                }
                Object prototype = grid.settings().get(modelColumn, c -> c.prototypeValue);
                if (prototype != null) {
                    paintWith(gc, prepare(-1, column, -1, modelColumn, prototype, null, 0, 0, MEASURE_WIDTH,
                            height, false, false), "cell renderer");
                }
            }
        } finally {
            // Reset, so fitting text in a later paint is not mistaken for measuring.
            measuring = false;
            wanted = null;
            cell.value = null;
            gc.clearRect(0, 0, 1, 1);
        }
        return result;
    }

    boolean measuredValues() {
        return measuredValues;
    }

    /// The text a view cell shows: what its renderer passes to [CellContext#fitText] or
    /// [CellContext#wrapText], for copying and for screen readers. Cells that draw no text (check boxes) give
    /// their value as text.
    String textOf(int row, int column) {
        GridModel model = grid.getModel();
        if (model == null || row < 0 || column < 0 || row >= grid.getViewRowCount()
                || column >= grid.getViewColumnCount()) {
            return "";
        }
        int modelRow = grid.viewToModelRow(row);
        int modelColumn = grid.viewToModelColumn(column);
        Object value = valueAt(model, modelRow, modelColumn);
        captured = new StringBuilder();
        try {
            paintWith(scratch.getGraphicsContext2D(), prepare(row, column, modelRow, modelColumn, value,
                    styleFor(modelRow, modelColumn, value), 0, 0, MEASURE_WIDTH, grid.getRowHeight() - 1, false,
                    false), "cell renderer");
            String text = captured.toString();
            if (!text.isEmpty()) {
                return text;
            }
        } finally {
            captured = null;
            cell.value = null;
            scratch.getGraphicsContext2D().clearRect(0, 0, 1, 1); // drops the recorded commands
        }
        if (value instanceof Boolean checked) {
            return Texts.get(checked ? "cell.checked" : "cell.unchecked");
        }
        return value == null ? "" : value.toString();
    }

    /** Called by renderers through {@link CellContext#fitText(String, Font)}. */
    private String fitCellText(String text, Font font) {
        if (captured != null) {
            captured.append(captured.isEmpty() ? "" : " ").append(text);
            return text;
        }
        if (measuring) {
            double needed = Math.min(fitter.width(text, font, MEASURE_CAP), MEASURE_CAP);
            // The grid line, and up to one device pixel that snapping the column edges can take away.
            wanted[measuredSlot] = Math.max(wanted[measuredSlot], needed + 2 * cell.padding + 2 + cell.indent);
            return text;
        }
        String fitted = fitter.fit(text, font, cell.width - 2 * cell.padding);
        if (fitted != text) {
            cutOffTexts.putIfAbsent(key(cell.row, cell.column), text);
        }
        return fitted;
    }

    /** Called by renderers through {@link CellContext#wrapText}. */
    private List<String> wrapCellText(String text) {
        if (captured != null || measuring) {
            List<String> lines = fitter.wrap(text, cell.font, Double.MAX_VALUE, Integer.MAX_VALUE);
            for (String line : lines) {
                fitCellText(line, cell.font);
            }
            return lines;
        }
        double available = cell.width - 2 * cell.padding;
        int maxLines = Math.max(1, (int) Math.floor(cell.height / fitter.lineHeight(cell.font)));
        List<String> lines = fitter.wrap(text, cell.font, available, maxLines);
        if (lines.getLast().endsWith(TextFitter.ELLIPSIS) && !text.endsWith(TextFitter.ELLIPSIS)) {
            cutOffTexts.put(key(cell.row, cell.column), text);
        }
        return lines;
    }

    // ---- cells

    private void collectVisibleRanges(int firstRow, int lastRow) {
        GridSelectionModel selection = grid.getSelectionModel();
        List<CellRange> ranges = selection.getRanges();
        visibleRanges.clear();
        for (CellRange range : ranges) {
            if (range.lastRow() >= firstRow && range.firstRow() <= lastRow) {
                visibleRanges.add(range);
            }
        }
        // As in Excel, the focused cell of a selection of several cells keeps its normal background, so its border
        // stands out. Not while no border is painted, and not in row mode, where the border goes around the row.
        boolean plain = grid.isFocused() && selection.getMode() != SelectionMode.ROW
                && (ranges.size() > 1 || ranges.size() == 1 && ranges.getFirst().cellCount() > 1);
        plainRow = plain ? selection.getFocusedRow() : -1;
        plainColumn = plain ? selection.getFocusedColumn() : -1;
    }

    /** Whether a cell is painted as selected: on the selection background, and so for its renderer. */
    private boolean selected(int row, int column) {
        if (row == plainRow && column == plainColumn) {
            return false;
        }
        for (int i = 0; i < visibleRanges.size(); i++) {
            if (visibleRanges.get(i).contains(row, column)) {
                return true;
            }
        }
        return false;
    }

    /// Fills [#rowTops] for rows `firstRow..lastRow` of one region, and the bottom of the last one; below the last
    /// frozen row the next row is a scrolled one, somewhere else.
    private void collectRowTops(int firstRow, int lastRow) {
        int count = lastRow - firstRow + 2;
        if (rowTops.length < count) {
            rowTops = new double[count];
        }
        for (int i = 0; i < count - 1; i++) {
            rowTops[i] = g.top(firstRow + i);
        }
        rowTops[count - 1] = lastRow >= firstRow ? g.bottom(lastRow) : g.top(firstRow);
    }

    /** Fills {@link #columnLefts} and {@link #columnRights} for the columns of a region. */
    private void collectColumnEdges(int firstColumn, int lastColumn) {
        int count = lastColumn - firstColumn + 1;
        if (columnLefts.length < count) {
            columnLefts = new double[count];
            columnRights = new double[count];
        }
        for (int i = 0; i < count; i++) {
            columnLefts[i] = g.left(firstColumn + i);
            columnRights[i] = g.right(firstColumn + i);
        }
    }

    /// The cells of rows `firstRow..lastRow` in the scrolled and the frozen columns, and their row headers. The
    /// frozen columns and the row header are filled down to `bottom` first, covering what scrolled under them.
    private void paintRows(GraphicsContext gc, int firstRow, int lastRow, int firstScrolled, int lastScrolled,
                           double bottom) {
        double headerHeight = g.headerHeight();
        collectRowTops(firstRow, lastRow);
        if (firstScrolled >= 0) {
            paintCells(gc, firstRow, lastRow, firstScrolled, lastScrolled);
        }
        if (g.frozenColumns() > 0) {
            double left = g.rowHeaderWidth();
            double right = g.snap(left + g.frozenWidth());
            gc.setFill(grid.getCellBackground());
            gc.fillRect(left, headerHeight, right - left, bottom - headerHeight);
            paintCells(gc, firstRow, lastRow, 0, g.frozenColumns() - 1);
        }
        if (g.rowHeaderWidth() > 0) {
            paintRowHeader(gc, firstRow, lastRow, bottom);
        }
    }

    private void paintCells(GraphicsContext gc, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        if (lastRow < firstRow) {
            return;
        }
        int cellCount = (lastRow - firstRow + 1) * (lastColumn - firstColumn + 1);
        if (values.length < cellCount) {
            values = new Object[cellCount];
            styles = new CellStyle[cellCount];
        }
        collectColumnEdges(firstColumn, lastColumn);
        paintBackgrounds(gc, firstRow, lastRow, firstColumn, lastColumn);
        paintRenderers(gc, firstRow, lastRow, firstColumn, lastColumn);
        Arrays.fill(values, 0, cellCount, null);
        Arrays.fill(styles, 0, cellCount, null);
        paintLines(gc, firstRow, lastRow, firstColumn, lastColumn);
    }

    /** Fills the cell backgrounds, fetching each cell's value and style once for the renderers. */
    private void paintBackgrounds(GraphicsContext gc, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        GridModel model = grid.getModel();
        ColumnLayout columns = grid.columns();
        Color alternate = grid.getAlternateCellBackground();
        Color columnStripe = grid.getAlternateColumnBackground();
        Color selectionBackground = grid.getSelectionBackground();
        int columnCount = lastColumn - firstColumn + 1;
        for (int r = firstRow; r <= lastRow; r++) {
            int modelRow = grid.viewToModelRow(r);
            double y = rowTops[r - firstRow];
            double yEnd = rowTops[r - firstRow + 1];
            for (int c = firstColumn; c <= lastColumn; c++) {
                int i = (r - firstRow) * columnCount + c - firstColumn;
                int modelColumn = columns.toModel(c);
                Object value = valueAt(model, modelRow, modelColumn);
                CellStyle style = styleFor(modelRow, modelColumn, value);
                values[i] = value;
                styles[i] = style;
                boolean plain = !selected(r, c) && (style == null || style.background() == null);
                Color background = !plain ? (selected(r, c) ? selectionBackground : style.background())
                        : (r & 1) == 1 ? alternate : null;
                double x = columnLefts[c - firstColumn];
                if (background != null) {
                    gc.setFill(background);
                    gc.fillRect(x, y, columnRights[c - firstColumn] - x, yEnd - y);
                }
                if (plain && columnStripe != null && (c & 1) == 1) { // over the row stripe, see-through if wanted
                    gc.setFill(columnStripe);
                    gc.fillRect(x, y, columnRights[c - firstColumn] - x, yEnd - y);
                }
            }
        }
    }

    /** Runs the cell renderers column by column: neighbors usually share renderer and font. */
    private void paintRenderers(GraphicsContext gc, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        ColumnLayout columns = grid.columns();
        GridSelectionModel selection = grid.getSelectionModel();
        int focusRow = selection.getFocusedRow();
        int focusColumn = selection.getFocusedColumn();
        int columnCount = lastColumn - firstColumn + 1;
        double line = g.line();
        for (int c = firstColumn; c <= lastColumn; c++) {
            int modelColumn = columns.toModel(c);
            double x = columnLefts[c - firstColumn];
            double width = columnRights[c - firstColumn] - x - line;
            int digits = 0;
            for (int i = c - firstColumn; i < (lastRow - firstRow + 1) * columnCount; i += columnCount) {
                digits = Math.max(digits, neededDigits(values[i]));
            }
            for (int r = firstRow; r <= lastRow; r++) {
                int i = (r - firstRow) * columnCount + c - firstColumn;
                double y = rowTops[r - firstRow];
                double height = rowTops[r - firstRow + 1] - y - line;
                CellRenderer renderer = prepare(r, c, grid.viewToModelRow(r), modelColumn, values[i], styles[i],
                        x, y, width, height, selected(r, c), r == focusRow && c == focusColumn);
                cell.fractionDigits = digits;
                paintWith(gc, renderer, "cell renderer");
            }
        }
    }

    /** The fraction digits a value needs if it is shown as a decimal number, else 0. */
    private static int neededDigits(Object value) {
        return Numbers.isDecimal(value) ? Numbers.neededFractionDigits(value) : 0;
    }

    /** Grid lines, and while the grid has the focus the outlines of selected ranges and the focus border. */
    private void paintLines(GraphicsContext gc, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        int rowCount = lastRow - firstRow + 1;
        int columnCount = lastColumn - firstColumn + 1;
        double line = g.line();
        double left = columnLefts[0];
        double right = columnRights[columnCount - 1];
        double top = rowTops[0];
        double bottom = rowTops[rowCount];
        gc.setGlobalAlpha(1);
        gc.setFill(grid.getGridLineColor());
        for (int i = 1; i <= rowCount; i++) {
            gc.fillRect(left, rowTops[i] - line, right - left, line);
        }
        for (int i = 0; i < columnCount; i++) {
            gc.fillRect(columnRights[i] - line, top, line, bottom - top);
        }

        if (grid.isFocused()) {
            gc.setFill(grid.getFocusColor());
            for (CellRange range : visibleRanges) {
                outline(gc, range, firstRow, lastRow, firstColumn, lastColumn, line);
            }
            paintFocus(gc, firstRow, lastRow, firstColumn, lastColumn, 2 * line);
        }
    }

    /** The edges of a selected range inside a region; edges where the range goes on outside the region are left out. */
    private void outline(GraphicsContext gc, CellRange range, int firstRow, int lastRow, int firstColumn,
                         int lastColumn, double t) {
        if (range.cellCount() == 1 || range.lastColumn() < firstColumn || range.firstColumn() > lastColumn) {
            return;
        }
        int r1 = Math.max(range.firstRow(), firstRow);
        int r2 = Math.min(range.lastRow(), lastRow);
        int c1 = Math.max(range.firstColumn(), firstColumn);
        int c2 = Math.min(range.lastColumn(), lastColumn);
        double x1 = columnLefts[c1 - firstColumn];
        double x2 = columnRights[c2 - firstColumn] - t;
        double y1 = rowTops[r1 - firstRow];
        double y2 = rowTops[r2 + 1 - firstRow] - t;
        if (range.firstRow() >= firstRow) {
            gc.fillRect(x1, y1, x2 - x1 + t, t);
        }
        if (range.lastRow() <= lastRow) {
            gc.fillRect(x1, y2, x2 - x1 + t, t);
        }
        if (range.firstColumn() >= firstColumn) {
            gc.fillRect(x1, y1, t, y2 - y1 + t);
        }
        if (range.lastColumn() <= lastColumn) {
            gc.fillRect(x2, y1, t, y2 - y1 + t);
        }
    }

    /** The focused cell's border; in row mode around the whole row, as far as it lies in this region. */
    private void paintFocus(GraphicsContext gc, int firstRow, int lastRow, int firstColumn, int lastColumn, double t) {
        GridSelectionModel selection = grid.getSelectionModel();
        int focusRow = selection.getFocusedRow();
        int focusColumn = selection.getFocusedColumn();
        if (focusRow < firstRow || focusRow > lastRow || focusColumn < 0) {
            return;
        }
        boolean wholeRow = selection.getMode() == SelectionMode.ROW;
        if (!wholeRow && (focusColumn < firstColumn || focusColumn > lastColumn)) {
            return;
        }
        int c1 = wholeRow ? firstColumn : focusColumn;
        int c2 = wholeRow ? lastColumn : focusColumn;
        double x = columnLefts[c1 - firstColumn];
        double w = columnRights[c2 - firstColumn] - x;
        double y = rowTops[focusRow - firstRow];
        double h = rowTops[focusRow + 1 - firstRow] - y;
        gc.fillRect(x, y, w, t);
        gc.fillRect(x, y + h - t, w, t);
        if (!wholeRow || c1 == 0) {
            gc.fillRect(x, y, t, h);
        }
        if (!wholeRow || c2 == grid.columns().count() - 1) {
            gc.fillRect(x + w - t, y, t, h);
        }
    }

    /// Fills the shared cell context, including the cell style, and returns the renderer: the grid's cell renderer
    /// provider if it picks one, otherwise the default choice for the column and value.
    private CellRenderer prepare(int row, int column, int modelRow, int modelColumn, Object value, CellStyle style,
                                 double x, double y, double width, double height, boolean selected, boolean focused) {
        fill(row, column, modelRow, modelColumn, value, style, x, y, width, height, grid.getFont(), grid.getTextFill());
        cell.selected = selected;
        cell.focused = focused;
        CellRendererProvider renderers = grid.getCellRendererProvider();
        CellRenderer renderer = null;
        if (renderers != null && modelRow >= 0) {
            try {
                renderer = renderers.rendererFor(modelRow, modelColumn, value);
            } catch (RuntimeException e) {
                grid.report("cell renderer provider", e);
            }
        }
        return grid.decorate(modelColumn, renderer != null ? renderer : grid.rendererFor(modelColumn, value));
    }

    /// Fills the shared cell context for a column header and returns its renderer. The default renderer,
    /// [CellRenderers#TEXT], is replaced by [#headerText].
    private CellRenderer prepareHeader(int column, int modelColumn, String name, double x, double y, double width,
                                       double height) {
        CellStyle style = grid.settings().get(modelColumn, c -> c.headerStyle);
        fill(-1, column, -1, modelColumn, name, style, x, y, width, height, grid.getHeaderFont(),
                grid.getHeaderTextFill());
        CellRenderer renderer = grid.headerRendererFor(modelColumn);
        return renderer == CellRenderers.TEXT ? headerText : renderer;
    }

    /** Header text goes right above numbers, centered above check boxes and left above anything else. */
    private HPos headerAlignment(int modelColumn) {
        Class<?> type = grid.getModel().getColumnClass(modelColumn);
        Class<?> boxed = type == null ? Object.class : ValueParser.boxed(type);
        return Number.class.isAssignableFrom(boxed) ? HPos.RIGHT : boxed == Boolean.class ? HPos.CENTER : HPos.LEFT;
    }

    /** What cells, headers and the row header have in common: position, value and style. */
    private void fill(int row, int column, int modelRow, int modelColumn, Object value, CellStyle style, double x,
                      double y, double width, double height, Font baseFont, Color baseFill) {
        cell.row = row;
        cell.column = column;
        cell.modelRow = modelRow;
        cell.modelColumn = modelColumn;
        cell.value = value;
        cell.x = x;
        cell.y = y;
        cell.width = width;
        cell.height = height;
        cell.padding = grid.getCellPadding();
        cell.indent = 0;
        cell.selected = false;
        cell.focused = false;
        cell.fractionDigits = 0;
        cell.background = style == null ? null : style.background();
        cell.font = font(style, baseFont);
        cell.textFill = style != null && style.textFill() != null ? style.textFill() : baseFill;
        cell.alignment = style == null ? null : style.alignment();
    }

    /// Runs a renderer from a known state: font, fill and stroke from the context, left aligned text centered on
    /// its baseline, full opacity, line width 1, no effect. A renderer that throws leaves its cell empty.
    private void paintWith(GraphicsContext gc, CellRenderer renderer, String what) {
        gc.setFont(cell.font);
        gc.setFill(cell.textFill);
        gc.setStroke(cell.textFill);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setGlobalAlpha(1);
        gc.setLineWidth(1);
        gc.setEffect(null);
        try {
            renderer.paint(gc, cell);
        } catch (RuntimeException e) {
            grid.report(what, e);
        }
    }

    private Object valueAt(GridModel model, int modelRow, int modelColumn) {
        try {
            return model.getValueAt(modelRow, modelColumn);
        } catch (RuntimeException e) {
            grid.report("GridModel.getValueAt", e);
            return null;
        }
    }

    private CellStyle styleFor(int modelRow, int modelColumn, Object value) {
        CellStyleProvider styles = grid.getCellStyleProvider();
        if (styles == null) {
            return null;
        }
        try {
            return styles.styleFor(modelRow, modelColumn, value);
        } catch (RuntimeException e) {
            grid.report("cell style provider", e);
            return null;
        }
    }

    /// The font for a style: its own font or `gridFont`, with the style's weight and posture if it has them.
    /// Derived fonts are cached, so a shared style costs a lookup, not a font creation, per cell.
    private Font font(CellStyle style, Font gridFont) {
        if (style == null) {
            return gridFont;
        }
        Font base = style.font() != null ? style.font() : gridFont;
        if (style.fontWeight() == null && style.fontPosture() == null) {
            return base;
        }
        if (style == lastStyle && base == lastBase) {
            return lastFont;
        }
        Map<CellStyle, Font> byStyle = derivedFonts.computeIfAbsent(base, f -> new IdentityHashMap<>());
        Font font = byStyle.get(style);
        if (font == null) {
            if (byStyle.size() >= MAX_DERIVED_FONTS) {
                byStyle.clear();
            }
            String current = base.getStyle().toLowerCase(Locale.ROOT);
            FontWeight weight = style.fontWeight() != null ? style.fontWeight()
                    : current.contains("bold") ? FontWeight.BOLD : FontWeight.NORMAL;
            FontPosture posture = style.fontPosture() != null ? style.fontPosture()
                    : current.contains("italic") ? FontPosture.ITALIC : FontPosture.REGULAR;
            font = Font.font(base.getFamily(), weight, posture, base.getSize());
            byStyle.put(style, font);
        }
        lastStyle = style;
        lastBase = base;
        lastFont = font;
        return font;
    }

    // ---- headers

    /** Row names from the model, or row numbers (view row + 1) where the model has none. */
    private void paintRowHeader(GraphicsContext gc, int firstRow, int lastRow, double bottom) {
        GridModel model = grid.getModel();
        double right = g.snap(g.rowHeaderWidth());
        double headerHeight = g.headerHeight();
        double line = g.line();
        gc.setGlobalAlpha(1);
        gc.setFill(grid.getHeaderBackground());
        gc.fillRect(0, headerHeight, right, bottom - headerHeight);
        for (int r = firstRow; r <= lastRow; r++) {
            if (rowSelected(r)) {
                double y = rowTops[r - firstRow];
                gc.setFill(headerHighlight);
                gc.fillRect(0, y, right, rowTops[r - firstRow + 1] - y);
            }
        }
        for (int r = firstRow; r <= lastRow; r++) {
            String name = null;
            int modelRow = grid.viewToModelRow(r);
            try {
                name = model.getRowName(modelRow);
            } catch (RuntimeException e) {
                grid.report("GridModel.getRowName", e);
            }
            String text = name != null ? name : Integer.toString(r + 1);
            double y = rowTops[r - firstRow];
            fill(r, -1, modelRow, -1, text, null, 0, y, right - line, rowTops[r - firstRow + 1] - y - line,
                    grid.getHeaderFont(), grid.getHeaderTextFill());
            cell.alignment = name != null ? HPos.LEFT : HPos.CENTER;
            paintWith(gc, CellRenderers.TEXT, "row header");
        }
        gc.setFill(headerLineColor);
        for (int i = 1; i <= lastRow - firstRow + 1; i++) {
            gc.fillRect(0, rowTops[i] - line, right, line);
        }
        gc.fillRect(right - line, headerHeight, line, bottom - headerHeight);
    }

    private boolean rowSelected(int row) {
        for (int i = 0; i < visibleRanges.size(); i++) {
            CellRange range = visibleRanges.get(i);
            if (row >= range.firstRow() && row <= range.lastRow()) {
                return true;
            }
        }
        return false;
    }

    private boolean columnSelected(int column) {
        for (CellRange range : grid.getSelectionModel().getRanges()) {
            if (column >= range.firstColumn() && column <= range.lastColumn()) {
                return true;
            }
        }
        return false;
    }

    private void paintColumnHeader(GraphicsContext gc, int firstScrolled, int lastScrolled) {
        double headerHeight = g.headerHeight();
        double rowHeaderWidth = g.rowHeaderWidth();
        Color background = grid.getHeaderBackground();
        gc.setGlobalAlpha(1);
        gc.setFill(background);
        gc.fillRect(0, 0, g.width(), headerHeight);
        if (firstScrolled >= 0) {
            paintHeaderCells(gc, firstScrolled, lastScrolled);
        }
        if (g.frozenColumns() > 0) {
            gc.setFill(background);
            gc.fillRect(rowHeaderWidth, 0, g.frozenWidth(), headerHeight);
            paintHeaderCells(gc, 0, g.frozenColumns() - 1);
        }
        gc.setFill(background);
        gc.fillRect(0, 0, rowHeaderWidth, headerHeight);
        gc.setFill(headerLineColor);
        gc.fillRect(0, g.snap(headerHeight) - g.line(), g.width(), g.line());
        if (rowHeaderWidth > 0) {
            gc.fillRect(g.snap(rowHeaderWidth) - g.line(), 0, g.line(), headerHeight);
        }
    }

    /// Paints header cells: the background from the header style (or the selection highlight), the content from
    /// the header renderer, then the sort arrow in the cell's text color, so a custom renderer cannot hide the sort
    /// order.
    private void paintHeaderCells(GraphicsContext gc, int firstColumn, int lastColumn) {
        GridModel model = grid.getModel();
        ColumnLayout columns = grid.columns();
        double line = g.line();
        double headerHeight = g.headerHeight();
        List<SortKey> sortKeys = grid.getSortKeys();
        int levels = grid.groupLevels();
        double groupHeight = grid.groupRowHeight();
        if (levels > 0) {
            collectGroupNames(firstColumn, lastColumn, levels);
        }

        for (int c = firstColumn; c <= lastColumn; c++) {
            int modelColumn = columns.toModel(c);
            double x = g.left(c);
            double xEnd = g.right(c);
            double top = headerTop(c - firstColumn, levels, groupHeight);
            double sortSpace = sortSpace(sortKeys, modelColumn);
            CellRenderer renderer = prepareHeader(c, modelColumn, model.getColumnName(modelColumn), x, top,
                    xEnd - x - line - sortSpace, headerHeight - top - line);
            Color background = cell.background != null ? cell.background : columnSelected(c) ? headerHighlight : null;
            if (background != null) {
                gc.setFill(background);
                gc.fillRect(x, top, xEnd - x, headerHeight - top);
            }
            paintWith(gc, renderer, "header renderer");
            if (sortSpace > 0) {
                paintSortOrder(gc, sortKeys, modelColumn, xEnd - line - cell.padding, (top + headerHeight - line) / 2);
            }
        }
        if (levels > 0) {
            paintColumnGroups(gc, firstColumn, lastColumn, levels);
        }
        gc.setGlobalAlpha(1);
        gc.setFill(headerLineColor);
        for (int c = firstColumn; c <= lastColumn; c++) {
            double top = levels == 0 ? 0 : sharedLevels(c - firstColumn, levels) * groupHeight;
            gc.fillRect(g.right(c) - line, top, line, headerHeight - top);
        }
    }

    /** Fills {@link #groupNames} for the columns of a header region and the column after them. */
    private void collectGroupNames(int firstColumn, int lastColumn, int levels) {
        ColumnLayout columns = grid.columns();
        int count = lastColumn - firstColumn + 2;
        if (groupNames.length < levels || groupNames[0].length < count) {
            groupNames = new String[levels][count];
        }
        for (int c = firstColumn; c <= lastColumn + 1; c++) {
            int modelColumn = c < columns.count() ? columns.toModel(c) : -1;
            for (int level = 0; level < levels; level++) {
                groupNames[level][c - firstColumn] = modelColumn < 0 ? null : grid.groupName(level, modelColumn);
            }
        }
    }

    /** Where a column's own header starts, as {@link Grid#headerTop}, by its index in {@link #groupNames}. */
    private double headerTop(int i, int levels, double groupHeight) {
        for (int level = levels - 1; level >= 0; level--) {
            if (groupNames[level][i] != null) {
                return (level + 1) * groupHeight;
            }
        }
        return 0;
    }

    /// Paints the rows of column groups above the column headers: one cell per run of neighbouring columns with
    /// the same groups, its name centered in the part that is on screen, and a line below.
    private void paintColumnGroups(GraphicsContext gc, int firstColumn, int lastColumn, int levels) {
        double groupHeight = grid.groupRowHeight();
        double line = g.line();
        boolean frozen = firstColumn < g.frozenColumns();
        double regionLeft = g.rowHeaderWidth() + (frozen ? 0 : g.frozenWidth());
        double regionRight = frozen ? g.rowHeaderWidth() + g.frozenWidth() : g.width();
        for (int level = 0; level < levels; level++) {
            double y = level * groupHeight;
            int c = firstColumn;
            while (c <= lastColumn) {
                String name = groupNames[level][c - firstColumn];
                int end = c;
                while (name != null && end < lastColumn && sharedLevels(end - firstColumn, level + 1) > level) {
                    end++;
                }
                if (name != null) {
                    double left = Math.max(g.left(c), regionLeft);
                    double right = Math.min(g.right(end), regionRight);
                    // Rows below -1 keep cut group names apart from the header tooltips, which use row -1.
                    fill(-2 - level, c, -1, grid.columns().toModel(c), name, null, left, y, right - left - line,
                            groupHeight - line, grid.getHeaderFont(), grid.getHeaderTextFill());
                    cell.alignment = HPos.CENTER;
                    paintWith(gc, CellRenderers.TEXT, "column group");
                    gc.setFill(headerLineColor);
                    gc.fillRect(g.left(c), y + groupHeight - line, g.right(end) - g.left(c), line);
                }
                c = end + 1;
            }
        }
    }

    /// How many group levels from the top a column shares with the next one, so no line parts them there. `i` is
    /// the column's index in [#groupNames].
    private int sharedLevels(int i, int levels) {
        int shared = 0;
        while (shared < levels) {
            String name = groupNames[shared][i];
            if (name == null || !name.equals(groupNames[shared][i + 1])) {
                break;
            }
            shared++;
        }
        return shared;
    }

    /// Paints the sort arrow of a sorted column, pointing up for ascending, and when sorted by several columns the
    /// column's place in the sort order before it.
    private void paintSortOrder(GraphicsContext gc, List<SortKey> sortKeys, int modelColumn, double right,
                                double cy) {
        int index = Grid.sortIndex(sortKeys, modelColumn);
        double arrowSpace = sortArrowSpace();
        double scale = arrowSpace / SORT_ARROW_SPACE;
        double ax = right - 4 * scale;
        double dy = (sortKeys.get(index).ascending() ? -2.5 : 2.5) * scale;
        arrowX[0] = ax - 4 * scale;
        arrowY[0] = cy - dy;
        arrowX[1] = ax + 4 * scale;
        arrowY[1] = cy - dy;
        arrowX[2] = ax;
        arrowY[2] = cy + dy;
        gc.setGlobalAlpha(1);
        gc.setFill(cell.textFill);
        gc.fillPolygon(arrowX, arrowY, 3);
        if (sortKeys.size() > 1) {
            gc.setFont(sortNumberFont());
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText(Integer.toString(index + 1), right - arrowSpace, cy);
        }
    }

    /// Space at the right of a sorted column's header: the arrow, and when sorted by several columns its place in
    /// the sort order with a gap before it.
    private double sortSpace(List<SortKey> sortKeys, int modelColumn) {
        int index = Grid.sortIndex(sortKeys, modelColumn);
        if (index < 0) {
            return 0;
        }
        double number = sortKeys.size() > 1 ? fitter.width(Integer.toString(index + 1), sortNumberFont())
                + SORT_NUMBER_GAP * grid.getHeaderFont().getSize() / 12 : 0;
        return sortArrowSpace() + number;
    }

    /** The header font at three quarters of its size, for the places in the sort order. */
    private Font sortNumberFont() {
        Font header = grid.getHeaderFont();
        if (sortNumberFont == null || sortNumberFontFor != header) {
            sortNumberFont = Font.font(header.getFamily(), header.getSize() * 0.75);
            sortNumberFontFor = header;
        }
        return sortNumberFont;
    }

    /** A see-through copy of the dragged column's header under the mouse, outlined in the focus color. */
    private void paintGhost(GraphicsContext gc) {
        int modelColumn = grid.columns().toModel(ghostColumn);
        double width = g.right(ghostColumn) - g.left(ghostColumn);
        double x = ghostX / g.zoom() - width / 2;
        double top = grid.headerTop(modelColumn);
        double height = g.headerHeight() - top;
        CellRenderer renderer = prepareHeader(ghostColumn, modelColumn, grid.getModel().getColumnName(modelColumn), x,
                top, width - g.line(), height - g.line());
        gc.setGlobalAlpha(0.8);
        gc.setFill(cell.background != null ? cell.background : grid.getHeaderBackground());
        gc.fillRect(x, top, width, height);
        paintWith(gc, renderer, "header renderer");
        gc.setGlobalAlpha(1);
        gc.setFill(grid.getFocusColor());
        double t = 2 * g.line();
        gc.fillRect(x, top, width, t);
        gc.fillRect(x, top + height - t, width, t);
        gc.fillRect(x, top, t, height);
        gc.fillRect(x + width - t, top, t, height);
    }

    private double sortArrowSpace() {
        return SORT_ARROW_SPACE * grid.getHeaderFont().getSize() / 12;
    }

    /// A line color that stands out more against `background`: darker on light backgrounds, lighter on dark ones.
    private static Color stronger(Color color, Color background, double amount) {
        return color.interpolate(background.getBrightness() < 0.5 ? Color.WHITE : Color.BLACK, amount);
    }

    private static long key(int row, int column) {
        return ((long) row << 32) ^ (column & 0xFFFFFFFFL);
    }

    /** The one context the painter fills in for every cell and header. */
    private final class Cell extends CellContext {

        int row;
        int column;
        int modelRow;
        int modelColumn;
        Object value;
        double x;
        double y;
        double width;
        double height;
        double padding;
        /** Space taken at the left by {@link #indent}, counted when measuring. */
        double indent;
        boolean selected;
        boolean focused;
        int fractionDigits;
        Font font;
        Color textFill;
        Color background;
        HPos alignment;

        @Override
        public int getViewRow() {
            return row;
        }

        @Override
        public int getViewColumn() {
            return column;
        }

        @Override
        public int getModelRow() {
            return modelRow;
        }

        @Override
        public int getModelColumn() {
            return modelColumn;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        @Override
        public double getWidth() {
            return width;
        }

        @Override
        public double getHeight() {
            return height;
        }

        @Override
        public double getPadding() {
            return padding;
        }

        @Override
        public boolean isSelected() {
            return selected;
        }

        @Override
        public boolean isFocused() {
            return focused;
        }

        @Override
        public boolean isEditable() {
            return grid.isEditable() && modelRow >= 0 && grid.getModel().isCellEditable(modelRow, modelColumn);
        }

        @Override
        public int getFractionDigits() {
            return fractionDigits;
        }

        @Override
        public Font getFont() {
            return font;
        }

        @Override
        public Color getTextFill() {
            return textFill;
        }

        @Override
        public HPos getAlignment() {
            return alignment;
        }

        @Override
        public String fitText(String text, Font font) {
            return fitCellText(text, font);
        }

        @Override
        public List<String> wrapText(String text) {
            return wrapCellText(text);
        }

        @Override
        public double getLineHeight() {
            return fitter.lineHeight(font);
        }

        @Override
        public void indent(double width) {
            x += width;
            this.width -= width;
            indent += width;
            if (measuring) { // room taken counts when fitting, also without text after it
                wanted[measuredSlot] = Math.max(wanted[measuredSlot], indent + 2 * padding + 2);
            }
        }

        @Override
        public double snap(double position) {
            return g == null || measuring || captured != null ? position : g.snap(position);
        }
    }
}
