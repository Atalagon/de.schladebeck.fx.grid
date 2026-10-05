package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.AbstractGridModel;
import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.PagedGridModel;
import de.schladebeck.fx.grid.model.SortKey;
import de.schladebeck.fx.grid.model.TreeGridModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Bugs found in reviews, one test each, so they stay fixed. */
class RegressionFxTest {

    record Unit(String name, List<Unit> children) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static ListGridModel<String> strings(String... items) {
        ListGridModel<String> model = new ListGridModel<String>().column("Name", s -> s, (s, v) -> (String) v);
        model.setAll(Arrays.asList(items));
        return model;
    }

    private static ListGridModel<String> strings(int count) {
        return strings(IntStream.range(0, count).mapToObj(i -> "row " + i).toArray(String[]::new));
    }

    private static TreeGridModel<Unit> oneRoot() {
        TreeGridModel<Unit> tree = new TreeGridModel<Unit>(Unit::children).column("Name", Unit::name);
        tree.setRoots(List.of(new Unit("root", List.of(new Unit("a", List.of()), new Unit("b", List.of())))));
        return tree;
    }

    private static List<String> rows(TreeGridModel<String> tree) {
        return IntStream.range(0, tree.getRowCount()).mapToObj(r -> tree.getDepth(r) + tree.getItem(r)).toList();
    }

    // ---- selection

    @Test
    void selectingTheOnlyFilteredRowThenClearingTheFilterSelectsOneRow() throws Exception {
        Fx.run(() -> {
            ListGridModel<String> model = strings("a", "b", "c", "d", "e");
            Grid grid = show(new Grid(model));
            grid.setRowFilter(row -> model.getItem(row).equals("c"));
            grid.getSelectionModel().select(0, 0);
            grid.setRowFilter(null);
            assertArrayEquals(new int[] {2}, grid.getSelectedModelRows());
        });
    }

    @Test
    void insertingAboveTheOnlyRowMovesTheSelection() throws Exception {
        Fx.run(() -> {
            ListGridModel<String> model = strings("a");
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(0, 0);
            model.add(0, "z");
            assertArrayEquals(new int[] {1}, grid.getSelectedModelRows());
        });
    }

