package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.SelectionMode;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.Arrays;
import java.util.Locale;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

public final class SelectionExample extends Example {

    public SelectionExample() {
        super("Selection", "Pick a selection mode and select something: cells, whole rows or columns, Shift extends, "
                + "Ctrl adds. The panel reads the selection with getSelectedModelRows() for the selected people and "
                + "forEachSelectedCell() for the values. Ctrl+C copies the selection as tab-separated text.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = ListGridModel.ofRecords(Person.class, SampleData.people(100_000));
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.setRowHeaderVisible(true);

        ChoiceBox<SelectionMode> mode = new ChoiceBox<>(FXCollections.observableArrayList(SelectionMode.values()));
        mode.valueProperty().bindBidirectional(grid.getSelectionModel().modeProperty());
        mode.setConverter(new StringConverter<>() {
            @Override
            public String toString(SelectionMode value) {
                return value == null ? "" : value.name().replace('_', ' ').toLowerCase(Locale.ROOT);
            }

            @Override
            public SelectionMode fromString(String text) {
                return null; // the choices are fixed, nothing is typed
            }
        });

        Label summary = new Label("Nothing selected");
        ListView<String> names = new ListView<>();
        grid.getSelectionModel().addListener(o -> {
            int[] rows = grid.getSelectedModelRows();
            long[] cells = {0};
            double[] salaries = {0};
            grid.forEachSelectedCell((row, column, value) -> {
                cells[0]++;
                if (value instanceof Double salary) {
                    salaries[0] += salary;
                }
            });
            summary.setText(String.format("%,d cells in %,d rows%nSelected salaries: %,.2f",
                    cells[0], rows.length, salaries[0]));
            int[] firstRows = Arrays.copyOf(rows, Math.min(rows.length, 1_000));
            names.getItems().setAll(people.getItems(firstRows).stream().map(Person::name).toList());
        });

        VBox panel = new VBox(8, summary, names);
        panel.setPadding(new Insets(8));
        panel.setPrefWidth(260);
        return new BorderPane(grid, new ToolBar(new Label("Selection mode"), mode), panel, null, null);
    }
}
