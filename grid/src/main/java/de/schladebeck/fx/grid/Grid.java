package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.internal.ColumnLayout;
import de.schladebeck.fx.grid.internal.TextFitter;
import de.schladebeck.fx.grid.internal.ViewRows;
import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.model.GridModelEvent;
import de.schladebeck.fx.grid.model.GridModelListener;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.SortKey;
import de.schladebeck.fx.grid.render.CellContext;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRendererProvider;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import de.schladebeck.fx.grid.render.CellStyleProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.css.CssMetaData;
import javafx.css.SimpleStyleableDoubleProperty;
import javafx.css.SimpleStyleableObjectProperty;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.StyleablePropertyFactory;
import javafx.geometry.Bounds;
import javafx.geometry.NodeOrientation;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.StringConverter;

/// A table that paints all cells into one canvas: one canvas and two scroll bars, however many rows and columns
/// the model has.
///
/// Data comes from any [GridModel], such as a list of objects ([ListGridModel]), a function
/// ([ComputedGridModel][de.schladebeck.fx.grid.model.ComputedGridModel]) or pages loaded on demand
/// ([PagedGridModel][de.schladebeck.fx.grid.model.PagedGridModel]). Cells are painted by [CellRenderer]s, chosen
/// per cell, and styled by a [CellStyleProvider]. Table-wide colors, fonts and sizes are CSS properties
/// (`-grid-*`), resolved once per CSS pass; the [CSS reference][de.schladebeck.fx.grid##css-reference-heading]
/// lists them.
///
/// Rows and columns each have a view order (as shown: filtered, sorted, reordered) and a model order (as in
/// the data). Selection, [#cellAt] and [#getCellBounds] use view indices. Configuration (widths,
/// renderers, sorting) and the selection data methods use model indices. Convert with
/// [#viewToModelRow], [#modelToViewRow], [#viewToModelColumn] and [#modelToViewColumn].
///
/// Columns run left to right also in right-to-left scenes.
public class Grid extends Control {

    /** Copying more cells than this is refused: building the clipboard text would take too long. */
    static final long MAX_COPY_CELLS = 1_000_000;
    /** Width of columns that are neither fitted nor set. */
    static final double DEFAULT_COLUMN_WIDTH = 100;
    /** Default minimum width of columns, see {@link GridColumn#setMinWidth}. */
    static final double MIN_COLUMN_WIDTH = 20;
    /** Columns fitted automatically get at most this width; longer text is cut off and shown in a tooltip. */
    static final double MAX_AUTO_FIT_WIDTH = 320;
    /** How many rows from the top auto-fitting looks at. */
    static final int AUTO_FIT_ROWS = 50;
    /** How many columns from the left auto-fitting looks at; wide tables keep the default width further right. */
    static final int AUTO_FIT_COLUMNS = 100;
    /** Smallest zoom factor; zooming out further would multiply the visible cells and the paint cost. */
    public static final double MIN_ZOOM = 0.5;
    /** Largest zoom factor. */
    public static final double MAX_ZOOM = 4;
    /** A selection over more rows than this is cleared instead of kept when the view rows are remapped. */
    static final int MAX_REMAPPED_ROWS = 100_000;
    /// Up to this many rows inserted or deleted at once move the row heights along, and in a sorted or filtered
    /// view the selection too. For more rows the row height provider is asked for every row, and a sorted or
    /// filtered view remaps the selection like a sort.
    static final int MAX_SHIFTED_ROWS = 1_000;

    /** Receives the selected cells of {@link #forEachSelectedCell}. */
    @FunctionalInterface
    public interface CellVisitor {

        /// Called once for each selected cell.
        ///
        /// @param modelRow    model row of the selected cell
        /// @param modelColumn model column of the selected cell
        /// @param value       the cell's value
        void visit(int modelRow, int modelColumn, Object value);
    }

