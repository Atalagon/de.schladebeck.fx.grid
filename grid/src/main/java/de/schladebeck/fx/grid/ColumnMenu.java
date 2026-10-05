package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.model.SortKey;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

/// The built-in column menu, see [Grid#createColumnMenu]: sort, fit and hide the column, fit the visible columns,
/// and a check box per column to show or hide it. The sort items show how the column is sorted. The check boxes
/// keep the menu open, so several columns can be switched at once, by mouse or with Enter. With more than
/// [#COLUMN_LIMIT] columns a single item shows all hidden columns instead.
final class ColumnMenu {

    /** Up to this many columns the menu lists them all with check boxes. */
    static final int COLUMN_LIMIT = 100;

    private ColumnMenu() {
    }

    static ContextMenu create(Grid grid, GridColumn column) {
        ContextMenu menu = new ContextMenu();
        GridModel model = grid.getModel();
        if (model == null) {
            return menu;
        }
        if (column != null) {
            int modelColumn = column.getModelIndex();
            String name = column.getName();
            if (grid.isSortable()) {
                List<SortKey> keys = grid.getSortKeys();
                boolean sorted = Grid.sortIndex(keys, modelColumn) >= 0;
                ToggleGroup direction = new ToggleGroup();
                menu.getItems().addAll(sortItem(grid, modelColumn, true, direction),
                        sortItem(grid, modelColumn, false, direction));
                if (!sorted && !keys.isEmpty()) {
                    List<SortKey> added = new ArrayList<>(keys);
                    added.add(new SortKey(modelColumn, true));
                    menu.getItems().add(item(Texts.get("column.addToSort"), () -> grid.sort(added)));
                }
                MenuItem remove = item(Texts.get("column.removeSort"),
                        () -> grid.sort(keys.stream().filter(key -> key.column() != modelColumn).toList()));
                remove.setDisable(!sorted);
                menu.getItems().add(remove);
                if (keys.size() > 1) {
                    menu.getItems().add(item(Texts.get("columns.clearSort"), grid::clearSort));
                }
                menu.getItems().add(new SeparatorMenuItem());
            }
            MenuItem hide = item(Texts.get("column.hide", name), () -> column.setVisible(false));
            hide.setDisable(grid.getViewColumnCount() <= 1);
            menu.getItems().addAll(item(Texts.get("column.fit", name), column::fitToContent), hide);
        }
        menu.getItems().add(item(Texts.get("columns.fitVisible"), grid::fitVisibleColumns));
        if (grid.columns().modelCount() > COLUMN_LIMIT) {
            MenuItem showAll = item(Texts.get("columns.showAll"), grid::showAllColumns);
            showAll.setDisable(grid.columns().hidden().length == 0);
            menu.getItems().add(showAll);
            return menu;
        }
        menu.getItems().add(new SeparatorMenuItem());
        for (int c : grid.columns().order()) {
            CheckBox box = new CheckBox(model.getColumnName(c));
            box.setMaxWidth(Double.MAX_VALUE);
            box.setMouseTransparent(true); // the item toggles it, so a click anywhere on the row and Enter work
            box.setSelected(grid.getColumn(c).isVisible());
            box.selectedProperty().addListener((o, was, shown) -> {
                if (!shown && grid.getViewColumnCount() <= 1) {
                    box.setSelected(true);
                } else {
                    grid.getColumn(c).setVisible(shown);
                }
            });
            CustomMenuItem item = new CustomMenuItem(box, false);
            item.setOnAction(e -> box.setSelected(!box.isSelected()));
            menu.getItems().add(item);
        }
        return menu;
    }

    /// "Sort ascending" or "Sort descending", checked while the column is sorted that way. A column among several
    /// sort columns changes its direction there; any other column becomes the only sort column.
    private static MenuItem sortItem(Grid grid, int modelColumn, boolean ascending, ToggleGroup group) {
        List<SortKey> keys = new ArrayList<>(grid.getSortKeys());
        int index = Grid.sortIndex(keys, modelColumn);
        RadioMenuItem item = new RadioMenuItem(Texts.get(ascending ? "column.sortAscending" : "column.sortDescending"));
        item.setToggleGroup(group);
        item.setSelected(index >= 0 && keys.get(index).ascending() == ascending);
        item.setOnAction(e -> {
            SortKey key = new SortKey(modelColumn, ascending);
            if (index >= 0) {
                keys.set(index, key);
            } else {
                keys.clear();
                keys.add(key);
            }
            grid.sort(keys);
        });
        return item;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }
}
