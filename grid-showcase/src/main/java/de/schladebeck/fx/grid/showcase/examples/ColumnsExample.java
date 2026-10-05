package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.GridLayout;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.List;
import javafx.beans.property.IntegerProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;

public final class ColumnsExample extends Example {

    public ColumnsExample() {
        super("Columns and layout", "Columns fit their content when the data arrives. Drag a header to move its "
                + "column, drag its border to resize, double-click the border to fit. ID stays between 40 and 80 "
                + "pixels, Notes between 120 and 400. Right-click a header to hide and show columns. Save layout keeps "
                + "order, widths, hidden columns, frozen columns and rows, sorting and zoom in one string; Restore "
                + "layout brings it back. Hover over text ending in … to read all of it. Ctrl+mouse wheel zooms. The "
                + "column groups above the headers come from one function per header row.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = new ListGridModel<Person>()
                .column("ID", Person::id)
                .column("Name", Person::name)
                .column("Age", Person::age)
                .column("City", Person::city)
                .column("Notes", SampleData::longNotes)
                .column("Department", Person::department)
                .column("Salary", Person::salary)
                .column("Active", Person::active);
        people.setAll(SampleData.people(100_000));
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.setFrozenColumnCount(1);
        grid.getColumn("ID").setMinWidth(40).setMaxWidth(80);
        grid.getColumn("Notes").setMinWidth(120).setMaxWidth(400);
        grid.setColumnGroups(List.of(
                column -> column == 0 ? null : column < 5 ? "Person" : "Employment",
                column -> column < 6 ? null : "Contract"));

        String[] saved = {""};
        Label savedText = new Label();
        Button save = new Button("Save layout");
        save.setOnAction(e -> {
            saved[0] = grid.getLayout().encode();
            savedText.setText(saved[0]);
            savedText.setTooltip(new Tooltip(saved[0]));
        });
        Button restore = new Button("Restore layout");
        restore.setOnAction(e -> grid.setLayout(GridLayout.decode(saved[0])));

        CheckBox rowHeader = new CheckBox("Row header");
        rowHeader.selectedProperty().bindBidirectional(grid.rowHeaderVisibleProperty());
        Slider zoom = new Slider(Grid.MIN_ZOOM, Grid.MAX_ZOOM, 1);
        zoom.valueProperty().bindBidirectional(grid.zoomProperty());

        ToolBar tools = new ToolBar(save, restore, new Separator(), new Label("Frozen columns"),
                spinner(grid.frozenColumnCountProperty()), new Label("rows"), spinner(grid.frozenRowCountProperty()),
                rowHeader, new Separator(), new Label("Zoom"), zoom, new Separator(), savedText);
        return new BorderPane(grid, tools, null, null, null);
    }

    /** A spinner for a count the grid can also change itself, as Restore layout does. */
    private static Spinner<Integer> spinner(IntegerProperty count) {
        Spinner<Integer> spinner = new Spinner<>(0, 4, count.get());
        spinner.setPrefWidth(70);
        spinner.valueProperty().addListener((o, old, value) -> count.set(value));
        count.addListener((o, old, value) -> spinner.getValueFactory().setValue(value.intValue()));
        return spinner;
    }
}
