package de.schladebeck.fx.grid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.util.Duration;

/// Turns mouse, keyboard, scroll and zoom input into grid actions. All positions go through the skin's
/// [GridGeometry], so input always matches what was painted. While a cell is edited in place, keys belong to
/// the editor.
final class GridBehavior {

    private static final double ZOOM_STEP = 1.1;
    /** Wheel delta of one notch; zooming with the wheel goes one zoom step per notch, touchpads in between. */
    private static final double WHEEL_NOTCH = 40;
    /** Mouse movement before a header press turns from a click (sort) into a column drag. */
    private static final double DRAG_THRESHOLD = 5;
    /** Pixels per frame that dragging past an edge scrolls, at most. */
    private static final double MAX_AUTO_SCROLL = 40;

    private enum Drag { NONE, SELECT, ROWS, RESIZE, HEADER, MOVE }

    private final Grid grid;
    private final GridSkin skin;
    private final Canvas canvas;
    private final Tooltip tooltip = new Tooltip();
    /** The cell the tooltip graphic was made for, so it is made once while the mouse stays in that cell. */
    private int graphicRow = -1;
    private int graphicColumn = -1;
    private Node graphic;
    private final Tooltip message = new Tooltip();
    private final PauseTransition messageTimeout = new PauseTransition(Duration.seconds(3));
    /** Removes the event handlers the constructor added, run by {@link #dispose}. */
    private final List<Runnable> handlerRemovals = new ArrayList<>();
    /** Scrolls while the mouse drags a selection or a column past an edge. */
    private final AnimationTimer autoScroll = new AnimationTimer() {
        @Override
        public void handle(long now) {
            autoScrollStep();
        }
    };
    /** The menu currently shown, column or cell, so a new one replaces it. */
    private ContextMenu openMenu;
    private boolean tooltipShown;
    private Drag drag = Drag.NONE;
    private int dragColumn;
    private double dragStartX;
    private double dragStartWidth;
    /** Canvas position of the last drag event, for auto-scrolling. */
    private double dragX;
    private double dragY;

    GridBehavior(Grid grid, GridSkin skin, Canvas canvas) {
        this.grid = grid;
        this.skin = skin;
        this.canvas = canvas;
        tooltip.getStyleClass().add("grid-tooltip");
        messageTimeout.setOnFinished(e -> message.hide());
        addHandler(canvas, MouseEvent.MOUSE_PRESSED, this::mousePressed);
        addHandler(canvas, MouseEvent.MOUSE_DRAGGED, this::mouseDragged);
        addHandler(canvas, MouseEvent.MOUSE_RELEASED, this::mouseReleased);
        addHandler(canvas, MouseEvent.MOUSE_MOVED, this::mouseMoved);
        addHandler(canvas, MouseEvent.MOUSE_EXITED, e -> showTooltip(null, null));
        addHandler(grid, ContextMenuEvent.CONTEXT_MENU_REQUESTED, this::contextMenuRequested);
        addHandler(grid, KeyEvent.KEY_PRESSED, this::keyPressed);
        addHandler(grid, KeyEvent.KEY_TYPED, this::keyTyped);
        addHandler(grid, ScrollEvent.SCROLL, this::scrolled);
        addHandler(grid, ZoomEvent.ZOOM, this::zoomed);
    }

    void dispose() {
        autoScroll.stop();
        handlerRemovals.forEach(Runnable::run);
        showTooltip(null, null);
        message.hide();
    }

    private <T extends Event> void addHandler(Node node, EventType<T> type, EventHandler<? super T> handler) {
        node.addEventHandler(type, handler);
        handlerRemovals.add(() -> node.removeEventHandler(type, handler));
    }

    // ---- mouse

