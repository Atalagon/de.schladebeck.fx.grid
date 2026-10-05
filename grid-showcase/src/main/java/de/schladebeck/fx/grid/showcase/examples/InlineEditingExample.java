package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import javafx.scene.Node;

public final class InlineEditingExample extends Example {

    public InlineEditingExample() {
        super("Inline editing", "For the rare cases where editing inside the table fits better. Columns get an update "
                + "function, and grid.setEditable(true) turns editing on. Typing, double-click or F2 edits, Enter "
                + "saves, Tab saves and edits the next cell, Escape cancels. Invalid input stays in the editor, "
                + "explained. A click on a check box or Space toggles it. The ID column has no update function and "
                + "stays read-only.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = new ListGridModel<Person>()
                .column("ID", Person::id)
                .column("Name", Person::name, Person::withName)
                .column("City", Person::city, Person::withCity)
                .column("Salary", Person::salary, Person::withSalary)
                .column("Active", Person::active, Person::withActive);
        people.setAll(SampleData.people(1_000));

        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.setEditable(true);
        return grid;
    }
}
