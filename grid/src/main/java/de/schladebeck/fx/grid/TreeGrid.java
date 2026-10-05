package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.TreeGridModel;
import de.schladebeck.fx.grid.render.CellContext;
import de.schladebeck.fx.grid.render.CellRenderer;
import java.util.IdentityHashMap;
import java.util.Map;
import javafx.geometry.Bounds;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/// A [Grid] for a [TreeGridModel]: the first model column shows each item indented by its depth, with a triangle
/// that expands and collapses it. Click the triangle to expand or collapse; a double click does the same unless
/// the grid has a cell action or is editable. On the keyboard, Right and + expand, Left and - collapse, Left on a
/// collapsed item goes to its parent, and Right on an expanded one in row selection goes to its first child.
/// Expanding widens the tree column for wider children unless it has an explicit width. Sorting sorts children
/// among their siblings. The extra style class is `tree-grid`.
///
/// The first column keeps its renderer and gets the indentation and the triangle in front of it. Another model
/// than a [TreeGridModel] is shown like in any grid. A row filter tests each row on its own: a child that passes
/// stays visible when its parent does not.
public final class TreeGrid extends Grid {

    /** The tree column's renderers with indentation and triangle, one per renderer they wrap. */
    private final Map<CellRenderer, CellRenderer> treeRenderers = new IdentityHashMap<>();
    private final double[] triangleX = new double[3];
    private final double[] triangleY = new double[3];

    /** A tree grid without data, for example for FXML; set a {@link TreeGridModel} with {@link #setModel}. */
    public TreeGrid() {
        getStyleClass().add("tree-grid");
        addEventFilter(MouseEvent.MOUSE_PRESSED, this::mousePressed);
        addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
    }

    /// A grid showing a tree.
    ///
    /// @param model the tree
    public TreeGrid(TreeGridModel<?> model) {
        this();
        setModel(model);
    }

    private TreeGridModel<?> tree() {
        return getModel() instanceof TreeGridModel<?> tree ? tree : null;
    }

    @Override
    CellRenderer decorate(int modelColumn, CellRenderer renderer) {
        return modelColumn == 0 && tree() != null ? treeRenderers.computeIfAbsent(renderer, this::treeCell) : renderer;
    }

    /** Indentation per level, also the room for the triangle; it grows with the grid's font. */
    private double step() {
        return Math.round(getFont().getSize() * 1.4);
    }

    /** Where a row's triangle starts, from the left of its cell, before zooming. */
    private double triangleOffset(int depth) {
        return getCellPadding() + depth * step();
    }

    /** Indentation and triangle, then the column's own renderer in the rest of the cell. */
    private CellRenderer treeCell(CellRenderer content) {
        return (gc, cell) -> {
            TreeGridModel<?> tree = tree();
            int row = cell.getModelRow();
            int depth = row < 0 ? 0 : tree.getDepth(row); // row -1: a prototype value, measured for fitting
            if (row >= 0 && !tree.isLeaf(row)) {
                paintTriangle(gc, cell, cell.getX() + triangleOffset(depth), tree.isExpanded(row));
            }
            cell.indent((depth + 1) * step());
            content.paint(gc, cell);
        };
    }

    /** A triangle pointing right when collapsed and down when expanded, in the text color. */
    private void paintTriangle(GraphicsContext gc, CellContext cell, double left, boolean expanded) {
        double h = getFont().getSize() * 0.3;
        double cx = cell.snap(left + step() * 0.4);
        double cy = cell.snap(cell.getY() + cell.getHeight() / 2);
        if (expanded) {
            corners(cx - h, cy - h * 0.6, cx + h, cy - h * 0.6, cx, cy + h * 0.8);
        } else {
            corners(cx - h * 0.6, cy - h, cx + h * 0.8, cy, cx - h * 0.6, cy + h);
        }
        gc.setGlobalAlpha(0.75);
        gc.fillPolygon(triangleX, triangleY, 3);
        gc.setGlobalAlpha(1);
    }

    private void corners(double x0, double y0, double x1, double y1, double x2, double y2) {
        triangleX[0] = x0;
        triangleY[0] = y0;
        triangleX[1] = x1;
        triangleY[1] = y1;
        triangleX[2] = x2;
        triangleY[2] = y2;
    }

    /** A click on a triangle, or a double click in the tree column of a plain grid, expands or collapses. */
    private void mousePressed(MouseEvent e) {
        TreeGridModel<?> tree = tree();
        GridCell cell = tree == null || e.getButton() != MouseButton.PRIMARY ? null : cellAt(e.getX(), e.getY());
        if (cell == null || cell.modelColumn() != 0 || tree.isLeaf(cell.modelRow())) {
            return;
        }
        double zoom = effectiveZoom();
        Bounds bounds = getCellBounds(cell.viewRow(), cell.viewColumn());
        double triangle = bounds.getMinX() + triangleOffset(tree.getDepth(cell.modelRow())) * zoom;
        boolean onTriangle = e.getX() >= triangle && e.getX() < triangle + step() * zoom;
        boolean toggles = e.getClickCount() == 2 && getOnCellAction() == null && !isEditable();
        if (onTriangle || toggles) {
            requestFocus();
            setExpanded(tree, cell.modelRow(), !tree.isExpanded(cell.modelRow()));
            e.consume();
        }
    }

    /// Expands or collapses an item. Children wider than the tree column widen it ([Grid#widenToFit]).
    private void setExpanded(TreeGridModel<?> tree, int row, boolean expanded) {
        int rowsBefore = getViewRowCount();
        tree.setExpanded(row, expanded);
        int viewRow = modelToViewRow(row);
        if (expanded && viewRow >= 0) {
            widenToFit(0, viewRow + 1, getViewRowCount() - rowsBefore);
        }
    }

    /// The tree keys of the focused row, in the tree column or anywhere in row selection. Keys with nothing to
    /// expand or collapse move as in any grid.
    private void keyPressed(KeyEvent e) {
        TreeGridModel<?> tree = tree();
        GridSelectionModel selection = getSelectionModel();
        int viewRow = selection.getFocusedRow();
        boolean rowMode = selection.getMode() == SelectionMode.ROW;
        boolean treeColumn = rowMode || selection.getFocusedColumn() == modelToViewColumn(0);
        if (tree == null || e.getTarget() != this || viewRow < 0 || !treeColumn || e.isShiftDown()
                || e.isShortcutDown() || e.isAltDown()) {
            return;
        }
        int row = viewToModelRow(viewRow);
        boolean expandable = !tree.isLeaf(row) && !tree.isExpanded(row);
        switch (e.getCode()) {
            case RIGHT, ADD, PLUS -> {
                if (expandable) {
                    setExpanded(tree, row, true);
                } else if (e.getCode() == KeyCode.RIGHT && rowMode && tree.isExpanded(row)) {
                    selectModelRow(row + 1);
                } else {
                    return;
                }
            }
            case LEFT, SUBTRACT, MINUS -> {
                if (tree.isExpanded(row)) {
                    setExpanded(tree, row, false);
                } else if (e.getCode() == KeyCode.LEFT && tree.getParentRow(row) >= 0) {
                    selectModelRow(tree.getParentRow(row));
                } else {
                    return;
                }
            }
            default -> {
                return;
            }
        }
        e.consume();
    }
}
