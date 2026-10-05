package de.schladebeck.fx.grid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TreeGridModelTest {

    record Item(String name, List<Item> children) {
    }

    private static final Comparator<Object> TEXT = Comparator.nullsFirst(Comparator.comparing(Object::toString));

    private final List<String> events = new ArrayList<>();

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static Item item(String name, Item... children) {
        return new Item(name, List.of(children));
    }

    private static Item[] sample() {
        return new Item[] {item("A", item("A1", item("A1a")), item("A2")), item("B")};
    }

    private TreeGridModel<Item> tree(Item... roots) {
        TreeGridModel<Item> model = new TreeGridModel<Item>(Item::children).column("Name", Item::name);
        model.setRoots(List.of(roots));
        model.addGridModelListener(e -> events.add(e.type() + " " + e.firstRow() + "-" + e.lastRow()));
        return model;
    }

    /** The rows' names, indented by depth. */
    private static List<String> rows(TreeGridModel<Item> model) {
        return IntStream.range(0, model.getRowCount())
                .mapToObj(row -> " ".repeat(model.getDepth(row)) + model.getValueAt(row, 0)).toList();
    }

    @Test
    void expandingInsertsRowsAndCollapsingDeletesThem() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree(sample());
            assertEquals(List.of("A", "B"), rows(model));
            assertFalse(model.isLeaf(0));
            assertTrue(model.isLeaf(1));

            model.setExpanded(0, true);
            model.setExpanded(1, true);
            assertEquals(List.of("A", " A1", "  A1a", " A2", "B"), rows(model));
            assertEquals(1, model.getParentRow(2));
            assertEquals(-1, model.getParentRow(4));

            model.setExpanded(0, false);
            assertEquals(List.of("A", "B"), rows(model));
            model.setExpanded(0, true);
            assertEquals(List.of("A", " A1", "  A1a", " A2", "B"), rows(model), "A1 stays expanded");
            model.setExpanded(4, true);
            assertEquals(List.of("INSERT 1-2", "INSERT 2-2", "DELETE 1-3", "INSERT 1-3"), events, "a leaf has nothing");
        });
    }

    @Test
    void newRootsKeepWhatWasExpanded() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree(sample());
            model.setExpanded(0, true);
            model.setRoots(List.of(sample()));
            assertEquals(List.of("A", " A1", " A2", "B"), rows(model));
        });
    }

    @Test
    void sortingSortsTheChildrenOfEachItem() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree(sample());
            model.setExpanded(0, true);
            model.setExpanded(1, true);

            assertTrue(model.sortRows(List.of(new SortKey(0, false)), column -> TEXT));
            assertEquals(List.of("B", "A", " A2", " A1", "  A1a"), rows(model));
            model.sortRows(List.of(), column -> TEXT);
            assertEquals(List.of("A", " A1", "  A1a", " A2", "B"), rows(model));
        });
    }
}
