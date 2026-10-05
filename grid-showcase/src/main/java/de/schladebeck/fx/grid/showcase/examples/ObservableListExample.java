package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.util.Comparator;
import java.util.Random;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.Observable;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

public final class ObservableListExample extends Example {

    /** A person whose name and salary are JavaFX properties, as in many JavaFX applications. */
    public static final class Employee {
        final StringProperty name;
        final String city;
        final DoubleProperty salary;

        Employee(String name, String city, double salary) {
            this.name = new SimpleStringProperty(name);
            this.city = city;
            this.salary = new SimpleDoubleProperty(salary);
        }

        @Override
        public String toString() {
            return name.get() + ", " + Math.round(salary.get());
        }
    }

    private Timeline raises;

    public ObservableListExample() {
        super("Observable list", "ListGridModel.of(list) shows a JavaFX ObservableList and follows it: adding, "
                + "removing and sorting the list change the grid, and the list's extractor reports salaries that "
                + "change, so their rows are painted again. Nothing here calls the grid. Every half second someone "
                + "gets a raise; the ListView on the right shows the same list.");
    }

    @Override
    public Node create() {
        // The extractor makes the list report a changed salary as a change of its item.
        ObservableList<Employee> people = FXCollections.observableArrayList(e -> new Observable[] {e.salary});
        Random random = new Random(5);
        SampleData.people(200).forEach(p -> people.add(new Employee(p.name(), p.city(), p.salary())));

        ListGridModel<Employee> model = ListGridModel.of(people)
                .column("Name", (Employee e) -> e.name.get())
                .column("City", (Employee e) -> e.city)
                .column("Salary", (Employee e) -> e.salary.get());
        Grid grid = new Grid(model);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Salary").setRenderer(CellRenderers.number("#,##0.00"));

        Button add = new Button("Add");
        add.setOnAction(e -> people.add(0, new Employee(SampleData.name(random), "Berlin", 40_000)));
        Button remove = new Button("Remove selected");
        remove.setOnAction(e -> people.removeAll(model.getItems(grid.getSelectedModelRows())));
        Button sort = new Button("Sort the list by name");
        sort.setOnAction(e -> FXCollections.sort(people, Comparator.comparing((Employee p) -> p.name.get())));
        raises = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            DoubleProperty salary = people.get(random.nextInt(people.size())).salary;
            salary.set(salary.get() + 100 * (1 + random.nextInt(10)));
        }));
        raises.setCycleCount(Animation.INDEFINITE);
        raises.play();

        ListView<Employee> list = new ListView<>(people);
        list.setPrefWidth(260);
        return new BorderPane(grid, new ToolBar(add, remove, sort), list, null, null);
    }

    @Override
    public void stop() {
        raises.stop();
    }
}
