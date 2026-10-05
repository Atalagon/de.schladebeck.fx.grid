package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import javafx.scene.Node;
import javafx.scene.paint.Color;

public final class RecordsExample extends Example {

    public RecordsExample() {
        super("Records", "ListGridModel.ofRecords creates one column per record component and names it after the "
                + "component. The column types come from the record, so numbers are right aligned and booleans are "
                + "check boxes. Two built-in renderers show departments as labels and salaries with a bar.");
    }

    @Override
    public Node create() {
        Grid grid = new Grid(ListGridModel.ofRecords(Person.class, SampleData.people(100_000)));
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Department").setRenderer(CellRenderers.badge( // a light color per department
                (String department) -> Color.hsb(Math.floorMod(department.hashCode(), 360), 0.25, 1)));
        grid.getColumn("Salary").setRenderer(CellRenderers.dataBar(0, 150_000, Color.web("#14b8a6")));
        return grid;
    }
}
