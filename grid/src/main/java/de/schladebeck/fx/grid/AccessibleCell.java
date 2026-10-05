package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.TreeGridModel;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.Region;

/// Stands in for one cell or one column header towards screen readers and UI test tools, which only see nodes.
/// It paints nothing, ignores the mouse, and sits over its cell so it reports the right bounds. The skin creates
/// these on demand, only while assistive technology asks, and keeps a few of them. A cell reports the text it
/// shows, as its renderer draws it; a header reports the column name.
final class AccessibleCell extends Region {

    private final Grid grid;
    private final GridSkin skin;
    private final int row;
    private final int column;

    /** A cell, or the header of {@code column} for row -1. */
    AccessibleCell(Grid grid, GridSkin skin, int row, int column) {
        this.grid = grid;
        this.skin = skin;
        this.row = row;
        this.column = column;
        setAccessibleRole(row < 0 ? AccessibleRole.TABLE_COLUMN : AccessibleRole.TABLE_CELL);
        setManaged(false);
        setMouseTransparent(true);
        setFocusTraversable(false);
    }

    int row() {
        return row;
    }

    int column() {
        return column;
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        return switch (attribute) {
            case ROW_INDEX -> row;
            case COLUMN_INDEX, INDEX -> column;
            case TEXT -> text();
            case SELECTED -> row >= 0 && grid.getSelectionModel().isSelected(row, column);
            case FOCUSED -> row >= 0 && grid.getSelectionModel().getFocusedRow() == row
                    && grid.getSelectionModel().getFocusedColumn() == column;
            case HELP -> rowName();
            case DISCLOSURE_LEVEL -> tree() == null ? null : tree().getDepth(modelRow());
            case EXPANDED -> tree() != null && tree().isExpanded(modelRow());
            case LEAF -> tree() == null || tree().isLeaf(modelRow());
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }

    /** The tree, if this is a cell of a tree's first column. */
    private TreeGridModel<?> tree() {
        boolean treeColumn = row >= 0 && row < grid.getViewRowCount() && column < grid.getViewColumnCount()
                && grid.viewToModelColumn(column) == 0;
        return treeColumn && grid.getModel() instanceof TreeGridModel<?> tree ? tree : null;
    }

    private int modelRow() {
        return grid.viewToModelRow(row);
    }

    /** The model's name of the cell's row, for a cell that is read without its row header. */
    private String rowName() {
        return row < 0 || row >= grid.getViewRowCount() || grid.getModel() == null ? null
                : grid.getModel().getRowName(modelRow());
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.REQUEST_FOCUS && row >= 0) {
            grid.getSelectionModel().select(row, column);
            grid.scrollTo(row, column);
            grid.requestFocus();
        } else if ((action == AccessibleAction.EXPAND || action == AccessibleAction.COLLAPSE) && tree() != null) {
            tree().setExpanded(modelRow(), action == AccessibleAction.EXPAND);
        } else {
            super.executeAccessibleAction(action, parameters);
        }
    }

    /** What the cell shows, or the column name for a header; empty once the cell no longer exists. */
    private String text() {
        if (row >= grid.getViewRowCount() || column >= grid.getViewColumnCount() || grid.getModel() == null) {
            return "";
        }
        return row < 0 ? grid.getModel().getColumnName(grid.viewToModelColumn(column))
                : skin.displayedText(row, column);
    }
}
