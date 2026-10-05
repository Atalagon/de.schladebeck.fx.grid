package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.internal.ColumnLayout;
import de.schladebeck.fx.grid.internal.Spans;
import de.schladebeck.fx.grid.internal.ViewRows;
import de.schladebeck.fx.grid.model.GridModel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import javafx.beans.Observable;
import javafx.beans.value.ObservableValue;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SkinBase;
import javafx.scene.control.TextField;
import javafx.scene.text.Font;
import javafx.stage.Window;

/// Three nodes, no matter the model size: a viewport-sized canvas and two scroll bars. A text field joins while a
/// cell is edited in place, the placeholder while there are no rows, and a few invisible placeholders while a
/// screen reader asks about cells.
///
/// Every change only marks the skin dirty and requests a layout pass. In [#layoutChildren] the skin
/// updates the row positions, the [GridGeometry] and the scroll bars and then repaints the canvas once, so any
/// number of changes within one pulse cost one repaint. Input handling lives in [GridBehavior], painting in
/// [GridPainter].
final class GridSkin extends SkinBase<Grid> {

    /** Most placeholder nodes kept for assistive technology at a time. */
    static final int MAX_ACCESSIBLE_CELLS = 32;
    /** The row header is as wide as the names of this many rows from the top need. */
    private static final int ROW_NAME_SAMPLE = 1_000;
    /** Row updates beyond this many rows per pulse ask the row height provider for all rows instead. */
    private static final int MAX_UPDATED_ROWS = 10_000;