    private void mousePressed(MouseEvent e) {
        CellEditor editor = skin.editor();
        if (editor.isEditing() && !editor.commit()) {
            editor.focus();
            e.consume();
            return;
        }
        grid.requestFocus();
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        GridGeometry g = skin.geometry();
        GridSelectionModel selection = grid.getSelectionModel();
        double x = e.getX();
        double y = e.getY();
        if (g.inColumnHeader(y)) {
            pressHeader(g, e);
            return;
        }
        // The geometry is from the last layout pass; rows deleted since then are not there to click.
        int row = g.rowAt(y);
        if (row < 0 || row >= grid.getViewRowCount()) {
            return;
        }
        if (g.inRowHeader(x)) {
            if (e.isShiftDown()) {
                selection.extendRows(row);
            } else {
                selection.selectRow(row, e.isShortcutDown());
            }
            drag = Drag.ROWS;
            return;
        }
        int column = g.columnAt(x);
        if (column < 0 || column >= grid.getViewColumnCount()) {
            return;
        }
        boolean plain = !e.isShiftDown() && !e.isShortcutDown();
        boolean checkBox = grid.isEditable() && grid.cell(row, column).value() instanceof Boolean
                && onCheckBox(g, row, column, x, e.getY());
        if (e.getClickCount() == 2 && plain) {
            if (!checkBox) {
                activate(row, column);
            }
            return;
        }
        if (e.isShiftDown()) {
            selection.extend(row, column);
        } else if (e.isShortcutDown()) {
            selection.add(row, column);
        } else {
            selection.select(row, column);
        }
        if (checkBox && plain) {
            skin.startEdit(row, column);
            return;
        }
        drag = Drag.SELECT;
    }

    /** The corner selects all, a column border fits or resizes its column, a column header may become a drag. */
    private void pressHeader(GridGeometry g, MouseEvent e) {
        double x = e.getX();
        if (g.inRowHeader(x)) {
            grid.getSelectionModel().selectAll();
            return;
        }
        if (inColumnGroup(x, e.getY())) {
            return;
        }
        int border = g.resizeBorderAt(x);
        if (border >= 0) {
            if (e.getClickCount() == 2) {
                grid.getColumnAtView(border).fitToContent();
                return;
            }
            drag = Drag.RESIZE;
            dragColumn = border;
            dragStartX = x;
            dragStartWidth = grid.getColumnAtView(border).getWidth();
            return;
        }
        int column = g.columnAt(x);
        if (column >= 0) {
            drag = Drag.HEADER;
            dragColumn = column;
            dragStartX = x;
        }
    }

    /// Whether a canvas point is on the check box [CellRenderers#BOOLEAN] paints in the middle of a cell, give
    /// or take a few pixels; a click elsewhere in the cell only selects it.
    private boolean onCheckBox(GridGeometry g, int row, int column, double x, double y) {
        Bounds cell = g.cellBounds(row, column);
        double size = Math.min(Math.round(grid.getFont().getSize()), cell.getHeight() / g.zoom() - 4) * g.zoom();
        double reach = size / 2 + 3;
        return Math.abs(x - cell.getCenterX()) <= reach && Math.abs(y - cell.getCenterY()) <= reach;
    }

    private void mouseDragged(MouseEvent e) {
        GridGeometry g = skin.geometry();
        dragX = e.getX();
        dragY = e.getY();
        switch (drag) {
            case RESIZE -> grid.getColumnAtView(dragColumn).setWidth(dragStartWidth + (dragX - dragStartX) / g.zoom());
            case HEADER -> {
                if (grid.isColumnsReorderable() && Math.abs(dragX - dragStartX) > DRAG_THRESHOLD) {
                    drag = Drag.MOVE;
                    canvas.setCursor(Cursor.CLOSED_HAND);
                    followDrag();
                }
            }
            case MOVE, SELECT, ROWS -> {
                followDrag();
                if (overshootX() != 0 || overshootY() != 0) {
                    autoScroll.start();
                }
            }
            default -> {
            }
        }
    }