    @Test
    void expandingTheOnlySelectedRootKeepsOnlyItSelected() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Unit> tree = oneRoot();
            Grid grid = show(new TreeGrid(tree));
            grid.getSelectionModel().select(0, 0);
            tree.setExpanded(0, true);
            assertArrayEquals(new int[] {0}, grid.getSelectedModelRows());
        });
    }

    @Test
    void leftThenRightOnTheOnlyRootSelectsOnlyIt() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Unit> tree = oneRoot();
            Grid grid = show(new TreeGrid(tree));
            tree.setExpanded(0, true);
            grid.getSelectionModel().select(0, 0);
            Fx.key(grid, KeyCode.LEFT);
            Fx.key(grid, KeyCode.RIGHT);
            assertEquals(3, grid.getViewRowCount());
            assertArrayEquals(new int[] {0}, grid.getSelectedModelRows());
        });
    }

    @Test
    void sortingATreeClearsTheSelectionItsNewRowNumbersWouldMove() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Unit> tree = new TreeGridModel<Unit>(Unit::children).column("Name", Unit::name);
            tree.setRoots(List.of(new Unit("b", List.of()), new Unit("a", List.of()), new Unit("c", List.of())));
            Grid grid = show(new TreeGrid(tree));
            grid.getSelectionModel().select(0, 0);
            grid.sort(0, true);
            assertTrue(grid.getSelectionModel().isEmpty());
            assertEquals(-1, grid.getFocusedModelRow());
        });
    }

    // ---- trees

    @Test
    void sortingAnEmptyTreeDoesNotThrow() throws Exception {
        Fx.run(() -> {
            TreeGridModel<String> tree = new TreeGridModel<String>(s -> List.of()).column("Name", s -> s);
            Grid grid = show(new TreeGrid(tree));
            assertDoesNotThrow(() -> grid.sort(0, true));
        });
    }

    @Test
    void equalItemsInTwoPlacesAreExpandedEachOnItsOwn() throws Exception {
        Fx.run(() -> {
            TreeGridModel<String> tree = new TreeGridModel<String>(s -> s.equals("Other") ? List.of("x")
                    : List.of("Other")).column("Name", s -> s);
            tree.setRoots(List.of("Fruit", "Vegetables"));
            tree.setExpanded(0, true);
            tree.setExpanded(2, true);
            tree.setExpanded(1, true);
            assertFalse(tree.isExpanded(4), "the second Other");
            tree.setExpanded(4, false);
            tree.setExpanded(1, true);
            assertEquals(List.of("0Fruit", "1Other", "2x", "0Vegetables", "1Other"), rows(tree));
        });
    }

    @Test
    void sortingATreeOfMixedValuesDoesNotThrow() throws Exception {
        Fx.run(() -> {
            Random random = new Random(1);
            for (int attempt = 0; attempt < 30; attempt++) {
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < 2000; i++) {
                    switch (random.nextInt(3)) {
                        case 0 -> items.add(random.nextInt(200));
                        case 1 -> items.add(random.nextInt(2000) / 10.0);
                        default -> items.add(Integer.toString(random.nextInt(200)));
                    }
                }
                TreeGridModel<Object> tree = new TreeGridModel<Object>(o -> List.of()).column("Value", o -> o);
                tree.setRoots(items);
                Grid grid = show(new TreeGrid(tree));
                int a = attempt;
                assertDoesNotThrow(() -> grid.sort(0, true), "attempt " + a);
            }
        });
    }

    // ---- model changes and threads

    @Test
    void editingWhileABackgroundInsertIsQueuedEditsTheRowOnScreen() throws Exception {
        ListGridModel<String> model = Fx.call(() -> strings("a", "b", "c"));
        Fx.run(() -> {
            Thread background = new Thread(() -> model.add(0, "z"));
            background.start();
            try {
                background.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            model.setValueAt("B", 1, 0);
        });
        Fx.run(() -> assertEquals(List.of("z", "a", "B", "c"), model.getItems()));
    }

    @Test
    void reloadThenReloadWithCountKeepsTheCount() throws Exception {
        PagedGridModel<String> paged = Fx.call(() -> new PagedGridModel<String>(1000, 100,
                request -> List.of(), Runnable::run).column("Name", s -> s));
        Fx.run(() -> {
            Thread background = new Thread(() -> {
                paged.reload();
                paged.reload(5);
            });
            background.start();
            try {
                background.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Fx.run(() -> assertEquals(5, paged.getRowCount()));
    }

    @Test
    void sortKeysOfRemovedColumnsAreDropped() throws Exception {
        Fx.run(() -> {
            ComputedGridModel model = new ComputedGridModel(10, 3, (r, c) -> r * c);
            Grid grid = show(new Grid(model));
            grid.sort(2, true);
            model.setSize(10, 2);
            layout(grid);
            assertEquals(List.of(), grid.getSortKeys());
            assertDoesNotThrow(() -> grid.clickSort(0, true));
        });
    }

    @Test
    void aThrowingFilterCanBeRemovedAndTheModelReplaced() throws Exception {
        Fx.run(() -> {
            ListGridModel<String> model = strings("a", "b", null, "d");
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(1, 0);
            grid.setRowFilter(row -> model.getItem(row).startsWith("a"));
            assertEquals(4, grid.getViewRowCount(), "a broken filter shows all rows");
            assertDoesNotThrow(() -> grid.setRowFilter(null));
            assertDoesNotThrow(() -> grid.setModel(strings("x", "y")));
            assertEquals(2, grid.getViewRowCount());
        });
    }

    @Test
    void aThrowingComparatorCanBeCleared() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(strings("a", "b", "c", "d")));
            grid.getSelectionModel().select(1, 0);
            grid.getColumn(0).setComparator((a, b) -> {
                throw new ClassCastException("broken");
            });
            grid.sort(0, true);
            assertDoesNotThrow(grid::clearSort);
        });
    }

    @Test
    void anUpdateOfAllRowsWithRowHeightsEnds() throws Exception {
        var model = new AbstractGridModel() {
            @Override
            public int getRowCount() {
                return 10;
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public Object getValueAt(int row, int column) {
                return row;
            }

            void updateAll() {
                fireRowsUpdated(0, Integer.MAX_VALUE);
            }
        };
        Fx.run(() -> {
            Grid grid = show(new Grid(model));
            grid.setRowHeightProvider(row -> 30);
            layout(grid);
            model.updateAll();
            layout(grid);
        });
    }

    @Test
    void aColumnOfAnotherModelIsRejected() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new ComputedGridModel(10, 3, (r, c) -> r * c)));
            GridColumn third = grid.getColumn(2);
            grid.setModel(new ComputedGridModel(10, 1, (r, c) -> r));
            assertThrows(IllegalStateException.class, () -> third.setWidth(50));
            assertTrue(grid.getLayout().columnWidths().isEmpty());
        });
    }

    @Test
    void aLayoutSavedBeforeAColumnWasInsertedFindsItsColumnsByName() throws Exception {
        Fx.run(() -> {
            ListGridModel<String> before = new ListGridModel<String>().column("A", s -> s).column("B", s -> s)
                    .column("C", s -> s);
            Grid grid = show(new Grid(before));
            grid.getColumn("B").setWidth(150);
            grid.getColumn("C").setVisible(false);
            grid.sort(1, false);
            String saved = grid.getLayout().encode();

            ListGridModel<String> after = new ListGridModel<String>().column("A", s -> s).column("New", s -> s)
                    .column("B", s -> s).column("C", s -> s);
            grid.setModel(after);
            grid.setLayout(GridLayout.decode(saved));
            assertEquals(150, grid.getColumn("B").getWidth());
            assertFalse(grid.getColumn("C").isVisible());
            assertTrue(grid.getColumn("New").isVisible());
            assertEquals(List.of(new SortKey(2, false)), grid.getSortKeys());
        });
    }

    // ---- scrolling and editing

    @Test
    void notANumberScrollsToTheStart() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(strings(1000)));
            grid.setScrollY(Double.NaN);
            layout(grid);
            assertEquals(0, grid.getScrollY());
        });
    }

    @Test
    void editingACellOutsideTheGridReturnsFalse() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(strings(10)));
            grid.setEditable(true);
            assertFalse(grid.editCell(-1, 0));
        });
    }

    @Test
    void theCellScrolledToFollowsItsRowWhenRowsAreInserted() throws Exception {
        Fx.run(() -> {
            ListGridModel<String> model = strings(1000);
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(500, 0);
            grid.scrollTo(500, 0);
            model.addAll(0, IntStream.range(0, 30).mapToObj(i -> "new " + i).toList());
            layout(grid);
            int focused = grid.getSelectionModel().getFocusedRow();
            GridGeometry g = ((GridSkin) grid.getSkin()).geometry();
            assertTrue(focused >= g.firstScrolledRow() && focused <= g.lastScrolledRow(),
                    "focused " + focused + ", visible " + g.firstScrolledRow() + ".." + g.lastScrolledRow());
        });
    }

    @Test
    void deletingTheTopRowOfASortedViewKeepsThePlace() throws Exception {
        Fx.run(() -> {
            ListGridModel<Integer> model = new ListGridModel<Integer>().column("Value", i -> (i * 7919) % 1000);
            model.setAll(IntStream.range(0, 1000).boxed().toList());
            Grid grid = show(new Grid(model));
            grid.sort(0, true);
            layout(grid);
            grid.setScrollY(500 * grid.getRowHeight());
            layout(grid);
            GridGeometry g = ((GridSkin) grid.getSkin()).geometry();
            int top = g.firstScrolledRow();
            model.remove(grid.viewToModelRow(top));
            layout(grid);
            int now = g.firstScrolledRow();
            assertTrue(Math.abs(now - top) <= 1, "top row was " + top + ", is " + now);
        });
    }
}
