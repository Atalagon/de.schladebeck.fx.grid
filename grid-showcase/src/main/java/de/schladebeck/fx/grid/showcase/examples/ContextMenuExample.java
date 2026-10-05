package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.GridCell;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

public final class ContextMenuExample extends Example {

    public ContextMenuExample() {
        super("Context menus", "Right-click a cell, or press the context menu key. The grid selects the cell unless it "
                + "is already selected and asks the cell context menu factory for a menu, which can depend on the "
                + "cell: here it filters by the clicked value, switches the selected people between active and "
                + "inactive, and deletes them with removeRows. Headers have their own menu to sort, fit, hide and show "
                + "columns; Alt+Down opens it from the keyboard.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = ListGridModel.ofRecords(Person.class, SampleData.people(10_000));
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.setCellContextMenuFactory(cell -> menu(grid, people, cell));
        return grid;
    }

    private static ContextMenu menu(Grid grid, ListGridModel<Person> people, GridCell cell) {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> grid.copySelection());

        String column = people.getColumnName(cell.modelColumn());
        MenuItem filter = new MenuItem("Only " + column + " = " + cell.value());
        filter.setOnAction(e -> grid.setRowFilter(
                row -> Objects.equals(people.getValueAt(row, cell.modelColumn()), cell.value())));
        MenuItem clearFilter = new MenuItem("Show all rows");
        clearFilter.setDisable(grid.getRowFilter() == null);
        clearFilter.setOnAction(e -> grid.setRowFilter(null));

        MenuItem activate = new MenuItem("Mark selected as active");
        activate.setOnAction(e -> setActive(grid, people, true));
        MenuItem deactivate = new MenuItem("Mark selected as inactive");
        deactivate.setOnAction(e -> setActive(grid, people, false));
        MenuItem delete = new MenuItem("Delete selected");
        delete.setOnAction(e -> people.removeRows(grid.getSelectedModelRows()));

        return new ContextMenu(copy, new SeparatorMenuItem(), filter, clearFilter, new SeparatorMenuItem(),
                activate, deactivate, delete);
    }

    private static void setActive(Grid grid, ListGridModel<Person> people, boolean active) {
        for (int row : grid.getSelectedModelRows()) {
            people.set(row, people.getItem(row).withActive(active));
        }
    }
}
