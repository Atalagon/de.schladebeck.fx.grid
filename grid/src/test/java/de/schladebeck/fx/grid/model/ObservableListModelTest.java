package de.schladebeck.fx.grid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.schladebeck.fx.grid.Fx;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.util.Callback;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link ListGridModel#of}: changes of an {@code ObservableList} become events, the model's changes go to the list. */
class ObservableListModelTest {

    /** An item with a JavaFX property, reported by lists made with {@link #EXTRACTOR}. */
    static final class Person {
        final StringProperty name;

        Person(String name) {
            this.name = new SimpleStringProperty(name);
        }

        String getName() {
            return name.get();
        }
    }

    private static final Callback<Person, Observable[]> EXTRACTOR = p -> new Observable[] {p.name};

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static List<Person> people(String... names) {
        return Arrays.stream(names).map(Person::new).toList();
    }

    private static List<String> names(List<Person> people) {
        return people.stream().map(Person::getName).toList();
    }

    /** Records each event as text, e.g. {@code DELETE 1-2}. */
    private static List<String> record(GridModel model) {
        List<String> events = new ArrayList<>();
        model.addGridModelListener(e -> events.add(e.type() == GridModelEvent.Type.DATA ? "DATA"
                : e.type() + " " + e.firstRow() + "-" + e.lastRow()));
        return events;
    }

    @Test
    void eachChangeOfTheListFiresTheMatchingEvent() throws Exception {
        Fx.run(() -> {
            ObservableList<Person> list = FXCollections.observableArrayList(EXTRACTOR);
            list.addAll(people("a", "b", "c"));
            ListGridModel<Person> model = ListGridModel.of(list).column("Name", Person::getName);
            List<String> events = record(model);

            list.add(new Person("d"));
            list.addAll(1, people("x", "y"));
            list.remove(1, 3);
            list.set(0, new Person("A"));
            list.get(1).name.set("B");
            FXCollections.sort(list, Comparator.comparing(Person::getName).reversed());
            assertEquals(List.of("d", "c", "B", "A"), names(model.getItems()));
            list.setAll(people("e", "f"));
            list.clear();
            list.setAll(people("g"));

            assertEquals(List.of("INSERT 3-3", "INSERT 1-2", "DELETE 1-2", "UPDATE 0-0", "UPDATE 1-1", "DATA", "DATA",
                    "DELETE 0-1", "INSERT 0-0"), events);
            assertEquals("g", model.getValueAt(0, 0));
        });
    }

    @Test
    void aChangeInSeveralPartsFiresOneEventPerPartThatMatchesTheModel() throws Exception {
        Fx.run(() -> {
            ObservableList<Integer> list = FXCollections.observableArrayList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
            ListGridModel<Integer> model = ListGridModel.of(list).column("Value", i -> i);
            List<String> events = new ArrayList<>();
            model.addGridModelListener(e -> events.add(e.type() + " " + e.firstRow() + "-" + e.lastRow() + " "
                    + model.getItems()));

            list.removeAll(1, 3, 4, 8);

            assertEquals(List.of("DELETE 1-1 [0, 2, 3, 4, 5, 6, 7, 8, 9]", "DELETE 2-3 [0, 2, 5, 6, 7, 8, 9]",
                    "DELETE 5-5 [0, 2, 5, 6, 7, 9]"), events);
        });
    }

    /// A sorted list puts the items added to or removed from its source where they belong, so one change of the
    /// source becomes a change in several parts. Each event must turn the rows before it into the rows after it.
    @Test
    void aSortedListIsFollowedPartByPart() throws Exception {
        Fx.run(() -> {
            ObservableList<Integer> source = FXCollections.observableArrayList(0, 10, 20, 30, 40);
            ListGridModel<Integer> model = ListGridModel.of(source.sorted()).column("Value", i -> i);
            List<Integer> replayed = new ArrayList<>(model.getItems());
            List<String> events = new ArrayList<>();
            model.addGridModelListener(e -> {
                List<Integer> rows = replayed.subList(e.firstRow(), Math.min(e.lastRow() + 1, replayed.size()));
                switch (e.type()) {
                    case DELETE -> rows.clear();
                    case INSERT -> replayed.addAll(e.firstRow(), model.getItems().subList(e.firstRow(),
                            e.lastRow() + 1));
                    default -> replayed.clear();
                }
                events.add(e.type() + " " + e.firstRow() + "-" + e.lastRow()
                        + (replayed.equals(model.getItems()) ? "" : " does not match " + model.getItems()));
            });

            source.addAll(35, 5, 25);
            source.removeAll(10, 30);

            assertEquals(List.of("INSERT 1-1", "INSERT 4-4", "INSERT 6-6", "DELETE 2-2", "DELETE 4-4"), events);
            assertEquals(List.of(0, 5, 20, 25, 35, 40), model.getItems());
        });
    }

    @Test
    void theModelsOwnChangesGoToTheListWithOneEventEach() throws Exception {
        Fx.run(() -> {
            ObservableList<Person> list = FXCollections.observableArrayList(people("a", "b", "c", "d"));
            ListGridModel<Person> model = ListGridModel.of(list)
                    .column("Name", Person::getName, (person, name) -> new Person(name));
            List<String> events = record(model);

            model.add(new Person("e"));
            model.removeRows(3, 0, 1);
            model.setValueAt("C", 0, 0);
            model.update(1, p -> new Person(p.getName().toUpperCase()));
            assertEquals(List.of("C", "E"), names(list));
            model.setAll(people("x", "y", "z"));
            model.clear();

            assertEquals(List.of("INSERT 4-4", "DELETE 3-3", "DELETE 0-1", "UPDATE 0-0", "UPDATE 1-1", "DATA",
                    "DELETE 0-2"), events);
            assertEquals(List.of(), list);
        });
    }

    @Test
    void theModelsChangesFromAnotherThreadChangeTheListOnTheFxThread() throws Exception {
        ObservableList<Person> list = FXCollections.observableArrayList();
        ListGridModel<Person> model = Fx.call(() -> ListGridModel.of(list).column("Name", Person::getName));
        List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        Fx.run(() -> list.addListener((ListChangeListener<Person>) c -> onFxThread.add(
                Platform.isFxApplicationThread())));

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                model.add(new Person("p" + i));
            }
        });
        writer.start();
        writer.join();

        assertEquals(100, (int) Fx.call(list::size));
        assertEquals("p99", Fx.call(() -> model.getItem(99).getName()));
        assertEquals(100, onFxThread.size());
        assertFalse(onFxThread.contains(false));
    }

    @Test
    void aListThatLivesOnDoesNotKeepTheModelAlive() throws Exception {
        ObservableList<Person> list = FXCollections.observableArrayList(people("a"));
        WeakReference<ListGridModel<Person>> model = new WeakReference<>(ListGridModel.of(list));

        for (int i = 0; i < 100 && model.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(model.get());
        list.add(new Person("b")); // the listener of the collected model goes away quietly
        assertEquals(2, list.size());
    }
}