    /** Extends the selection, or moves the drop marker and the dragged header, to the last drag position. */
    private void followDrag() {
        GridGeometry g = skin.geometry();
        if (drag == Drag.MOVE) {
            skin.setDropGap(g.dropGapAt(dragX));
            skin.setDragGhost(dragColumn, dragX);
            return;
        }
        int rows = grid.getViewRowCount();
        int columns = grid.getViewColumnCount();
        if (rows == 0 || columns == 0) {
            return;
        }
        // Clamp into the visible body, so dragging past an edge selects up to the edge.
        double x = Math.clamp(dragX, g.rowHeaderWidth() * g.zoom(), Math.max(0, canvas.getWidth() - 1));
        double y = Math.clamp(dragY, g.headerHeight() * g.zoom(), Math.max(0, canvas.getHeight() - 1));
        int row = g.rowAt(y);
        int column = g.columnAt(x);
        row = row < 0 || row >= rows ? rows - 1 : row;
        column = column < 0 || column >= columns ? columns - 1 : column;
        GridSelectionModel selection = grid.getSelectionModel();
        if (drag == Drag.ROWS && row != selection.getFocusedRow()) {
            selection.extendRows(row);
        } else if (drag == Drag.SELECT
                && (row != selection.getFocusedRow() || column != selection.getFocusedColumn())) {
            selection.extend(row, column);
        }
    }

    /** How far the drag is left (negative) or right (positive) of the scrolling columns; 0 inside. */
    private double overshootX() {
        if (drag == Drag.ROWS) {
            return 0;
        }
        GridGeometry g = skin.geometry();
        double left = (g.rowHeaderWidth() + g.frozenWidth()) * g.zoom();
        return dragX < left ? dragX - left : Math.max(0, dragX - canvas.getWidth());
    }

    /** How far the drag is above (negative) or below (positive) the scrolling rows; 0 inside. */
    private double overshootY() {
        if (drag == Drag.MOVE) {
            return 0;
        }
        GridGeometry g = skin.geometry();
        double top = (g.headerHeight() + g.frozenHeight()) * g.zoom();
        return dragY < top ? dragY - top : Math.max(0, dragY - canvas.getHeight());
    }

    /** One frame of auto-scrolling: faster the further the mouse is outside, then the selection follows. */
    private void autoScrollStep() {
        double dx = overshootX();
        double dy = overshootY();
        if (drag == Drag.NONE || dx == 0 && dy == 0) {
            autoScroll.stop();
            return;
        }
        double zoom = grid.effectiveZoom();
        grid.setScrollX(grid.getScrollX() + speed(dx) / zoom);
        grid.setScrollY(grid.getScrollY() + speed(dy) / zoom);
        followDrag();
    }

    private static double speed(double overshoot) {
        return overshoot == 0 ? 0 : Math.signum(overshoot) * Math.min(MAX_AUTO_SCROLL, 2 + Math.abs(overshoot) / 2);
    }

    private void mouseReleased(MouseEvent e) {
        autoScroll.stop();
        GridGeometry g = skin.geometry();
        if (drag == Drag.MOVE) {
            int gap = g.dropGapAt(e.getX());
            endColumnDrag();
            grid.moveColumn(dragColumn, gap > dragColumn ? gap - 1 : gap);
        } else if (drag == Drag.HEADER && g.inColumnHeader(e.getY()) && g.columnAt(e.getX()) == dragColumn) {
            clickHeader(dragColumn, e);
        }
        drag = Drag.NONE;
    }

    /// A click on a column header sorts. It selects the column instead when the grid is not sortable or selects
    /// whole columns. The second click of a double-click does not sort again.
    private void clickHeader(int column, MouseEvent e) {
        GridSelectionModel selection = grid.getSelectionModel();
        if (!grid.isSortable() || selection.getMode() == SelectionMode.COLUMN) {
            if (grid.getViewRowCount() > 0) {
                selection.selectColumn(column, e.isShortcutDown());
            }
        } else if (e.getClickCount() == 1) {
            grid.clickSort(grid.viewToModelColumn(column), e.isShiftDown());
        }
    }

