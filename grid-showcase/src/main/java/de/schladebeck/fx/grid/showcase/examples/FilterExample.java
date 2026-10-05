package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.RowFilters;
import java.util.function.IntPredicate;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;

public final class FilterExample extends Example {

    public FilterExample() {
        super("Filtering and sorting", "grid.setRowFilter() takes a condition on model rows. RowFilters.containsText() "
                + "searches all columns, people.matching() turns a condition on your objects into a row filter. "
                + "Filters and sorting combine, the model stays untouched, and the selection stays on its rows. Click "
                + "a header to sort, again to reverse, once more to unsort. When nothing matches, the grid shows its "
                + "placeholder.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = ListGridModel.ofRecords(Person.class, SampleData.people(100_000));
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.sort(people.findColumn("Salary"), false);
        grid.setPlaceholder(new Label("No people match"));

        TextField search = new TextField();
        search.setPromptText("Search all columns");
        CheckBox activeOnly = new CheckBox("Active only");
        Label count = new Label();
        Runnable applyFilter = () -> {
            IntPredicate filter = RowFilters.containsText(people, search.getText());
            if (activeOnly.isSelected()) {
                IntPredicate active = people.matching(Person::active);
                filter = filter == null ? active : filter.and(active);
            }
            grid.setRowFilter(filter);
            count.setText(String.format("%,d of %,d people", grid.getViewRowCount(), people.getRowCount()));
        };
        search.textProperty().addListener(o -> applyFilter.run());
        activeOnly.selectedProperty().addListener(o -> applyFilter.run());
        applyFilter.run();

        return new BorderPane(grid, new ToolBar(search, activeOnly, new Separator(), count), null, null, null);
    }
}
