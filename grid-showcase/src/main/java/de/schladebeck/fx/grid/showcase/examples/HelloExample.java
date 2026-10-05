package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import javafx.scene.Node;

public final class HelloExample extends Example {

    public HelloExample() {
        super("Hello grid", "A list of objects and one line per column. Click a header to sort, drag a header to move "
                + "the column, drag its border to resize it.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = new ListGridModel<Person>()
                .column("Name", Person::name)
                .column("City", Person::city)
                .column("Age", Person::age)
                .column("Salary", Person::salary);
        people.setAll(SampleData.people(10_000));
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        return grid;
    }
}