    private void endColumnDrag() {
        skin.setDropGap(-1);
        skin.setDragGhost(-1, 0);
        canvas.setCursor(Cursor.DEFAULT);
    }

    /** Whether a canvas point is on a column group above a column's own header, where clicks do nothing. */
    private boolean inColumnGroup(double x, double y) {
        GridGeometry g = skin.geometry();
        int column = g.columnAt(x);
        return column >= 0 && y / g.zoom() < grid.headerTop(grid.viewToModelColumn(column));
    }

    private void mouseMoved(MouseEvent e) {
        GridGeometry g = skin.geometry();
        double x = e.getX();
        double y = e.getY();
        boolean inHeader = g.inColumnHeader(y);
        boolean inRowHeader = g.inRowHeader(x);
        canvas.setCursor(inHeader && !inColumnGroup(x, y) && g.resizeBorderAt(x) >= 0 ? Cursor.H_RESIZE
                : Cursor.DEFAULT);

        String text = null;
        Node node = null;
        if (inHeader && !inRowHeader) {
            int column = g.columnAt(x);
            text = column < 0 ? null : inColumnGroup(x, y) ? groupTooltip(g, column, y)
                    : skin.cutOffText(-1, column);
        } else if (inRowHeader && !inHeader) {
            int row = g.rowAt(y);
            text = row < 0 ? null : skin.cutOffText(row, -1);
        } else if (!inHeader) {
            text = cellTooltip(g.rowAt(y), g.columnAt(x));
            node = cellGraphic(g.rowAt(y), g.columnAt(x));
        }
        showTooltip(text, node);
    }

    /** The tooltip graphic provider's node for a cell, made once while the mouse stays in the cell. */
    private Node cellGraphic(int row, int column) {
        Function<GridCell, Node> provider = grid.getTooltipGraphicProvider();
        if (provider == null || row < 0 || column < 0) {
            return null;
        }
        if (row != graphicRow || column != graphicColumn) {
            graphicRow = row;
            graphicColumn = column;
            try {
                graphic = provider.apply(grid.cell(row, column));
            } catch (RuntimeException ex) {
                grid.report("tooltip graphic provider", ex);
                graphic = null;
            }
        }
        return graphic;
    }

    /// The full name of a cut-off column group. The painter keeps it under the group's first column on screen, so
    /// this looks left from the column for as long as the group goes on.
    private String groupTooltip(GridGeometry g, int column, double y) {
        int level = (int) (y / g.zoom() / grid.groupRowHeight());
        String name = grid.groupName(level, grid.viewToModelColumn(column));
        int first = column < g.frozenColumns() ? 0 : g.firstScrolledColumn();
        for (int c = column; name != null && c >= first; c--) {
            String text = skin.cutOffText(-2 - level, c);
            if (text != null || c == first || !name.equals(grid.groupName(level, grid.viewToModelColumn(c - 1)))) {
                return text;
            }
        }
        return null;
    }

    /** The tooltip provider's text for a cell, else the full text if the cell is cut off. */
    private String cellTooltip(int row, int column) {
        if (row < 0 || column < 0) {
            return null;
        }
        Function<GridCell, String> provider = grid.getTooltipProvider();
        GridCell cell = provider == null ? null : grid.cell(row, column);
        if (cell != null) {
            try {
                String text = provider.apply(cell);
                if (text != null) {
                    return text;
                }
            } catch (RuntimeException ex) {
                grid.report("tooltip provider", ex);
            }
        }
        return skin.cutOffText(row, column);
    }

