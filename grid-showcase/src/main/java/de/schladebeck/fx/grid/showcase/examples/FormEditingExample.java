package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.SelectionMode;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.text.NumberFormat;
import java.text.ParseException;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;

public final class FormEditingExample extends Example {

    public FormEditingExample() {
        super("Form editing", "The recommended way to edit: the grid stays read-only, a form edits the selected "
                + "person, and Save replaces the item in the model. The grid repaints that row by itself. Double-click "
                + "a row or press Enter to jump into the form.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = new ListGridModel<Person>()
                .column("Name", Person::name)
                .column("City", Person::city)
                .column("Department", Person::department)
                .column("Salary", Person::salary);
        people.setAll(SampleData.people(1_000));
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getSelectionModel().setMode(SelectionMode.ROW);

        TextField name = new TextField();
        TextField city = new TextField();
        TextField salary = new TextField();
        NumberFormat number = NumberFormat.getNumberInstance(); // the grid's format, so both show the same text
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setDisable(true);

        grid.focusedModelRowProperty().addListener((o, old, row) -> {
            Person person = row.intValue() < 0 ? null : people.getItem(row.intValue());
            name.setText(person == null ? "" : person.name());
            city.setText(person == null ? "" : person.city());
            salary.setText(person == null ? "" : number.format(person.salary()));
            save.setDisable(person == null);
        });
        grid.setOnCellAction(cell -> name.requestFocus());

        save.setOnAction(e -> {
            try {
                int row = grid.getFocusedModelRow();
                Person person = people.getItem(row);
                people.set(row, person.withName(name.getText()).withCity(city.getText())
                        .withSalary(number.parse(salary.getText()).doubleValue()));
                grid.requestFocus();
            } catch (ParseException invalid) {
                salary.requestFocus();
            }
        });

        GridPane form = new GridPane(8, 8);
        form.setPadding(new Insets(12));
        form.addRow(0, new Label("Name"), name);
        form.addRow(1, new Label("City"), city);
        form.addRow(2, new Label("Salary"), salary);
        form.add(save, 1, 3);
        return new BorderPane(grid, null, form, null, null);
    }
}
