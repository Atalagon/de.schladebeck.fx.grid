package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ListGridModel;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import javafx.beans.Observable;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A grid showing an {@code ObservableList} through {@link ListGridModel#of} follows the list without further calls. */
class ObservableListFxTest {

    /** An item with a JavaFX property, which the list reports through its extractor. */
    static final class Person {
        final StringProperty name;
        final int age;

        Person(String name, int age) {
            this.name = new SimpleStringProperty(name);
            this.age = age;
        }

        String getName() {
            return name.get();
        }

        int getAge() {
            return age;
        }
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    /** People {@code p0} to {@code p<count - 1>}, aged by their number, in a list that reports new names. */
    private static ObservableList<Person> people(int count) {
        ObservableList<Person> people = FXCollections.observableArrayList(p -> new Observable[] {p.name});
        for (int i = 0; i < count; i++) {
            people.add(new Person("p" + i, i));
        }
        return people;
    }

    private static Grid show(ObservableList<Person> people) {
        return Fx.show(new Grid(ListGridModel.of(people)
                .column("Name", Person::getName)
                .column("Age", Person::getAge)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> selectedNames(Grid grid) {
        ListGridModel<Person> model = (ListGridModel<Person>) grid.getModel();
        return model.getItems(grid.getSelectedModelRows()).stream().map(Person::getName).toList();
    }

    private static List<Object> names(Grid grid) {
        return IntStream.range(0, grid.getViewRowCount()).mapToObj(row -> grid.getViewValueAt(row, 0)).toList();
    }

    @Test
    void aChangeInsideAnItemRepaintsItsRow() throws Exception {
        Fx.run(() -> {
            ObservableList<Person> people = people(20);
            Grid grid = show(people);
            List<Object> painted = new ArrayList<>();
            grid.getColumn(0).setRenderer((gc, cell) -> painted.add(cell.getValue()));
            layout(grid);
            painted.clear();

            people.get(3).name.set("Zoe");
            layout(grid);

            assertTrue(painted.contains("Zoe"), "painted again: " + painted);
            assertEquals("Zoe", grid.getViewValueAt(3, 0));
        });
    }

    @Test
    void sortingTheListShowsItsNewOrderUnlessTheGridSortsItself() throws Exception {
        Fx.run(() -> {
            ObservableList<Person> people = people(5);
            Grid grid = show(people);

            FXCollections.sort(people, Comparator.comparing(Person::getAge).reversed());
            layout(grid);
            assertEquals(List.of("p4", "p3", "p2", "p1", "p0"), names(grid));

            grid.sort(1, false);
            FXCollections.sort(people, Comparator.comparing(Person::getAge));
            layout(grid);
            assertEquals(List.of("p4", "p3", "p2", "p1", "p0"), names(grid), "sorted by the grid's own column");
        });
    }

    @Test
    void addedAndRemovedRangesMoveTheSelectionWithItsItem() throws Exception {
        Fx.run(() -> {
            ObservableList<Person> people = people(30);
            Grid grid = show(people);
            grid.getSelectionModel().select(10, 0);

            people.addAll(2, List.of(new Person("x", 50), new Person("y", 51), new Person("z", 52)));
            assertEquals(13, grid.getSelectionModel().getFocusedRow());
            assertEquals(List.of("p10"), selectedNames(grid));

            people.remove(0, 5);
            assertEquals(8, grid.getSelectionModel().getFocusedRow());
            assertEquals(List.of("p10"), selectedNames(grid));

            people.subList(8, 10).clear();
            assertEquals(List.of(), selectedNames(grid), "the selected item is gone");
            layout(grid);
            assertEquals(26, grid.getViewRowCount());
            assertEquals("p12", grid.getViewValueAt(8, 0));
        });
    }

    /// `removeAll` of items far apart reports one change in several parts. A sorted grid follows each part, so
    /// the selection stays on its item.
    @Test
    void aSortedGridKeepsTheSelectionOnItsItemThroughAChangeInSeveralParts() throws Exception {
        Fx.run(() -> {
            ObservableList<Person> people = people(30);
            Grid grid = show(people);
            grid.sort(1, false);
            grid.getSelectionModel().select(19, 0);
            assertEquals(List.of("p10"), selectedNames(grid));

            people.removeAll(people.get(25), people.get(3), people.get(12));
            people.addAll(0, List.of(new Person("old", 99), new Person("young", -1)));

            assertEquals(List.of("p10"), selectedNames(grid));
            assertEquals(18, grid.getSelectionModel().getFocusedRow());
            layout(grid);
            assertEquals(List.of("old", "p29", "p28"), names(grid).subList(0, 3));
            assertEquals("young", names(grid).getLast());
        });
    }

    @Test
    void aListThatLivesOnDoesNotKeepADroppedGridAlive() throws Exception {
        ObservableList<Person> people = Fx.call(() -> people(5));
        WeakReference<Grid> grid = Fx.call(() -> new WeakReference<>(show(people)));

        for (int i = 0; i < 100 && grid.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(grid.get());
        Fx.run(() -> people.add(new Person("late", 5)));
    }
}
