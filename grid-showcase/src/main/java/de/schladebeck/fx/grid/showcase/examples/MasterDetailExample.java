package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.SelectionMode;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;

public final class MasterDetailExample extends Example {

    public record Department(String name, int people, double averageSalary) {
    }

    public MasterDetailExample() {
        super("Master and detail", "Two grids. Select departments on the left, Ctrl and Shift select several: the grid "
                + "on the right filters its people with a row filter. Both grids read the same list of people; the "
                + "filter only hides rows, so sorting and the selection of the right grid survive a change on the "
                + "left.");
    }

    @Override
    public Node create() {
        List<Person> all = SampleData.people(50_000);
        ListGridModel<Person> people = ListGridModel.ofRecords(Person.class, all);
        Map<String, List<Person>> byDepartment = all.stream()
                .collect(Collectors.groupingBy(Person::department, TreeMap::new, Collectors.toList()));
        ListGridModel<Department> departments = ListGridModel.ofRecords(Department.class, byDepartment.entrySet()
                .stream()
                .map(e -> new Department(e.getKey(), e.getValue().size(),
                        e.getValue().stream().mapToDouble(Person::salary).average().orElse(0)))
                .toList());

        Grid master = new Grid(departments);
        master.getStyleClass().add("modern"); // styles.css
        master.getSelectionModel().setMode(SelectionMode.ROW);
        master.getColumn("Average salary").setRenderer(CellRenderers.number("#,##0.00"));
        Grid detail = new Grid(people);
        detail.getStyleClass().add("modern");
        detail.getColumn("Salary").setRenderer(CellRenderers.number("#,##0.00"));

        master.getSelectionModel().addListener(o -> {
            Set<String> selected = departments.getItems(master.getSelectedModelRows()).stream()
                    .map(Department::name)
                    .collect(Collectors.toSet());
            detail.setRowFilter(selected.isEmpty() ? null : row -> selected.contains(people.getItem(row).department()));
        });

        SplitPane split = new SplitPane(master, detail);
        split.setDividerPositions(0.33);
        split.getStyleClass().add("cards");
        return split;
    }
}