    private final Canvas canvas = new Canvas();
    private final ScrollBar vbar = new ScrollBar();
    private final ScrollBar hbar = new ScrollBar();
    private final GridGeometry geometry = new GridGeometry();
    private final GridPainter painter;
    private final CellEditor editor;
    private final GridBehavior behavior;
    /** Placeholders for assistive technology, the most recently used last; row -1 are column headers. */
    private final Map<Long, AccessibleCell> accessibleCells = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, AccessibleCell> eldest) {
            if (size() > MAX_ACCESSIBLE_CELLS) {
                getChildren().remove(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private final Spans rowSpans = new Spans();
    private boolean dirty = true;
    private boolean inLayout;
    private int paintCount;
    private boolean accessibilityChanged;
    /** The render scale of the grid's window, null without one. */
    private final ObservableValue<Number> renderScale;
    private Node shownPlaceholder;
    private Label defaultPlaceholder;

    // Row positions: rebuilt when the rows or sizes changed, patched for updated rows.
    private boolean rowsResized = true;
    private int rowSpansVersion = -1;
    private int[] updatedRows = new int[16];
    private int updatedCount;
    private long updatedTotal;

    // Row header width: the names of the first rows, measured again only when they may have changed.
    private boolean rowNamesChanged = true;
    private Font rowNamesFont;
    private double rowNamesWidth;

    // The model row at the top of the viewport while rows are inserted or deleted, and how far it is scrolled.
    private int topModelRow = -1;
    /** The model row below {@link #topModelRow} on screen, to take its place if it is deleted. */
    private int nextModelRow = -1;
    private double topOffset;

    GridSkin(Grid grid) {
        super(grid);
        painter = new GridPainter(grid);
        editor = new CellEditor(grid, this);
        vbar.setOrientation(Orientation.VERTICAL);
        // Positioned by layoutChildren. Managed, their sizes would feed back into the grid's own size.
        canvas.setManaged(false);
        vbar.setManaged(false);
        hbar.setManaged(false);
        getChildren().addAll(canvas, vbar, hbar);
        vbar.valueProperty().bindBidirectional(grid.scrollYProperty());
        hbar.valueProperty().bindBidirectional(grid.scrollXProperty());

        for (Observable property : new Observable[] {
                grid.scrollXProperty(), grid.scrollYProperty(), grid.zoomProperty(), grid.getSelectionModel(),
                grid.cellStyleProviderProperty(), grid.frozenColumnCountProperty(), grid.frozenRowCountProperty(),
                grid.focusedProperty(),
                grid.columnHeaderVisibleProperty(), grid.rowHeaderVisibleProperty(), grid.rowHeaderWidthProperty(),
                grid.cellRendererProviderProperty(), grid.headerRendererProperty(), grid.autoFitColumnsProperty(),
                grid.placeholderProperty(), grid.fontProperty(), grid.headerFontProperty(), grid.textFillProperty(),
                grid.cellBackgroundProperty(), grid.alternateCellBackgroundProperty(),
                grid.alternateColumnBackgroundProperty(),
                grid.gridLineColorProperty(), grid.selectionBackgroundProperty(), grid.focusColorProperty(),
                grid.headerBackgroundProperty(), grid.headerTextFillProperty(), grid.rowHeightProperty(),
                grid.headerHeightProperty(), grid.cellPaddingProperty(), grid.columnGroupsProperty()}) {
            registerInvalidationListener(property, p -> markDirty());
        }
        // Text of another size needs other widths: a theme or font change at runtime fits the columns again.
        for (Observable property : new Observable[] {
                grid.fontProperty(), grid.headerFontProperty(), grid.cellPaddingProperty(),
                grid.headerRendererProperty()}) {
            registerInvalidationListener(property, p -> grid.refitColumns());
        }
        registerInvalidationListener(grid.headerFontProperty(), p -> rowNamesChanged(0));
        // Lines and text are snapped to device pixels: a window moved to a screen with other scaling paints anew.
        renderScale = grid.sceneProperty().flatMap(Scene::windowProperty).flatMap(Window::renderScaleXProperty);
        registerInvalidationListener(renderScale, p -> markDirty());
        registerChangeListener(grid.editableProperty(), p -> {
            editor.cancel();
            markDirty(); // check boxes look different when they cannot be edited
        });
        behavior = new GridBehavior(grid, this, canvas);
    }

    @Override
    public void dispose() {
        Grid grid = getSkinnable();
        editor.cancel();
        behavior.dispose();
        vbar.valueProperty().unbindBidirectional(grid.scrollYProperty());
        hbar.valueProperty().unbindBidirectional(grid.scrollXProperty());
        super.dispose();
    }

    /** While dirty, a layout pass is already pending, or we are inside it and paint before it ends. */
    void markDirty() {
        if (!dirty) {
            dirty = true;
            if (!inLayout) {
                getSkinnable().markNeedsLayout();
            }
        }
    }

    GridGeometry geometry() {
        return geometry;
    }

    int paintCount() {
        return paintCount;
    }

    String cutOffText(int row, int column) {
        return painter.cutOffText(row, column);
    }

    /** The text a view cell shows, as its renderer draws it. */
    String displayedText(int row, int column) {
        return painter.textOf(row, column);
    }

    /** The selection changed: tell assistive technology once in the next layout pass, not for every change. */
    void selectionChanged() {
        accessibilityChanged = true;
        markDirty();
    }

    /** Widths the header and the rows on screen, frozen or scrolled, want, for the given model columns. */
    double[] measureVisibleRows(int[] modelColumns) {
        int first = geometry.firstScrolledRow();
        double[] wanted = measure(first, Math.max(1, geometry.lastScrolledRow() - first + 1), modelColumns);
        if (geometry.lastFrozenRow() >= 0) {
            double[] frozen = measure(0, geometry.lastFrozenRow() + 1, modelColumns);
            Arrays.setAll(wanted, i -> Math.max(wanted[i], frozen[i]));
        }
        return wanted;
    }

    /** Widths the header and view rows {@code firstRow..firstRow+rowCount-1} want, for the given model columns. */
    double[] measure(int firstRow, int rowCount, int[] modelColumns) {
        return painter.measure(firstRow, rowCount, modelColumns);
    }

    /** Model columns on screen: the frozen ones and the scrolled ones in view. */
    int[] visibleModelColumns() {
        ColumnLayout columns = getSkinnable().columns();
        int frozen = geometry.frozenColumns();
        int first = geometry.firstScrolledColumn();
        int last = first < 0 ? -1 : geometry.lastScrolledColumn();
        int[] result = new int[frozen + Math.max(0, last - first + 1)];
        for (int c = 0; c < frozen; c++) {
            result[c] = columns.toModel(c);
        }
        for (int c = first; first >= 0 && c <= last; c++) {
            result[frozen + c - first] = columns.toModel(c);
        }
        return result;
    }

    /** A placeholder node for a view cell that assistive technology asked about, or {@code null}. */
    Node accessibleCell(int row, int column) {
        Grid grid = getSkinnable();
        if (row < 0 || column < 0 || row >= grid.getViewRowCount() || column >= grid.getViewColumnCount()) {
            return null;
        }
        return accessible(row, column);
    }

    /** A placeholder node for the header of a view column, or {@code null}. */
    Node accessibleHeader(int column) {
        return column < 0 || column >= getSkinnable().getViewColumnCount() ? null : accessible(-1, column);
    }

    private AccessibleCell accessible(int row, int column) {
        AccessibleCell cell = accessibleCells.computeIfAbsent(((long) row << 32) | (column & 0xFFFFFFFFL), key -> {
            AccessibleCell created = new AccessibleCell(getSkinnable(), this, row, column);
            getChildren().add(created);
            return created;
        });
        placeAccessibleCell(cell);
        return cell;
    }

    private void placeAccessibleCell(AccessibleCell cell) {
        Bounds b = cell.row() < 0 ? headerBounds(cell.column()) : cellBounds(cell.row(), cell.column());
        cell.resizeRelocate(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }

    void setDropGap(int gap) {
        painter.setDropGap(gap);
        markDirty();
    }

    /** Shows a copy of a dragged column header at a canvas x, or none for a negative column. */
    void setDragGhost(int column, double canvasX) {
        painter.setDragGhost(column, canvasX);
        markDirty();
    }

    boolean startEdit(int row, int column) {
        boolean started = editor.start(row, column);
        if (started) {
            getSkinnable().scrollTo(row, column);
        }
        return started;
    }

    CellEditor editor() {
        return editor;
    }

    void showEditor(TextField field) {
        getChildren().add(field);
        markDirty();
    }

    void hideEditor(TextField field) {
        getChildren().remove(field);
    }

    // ---- rows

    /** All row heights may have changed: ask the row height provider for every row in the next layout pass. */
    void rowsResized() {
        rowsResized = true;
        markDirty();
    }

    /// Whether the row positions match the view rows, so that rows inserted or deleted next can move them
    /// ([#rowsMoved]) instead of asking the row height provider for every row.
    boolean rowPositionsCurrent() {
        ViewRows rows = getSkinnable().rows();
        return !rowsResized && rows.isBuilt() && rows.version() == rowSpansVersion;
    }

    /// Rows were inserted at, or deleted from, view row `first`, and the view rows already show it: the rows
    /// after them move, and only the new rows are measured.
    void rowsMoved(boolean inserted, int first, int count) {
        Grid grid = getSkinnable();
        ViewRows rows = grid.rows();
        if (!inserted) {
            rowSpans.removed(first, count);
        } else {
            rowSpans.inserted(first, count);
            RowHeightProvider provider = grid.getRowHeightProvider();
            for (int row = first; provider != null && row < first + count; row++) {
                rowSpans.setSize(row, heightOf(provider, rows.toModel(row), rowSpans.defaultSize()));
            }
        }
        rowSpansVersion = rows.version();
        markDirty();
    }

    /** Values of model rows changed: ask the row height provider again for these rows only. */
    void rowsUpdated(int firstModelRow, int lastModelRow) {
        GridModel model = getSkinnable().getModel();
        lastModelRow = Math.min(lastModelRow, model == null ? -1 : model.getRowCount() - 1);
        if (lastModelRow < firstModelRow) {
            return;
        }
        updatedTotal += lastModelRow - firstModelRow + 1;
        if (updatedTotal > MAX_UPDATED_ROWS) {
            rowsResized = true;
        } else if (!rowsResized) {
            if (updatedCount + 2 > updatedRows.length) {
                updatedRows = Arrays.copyOf(updatedRows, 2 * updatedRows.length);
            }
            updatedRows[updatedCount++] = firstModelRow;
            updatedRows[updatedCount++] = lastModelRow;
        }
        markDirty();
    }

    /** Names of rows from this model row on may have changed; only the first rows count for the width. */
    void rowNamesChanged(int firstModelRow) {
        if (firstModelRow < ROW_NAME_SAMPLE) {
            rowNamesChanged = true;
        }
    }

    /// Rows are about to be inserted or deleted: remember the row at the top so it stays in place. `shift`
    /// maps old model rows to new ones (-1 for deleted rows). A deleted top row is replaced by the row below it on
    /// screen, or by `fallbackModelRow`, the row moving into its place, if that one is gone too. At the very top
    /// nothing is kept, so new rows there are seen.
    void keepTopRow(IntUnaryOperator shift, int fallbackModelRow) {
        Grid grid = getSkinnable();
        if (topModelRow < 0) {
            int top = geometry.firstScrolledRow();
            if (grid.getScrollY() <= 0 || top >= rowSpans.count() || top >= grid.getViewRowCount()) {
                return;
            }
            topModelRow = grid.viewToModelRow(top);
            nextModelRow = top + 1 < grid.getViewRowCount() ? grid.viewToModelRow(top + 1) : -1;
            topOffset = grid.getScrollY() - rowSpans.start(top);
        }
        int moved = shift.applyAsInt(topModelRow);
        nextModelRow = nextModelRow < 0 ? -1 : shift.applyAsInt(nextModelRow);
        topModelRow = moved >= 0 ? moved : nextModelRow >= 0 ? nextModelRow : fallbackModelRow;
    }

    // ---- public API support, control coordinates

    GridCell cellAt(double x, double y) {
        double cx = x - canvas.getLayoutX();
        double cy = y - canvas.getLayoutY();
        if (cx < 0 || cy < 0 || cx >= canvas.getWidth() || cy >= canvas.getHeight()) {
            return null;
        }
        int row = geometry.rowAt(cy);
        int column = geometry.columnAt(cx);
        return row < 0 || column < 0 ? null : getSkinnable().cell(row, column);
    }

    Bounds cellBounds(int row, int column) {
        Bounds b = geometry.cellBounds(row, column);
        return new BoundingBox(canvas.getLayoutX() + b.getMinX(), canvas.getLayoutY() + b.getMinY(),
                b.getWidth(), b.getHeight());
    }

    private Bounds headerBounds(int column) {
        Bounds b = geometry.cellBounds(0, column);
        return new BoundingBox(canvas.getLayoutX() + b.getMinX(), canvas.getLayoutY(),
                b.getWidth(), geometry.headerHeight() * geometry.zoom());
    }

    // ---- layout and painting

    /*
     * Fixed sizes, independent of the content and the current canvas: SkinBase would derive them from the
     * children, and a canvas is not resizable, so the grid could never shrink and would grow in split panes.
     */
    @Override
    protected double computeMinWidth(double height, double top, double right, double bottom, double left) {
        return left + right + 40;
    }

    @Override
    protected double computeMinHeight(double width, double top, double right, double bottom, double left) {
        return top + bottom + 40;
    }

    @Override
    protected double computePrefWidth(double height, double top, double right, double bottom, double left) {
        return left + right + 600;
    }

    @Override
    protected double computePrefHeight(double width, double top, double right, double bottom, double left) {
        return top + bottom + 400;
    }

    /// Never throws: an exception from code the grid calls is reported and the pass goes on, because a layout
    /// pass that fails leaves JavaFX unable to lay out the grid again.
    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        inLayout = true;
        try {
            layout(x, y, w, h);
        } catch (RuntimeException e) {
            getSkinnable().report("layout", e);
        } finally {
            dirty = false;
            inLayout = false;
        }
    }

    private void layout(double x, double y, double w, double h) {
        Grid grid = getSkinnable();
        if (grid.needsAutoFit()) {
            int[] fit = grid.columnsToAutoFit();
            grid.autoFit(fit, painter.measure(0, Grid.AUTO_FIT_ROWS, fit), painter.measuredValues());
            dirty = true;
        }
        ColumnLayout columns = grid.columns();
        Spans rows = rowSpans();
        double zoom = grid.effectiveZoom();
        double headerHeight = grid.columnHeaderHeight();
        double rowHeaderWidth = rowHeaderWidth();
        double contentHeight = (headerHeight + rows.total()) * zoom;
        double contentWidth = (rowHeaderWidth + columns.total()) * zoom;
        layoutScrollBars(x, y, w, h, contentWidth, contentHeight);
        double viewWidth = canvas.getWidth();
        double viewHeight = canvas.getHeight();

        geometry.update(columns, rows, grid.getFrozenColumnCount(), grid.getFrozenRowCount(), headerHeight,
                rowHeaderWidth,
                zoom, viewWidth, viewHeight, renderScale());
        applyPendingScroll();
        geometry.setScroll(grid.getScrollX(), grid.getScrollY());
        grid.setScrollX(geometry.scrollX());
        grid.setScrollY(geometry.scrollY());
        configure(vbar, geometry.maxScrollY(), geometry.scrollableHeight(), rows.defaultSize());
        configure(hbar, geometry.maxScrollX(), geometry.scrollableWidth(), rows.defaultSize());
        editor.layout(geometry, x, y);
        layoutPlaceholder(x, y, viewWidth, viewHeight, headerHeight * zoom);
        placeAccessibleCells();

        boolean paint = dirty;
        dirty = false;
        if (paint && viewWidth > 0 && viewHeight > 0) {
            paintCount++;
            painter.paint(canvas.getGraphicsContext2D(), geometry);
        }
        if (accessibilityChanged) {
            accessibilityChanged = false;
            grid.notifyAccessibleAttributeChanged(AccessibleAttribute.FOCUS_ITEM);
            grid.notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED_ITEMS);
        }
    }

    /** Shows the scroll bars the content needs and sizes the canvas to the rest of the area. */
    private void layoutScrollBars(double x, double y, double w, double h, double contentWidth, double contentHeight) {
        double vbarWidth = vbar.prefWidth(-1);
        double hbarHeight = hbar.prefHeight(-1);
        boolean needV = contentHeight > h;
        boolean needH = contentWidth > w - (needV ? vbarWidth : 0);
        needV = needV || needH && contentHeight > h - hbarHeight;
        double viewWidth = Math.max(0, snapSizeX(w - (needV ? vbarWidth : 0)));
        double viewHeight = Math.max(0, snapSizeY(h - (needH ? hbarHeight : 0)));

        vbar.setVisible(needV);
        hbar.setVisible(needH);
        if (needV) {
            vbar.resizeRelocate(x + viewWidth, y, vbarWidth, viewHeight);
        }
        if (needH) {
            hbar.resizeRelocate(x, y + viewHeight, viewWidth, hbarHeight);
        }
        canvas.relocate(x, y);
        if (canvas.getWidth() != viewWidth || canvas.getHeight() != viewHeight) {
            canvas.setWidth(viewWidth);
            canvas.setHeight(viewHeight);
            dirty = true;
        }
    }

    /** Scrolls to the row {@link #keepTopRow} kept, then to the cell {@link Grid#scrollTo} asked for. */
    private void applyPendingScroll() {
        Grid grid = getSkinnable();
        if (topModelRow >= 0) {
            GridModel model = grid.getModel();
            int view = model != null && topModelRow < model.getRowCount() ? grid.modelToViewRow(topModelRow) : -1;
            if (view >= 0) {
                grid.setScrollY(rowSpans.start(view) + topOffset);
            }
            topModelRow = -1;
            nextModelRow = -1;
        }
        int[] target = grid.takeScrollTarget();
        if (target != null && target[0] < grid.getViewRowCount() && target[1] < grid.getViewColumnCount()) {
            grid.setScrollY(geometry.scrollYToShow(target[0], grid.getScrollY()));
            grid.setScrollX(geometry.scrollXToShow(target[1], grid.getScrollX()));
        }
    }

    /** Moves the placeholders for assistive technology onto their cells and drops those whose cell is gone. */
    private void placeAccessibleCells() {
        Grid grid = getSkinnable();
        accessibleCells.values().removeIf(cell -> {
            boolean gone = cell.row() >= grid.getViewRowCount() || cell.column() >= grid.getViewColumnCount();
            if (gone) {
                getChildren().remove(cell);
            } else {
                placeAccessibleCell(cell);
            }
            return gone;
        });
    }

    /** Row positions, rebuilt when the view rows or sizes changed, and patched for rows whose values changed. */
    private Spans rowSpans() {
        Grid grid = getSkinnable();
        ViewRows rows = grid.rows();
        RowHeightProvider provider = grid.getModel() == null ? null : grid.getRowHeightProvider();
        double height = grid.clampRowHeight(grid.getRowHeight());
        int version = rows.version();
        if (rowsResized || version != rowSpansVersion || height != rowSpans.defaultSize()) {
            rowsResized = false;
            rowSpansVersion = version;
            int count = rows.count();
            if (provider == null) {
                rowSpans.setUniform(count, height);
            } else {
                int[] tall = new int[16];
                double[] heights = new double[16];
                int n = 0;
                for (int row = 0; row < count; row++) {
                    double h = heightOf(provider, rows.toModel(row), height);
                    if (h != height) {
                        if (n == tall.length) {
                            tall = Arrays.copyOf(tall, 2 * n);
                            heights = Arrays.copyOf(heights, 2 * n);
                        }
                        tall[n] = row;
                        heights[n++] = h;
                    }
                }
                rowSpans.set(count, height, tall, heights, n);
            }
        } else if (provider != null) {
            for (int i = 0; i < updatedCount; i += 2) {
                for (int modelRow = updatedRows[i]; modelRow <= updatedRows[i + 1]; modelRow++) {
                    int row = rows.toView(modelRow);
                    if (row >= 0 && row < rowSpans.count()) {
                        rowSpans.setSize(row, heightOf(provider, modelRow, height));
                    }
                }
            }
        }
        updatedCount = 0;
        updatedTotal = 0;
        return rowSpans;
    }

    private double heightOf(RowHeightProvider provider, int modelRow, double defaultHeight) {
        try {
            double h = provider.heightOf(modelRow);
            return h > 0 ? getSkinnable().clampRowHeight(h) : defaultHeight;
        } catch (RuntimeException e) {
            getSkinnable().report("row height provider", e);
            return defaultHeight;
        }
    }

    /** The row header's width: as set, or wide enough for the largest row number and the first row names. */
    private double rowHeaderWidth() {
        Grid grid = getSkinnable();
        if (!grid.isRowHeaderVisible()) {
            return 0;
        }
        if (grid.getRowHeaderWidth() > 0) {
            return grid.getRowHeaderWidth();
        }
        ViewRows rows = grid.rows();
        Font font = grid.getHeaderFont();
        double width = grid.textFitter().width(Integer.toString(Math.max(1, rows.count())), font);
        GridModel data = grid.getModel();
        if (data != null) {
            if (rowNamesChanged || font != rowNamesFont) {
                rowNamesChanged = false;
                rowNamesFont = font;
                rowNamesWidth = 0;
                for (int row = 0; row < Math.min(rows.count(), ROW_NAME_SAMPLE); row++) {
                    String name = data.getRowName(rows.toModel(row));
                    if (name != null) {
                        double nameWidth = grid.textFitter().width(name, font, Grid.MAX_AUTO_FIT_WIDTH);
                        rowNamesWidth = Math.max(rowNamesWidth, nameWidth);
                    }
                }
            }
            width = Math.max(width, Math.min(rowNamesWidth, Grid.MAX_AUTO_FIT_WIDTH));
        }
        return Math.ceil(width + 2 * grid.getCellPadding() + 1);
    }

    /** Centers the placeholder in the body while no rows are shown. */
    private void layoutPlaceholder(double x, double y, double width, double height, double header) {
        Grid grid = getSkinnable();
        Node wanted = grid.getViewRowCount() > 0 ? null
                : grid.getPlaceholder() != null ? grid.getPlaceholder() : defaultPlaceholder();
        if (wanted != shownPlaceholder) {
            if (shownPlaceholder != null) {
                getChildren().remove(shownPlaceholder);
            }
            shownPlaceholder = wanted;
            if (wanted != null) {
                wanted.setManaged(false);
                getChildren().add(wanted);
            }
        }
        if (wanted != null) {
            double w = Math.min(width, wanted.prefWidth(-1));
            double h = Math.min(Math.max(0, height - header), wanted.prefHeight(w));
            wanted.resizeRelocate(x + (width - w) / 2, y + header + (height - header - h) / 2, w, h);
        }
    }

    /** "No rows", for a grid without a placeholder of its own. */
    private Node defaultPlaceholder() {
        if (defaultPlaceholder == null) {
            defaultPlaceholder = new Label(Texts.get("grid.empty"));
            defaultPlaceholder.getStyleClass().add("placeholder");
        }
        return defaultPlaceholder;
    }

    private static void configure(ScrollBar bar, double max, double visible, double unit) {
        bar.setMin(0);
        bar.setMax(max);
        // A JavaFX thumb shows visibleAmount / (max - min) of the track.
        bar.setVisibleAmount(max > 0 ? max * visible / (max + visible) : 0);
        bar.setUnitIncrement(unit);
        bar.setBlockIncrement(Math.max(unit, visible));
    }

    private double renderScale() {
        Number scale = renderScale.getValue();
        return scale == null ? 1 : scale.doubleValue();
    }
}
