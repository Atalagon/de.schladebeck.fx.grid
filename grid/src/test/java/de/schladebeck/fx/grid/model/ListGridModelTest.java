package de.schladebeck.fx.grid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx;
import de.schladebeck.fx.grid.Grid;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ListGridModelTest {

    record Person(String firstName, int age, double salary) {
        Person withFirstName(String name) {
            return new Person(name, age, salary);
        }
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @Test
    void columnsFromGetters() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = new ListGridModel<Person>()
                    .column("Name", Person::firstName)
                    .column("Salary", Person::salary);
            people.setAll(List.of(new Person("Ann", 30, 5_000)));

            assertEquals(2, people.getColumnCount());
            assertEquals("Salary", people.getColumnName(1));
            assertEquals(5_000.0, people.getValueAt(0, 1));
            assertEquals(Double.class, people.getColumnClass(1), "inferred from the values");
            assertFalse(people.isCellEditable(0, 0));
        });
    }

    @Test
    void columnsFromRecordComponents() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ListGridModel.ofRecords(Person.class, List.of(new Person("Ann", 30, 1)));

            assertEquals("First name", people.getColumnName(0));
            assertEquals(Integer.class, people.getColumnClass(1));
            assertEquals(30, people.getValueAt(0, 1));
        });
    }

    @Test
    void changesFromBackgroundThreadsArriveOnTheFxThread() throws Exception {
        ListGridModel<Person> people = Fx.call(() -> new ListGridModel<Person>().column("Name", Person::firstName));
        List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        Fx.run(() -> people.addGridModelListener(e -> onFxThread.add(Platform.isFxApplicationThread())));

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                people.add(new Person("P" + i, i, i));
            }
            people.set(0, new Person("first", 0, 0));
        });
        writer.start();
        writer.join();

        assertEquals(1_000, (int) Fx.call(people::getRowCount));
        assertEquals("first", Fx.call(() -> people.getItem(0).firstName()));
        assertEquals("P999", Fx.call(() -> people.getItem(999).firstName()), "order is kept");
        assertFalse(onFxThread.contains(false));
        assertEquals(1_001, onFxThread.size());
    }

    @Test
    void updaterMakesColumnEditable() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = new ListGridModel<Person>()
                    .column("Name", Person::firstName, Person::withFirstName)
                    .column("Age", Person::age);
            people.setAll(List.of(new Person("Ann", 30, 1)));
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            assertTrue(people.isCellEditable(0, 0));
            people.setValueAt("Bea", 0, 0);

            assertEquals("Bea", people.getItem(0).firstName());
            assertEquals(GridModelEvent.Type.UPDATE, events.getFirst().type());
        });
    }

    @Test
    void buildersFromBackgroundThreadsArriveOnTheFxThread() throws Exception {
        ListGridModel<Person> people = Fx.call(() -> new ListGridModel<Person>().column("Name", Person::firstName));
        List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        Fx.run(() -> people.addGridModelListener(e -> onFxThread.add(Platform.isFxApplicationThread())));

        people.column("Age", Person::age);
        people.column("First name", Person::firstName, Person::withFirstName);
        people.rowNames(p -> "#" + p.age());
        Fx.run(() -> people.add(new Person("Ann", 30, 1)));

        assertEquals(3, (int) Fx.call(people::getColumnCount));
        assertEquals("#30", Fx.call(() -> people.getRowName(0)));
        assertEquals(List.of(true, true, true, true), onFxThread);
    }

    /** Type and rows of each event, e.g. {@code DELETE 1-2}. */
    private static List<String> describe(List<GridModelEvent> events) {
        return events.stream().map(e -> e.type() + " " + e.firstRow() + "-" + e.lastRow()).toList();
    }

    private static ListGridModel<Person> ages(int... ages) {
        ListGridModel<Person> people = new ListGridModel<Person>().column("Age", Person::age);
        for (int age : ages) {
            people.add(new Person("p" + age, age, 0));
        }
        return people;
    }

    private static List<Integer> agesOf(ListGridModel<Person> people) {
        return people.getItems().stream().map(Person::age).toList();
    }

    @Test
    void removeIfFiresOneDeletePerRunFromTheBottom() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ages(1, 2, 4, 5, 6, 7);
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            people.removeIf(p -> p.age() % 2 == 0);

            assertEquals(List.of("DELETE 4-4", "DELETE 1-2"), describe(events));
            assertEquals(List.of(1, 5, 7), agesOf(people));
            assertEquals(List.of(5), people.getItems(new int[] {1}).stream().map(Person::age).toList());
            assertTrue(people.matching(p -> p.age() == 5).test(1));
        });
    }

    @Test
    void removeRowsTakesRowsInAnyOrder() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ages(0, 1, 2, 3, 4, 5, 6, 7);
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            people.removeRows(5, 1, 2, 7, 2);

            assertEquals(List.of("DELETE 7-7", "DELETE 5-5", "DELETE 1-2"), describe(events));
            assertEquals(List.of(0, 3, 4, 6), agesOf(people));
        });
    }

    @Test
    void removeRowsOutOfRangeRemovesNothing() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ages(0, 1, 2);
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            assertThrows(IndexOutOfBoundsException.class, () -> people.removeRows(0, 3));
            assertThrows(IndexOutOfBoundsException.class, () -> people.removeRows(-1, 1));

            assertEquals(List.of(0, 1, 2), agesOf(people));
            assertEquals(List.of(), events);
        });
    }

    @Test
    void addAllAtAnIndexFiresOneInsert() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ages(0, 3);
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            people.addAll(1, List.of(new Person("p1", 1, 0), new Person("p2", 2, 0)));
            people.addAll(4, List.of());

            assertEquals(List.of("INSERT 1-2"), describe(events));
            assertEquals(List.of(0, 1, 2, 3), agesOf(people));
        });
    }

    @Test
    void updateReplacesTheItemWithTheChangedOneAndFiresAnUpdate() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ages(1, 2, 3);
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            people.update(1, p -> p.withFirstName("Bea"));

            assertEquals("Bea", people.getItem(1).firstName(), "on the JavaFX thread the change runs right away");
            assertEquals(List.of(1, 2, 3), agesOf(people));
            assertEquals(List.of("UPDATE 1-1"), describe(events));
        });
    }

    @Test
    void updatesFromAnotherThreadRunOnTheFxThreadInOrder() throws Exception {
        ListGridModel<Person> people = Fx.call(() -> ages(0));
        List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                people.update(0, p -> {
                    onFxThread.add(Platform.isFxApplicationThread());
                    return new Person(p.firstName(), p.age() + 1, p.salary());
                });
            }
            people.update(0, p -> {
                done.countDown();
                return p;
            });
        });
        writer.start();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(1_000, (int) Fx.call(() -> people.getItem(0).age()), "each change sees the one before");
        assertEquals(1_000, onFxThread.size());
        assertFalse(onFxThread.contains(false));
    }

    @Test
    void clearFiresOneDeleteForAllRowsAndNothingWhenAlreadyEmpty() throws Exception {
        Fx.run(() -> {
            ListGridModel<Person> people = ages(1, 2, 3);
            List<GridModelEvent> events = new ArrayList<>();
            people.addGridModelListener(events::add);

            people.clear();
            people.clear();

            assertEquals(List.of("DELETE 0-2"), describe(events));
            assertEquals(0, people.getRowCount());
            assertEquals(List.of(), people.getItems());
        });
    }

    @Test
    void clearFromAnotherThreadEmptiesASortedGridOnTheFxThread() throws Exception {
        ListGridModel<Person> people = Fx.call(() -> ages(3, 1, 2));
        Grid grid = Fx.call(() -> Fx.show(new Grid(people)));
        List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        CountDownLatch cleared = new CountDownLatch(1);
        Fx.run(() -> {
            grid.sort(0, true);
            grid.getSelectionModel().select(1, 0);
            people.addGridModelListener(e -> {
                onFxThread.add(Platform.isFxApplicationThread());
                cleared.countDown();
            });
        });

        Thread writer = new Thread(people::clear);
        writer.start();
        assertTrue(cleared.await(10, TimeUnit.SECONDS));

        Fx.run(() -> {
            Fx.layout(grid);
            assertEquals(0, grid.getViewRowCount());
            assertEquals(List.of(), grid.getSelectionModel().getRanges());
        });
        assertEquals(List.of(true), onFxThread);
    }

    /// On the module path, a record in a package that is not exported to the grid's module can be found but its
    /// components cannot be read. One of the JDK's own records stands in for such an application record.
    @Test
    void recordsThatCannotBeReadFailRightAway() throws Exception {
        Class<? extends Record> hidden = Class.forName("jdk.internal.foreign.abi.VMStorage").asSubclass(Record.class);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ListGridModel.ofRecords(hidden));

        assertEquals("jdk.internal.foreign.abi.VMStorage is not accessible to module de.schladebeck.fx.grid: make it"
                + " public and add `exports jdk.internal.foreign.abi to de.schladebeck.fx.grid;` to module-info.java",
                e.getMessage());
    }

    @Test
    void titlesFromComponentNames() {
        assertEquals("First name", ItemColumns.title("firstName"));
        assertEquals("Id", ItemColumns.title("id"));
    }
}