    /// Right-click or the keyboard's menu key: the column menu in the column header, the cell menu from
    /// [Grid#cellContextMenuFactoryProperty()] on cells.
    private void contextMenuRequested(ContextMenuEvent e) {
        GridGeometry g = skin.geometry();
        if (grid.getModel() == null) {
            return;
        }
        GridSelectionModel selection = grid.getSelectionModel();
        if (e.isKeyboardTrigger()) {
            int row = selection.getFocusedRow();
            int column = selection.getFocusedColumn();
            Point2D below = row >= 0 && column >= 0 ? pointBelowCell(row, column) : null;
            if (below != null) {
                showCellMenu(row, column, below.getX(), below.getY(), e);
            }
            return;
        }
        Point2D at = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (g.inColumnHeader(at.getY())) {
            if (!g.inRowHeader(at.getX())) {
                int column = g.columnAt(at.getX());
                showColumnMenu(column, e.getScreenX(), e.getScreenY(), e);
            }
            return;
        }
        int row = g.rowAt(at.getY());
        int column = g.columnAt(at.getX());
        if (row >= 0 && column >= 0 && row < grid.getViewRowCount() && column < grid.getViewColumnCount()) {
            showCellMenu(row, column, e.getScreenX(), e.getScreenY(), e);
        }
    }

    /** Scrolls a cell into view and returns the screen point below its lower left corner, null without a window. */
    private Point2D pointBelowCell(int row, int column) {
        grid.scrollTo(row, column);
        grid.layout();
        Bounds b = grid.getCellBounds(row, column);
        return grid.localToScreen(b.getMinX(), b.getMaxY());
    }

    private void showCellMenu(int row, int column, double screenX, double screenY, ContextMenuEvent e) {
        Function<GridCell, ContextMenu> factory = grid.getCellContextMenuFactory();
        if (factory == null) {
            return;
        }
        GridSelectionModel selection = grid.getSelectionModel();
        if (!selection.isSelected(row, column)) {
            selection.select(row, column);
        }
        try {
            ContextMenu menu = factory.apply(grid.cell(row, column));
            if (menu != null) {
                show(menu, screenX, screenY, e);
            }
        } catch (RuntimeException ex) {
            grid.report("cell context menu factory", ex);
        }
    }

    /** The column menu for a view column, or for the area right of the last column (-1). */
    private void showColumnMenu(int column, double screenX, double screenY, ContextMenuEvent e) {
        if (!grid.isColumnMenuEnabled()) {
            return;
        }
        GridColumn gridColumn = column < 0 ? null : grid.getColumnAtView(column);
        Function<GridColumn, ContextMenu> factory = grid.getColumnMenuFactory();
        try {
            ContextMenu menu = factory == null ? grid.createColumnMenu(gridColumn) : factory.apply(gridColumn);
            if (menu != null) {
                show(menu, screenX, screenY, e);
            }
        } catch (RuntimeException ex) {
            grid.report("column menu factory", ex);
        }
    }

    private void show(ContextMenu menu, double screenX, double screenY, ContextMenuEvent e) {
        if (openMenu != null) {
            openMenu.hide();
        }
        openMenu = menu;
        menu.show(canvas, screenX, screenY);
        if (e != null) {
            e.consume();
        }
    }

    /** Shows a tooltip with this text and graphic, or hides it if both are {@code null}. */
    private void showTooltip(String text, Node node) {
        boolean show = text != null || node != null;
        if (show != tooltipShown) {
            if (show) {
                Tooltip.install(canvas, tooltip);
            } else {
                Tooltip.uninstall(canvas, tooltip);
                graphicRow = -1; // the next time the mouse comes, the graphic is made again
            }
            tooltipShown = show;
        }
        if (!Objects.equals(text, tooltip.getText())) {
            tooltip.setText(text);
        }
        if (node != tooltip.getGraphic()) {
            tooltip.setGraphic(node);
        }
    }

    /** A short message below the focused cell, or the grid's top left corner, that goes away by itself. */
    private void showMessage(String text) {
        GridSelectionModel selection = grid.getSelectionModel();
        Point2D at = selection.getFocusedRow() >= 0 && selection.getFocusedColumn() >= 0
                ? pointBelowCell(selection.getFocusedRow(), selection.getFocusedColumn())
                : grid.localToScreen(0, 0);
        if (at == null) {
            return;
        }
        message.setText(text);
        message.show(canvas, at.getX(), at.getY());
        messageTimeout.playFromStart();
    }