    private static final String STYLESHEET = Grid.class.getResource("grid.css").toExternalForm();
    private static final StyleablePropertyFactory<Grid> CSS =
            new StyleablePropertyFactory<>(Control.getClassCssMetaData());
    // Created with the class, so tools reading getClassCssMetaData() see them before the first grid exists.
    // Initial values are what grid.css resolves to in Modena's light theme; CSS replaces them on the first pass.
    private static final CssMetaData<Grid, Font> FONT = CSS.createFontCssMetaData("-grid-font", g -> g.font,
            Font.getDefault());
    private static final CssMetaData<Grid, Font> HEADER_FONT = CSS.createFontCssMetaData("-grid-header-font",
            g -> g.headerFont, Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, Font.getDefault().getSize()));
    private static final CssMetaData<Grid, Color> TEXT_FILL = CSS.createColorCssMetaData("-grid-text-fill",
            g -> g.textFill, Color.web("#333333"));
    private static final CssMetaData<Grid, Color> CELL_BACKGROUND = CSS.createColorCssMetaData(
            "-grid-cell-background", g -> g.cellBackground, Color.WHITE);
    private static final CssMetaData<Grid, Color> ALTERNATE_CELL_BACKGROUND = CSS.createColorCssMetaData(
            "-grid-alternate-cell-background", g -> g.alternateCellBackground, Color.web("#fafafa"));
    private static final CssMetaData<Grid, Color> ALTERNATE_COLUMN_BACKGROUND = CSS.createColorCssMetaData(
            "-grid-alternate-column-background", g -> g.alternateColumnBackground, null);
    private static final CssMetaData<Grid, Color> GRID_LINE_COLOR = CSS.createColorCssMetaData("-grid-line-color",
            g -> g.gridLineColor, Color.web("#e0e0e0"));
    private static final CssMetaData<Grid, Color> SELECTION_BACKGROUND = CSS.createColorCssMetaData(
            "-grid-selection-background", g -> g.selectionBackground, Color.web("#d6d6d6"));
    private static final CssMetaData<Grid, Color> FOCUS_COLOR = CSS.createColorCssMetaData("-grid-focus-color",
            g -> g.focusColor, Color.web("#039ed3"));
    private static final CssMetaData<Grid, Color> HEADER_BACKGROUND = CSS.createColorCssMetaData(
            "-grid-header-background", g -> g.headerBackground, Color.web("#ececec"));
    private static final CssMetaData<Grid, Color> HEADER_TEXT_FILL = CSS.createColorCssMetaData(
            "-grid-header-text-fill", g -> g.headerTextFill, Color.web("#333333"));
    private static final CssMetaData<Grid, Number> ROW_HEIGHT = CSS.createSizeCssMetaData("-grid-row-height",
            g -> g.rowHeight, 24);
    private static final CssMetaData<Grid, Number> HEADER_HEIGHT = CSS.createSizeCssMetaData("-grid-header-height",
            g -> g.headerHeight, 28);
    private static final CssMetaData<Grid, Number> CELL_PADDING = CSS.createSizeCssMetaData("-grid-cell-padding",
            g -> g.cellPadding, 6);

    private final GridModelListener modelListener = this::modelChanged;
    private final ViewRows viewRows = new ViewRows();
    private final ColumnLayout columns = new ColumnLayout();
    private final GridSelectionModel selectionModel = new GridSelectionModel();
    private final RendererRegistry renderers = new RendererRegistry();
    private final ColumnSettings settings = new ColumnSettings();
    private final TextFitter textFitter = new TextFitter();
    private final ReadOnlyObjectWrapper<List<SortKey>> sortKeys = new ReadOnlyObjectWrapper<>(this, "sortKeys",
            List.of());
    private final ObjectProperty<List<ColumnGroup>> columnGroups =
            new SimpleObjectProperty<>(this, "columnGroups", List.of());
    private final ReadOnlyIntegerWrapper focusedModelRow = new ReadOnlyIntegerWrapper(this, "focusedModelRow", -1);
    private boolean autoFitDone;
    /** Whether the last fitting saw values; until it does, updates of the first rows fit again. */
    private boolean autoFitSawValues;
    /** While a layout is restored, its parts do not report layout changes one by one. */
    private boolean restoringLayout;
    /** Model row and column to show in the next layout pass, see {@link #scrollTo}. */
    private int[] scrollTarget;
    private final Set<String> reported = new HashSet<>();

    private final ObjectProperty<GridModel> model = new SimpleObjectProperty<>(this, "model") {
        private GridModel old;

        @Override
        protected void invalidated() {
            if (old != null) {
                old.removeGridModelListener(modelListener);
            }
            old = get();
            if (old != null) {
                old.addGridModelListener(modelListener);
            }
            // The filter knows the old model's rows; the providers stay, they usually belong to the view.
            setRowFilter(null);
            viewRows.setModel(old);
            // A new model starts with fresh columns; a structure change of the same model keeps them.
            columns.clear();
            renderers.resize(0);
            settings.clear();
            structureChanged();
            sortChanged();
        }
    };
    private final ObjectProperty<IntPredicate> rowFilter = new SimpleObjectProperty<>(this, "rowFilter") {
        @Override
        protected void invalidated() {
            changeView(() -> viewRows.setFilter(get()));
        }
    };
    private final ObjectProperty<CellStyleProvider> cellStyleProvider =
            new SimpleObjectProperty<>(this, "cellStyleProvider");
    private final ObjectProperty<CellRendererProvider> cellRendererProvider =
            new SimpleObjectProperty<>(this, "cellRendererProvider");
    private final ObjectProperty<RowHeightProvider> rowHeightProvider =
            new SimpleObjectProperty<>(this, "rowHeightProvider") {
                @Override
                protected void invalidated() {
                    refreshRowHeights();
                }
            };
    private final DoubleProperty rowHeaderWidth = new SimpleDoubleProperty(this, "rowHeaderWidth", -1);
    private final DoubleProperty minRowHeight = new SimpleDoubleProperty(this, "minRowHeight", 0);
    private final DoubleProperty maxRowHeight = new SimpleDoubleProperty(this, "maxRowHeight", Double.MAX_VALUE);
    private final BooleanProperty autoFitColumns = new SimpleBooleanProperty(this, "autoFitColumns", true);
    private final BooleanProperty columnMenuEnabled = new SimpleBooleanProperty(this, "columnMenuEnabled", true);
    private final ObjectProperty<Consumer<GridCell>> onCellAction = new SimpleObjectProperty<>(this, "onCellAction");
    private final ObjectProperty<Function<GridCell, ContextMenu>> cellContextMenuFactory =
            new SimpleObjectProperty<>(this, "cellContextMenuFactory");
    private final ObjectProperty<CellRenderer> headerRenderer =
            new SimpleObjectProperty<>(this, "headerRenderer", CellRenderers.TEXT);
    private final ObjectProperty<Function<GridColumn, ContextMenu>> columnMenuFactory =
            new SimpleObjectProperty<>(this, "columnMenuFactory");
    private final ObjectProperty<Function<GridCell, String>> tooltipProvider =
            new SimpleObjectProperty<>(this, "tooltipProvider");
    private final ObjectProperty<Function<GridCell, Node>> tooltipGraphicProvider =
            new SimpleObjectProperty<>(this, "tooltipGraphicProvider");
    private final ObjectProperty<Node> placeholder = new SimpleObjectProperty<>(this, "placeholder");
    private final ObjectProperty<Consumer<GridLayout>> onLayoutChanged =
            new SimpleObjectProperty<>(this, "onLayoutChanged");
    private final BooleanProperty sortable = new SimpleBooleanProperty(this, "sortable", true);
    private final BooleanProperty columnsReorderable = new SimpleBooleanProperty(this, "columnsReorderable", true);
    private final BooleanProperty editable = new SimpleBooleanProperty(this, "editable", false);
    private final BooleanProperty columnHeaderVisible = new SimpleBooleanProperty(this, "columnHeaderVisible", true);
    private final BooleanProperty rowHeaderVisible = new SimpleBooleanProperty(this, "rowHeaderVisible", false);
    private final IntegerProperty frozenColumnCount = new SimpleIntegerProperty(this, "frozenColumnCount") {
        @Override
        public void set(int value) {
            super.set(Math.max(0, value));
        }
    };
    private final IntegerProperty frozenRowCount = new SimpleIntegerProperty(this, "frozenRowCount") {
        @Override
        public void set(int value) {
            super.set(Math.max(0, value));
        }
    };
    private final DoubleProperty scrollX = new SimpleDoubleProperty(this, "scrollX");
    private final DoubleProperty scrollY = new SimpleDoubleProperty(this, "scrollY");
    private final DoubleProperty zoom = new SimpleDoubleProperty(this, "zoom", 1) {
        @Override
        public void set(double value) {
            super.set(Double.isFinite(value) ? Math.clamp(value, MIN_ZOOM, MAX_ZOOM) : 1);
        }
    };

    private final StyleableObjectProperty<Font> font = property(FONT, "font");
    private final StyleableObjectProperty<Font> headerFont = property(HEADER_FONT, "headerFont");
    private final StyleableObjectProperty<Color> textFill = property(TEXT_FILL, "textFill");
    private final StyleableObjectProperty<Color> cellBackground = property(CELL_BACKGROUND, "cellBackground");
    private final StyleableObjectProperty<Color> alternateCellBackground =
            property(ALTERNATE_CELL_BACKGROUND, "alternateCellBackground");
    private final StyleableObjectProperty<Color> alternateColumnBackground =
            property(ALTERNATE_COLUMN_BACKGROUND, "alternateColumnBackground");
    private final StyleableObjectProperty<Color> gridLineColor = property(GRID_LINE_COLOR, "gridLineColor");
    private final StyleableObjectProperty<Color> selectionBackground =
            property(SELECTION_BACKGROUND, "selectionBackground");
    private final StyleableObjectProperty<Color> focusColor = property(FOCUS_COLOR, "focusColor");
    private final StyleableObjectProperty<Color> headerBackground = property(HEADER_BACKGROUND, "headerBackground");
    private final StyleableObjectProperty<Color> headerTextFill = property(HEADER_TEXT_FILL, "headerTextFill");
    private final StyleableDoubleProperty rowHeight = size(ROW_HEIGHT, "rowHeight");
    private final StyleableDoubleProperty headerHeight = size(HEADER_HEIGHT, "headerHeight");
    private final StyleableDoubleProperty cellPadding = size(CELL_PADDING, "cellPadding");

    /** A grid without data; set a model with {@link #setModel}. */
    public Grid() {
        getStyleClass().add("grid");
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TABLE_VIEW);
        setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        selectionModel.setSize(viewRows::count, columns::count);
        viewRows.setOnError(e -> report("row filter or comparator", e));
        selectionModel.addListener(o -> {
            int row = selectionModel.getFocusedRow();
            focusedModelRow.set(row < 0 || row >= viewRows.count() ? -1 : viewRows.toModel(row));
            if (getSkin() instanceof GridSkin skin) {
                skin.selectionChanged();
            }
        });
        rowHeight.addListener(o -> refreshRowHeights());
        minRowHeight.addListener(o -> refreshRowHeights());
        maxRowHeight.addListener(o -> refreshRowHeights());
        frozenColumnCount.addListener(o -> layoutChanged());
        frozenRowCount.addListener(o -> layoutChanged());
        zoom.addListener(o -> layoutChanged());
    }

    /** A grid showing {@code model}. */
    public Grid(GridModel model) {
        this();
        setModel(model);
    }

    // ---- model and view mapping

    /// The data shown. Setting another model resets columns, sorting, the row filter and the selection. Style,
    /// renderer and row height providers stay.
    public final ObjectProperty<GridModel> modelProperty() {
        return model;
    }

    public final GridModel getModel() {
        return model.get();
    }

    public final void setModel(GridModel value) {
        model.set(value);
    }

    /** Rows shown, after filtering. */
    public final int getViewRowCount() {
        return viewRows.count();
    }

    /** Columns shown, without hidden ones. */
    public final int getViewColumnCount() {
        return columns.count();
    }

    /** Model row shown at a view row. */
    public final int viewToModelRow(int viewRow) {
        return viewRows.toModel(viewRow);
    }

    /** View row of a model row, or -1 if it is filtered out. */
    public final int modelToViewRow(int modelRow) {
        return viewRows.toView(modelRow);
    }

    /** Model column shown at a view column. */
    public final int viewToModelColumn(int viewColumn) {
        return columns.toModel(viewColumn);
    }

    /** View column of a model column, or -1 if it is hidden. */
    public final int modelToViewColumn(int modelColumn) {
        return columns.toView(modelColumn);
    }

    /// The value of a cell given in view coordinates. For model coordinates ask the model: [GridModel#getValueAt].
    ///
    /// @param viewRow    view row
    /// @param viewColumn view column
    /// @return the value, may be `null`
    /// @throws IllegalStateException if the grid has no model
    public final Object getViewValueAt(int viewRow, int viewColumn) {
        if (getModel() == null) {
            throw new IllegalStateException("The grid has no model");
        }
        return getModel().getValueAt(viewRows.toModel(viewRow), columns.toModel(viewColumn));
    }

    // ---- filtering and sorting

    /// Shows only model rows the filter accepts, `null` shows all. See
    /// [RowFilters][de.schladebeck.fx.grid.model.RowFilters] and [ListGridModel#matching]. Inserted rows are
    /// filtered when they arrive; rows whose values change are filtered again by [#refreshView()]. The selection
    /// stays on the rows that are still shown (up to 100,000 selected rows, a larger selection is cleared).
    public final ObjectProperty<IntPredicate> rowFilterProperty() {
        return rowFilter;
    }

    public final IntPredicate getRowFilter() {
        return rowFilter.get();
    }

    public final void setRowFilter(IntPredicate value) {
        rowFilter.set(value);
    }

    /// Sorts by one model column, see [#sort(List)].
    ///
    /// @param modelColumn the model column to sort by
    /// @param ascending   the direction
    public final void sort(int modelColumn, boolean ascending) {
        sort(List.of(new SortKey(modelColumn, ascending)));
    }

    /// Sorts by model columns, stable: the first key decides, the next ones break its ties. Strings sort ignoring
    /// case unless the column has its own [comparator][GridColumn#setComparator]. Inserted rows are placed in
    /// order; rows whose values change keep their place until the next sort. The selection stays on its rows (up
    /// to 100,000 selected rows), unless the model sorts itself ([GridModel#sortRows]), which clears it.
    ///
    /// @param keys the model columns and directions, each column once; empty for the model order
    /// @throws IndexOutOfBoundsException if a column does not exist
    /// @throws IllegalArgumentException if a column appears twice
    public final void sort(List<SortKey> keys) {
        Set<Integer> sorted = new HashSet<>();
        for (SortKey key : keys) {
            Objects.checkIndex(key.column(), columns.modelCount());
            if (!sorted.add(key.column())) {
                throw new IllegalArgumentException("Column " + key.column() + " appears twice in " + keys);
            }
        }
        boolean[] modelSorted = {false};
        changeView(() -> modelSorted[0] = viewRows.sort(keys, this::columnComparator));
        if (modelSorted[0]) {
            selectionModel.clear(); // the model gave its rows new numbers, which the selection cannot follow
        }
        sortChanged();
    }

    /** Shows the rows in model order again. The selection stays on its rows. */
    public final void clearSort() {
        sort(List.of());
    }

    /// Filters and sorts the rows again, for values that changed since. The selection stays on its rows.
    public final void refreshView() {
        sort(getSortKeys());
    }

    /// A click on a column header: ascending, descending, unsorted. Without `add` the column becomes the only sort
    /// column, continuing from its direction among several. With `add` (Shift+click) it joins the other sort
    /// columns, or changes its direction among them.
    void clickSort(int modelColumn, boolean add) {
        List<SortKey> keys = new ArrayList<>(getSortKeys());
        int index = sortIndex(keys, modelColumn);
        if (!add) {
            keys.retainAll(index < 0 ? List.of() : List.of(keys.get(index)));
            index = index < 0 ? -1 : 0;
        }
        if (index < 0) {
            keys.add(new SortKey(modelColumn, true));
        } else if (keys.get(index).ascending()) {
            keys.set(index, new SortKey(modelColumn, false));
        } else {
            keys.remove(index);
        }
        sort(keys);
    }

    /** Position of a model column among the sort keys, or -1. */
    static int sortIndex(List<SortKey> keys, int modelColumn) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).column() == modelColumn) {
                return i;
            }
        }
        return -1;
    }

    /// The columns the rows are sorted by, the first one decides; empty when unsorted. Changes when the user
    /// clicks a header.
    public final ReadOnlyObjectProperty<List<SortKey>> sortKeysProperty() {
        return sortKeys.getReadOnlyProperty();
    }

    public final List<SortKey> getSortKeys() {
        return sortKeys.get();
    }

    /// Rows of column groups above the column headers, outermost first. Each [ColumnGroup] names the group of a
    /// model column at its level, or returns `null` for none. Neighbouring columns with the same names at a level and
    /// all levels above share one header cell; a column without groups further down gets a taller header. Groups
    /// are functions, so they work for any number of columns. They are only shown: columns still move one by one,
    /// and a moved column may split its group.
    ///
    /// ```java
    /// grid.setColumnGroups(List.of(
    ///         column -> column == 0 ? null : column < 3 ? "Person" : "Address",
    ///         column -> column < 3 ? null : column < 5 ? "Street" : "Town"));
    /// ```
    public final ObjectProperty<List<ColumnGroup>> columnGroupsProperty() {
        return columnGroups;
    }

    public final List<ColumnGroup> getColumnGroups() {
        return columnGroups.get();
    }

    /** Sets a copy of the list; {@code null} means no groups. */
    public final void setColumnGroups(List<ColumnGroup> value) {
        columnGroups.set(value == null ? List.of() : List.copyOf(value));
    }

    /// Whether a click on a column header sorts: ascending, descending, then unsorted. A plain click makes the
    /// column the only sort column, continuing from its direction if it was one of several; Shift+click adds it to
    /// the sort or changes its direction there. A double-click sorts once. When off, or in [SelectionMode#COLUMN],
    /// a header click selects the column. Sorting reads every value of the column, so turn it off for huge models.
    public final BooleanProperty sortableProperty() {
        return sortable;
    }

    public final boolean isSortable() {
        return sortable.get();
    }

    public final void setSortable(boolean value) {
        sortable.set(value);
    }

    // ---- selection

    /** The selection, in view coordinates. */
    public final GridSelectionModel getSelectionModel() {
        return selectionModel;
    }

    /// Model row of the focused cell, or -1. Follows the keyboard and the mouse, so a detail view can listen to
    /// it: `grid.focusedModelRowProperty().addListener((o, old, row) -> show(people.getItem(row.intValue())))`.
    public final ReadOnlyIntegerProperty focusedModelRowProperty() {
        return focusedModelRow.getReadOnlyProperty();
    }

    public final int getFocusedModelRow() {
        return focusedModelRow.get();
    }

    /// Selects a model row the way a click on it would (its cell in the focused column, or the whole row in row
    /// mode) and scrolls it into view.
    ///
    /// @param modelRow the model row
    /// @return `false` if the row is filtered out
    public final boolean selectModelRow(int modelRow) {
        int row = viewRows.toView(modelRow);
        if (row < 0 || columns.count() == 0) {
            return false;
        }
        int column = Math.clamp(selectionModel.getFocusedColumn(), 0, columns.count() - 1);
        selectionModel.select(row, column);
        scrollTo(row, column);
        return true;
    }

    /// Model rows with at least one selected cell, in view order. To delete them from a [ListGridModel] use
    /// [ListGridModel#removeRows], which removes them in an order that keeps the indices valid.
    public final int[] getSelectedModelRows() {
        return selected(CellRange::firstRow, CellRange::lastRow, viewRows::toModel);
    }

    /** Model columns with at least one selected cell, in view order. */
    public final int[] getSelectedModelColumns() {
        return selected(CellRange::firstColumn, CellRange::lastColumn, columns::toModel);
    }

    /** The view rows or columns covered by a selected range, in view order, as model indices. */
    private int[] selected(ToIntFunction<CellRange> first, ToIntFunction<CellRange> last, IntUnaryOperator toModel) {
        BitSet selected = new BitSet();
        for (CellRange range : selectionModel.getRanges()) {
            selected.set(first.applyAsInt(range), last.applyAsInt(range) + 1);
        }
        return selected.stream().map(toModel).toArray();
    }

    /// Visits each selected cell once, row by row in view order, each row left to right. Check
    /// [CellRange#cellCount()] first if huge selections are possible.
    public final void forEachSelectedCell(CellVisitor visitor) {
        List<CellRange> ranges = new ArrayList<>(selectionModel.getRanges());
        ranges.sort(Comparator.comparingInt(CellRange::firstRow));
        GridModel data = getModel();
        List<CellRange> covering = new ArrayList<>();
        int next = 0;
        int row = 0;
        while (next < ranges.size() || !covering.isEmpty()) {
            if (covering.isEmpty()) {
                row = Math.max(row, ranges.get(next).firstRow());
            }
            while (next < ranges.size() && ranges.get(next).firstRow() <= row) {
                covering.add(ranges.get(next++));
            }
            // The same columns are selected down to the row before the next range starts or a range ends
            int last = next < ranges.size() ? ranges.get(next).firstRow() - 1 : Integer.MAX_VALUE;
            BitSet selected = new BitSet();
            for (CellRange range : covering) {
                last = Math.min(last, range.lastRow());
                selected.set(range.firstColumn(), range.lastColumn() + 1);
            }
            int[] modelColumns = selected.stream().map(columns::toModel).toArray();
            for (; row <= last; row++) {
                int modelRow = viewRows.toModel(row);
                for (int modelColumn : modelColumns) {
                    visitor.visit(modelRow, modelColumn, data.getValueAt(modelRow, modelColumn));
                }
            }
            int done = last;
            covering.removeIf(range -> range.lastRow() <= done);
        }
    }

    /// Copies the selected cells to the clipboard as tab-separated text, in view order, each cell as it is shown
    /// (the text its renderer draws). Several ranges are copied together when they span the same columns, such as
    /// rows picked with Ctrl+click, or the same rows. Returns false if nothing is selected, the ranges do not line
    /// up like that, or they hold more than 1,000,000 cells; Ctrl+C then shows a message.
    public final boolean copySelection() {
        List<CellRange> ranges = selectionModel.getRanges();
        if (getModel() == null || ranges.isEmpty() || cellCount(ranges) > MAX_COPY_CELLS) {
            return false;
        }
        CellRange first = ranges.getFirst();
        boolean sameColumns = ranges.stream()
                .allMatch(r -> r.firstColumn() == first.firstColumn() && r.lastColumn() == first.lastColumn());
        boolean sameRows = ranges.stream()
                .allMatch(r -> r.firstRow() == first.firstRow() && r.lastRow() == first.lastRow());
        if (!sameColumns && !sameRows) {
            return false;
        }
        IntUnaryOperator view = IntUnaryOperator.identity();
        int[] rows = sameColumns ? selected(CellRange::firstRow, CellRange::lastRow, view)
                : IntStream.rangeClosed(first.firstRow(), first.lastRow()).toArray();
        int[] columns = sameRows ? selected(CellRange::firstColumn, CellRange::lastColumn, view)
                : IntStream.rangeClosed(first.firstColumn(), first.lastColumn()).toArray();
        GridSkin skin = getSkin() instanceof GridSkin s ? s : null;
        ClipboardContent content = new ClipboardContent();
        content.putString(Tsv.of(rows, columns, (row, column) -> skin != null ? skin.displayedText(row, column)
                : String.valueOf(getViewValueAt(row, column))));
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    /** The cells of the ranges, overlaps counted twice. */
    static long cellCount(List<CellRange> ranges) {
        return ranges.stream().mapToLong(CellRange::cellCount).sum();
    }

    // ---- actions and editing

    /** Called on double-click or Enter, unless the cell is edited in place instead. */
    public final ObjectProperty<Consumer<GridCell>> onCellActionProperty() {
        return onCellAction;
    }

    public final Consumer<GridCell> getOnCellAction() {
        return onCellAction.get();
    }

    public final void setOnCellAction(Consumer<GridCell> value) {
        onCellAction.set(value);
    }

    /// Builds the context menu for a cell the user right-clicks, or opens the menu for with the keyboard (menu key
    /// or Shift+F10, for the focused cell). Right-clicking a cell outside the selection selects it first, so menu
    /// actions can work on [#getSelectedModelRows()] or [#forEachSelectedCell]. Return `null` for no menu.
    ///
    /// ```java
    /// grid.setCellContextMenuFactory(cell -> new ContextMenu(
    ///         menuItem("Open " + cell.value(), () -> open(cell.modelRow())),
    ///         menuItem("Delete selected rows", this::deleteSelection)));
    /// ```
    ///
    /// The column header has its own menu, see [#columnMenuEnabledProperty()].
    ///
    /// @return the property
    public final ObjectProperty<Function<GridCell, ContextMenu>> cellContextMenuFactoryProperty() {
        return cellContextMenuFactory;
    }

    public final Function<GridCell, ContextMenu> getCellContextMenuFactory() {
        return cellContextMenuFactory.get();
    }

    public final void setCellContextMenuFactory(Function<GridCell, ContextMenu> value) {
        cellContextMenuFactory.set(value);
    }

    /// In-place editing, off by default; a form next to the grid is usually the better choice. When on, cells the
    /// model marks editable ([GridModel#isCellEditable]) are edited on double-click, F2 or typing a character,
    /// which starts the text. Enter commits, Tab commits and edits the next cell, Escape cancels. Input that
    /// cannot be converted stays in the editor with a message that shows the old value as an example. Check boxes
    /// are not edited as text: a click on the box or Space toggles them.
    public final BooleanProperty editableProperty() {
        return editable;
    }

    public final boolean isEditable() {
        return editable.get();
    }

    public final void setEditable(boolean value) {
        editable.set(value);
    }

    /// Starts editing a view cell, or toggles a check box. Returns false if the grid or the cell is not editable,
    /// or there is no skin.
    public final boolean editCell(int viewRow, int viewColumn) {
        return viewRow >= 0 && viewRow < getViewRowCount() && viewColumn >= 0 && viewColumn < getViewColumnCount()
                && getSkin() instanceof GridSkin skin && skin.startEdit(viewRow, viewColumn);
    }

    // ---- columns and renderers

    /// The column at a model index, to configure it: `grid.getColumn(0).setWidth(80).setMaxWidth(120)`.
    ///
    /// @param modelColumn the column's index in the model
    /// @return the column
    /// @throws IndexOutOfBoundsException if the model has no such column
    public final GridColumn getColumn(int modelColumn) {
        if (modelColumn < 0 || modelColumn >= columns.modelCount()) {
            throw new IndexOutOfBoundsException("No column " + modelColumn + ", the model has " + columns.modelCount());
        }
        return new GridColumn(this, modelColumn);
    }

    /// The column with a name, so code does not depend on column positions.
    ///
    /// @param name the column name, as the model reports it
    /// @return the first column with that name
    /// @throws IllegalArgumentException if no column has that name
    public final GridColumn getColumn(String name) {
        int modelColumn = getModel() == null ? -1 : getModel().findColumn(name);
        if (modelColumn < 0) {
            throw new IllegalArgumentException("No column named \"" + name + "\"");
        }
        return getColumn(modelColumn);
    }

    /// The column shown at a view position, for example the focused one:
    /// `grid.getColumnAtView(grid.getSelectionModel().getFocusedColumn())`.
    ///
    /// @param viewColumn the view column
    /// @return the column
    public final GridColumn getColumnAtView(int viewColumn) {
        if (viewColumn < 0 || viewColumn >= columns.count()) {
            throw new IndexOutOfBoundsException("No view column " + viewColumn + ", the grid shows " + columns.count());
        }
        return getColumn(columns.toModel(viewColumn));
    }

    /** Fits the columns on screen to their header and the rows on screen, see {@link GridColumn#fitToContent()}. */
    public final void fitVisibleColumns() {
        if (getSkin() instanceof GridSkin skin) {
            int[] visible = skin.visibleModelColumns();
            double[] wanted = skin.measureVisibleRows(visible);
            for (int i = 0; i < visible.length; i++) {
                resizeColumn(visible[i], Math.ceil(wanted[i]));
            }
        }
    }

    /// Whether columns without an explicit width fit their header and the first 50 rows, at most 320 pixels wide
    /// and within their limits. On by default. The first 100 columns are fitted when data arrives and again when
    /// fonts, padding or the header renderer change. Sorting widens a column whose header no longer fits next to
    /// the sort arrow, and opening an item of a [TreeGrid] widens the tree column for its children. Columns resized
    /// by the user or with [GridColumn#setWidth] keep their width. When off, columns start 100 pixels wide.
    public final BooleanProperty autoFitColumnsProperty() {
        return autoFitColumns;
    }

    public final boolean isAutoFitColumns() {
        return autoFitColumns.get();
    }

    public final void setAutoFitColumns(boolean value) {
        autoFitColumns.set(value);
    }

    /** Moves a visible column from one view position to another. Keeps only whole-row selections. */
    public final void moveColumn(int fromViewColumn, int toViewColumn) {
        int old = columns.count();
        if (fromViewColumn < 0 || fromViewColumn >= old || toViewColumn < 0 || toViewColumn >= old) {
            throw new IndexOutOfBoundsException("Cannot move view column " + fromViewColumn + " to " + toViewColumn
                    + ", the grid shows " + old);
        }
        columns.move(fromViewColumn, toViewColumn);
        columnsChanged();
    }

    /// Whether right-clicking the column header (or Alt+Down on a focused cell) opens the column menu: sort,
    /// fit, hide and show columns. On by default; [#columnMenuFactoryProperty()] replaces or extends the menu.
    public final BooleanProperty columnMenuEnabledProperty() {
        return columnMenuEnabled;
    }

    public final boolean isColumnMenuEnabled() {
        return columnMenuEnabled.get();
    }

    public final void setColumnMenuEnabled(boolean value) {
        columnMenuEnabled.set(value);
    }

    /// Builds the column menu, `null` for the built-in one. The column is `null` when the menu is opened right
    /// of the last column. To extend the built-in menu, start from [#createColumnMenu]:
    ///
    /// ```java
    /// grid.setColumnMenuFactory(column -> {
    ///     ContextMenu menu = grid.createColumnMenu(column);
    ///     menu.getItems().add(new MenuItem("Export"));
    ///     return menu;
    /// });
    /// ```
    public final ObjectProperty<Function<GridColumn, ContextMenu>> columnMenuFactoryProperty() {
        return columnMenuFactory;
    }

    public final Function<GridColumn, ContextMenu> getColumnMenuFactory() {
        return columnMenuFactory.get();
    }

    public final void setColumnMenuFactory(Function<GridColumn, ContextMenu> value) {
        columnMenuFactory.set(value);
    }

    /// The built-in column menu: sort, fit and hide the column, fit the visible columns, and show or hide each
    /// column (or show all, for more than 100 columns). Texts are English or German, following the default
    /// display locale.
    ///
    /// @param column the column the menu is for, or `null`
    /// @return a new menu
    public final ContextMenu createColumnMenu(GridColumn column) {
        return ColumnMenu.create(this, column);
    }

    /// Text for the tooltip of a cell, `null` for the default: the full text of a cell that is cut off. Called
    /// while the mouse moves, so it should be fast.
    public final ObjectProperty<Function<GridCell, String>> tooltipProviderProperty() {
        return tooltipProvider;
    }

    public final Function<GridCell, String> getTooltipProvider() {
        return tooltipProvider.get();
    }

    public final void setTooltipProvider(Function<GridCell, String> value) {
        tooltipProvider.set(value);
    }

    /// A node shown in the tooltip of a cell, next to its text if it has one: a small chart, an image, a few
    /// labels. Asked once each time the mouse enters a cell; `null` for a cell without one. The grid's tooltips
    /// have the style class `grid-tooltip`, so CSS can change their look and timing, e.g. `-fx-show-delay`.
    ///
    /// ```java
    /// grid.setTooltipGraphicProvider(cell -> new VBox(new Label(person(cell).name()), chartOf(person(cell))));
    /// ```
    public final ObjectProperty<Function<GridCell, Node>> tooltipGraphicProviderProperty() {
        return tooltipGraphicProvider;
    }

    public final Function<GridCell, Node> getTooltipGraphicProvider() {
        return tooltipGraphicProvider.get();
    }

    public final void setTooltipGraphicProvider(Function<GridCell, Node> value) {
        tooltipGraphicProvider.set(value);
    }

    /// Shown in the middle of the grid while it shows no rows, for example a label "No matches" for a filter
    /// that hides everything. `null` (the default) shows "No rows"; an empty `Region` shows nothing.
    public final ObjectProperty<Node> placeholderProperty() {
        return placeholder;
    }

    public final Node getPlaceholder() {
        return placeholder.get();
    }

    public final void setPlaceholder(Node value) {
        placeholder.set(value);
    }

    /// Called with the new layout whenever the column order, widths, visibility, sorting, frozen columns or zoom
    /// change, by the user or in code; for example to save it: `layout -> prefs.put("grid", layout.encode())`.
    public final ObjectProperty<Consumer<GridLayout>> onLayoutChangedProperty() {
        return onLayoutChanged;
    }

    public final Consumer<GridLayout> getOnLayoutChanged() {
        return onLayoutChanged.get();
    }

    public final void setOnLayoutChanged(Consumer<GridLayout> value) {
        onLayoutChanged.set(value);
    }

    /// The user's arrangement: column order, the widths the user or code set (fitted widths are fitted again),
    /// visibility, sorting, frozen columns and zoom.
    public final GridLayout getLayout() {
        List<GridLayout.ColumnRange> order = columns.runs().stream()
                .map(run -> new GridLayout.ColumnRange(run[0], run[1]))
                .toList();
        Set<Integer> hidden = new TreeSet<>(Arrays.stream(columns.hidden()).boxed().toList());
        Map<Integer, Double> widths = new HashMap<>();
        settings.forEachExplicitWidth(column -> widths.put(column, columns.modelWidth(column)));
        Map<Integer, String> names = new HashMap<>();
        GridModel model = getModel();
        if (model != null) {
            IntConsumer name = column -> names.put(column, model.getColumnName(column));
            order.forEach(range -> {
                name.accept(range.first());
                name.accept(range.last());
            });
            widths.keySet().forEach(name::accept);
            hidden.forEach(name::accept);
            getSortKeys().forEach(key -> name.accept(key.column()));
        }
        return new GridLayout(order, widths, hidden, getSortKeys(), getFrozenColumnCount(), getFrozenRowCount(),
                getZoom(), names);
    }

    /// Restores an arrangement from [#getLayout()], as far as it fits the current columns: a column with another
    /// name at its index is found by name or left out, columns the layout does not know keep their defaults and
    /// come last, and the column order is used only if its columns kept their names. Frozen columns and zoom are
    /// kept within their limits. Clears the selection. Does nothing for `null`, which [GridLayout#decode] returns
    /// for blank text.
    public final void setLayout(GridLayout layout) {
        if (layout == null) {
            return;
        }
        restoringLayout = true;
        try {
            applyLayout(layout);
        } finally {
            restoringLayout = false;
        }
        layoutChanged();
    }

    private void applyLayout(GridLayout layout) {
        int count = columns.modelCount();
        IntUnaryOperator find = column -> findColumn(layout, column);
        Map<Integer, Double> widths = new HashMap<>();
        layout.columnWidths().forEach((saved, width) -> {
            int column = find.applyAsInt(saved);
            if (column >= 0 && Double.isFinite(width) && width > 0) {
                widths.put(column, clampWidth(column, width));
                settings.edit(column, c -> c.explicitWidth = true);
            }
        });
        columns.setModelWidths(widths);
        boolean orderFits = layout.columnOrder().stream().allMatch(range -> find.applyAsInt(range.first())
                == range.first() && (range.last() >= count || find.applyAsInt(range.last()) == range.last()));
        columns.setRuns(fittingRuns(orderFits ? layout.columnOrder() : List.of(), count));
        columns.setHidden(layout.hiddenColumns().stream().map(find::applyAsInt).filter(column -> column >= 0)
                .toList());
        Set<Integer> sorted = new HashSet<>();
        viewRows.sort(layout.sortKeys().stream()
                .map(key -> new SortKey(find.applyAsInt(key.column()), key.ascending()))
                .filter(key -> key.column() >= 0 && sorted.add(key.column()))
                .toList(), this::columnComparator);
        sortChanged();
        setFrozenColumnCount(Math.clamp(layout.frozenColumns(), 0, count));
        setFrozenRowCount(Math.max(0, layout.frozenRows()));
        setZoom(Double.isFinite(layout.zoom()) ? Math.clamp(layout.zoom(), MIN_ZOOM, MAX_ZOOM) : 1);
        refreshRowHeights();
        selectionModel.clear();
        requestRepaint();
    }

    /// Where a column a layout mentions is now: at its index if it has the same name there (or the layout has no
    /// name for it), else at the column with that name, else -1. Models with more than 10,000 columns are not
    /// searched by name.
    private int findColumn(GridLayout layout, int saved) {
        int count = columns.modelCount();
        String name = layout.columnNames().get(saved);
        if (saved >= 0 && saved < count && (name == null || name.equals(getModel().getColumnName(saved)))) {
            return saved;
        }
        return name == null || count > 10_000 ? -1 : getModel().findColumn(name);
    }

    /// A layout's column order applied to `count` columns: ranges are cut to the existing columns, and columns
    /// the layout does not know follow in their natural order. An order with overlapping ranges is not used.
    private static List<int[]> fittingRuns(List<GridLayout.ColumnRange> order, int count) {
        List<int[]> runs = new ArrayList<>();
        for (GridLayout.ColumnRange range : order) {
            if (range.first() < count) {
                runs.add(new int[] {range.first(), Math.min(range.last(), count - 1)});
            }
        }
        List<int[]> byFirst = new ArrayList<>(runs);
        byFirst.sort(Comparator.comparingInt(run -> run[0]));
        int next = 0;
        for (int[] run : byFirst) {
            if (run[0] < next) {
                return List.of();
            }
            if (run[0] > next) {
                runs.add(new int[] {next, run[0] - 1});
            }
            next = run[1] + 1;
        }
        if (next < count) {
            runs.add(new int[] {next, count - 1});
        }
        return runs;
    }

    /** Whether the user can drag column headers to reorder columns. */
    public final BooleanProperty columnsReorderableProperty() {
        return columnsReorderable;
    }

    public final boolean isColumnsReorderable() {
        return columnsReorderable.get();
    }

    public final void setColumnsReorderable(boolean value) {
        columnsReorderable.set(value);
    }

    /// Renderer for all columns whose [GridModel#getColumnClass] is `type`, a subclass or an implementation of it,
    /// and in columns of mixed types (`Object.class`) for every value of such a class. The nearest class wins, then
    /// the nearest interface; primitive column classes count as their wrappers. Built in: numbers right aligned,
    /// booleans as check boxes, everything else as text.
    ///
    /// @param type     the class, superclass or interface
    /// @param renderer its renderer
    public final void setDefaultRenderer(Class<?> type, CellRenderer renderer) {
        renderers.setDefault(type, renderer);
        requestRepaint();
    }

    /// Paints the content of the column headers, by default the column name as text, aligned like the column's
    /// values: right above numbers, centered above booleans, left otherwise. Its context has the column name as
    /// value and row -1; font, text color and alignment come from the header style ([GridColumn#setHeaderStyle])
    /// or the grid's `-grid-header-*` properties. The grid paints the background before and the sort arrow after
    /// it. A column's own header renderer ([GridColumn#setHeaderRenderer]) wins.
    ///
    /// The renderer can look at [CellContext#getModelColumn()], so one renderer serves millions of columns.
    public final ObjectProperty<CellRenderer> headerRendererProperty() {
        return headerRenderer;
    }

    public final CellRenderer getHeaderRenderer() {
        return headerRenderer.get();
    }

    public final void setHeaderRenderer(CellRenderer value) {
        headerRenderer.set(value);
    }

    /// Picks the renderer per cell, for full control. Where it returns `null`, or when none is set, the grid
    /// picks: the column's renderer ([GridColumn#setRenderer]), else the default renderer for the column class,
    /// else, for columns of mixed types, the default renderer for the value's class ([#setDefaultRenderer]).
    public final ObjectProperty<CellRendererProvider> cellRendererProviderProperty() {
        return cellRendererProvider;
    }

    public final CellRendererProvider getCellRendererProvider() {
        return cellRendererProvider.get();
    }

    public final void setCellRendererProvider(CellRendererProvider value) {
        cellRendererProvider.set(value);
    }

    /// Gives rows their own height, e.g. for wrapped text with [CellRenderers#WRAPPED_TEXT]. `null`
    /// (the default) gives all rows the row height.
    public final ObjectProperty<RowHeightProvider> rowHeightProviderProperty() {
        return rowHeightProvider;
    }

    public final RowHeightProvider getRowHeightProvider() {
        return rowHeightProvider.get();
    }

    public final void setRowHeightProvider(RowHeightProvider value) {
        rowHeightProvider.set(value);
    }

    /** Smallest height of any row, also of rows a {@link RowHeightProvider} makes smaller. 0 by default. */
    public final DoubleProperty minRowHeightProperty() {
        return minRowHeight;
    }

    public final double getMinRowHeight() {
        return minRowHeight.get();
    }

    public final void setMinRowHeight(double value) {
        minRowHeight.set(value);
    }

    /** Largest height of any row, also of rows a {@link RowHeightProvider} makes larger. Unlimited by default. */
    public final DoubleProperty maxRowHeightProperty() {
        return maxRowHeight;
    }

    public final double getMaxRowHeight() {
        return maxRowHeight.get();
    }

    public final void setMaxRowHeight(double value) {
        maxRowHeight.set(value);
    }

    /// Asks the row height provider again for all rows, e.g. after values it depends on changed. Updates of single
    /// rows do not need this: the grid asks for the updated rows itself.
    public final void refreshRowHeights() {
        if (getSkin() instanceof GridSkin skin) {
            skin.rowsResized();
        }
        requestRepaint();
    }

    /// Paints the visible cells again, for renderers or style providers that depend on something outside the model,
    /// such as a search term. Changes of the model repaint by themselves.
    public final void refresh() {
        renderers.invalidate();
        requestRepaint();
    }

    /** Per-cell styling. Called for every visible cell on every repaint, so it must return shared styles. */
    public final ObjectProperty<CellStyleProvider> cellStyleProviderProperty() {
        return cellStyleProvider;
    }

    public final CellStyleProvider getCellStyleProvider() {
        return cellStyleProvider.get();
    }

    public final void setCellStyleProvider(CellStyleProvider value) {
        cellStyleProvider.set(value);
    }

    /// Whether the column header is shown. Clicking it sorts or selects the column ([#sortableProperty()]),
    /// dragging moves columns, dragging a border resizes and double-clicking a border fits the column.
    public final BooleanProperty columnHeaderVisibleProperty() {
        return columnHeaderVisible;
    }

    public final boolean isColumnHeaderVisible() {
        return columnHeaderVisible.get();
    }

    public final void setColumnHeaderVisible(boolean value) {
        columnHeaderVisible.set(value);
    }

    /// Whether the row header with the model's row names ([GridModel#getRowName]) or row numbers is shown; hidden
    /// by default. Clicking it selects rows.
    public final BooleanProperty rowHeaderVisibleProperty() {
        return rowHeaderVisible;
    }

    public final boolean isRowHeaderVisible() {
        return rowHeaderVisible.get();
    }

    public final void setRowHeaderVisible(boolean value) {
        rowHeaderVisible.set(value);
    }

    /// Width of the row header, or 0 or less (the default) to fit the largest row number and the names of the
    /// first 1,000 rows, at most 320 pixels.
    public final DoubleProperty rowHeaderWidthProperty() {
        return rowHeaderWidth;
    }

    public final double getRowHeaderWidth() {
        return rowHeaderWidth.get();
    }

    public final void setRowHeaderWidth(double value) {
        rowHeaderWidth.set(value);
    }

    /** Number of leading view columns that stay in place when scrolling horizontally; negative values set 0. */
    public final IntegerProperty frozenColumnCountProperty() {
        return frozenColumnCount;
    }

    public final int getFrozenColumnCount() {
        return frozenColumnCount.get();
    }

    public final void setFrozenColumnCount(int value) {
        frozenColumnCount.set(value);
    }

    /// Number of leading view rows that stay in place below the column header when scrolling vertically; negative
    /// values set 0. They are view rows: after sorting, the first rows of the new order stay in place.
    public final IntegerProperty frozenRowCountProperty() {
        return frozenRowCount;
    }

    public final int getFrozenRowCount() {
        return frozenRowCount.get();
    }

    public final void setFrozenRowCount(int value) {
        frozenRowCount.set(value);
    }

    // ---- scrolling, zoom and geometry

    /** Horizontal scroll offset of the non-frozen columns, in unzoomed pixels. */
    public final DoubleProperty scrollXProperty() {
        return scrollX;
    }

    public final double getScrollX() {
        return scrollX.get();
    }

    public final void setScrollX(double value) {
        scrollX.set(value);
    }

    /** Vertical scroll offset in unzoomed pixels. */
    public final DoubleProperty scrollYProperty() {
        return scrollY;
    }

    public final double getScrollY() {
        return scrollY.get();
    }

    public final void setScrollY(double value) {
        scrollY.set(value);
    }

    /// Scale factor for everything inside the grid, kept within [#MIN_ZOOM] and [#MAX_ZOOM]; a value that is
    /// not a number sets 1.
    public final DoubleProperty zoomProperty() {
        return zoom;
    }

    public final double getZoom() {
        return zoom.get();
    }

    public final void setZoom(double value) {
        zoom.set(value);
    }

    /** The cell at a position in this control's coordinates, or {@code null} (header, empty area, no skin). */
    public final GridCell cellAt(double x, double y) {
        return getSkin() instanceof GridSkin skin ? skin.cellAt(x, y) : null;
    }

    /** Bounds of a view cell in this control's coordinates, also when scrolled out of view. Null without skin. */
    public final Bounds getCellBounds(int viewRow, int viewColumn) {
        return getSkin() instanceof GridSkin skin ? skin.cellBounds(viewRow, viewColumn) : null;
    }

    /// Scrolls as little as possible to show a view cell fully. It scrolls in the next layout pass, so it also
    /// works before the grid is shown and right after rows were added. For a model row or column, convert with
    /// [#modelToViewRow] and [#modelToViewColumn].
    ///
    /// @param viewRow    the view row
    /// @param viewColumn the view column
    /// @throws IndexOutOfBoundsException if the grid does not show that cell
    public final void scrollTo(int viewRow, int viewColumn) {
        if (viewRow < 0 || viewRow >= getViewRowCount() || viewColumn < 0 || viewColumn >= getViewColumnCount()) {
            throw new IndexOutOfBoundsException("No view cell " + viewRow + ", " + viewColumn + ": the grid shows "
                    + getViewRowCount() + " rows and " + getViewColumnCount() + " columns");
        }
        scrollTarget = new int[] {viewRows.toModel(viewRow), columns.toModel(viewColumn)};
        requestRepaint();
    }

    /** The view cell {@link #scrollTo} asked for, or null if there is none or it is gone; the skin takes it once. */
    int[] takeScrollTarget() {
        int[] target = scrollTarget;
        scrollTarget = null;
        int row = target == null ? -1 : viewRows.toView(target[0]);
        int column = target == null ? -1 : columns.toView(target[1]);
        return row < 0 || column < 0 ? null : new int[] {row, column};
    }

    // ---- CSS properties

    /// Font of the cells, CSS `-grid-font`. Default: the font the grid inherits (`-fx-font`), so
    /// `.root { -fx-font-size: 14px; }` changes it along with the rest of the application.
    public final ObjectProperty<Font> fontProperty() {
        return font;
    }

    public final Font getFont() {
        return font.get();
    }

    public final void setFont(Font value) {
        font.set(value);
    }

    /// Font of the row and column header, CSS `-grid-header-font`. Default: the inherited font in bold.
    public final ObjectProperty<Font> headerFontProperty() {
        return headerFont;
    }

    public final Font getHeaderFont() {
        return headerFont.get();
    }

    public final void setHeaderFont(Font value) {
        headerFont.set(value);
    }

    /// Text color of the cells, also used for check boxes, CSS `-grid-text-fill`. Default `-fx-text-inner-color`,
    /// which is dark or light depending on the theme's cell background.
    public final ObjectProperty<Color> textFillProperty() {
        return textFill;
    }

    public final Color getTextFill() {
        return textFill.get();
    }

    public final void setTextFill(Color value) {
        textFill.set(value);
    }

    /// Background of the cells, CSS `-grid-cell-background`. Default `-fx-control-inner-background`.
    public final ObjectProperty<Color> cellBackgroundProperty() {
        return cellBackground;
    }

    public final Color getCellBackground() {
        return cellBackground.get();
    }

    public final void setCellBackground(Color value) {
        cellBackground.set(value);
    }

    /// Background of every other row, CSS `-grid-alternate-cell-background`. Default
    /// `-fx-control-inner-background-alt`. `null` turns striping off.
    public final ObjectProperty<Color> alternateCellBackgroundProperty() {
        return alternateCellBackground;
    }

    public final Color getAlternateCellBackground() {
        return alternateCellBackground.get();
    }

    public final void setAlternateCellBackground(Color value) {
        alternateCellBackground.set(value);
    }

    /// Laid over every other column, CSS `-grid-alternate-column-background`. Default `null`: no column stripes. A
    /// see-through color such as `rgba(0, 0, 0, 0.04)` combines with the row stripes; for column stripes only, set
    /// `-grid-alternate-cell-background` to the cell background.
    public final ObjectProperty<Color> alternateColumnBackgroundProperty() {
        return alternateColumnBackground;
    }

    public final Color getAlternateColumnBackground() {
        return alternateColumnBackground.get();
    }

    public final void setAlternateColumnBackground(Color value) {
        alternateColumnBackground.set(value);
    }

    /// Color of the grid lines, CSS `-grid-line-color`; the line after the frozen columns stands out a little more.
    /// Default: the cell background, a little darker on light themes, lighter on dark ones, and plain gray on black
    /// (high contrast themes).
    public final ObjectProperty<Color> gridLineColorProperty() {
        return gridLineColor;
    }

    public final Color getGridLineColor() {
        return gridLineColor.get();
    }

    public final void setGridLineColor(Color value) {
        gridLineColor.set(value);
    }

    /// Background of selected cells, CSS `-grid-selection-background`. Default: a neutral tint of the cell
    /// background, and while the grid has the keyboard focus (`.grid:focused`) a tint of `-fx-accent`, light on
    /// light themes and dark on dark ones. With the focus, selected ranges get an outline in the focus color, and
    /// the focused cell of a larger selection keeps its normal background (not in row mode).
    public final ObjectProperty<Color> selectionBackgroundProperty() {
        return selectionBackground;
    }

    public final Color getSelectionBackground() {
        return selectionBackground.get();
    }

    public final void setSelectionBackground(Color value) {
        selectionBackground.set(value);
    }

    /// Border of the focused cell and the marker where a dragged column will drop, CSS `-grid-focus-color`.
    /// Default `-fx-focus-color`.
    public final ObjectProperty<Color> focusColorProperty() {
        return focusColor;
    }

    public final Color getFocusColor() {
        return focusColor.get();
    }

    public final void setFocusColor(Color value) {
        focusColor.set(value);
    }

    /// Background of the row and column header, CSS `-grid-header-background`. Default `-fx-base`.
    public final ObjectProperty<Color> headerBackgroundProperty() {
        return headerBackground;
    }

    public final Color getHeaderBackground() {
        return headerBackground.get();
    }

    public final void setHeaderBackground(Color value) {
        headerBackground.set(value);
    }

    /// Text color of the row and column header, CSS `-grid-header-text-fill`. Default `-fx-text-base-color`.
    public final ObjectProperty<Color> headerTextFillProperty() {
        return headerTextFill;
    }

    public final Color getHeaderTextFill() {
        return headerTextFill.get();
    }

    public final void setHeaderTextFill(Color value) {
        headerTextFill.set(value);
    }

    /// Height of rows without their own height, CSS `-grid-row-height`. Default `2em`: 24 pixels at the default
    /// font size of 12, and more for larger fonts.
    public final DoubleProperty rowHeightProperty() {
        return rowHeight;
    }

    public final double getRowHeight() {
        return rowHeight.get();
    }

    public final void setRowHeight(double value) {
        rowHeight.set(value);
    }

    /// Height of the column header, CSS `-grid-header-height`. Default `2.333333em`, 28 pixels at font size 12.
    public final DoubleProperty headerHeightProperty() {
        return headerHeight;
    }

    public final double getHeaderHeight() {
        return headerHeight.get();
    }

    public final void setHeaderHeight(double value) {
        headerHeight.set(value);
    }

    /// Space between a cell's left and right border and its text, CSS `-grid-cell-padding`. Default `0.5em`,
    /// 6 pixels at font size 12.
    public final DoubleProperty cellPaddingProperty() {
        return cellPadding;
    }

    public final double getCellPadding() {
        return cellPadding.get();
    }

    public final void setCellPadding(double value) {
        cellPadding.set(value);
    }

    /** The CSS properties of the grid, including those of {@link Control}. */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return CSS.getCssMetaData();
    }

    @Override
    public final List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return getClassCssMetaData();
    }

    /** Lets screen readers and UI test tools see the grid as a table, with its cells, focus and selection. */
    @Override
    public final Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        GridSkin skin = getSkin() instanceof GridSkin s ? s : null;
        return switch (attribute) {
            case ROW_COUNT -> getViewRowCount();
            case COLUMN_COUNT -> getViewColumnCount();
            case MULTIPLE_SELECTION -> selectionModel.getMode() != SelectionMode.SINGLE_CELL;
            case FOCUS_ITEM -> skin == null ? null
                    : skin.accessibleCell(selectionModel.getFocusedRow(), selectionModel.getFocusedColumn());
            case CELL_AT_ROW_COLUMN -> skin == null ? null
                    : skin.accessibleCell((Integer) parameters[0], (Integer) parameters[1]);
            case COLUMN_AT_INDEX -> skin == null ? null : skin.accessibleHeader((Integer) parameters[0]);
            case SELECTED_ITEMS -> {
                int max = GridSkin.MAX_ACCESSIBLE_CELLS;
                List<Node> cells = new ArrayList<>();
                for (CellRange range : skin == null ? List.<CellRange>of() : selectionModel.getRanges()) {
                    for (int row = range.firstRow(); row <= range.lastRow() && cells.size() < max; row++) {
                        for (int column = range.firstColumn(); column <= range.lastColumn() && cells.size() < max;
                             column++) {
                            cells.add(skin.accessibleCell(row, column));
                        }
                    }
                }
                yield FXCollections.observableArrayList(cells);
            }
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }

    @Override
    public final String getUserAgentStylesheet() {
        return STYLESHEET;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new GridSkin(this);
    }

    // ---- package-private access for skin, painter and behavior

    ColumnLayout columns() {
        return columns;
    }

    TextFitter textFitter() {
        return textFitter;
    }

    RendererRegistry renderers() {
        return renderers;
    }

    ColumnSettings settings() {
        return settings;
    }

    /** Sets a column width within its limits; the column is no longer fitted automatically. */
    void resizeColumn(int modelColumn, double width) {
        if (!Double.isFinite(width)) {
            return;
        }
        columns.setModelWidth(modelColumn, clampWidth(modelColumn, width));
        settings.edit(modelColumn, c -> c.explicitWidth = true);
        columnWidthChanged();
    }

    void limitColumn(int modelColumn, double minWidth, double maxWidth) {
        columns.setLimits(modelColumn, minWidth, maxWidth);
        columns.setModelWidth(modelColumn, clampWidth(modelColumn, columns.modelWidth(modelColumn)));
        columnWidthChanged();
    }

    void setColumnVisible(int modelColumn, boolean visible) {
        columns.setVisible(modelColumn, visible);
        columnsChanged();
    }

    /** Shows all hidden columns at once. */
    void showAllColumns() {
        columns.setHidden(List.of());
        columnsChanged();
    }

    void setColumnRenderer(int modelColumn, CellRenderer renderer) {
        renderers.setColumn(modelColumn, renderer);
        requestRepaint();
    }

    /** Changes a column's settings that affect how wide it is and how it looks: fits and repaints. */
    void editColumn(int modelColumn, Consumer<ColumnSettings.Column> change) {
        settings.edit(modelColumn, change);
        refitColumns();
        requestRepaint();
    }

    /** The column's own header renderer, else the grid's, else plain text. */
    CellRenderer headerRendererFor(int modelColumn) {
        CellRenderer own = settings.get(modelColumn, c -> c.headerRenderer);
        CellRenderer renderer = own != null ? own : getHeaderRenderer();
        return renderer != null ? renderer : CellRenderers.TEXT;
    }

    Comparator<Object> columnComparator(int modelColumn) {
        return settings.get(modelColumn, c -> c.comparator);
    }

    void setColumnComparator(int modelColumn, Comparator<Object> comparator) {
        settings.edit(modelColumn, c -> c.comparator = comparator);
        if (sortIndex(getSortKeys(), modelColumn) >= 0) {
            sort(getSortKeys());
        }
    }

    void fitColumn(int modelColumn) {
        if (getSkin() instanceof GridSkin skin) {
            resizeColumn(modelColumn, Math.ceil(skin.measureVisibleRows(new int[] {modelColumn})[0]));
        }
    }

    private double clampWidth(int modelColumn, double width) {
        return Math.clamp(width, columns.minWidth(modelColumn), columns.maxWidth(modelColumn));
    }

    private void columnWidthChanged() {
        if (getRowHeightProvider() != null) {
            refreshRowHeights();
        }
        requestRepaint();
        layoutChanged();
    }

    /** A row height within the minimum and maximum; anything that is not a finite number gets the row height. */
    double clampRowHeight(double height) {
        double h = Double.isFinite(height) ? height : getRowHeight();
        double min = Math.max(0, getMinRowHeight());
        return Math.clamp(h, min, Math.max(min, getMaxRowHeight()));
    }

    /** The default renderer choice for a cell, see {@link #cellRendererProviderProperty()}. */
    CellRenderer rendererFor(int modelColumn, Object value) {
        return renderers.get(getModel(), modelColumn, value);
    }

    /** Row mapping, for the skin. */
    ViewRows rows() {
        return viewRows;
    }

    /** Whether columns still wait to be fitted, see {@link #autoFitColumnsProperty()}. */
    boolean needsAutoFit() {
        return isAutoFitColumns() && !autoFitDone && getModel() != null;
    }

    /** Lets the next layout pass fit the columns without an explicit width again. */
    void refitColumns() {
        autoFitDone = false;
    }

    /** The model columns auto-fitting looks at: the first {@link #AUTO_FIT_COLUMNS} in view order. */
    int[] columnsToAutoFit() {
        int[] result = new int[Math.min(columns.count(), AUTO_FIT_COLUMNS)];
        Arrays.setAll(result, columns::toModel);
        return result;
    }

    /// Applies measured widths to the columns without an explicit width. Done once there are rows; until the
    /// measured cells had values (a model that is still loading), updates of the first rows fit again.
    void autoFit(int[] modelColumns, double[] wanted, boolean sawValues) {
        Map<Integer, Double> widths = new HashMap<>();
        for (int i = 0; i < modelColumns.length; i++) {
            int column = modelColumns[i];
            if (!settings.hasExplicitWidth(column)) {
                widths.put(column, Math.clamp(Math.ceil(wanted[i]), columns.minWidth(column), autoFitMax(column)));
            }
        }
        columns.setModelWidths(widths);
        autoFitDone = getViewRowCount() > 0;
        autoFitSawValues = sawValues;
        if (getRowHeightProvider() != null) {
            refreshRowHeights();
        }
    }

    /** The cell at a view position, or {@code null} if it no longer exists. */
    GridCell cell(int viewRow, int viewColumn) {
        if (getModel() == null || viewRow < 0 || viewColumn < 0 || viewRow >= viewRows.count()
                || viewColumn >= columns.count()) {
            return null;
        }
        int modelRow = viewRows.toModel(viewRow);
        int modelColumn = columns.toModel(viewColumn);
        return new GridCell(viewRow, viewColumn, modelRow, modelColumn, getModel().getValueAt(modelRow, modelColumn));
    }

    /** The zoom actually used: within the limits, 1 for anything that is not a finite number. */
    double effectiveZoom() {
        double z = getZoom();
        return Double.isFinite(z) ? Math.clamp(z, MIN_ZOOM, MAX_ZOOM) : 1;
    }

    /** Height of the whole column header: the rows of column groups and the column headers below them. */
    double columnHeaderHeight() {
        return isColumnHeaderVisible() ? groupLevels() * groupRowHeight() + getHeaderHeight() : 0;
    }

    /** Number of rows of column groups above the column headers. */
    int groupLevels() {
        List<ColumnGroup> groups = getColumnGroups();
        return groups == null ? 0 : groups.size();
    }

    /** Height of a row of column groups: one line of the header font. */
    double groupRowHeight() {
        return Math.ceil(textFitter.lineHeight(getHeaderFont()) + 2 * getCellPadding());
    }

    /** The group of a model column at a level, or null. A function that throws counts as no group. */
    String groupName(int level, int modelColumn) {
        try {
            return getColumnGroups().get(level).nameOf(modelColumn);
        } catch (RuntimeException e) {
            report("column group", e);
            return null;
        }
    }

    /** The renderer for the body cells of a model column; subclasses add to it, as {@link TreeGrid} does. */
    CellRenderer decorate(int modelColumn, CellRenderer renderer) {
        return renderer;
    }

    /** Where a column's own header starts, below the lowest group it belongs to; 0 without groups. */
    double headerTop(int modelColumn) {
        for (int level = groupLevels() - 1; level >= 0; level--) {
            if (groupName(level, modelColumn) != null) {
                return (level + 1) * groupRowHeight();
            }
        }
        return 0;
    }

    void markNeedsLayout() {
        setNeedsLayout(true);
    }

    /// Logs an exception from code the grid calls (a renderer, provider, model or filter) once per place and
    /// kind, so a broken renderer does not flood the log every frame.
    void report(String where, RuntimeException e) {
        if (reported.add(where + ": " + e.getClass().getName())) {
            System.getLogger(Grid.class.getName()).log(System.Logger.Level.WARNING, "Exception in " + where, e);
        }
    }

    // ---- model events

    private void requestRepaint() {
        if (getSkin() instanceof GridSkin skin) {
            skin.markDirty();
        }
    }

    private void modelChanged(GridModelEvent event) {
        if (event.source() != getModel()) {
            return; // queued for a model the grid has just left
        }
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("GridModel events must be fired on the JavaFX application thread. "
                    + "Change data inside AbstractGridModel.applyChange, which takes the change there.");
        }
        int first = event.firstRow();
        int count = event.lastRow() - first + 1;
        switch (event.type()) {
            case UPDATE -> {
                if (!autoFitSawValues && first < AUTO_FIT_ROWS) {
                    refitColumns();
                }
                if (getSkin() instanceof GridSkin skin) {
                    skin.rowNamesChanged(viewRows.isIdentity() ? first : 0);
                    if (getRowHeightProvider() != null) {
                        skin.rowsUpdated(first, event.lastRow());
                    }
                }
                if (getRowHeightProvider() != null || showsAnyOf(first, event.lastRow())) {
                    requestRepaint();
                }
            }
            case INSERT -> rowsChanged(event.type(), first, count, m -> m >= first ? m + count : m);
            case DELETE -> rowsChanged(event.type(), first, count,
                    m -> m < first ? m : m >= first + count ? m - count : -1);
            case DATA -> rowsChanged(event.type(), 0, 0, IntUnaryOperator.identity());
            case STRUCTURE -> structureChanged();
        }
    }

    /// `count` rows were inserted or deleted at model row `first`, or any row may have changed (`DATA`); `shift`
    /// maps old model rows to new ones, -1 for deleted rows. Updates the row mapping first, then everything that
    /// refers to rows: the top row on screen, the scroll target, the selection and the cell being edited.
    private void rowsChanged(GridModelEvent.Type type, int first, int count, IntUnaryOperator shift) {
        GridSkin skin = getSkin() instanceof GridSkin s ? s : null;
        boolean data = type == GridModelEvent.Type.DATA;
        boolean inserted = type == GridModelEvent.Type.INSERT;
        boolean identity = viewRows.isIdentity();
        // In a sorted or filtered view, few inserted or deleted rows are followed to their view rows
        boolean byViewRows = !identity && !data && count <= MAX_SHIFTED_ROWS && viewRows.isBuilt();
        int[] deleted = byViewRows && !inserted ? shownRows(first, count) : null;
        GridSkin heights = skin != null && !data && count <= MAX_SHIFTED_ROWS && (identity || byViewRows)
                && skin.rowPositionsCurrent() ? skin : null;
        GridSelectionModel.Snapshot selection = identity || byViewRows ? null
                : selectionModel.capture(viewRows::toModel, MAX_REMAPPED_ROWS);
        if (scrollTarget != null && !data) {
            scrollTarget[0] = shift.applyAsInt(scrollTarget[0]);
        }
        if (skin != null) {
            if (!data) {
                skin.keepTopRow(shift, first);
            }
            skin.editor().rowsMoved(data ? null : shift);
            skin.rowNamesChanged(identity ? first : 0);
        }
        switch (type) {
            case INSERT -> viewRows.rowsInserted(first, count);
            case DELETE -> viewRows.rowsDeleted(first, count);
            default -> viewRows.invalidate();
        }
        renderers.invalidate();
        if (data && !autoFitSawValues) {
            refitColumns();
        }
        if (identity && !data) {
            moveRows(inserted, first, count, heights);
        } else if (identity) {
            selectionModel.clipToRowCount();
        } else if (deleted != null) {
            for (int i = deleted.length - 1; i >= 0; i--) {
                moveRows(false, deleted[i], 1, heights);
            }
        } else if (byViewRows) {
            for (int row : shownRows(first, count)) {
                moveRows(true, row, 1, heights);
            }
        } else if (selection == null) {
            selectionModel.clear();
        } else {
            selectionModel.restore(selection, model -> {
                int moved = shift.applyAsInt(model);
                return moved < 0 ? -1 : viewRows.toView(moved);
            });
        }
        requestRepaint();
    }

    /// Moves the selection, and the row positions if `heights` is given, along with rows inserted at or deleted
    /// from a view row.
    private void moveRows(boolean inserted, int viewRow, int count, GridSkin heights) {
        if (inserted) {
            selectionModel.rowsInserted(viewRow, count);
        } else {
            selectionModel.rowsDeleted(viewRow, count);
        }
        if (heights != null) {
            heights.rowsMoved(inserted, viewRow, count);
        }
    }

    /** The view rows of model rows {@code first..first+count-1} that are shown, ascending. */
    private int[] shownRows(int first, int count) {
        return IntStream.range(first, first + count).map(viewRows::toView).filter(row -> row >= 0).sorted().toArray();
    }

    /// Changes which rows are shown or their order (sort, filter), keeping the selection on its model rows. A
    /// selection over more than [#MAX_REMAPPED_ROWS] rows is cleared instead.
    private void changeView(Runnable change) {
        GridSelectionModel.Snapshot selection = selectionModel.capture(viewRows::toModel, MAX_REMAPPED_ROWS);
        change.run();
        if (getSkin() instanceof GridSkin skin) {
            skin.rowNamesChanged(0);
        }
        if (selection == null) {
            selectionModel.clear();
        } else {
            selectionModel.restore(selection, viewRows::toView);
        }
        requestRepaint();
    }

    private void sortChanged() {
        List<SortKey> keys = viewRows.sortKeys();
        if (!keys.equals(getSortKeys())) {
            sortKeys.set(keys);
            for (SortKey key : keys) {
                widenToFit(key.column(), 0, 0); // the header label next to the sort arrow
            }
        }
        layoutChanged();
    }

    /// Widens a column without an explicit width to fit its header and view rows `firstRow..firstRow+rowCount-1`
    /// (at most 50), up to the auto-fit maximum; never narrows it. For what auto-fitting could not see, such as
    /// the sort arrow or the children of an item opened later.
    void widenToFit(int modelColumn, int firstRow, int rowCount) {
        if (!isAutoFitColumns() || settings.hasExplicitWidth(modelColumn) || !(getSkin() instanceof GridSkin skin)) {
            return;
        }
        int[] measured = {modelColumn};
        double wanted = Math.ceil(skin.measure(firstRow, Math.min(rowCount, AUTO_FIT_ROWS), measured)[0]);
        if (wanted > columns.modelWidth(modelColumn)) {
            columns.setModelWidths(Map.of(modelColumn, Math.min(wanted, autoFitMax(modelColumn))));
            requestLayout();
        }
    }

    /** The widest auto-fitting makes a column: its maximum, at most {@link #MAX_AUTO_FIT_WIDTH}. */
    private double autoFitMax(int modelColumn) {
        return Math.max(columns.minWidth(modelColumn), Math.min(columns.maxWidth(modelColumn), MAX_AUTO_FIT_WIDTH));
    }

    private void layoutChanged() {
        Consumer<GridLayout> listener = getOnLayoutChanged();
        if (listener != null && !restoringLayout) {
            listener.accept(getLayout());
        }
    }

    private void columnsChanged() {
        selectionModel.keepWholeRows();
        requestRepaint();
        layoutChanged();
    }

    private void structureChanged() {
        int count = getModel() == null ? 0 : getModel().getColumnCount();
        if (getSkin() instanceof GridSkin skin) {
            skin.editor().cancel();
            skin.rowNamesChanged(0);
            skin.rowsResized();
        }
        columns.resize(count, DEFAULT_COLUMN_WIDTH, MIN_COLUMN_WIDTH);
        renderers.resize(count);
        settings.prune(count);
        autoFitDone = false;
        scrollTarget = null;
        List<SortKey> kept = getSortKeys().stream().filter(key -> key.column() < count).toList();
        if (kept.size() < getSortKeys().size()) {
            viewRows.sort(kept, this::columnComparator);
            sortChanged();
        }
        viewRows.invalidate();
        selectionModel.clear();
        requestRepaint();
    }

    /// Whether any of the model rows is on screen. Without a filter or sort only the range is compared, otherwise
    /// the model rows of the visible view rows, so an update costs the same for one row or for all of them.
    private boolean showsAnyOf(int firstModelRow, int lastModelRow) {
        if (!(getSkin() instanceof GridSkin skin)) {
            return false;
        }
        GridGeometry g = skin.geometry();
        return showsAnyOf(firstModelRow, lastModelRow, 0, g.lastFrozenRow())
                || showsAnyOf(firstModelRow, lastModelRow, g.firstScrolledRow(), g.lastScrolledRow());
    }

    /** Whether any of the model rows is shown in view rows {@code first..last}. */
    private boolean showsAnyOf(int firstModelRow, int lastModelRow, int first, int last) {
        if (viewRows.isIdentity()) {
            return lastModelRow >= first && firstModelRow <= last;
        }
        // An outdated mapping repaints anyway; reading rows here would rebuild it in the middle of an event.
        if (!viewRows.isBuilt()) {
            return true;
        }
        for (int view = first; view <= Math.min(last, viewRows.count() - 1); view++) {
            int model = viewRows.toModel(view);
            if (model >= firstModelRow && model <= lastModelRow) {
                return true;
            }
        }
        return false;
    }

    private <T> StyleableObjectProperty<T> property(CssMetaData<Grid, T> css, String name) {
        return new SimpleStyleableObjectProperty<>(css, this, name, css.getInitialValue(this));
    }

    private StyleableDoubleProperty size(CssMetaData<Grid, Number> css, String name) {
        return new SimpleStyleableDoubleProperty(css, this, name, css.getInitialValue(this).doubleValue());
    }
}