    /** Double-click or Enter: edit the cell if the grid is editable, otherwise run the cell action. */
    private void activate(int row, int column) {
        if (skin.startEdit(row, column)) {
            return;
        }
        Consumer<GridCell> action = grid.getOnCellAction();
        GridCell cell = grid.cell(row, column);
        if (action != null && cell != null) {
            action.accept(cell);
        }
    }

    // ---- keyboard

    private void keyPressed(KeyEvent e) {
        if (skin.editor().isEditing()) {
            return;
        }
        KeyCode code = e.getCode();
        if (code == KeyCode.ESCAPE && drag == Drag.MOVE) {
            autoScroll.stop();
            endColumnDrag();
            drag = Drag.NONE;
            e.consume();
            return;
        }
        GridSelectionModel selection = grid.getSelectionModel();
        int rows = grid.getViewRowCount();
        int columns = grid.getViewColumnCount();
        if (rows == 0 || columns == 0) {
            if (isNavigation(code)) {
                e.consume();
            }
            return;
        }
        if (shortcutKey(e)) {
            e.consume();
            return;
        }
        if (selection.getFocusedRow() < 0 && isNavigation(code)) {
            // The first key focuses the top left cell on screen, so the grid stays where it was scrolled to.
            GridGeometry g = skin.geometry();
            moveTo(g.frozenRows() > 0 ? 0 : g.firstScrolledRow(),
                    g.frozenColumns() > 0 ? 0 : Math.max(0, g.firstScrolledColumn()), false);
            e.consume();
            return;
        }
        int row = Math.clamp(selection.getFocusedRow(), 0, rows - 1);
        int column = Math.clamp(selection.getFocusedColumn(), 0, columns - 1);
        boolean shortcut = e.isShortcutDown();
        boolean shift = e.isShiftDown();
        GridGeometry g = skin.geometry();

        if (code == KeyCode.DOWN && e.isAltDown()) {
            grid.scrollTo(row, column);
            grid.layout();
            Bounds cell = grid.getCellBounds(row, column);
            Point2D underHeader = grid.localToScreen(cell.getMinX(), grid.columnHeaderHeight() * grid.effectiveZoom());
            if (underHeader != null) {
                showColumnMenu(column, underHeader.getX(), underHeader.getY(), null);
            }
            e.consume();
            return;
        }
        switch (code) {
            case UP -> moveTo(shortcut ? 0 : row - 1, column, shift);
            case DOWN -> moveTo(shortcut ? rows - 1 : row + 1, column, shift);
            case LEFT -> moveTo(row, shortcut ? 0 : column - 1, shift);
            case RIGHT -> moveTo(row, shortcut ? columns - 1 : column + 1, shift);
            case PAGE_UP -> moveTo(row < g.rowCount() ? g.pageUp(row) : row - 1, column, shift);
            case PAGE_DOWN -> moveTo(row < g.rowCount() ? g.pageDown(row) : row + 1, column, shift);
            case HOME -> moveTo(shortcut ? 0 : row, 0, shift);
            case END -> moveTo(shortcut ? rows - 1 : row, columns - 1, shift);
            case ENTER -> {
                if (selection.getFocusedRow() < 0 || grid.getOnCellAction() == null && !grid.isEditable()) {
                    return;
                }
                activate(row, column);
            }
            case F2 -> {
                if (!skin.startEdit(row, column)) {
                    return;
                }
            }
            case SPACE -> {
                if (shortcut) {
                    selection.selectColumn(column, false);
                } else if (shift) {
                    selection.selectRow(row, false);
                } else if (!(grid.isEditable() && grid.cell(row, column).value() instanceof Boolean
                        && skin.startEdit(row, column))) {
                    return;
                }
            }
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Select all, copy and zoom with the shortcut key down; false for any other key. */
    private boolean shortcutKey(KeyEvent e) {
        if (!e.isShortcutDown()) {
            return false;
        }
        switch (e.getCode()) {
            case A -> grid.getSelectionModel().selectAll();
            case C -> copy();
            case PLUS, ADD, EQUALS -> zoomBy(ZOOM_STEP, 0, 0);
            case MINUS, SUBTRACT -> zoomBy(1 / ZOOM_STEP, 0, 0);
            case DIGIT0, NUMPAD0 -> zoomBy(1 / grid.effectiveZoom(), 0, 0);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean isNavigation(KeyCode code) {
        return switch (code) {
            case UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN, HOME, END -> true;
            default -> false;
        };
    }

    private void copy() {
        var ranges = grid.getSelectionModel().getRanges();
        if (ranges.isEmpty() || grid.copySelection()) {
            return;
        }
        long cells = Grid.cellCount(ranges);
        showMessage(cells > Grid.MAX_COPY_CELLS ? Texts.get("copy.tooMany", cells, Grid.MAX_COPY_CELLS)
                : Texts.get("copy.notAligned"));
    }

    /** Typing a character on the focused cell of an editable grid edits it, starting with that character. */
    private void keyTyped(KeyEvent e) {
        String typed = e.getCharacter();
        GridSelectionModel selection = grid.getSelectionModel();
        if (!grid.isEditable() || skin.editor().isEditing() || e.isShortcutDown() || e.isAltDown()
                || typed.isBlank() || Character.isISOControl(typed.charAt(0)) || selection.getFocusedRow() < 0) {
            return;
        }
        if (skin.editor().startTyping(selection.getFocusedRow(), selection.getFocusedColumn(), typed)) {
            e.consume();
        }
    }

    private void moveTo(int row, int column, boolean extend) {
        row = Math.clamp(row, 0, grid.getViewRowCount() - 1);
        column = Math.clamp(column, 0, grid.getViewColumnCount() - 1);
        if (extend) {
            grid.getSelectionModel().extend(row, column);
        } else {
            grid.getSelectionModel().select(row, column);
        }
        grid.scrollTo(row, column);
    }

    // ---- scrolling and zoom

    private void scrolled(ScrollEvent e) {
        if (e.isShortcutDown()) {
            if (e.getDeltaY() != 0) {
                zoomBy(Math.pow(ZOOM_STEP, e.getDeltaY() / WHEEL_NOTCH),
                        e.getX() - canvas.getLayoutX(), e.getY() - canvas.getLayoutY());
            }
        } else {
            double dx = e.getDeltaX();
            double dy = e.getDeltaY();
            if (e.isShiftDown() && dx == 0) {
                dx = dy;
                dy = 0;
            }
            grid.setScrollY(grid.getScrollY() - dy / grid.effectiveZoom());
            grid.setScrollX(grid.getScrollX() - dx / grid.effectiveZoom());
        }
        e.consume();
    }

    private void zoomed(ZoomEvent e) {
        zoomBy(e.getZoomFactor(), e.getX() - canvas.getLayoutX(), e.getY() - canvas.getLayoutY());
        e.consume();
    }

    /** Changes the zoom so that the content under the given canvas position stays in place. */
    private void zoomBy(double factor, double anchorX, double anchorY) {
        double old = grid.effectiveZoom();
        double zoom = Math.clamp(old * factor, Grid.MIN_ZOOM, Grid.MAX_ZOOM);
        if (zoom == old || !Double.isFinite(zoom)) {
            return;
        }
        double contentX = grid.getScrollX() + anchorX / old;
        double contentY = grid.getScrollY() + anchorY / old;
        grid.setZoom(zoom);
        grid.setScrollX(contentX - anchorX / zoom);
        grid.setScrollY(contentY - anchorY / zoom);
    }
}
