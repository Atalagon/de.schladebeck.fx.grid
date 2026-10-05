package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.SortKey;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.input.ContextMenuEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Column objects and their limits, row height limits, cell context menus, very wide grids. */
class ColumnsAndMenusFxTest {

    record Item(String name, String notes) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static ListGridModel<Item> items(int count) {
        ListGridModel<Item> model = new ListGridModel<Item>().column("Name", Item::name).column("Notes", Item::notes);
        model.setAll(IntStream.range(0, count)
                .mapToObj(i -> new Item("item " + i,
                        "a long note that wants a lot more room than a narrow column " + i))
                .toList());
        return model;
    }

    @Test
    void theColumnMenuShowsHowTheColumnIsSortedAndItsCheckBoxesWorkFromTheKeyboard() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(5)));
            grid.sort(List.of(new SortKey(0, true), new SortKey(1, false)));
            List<MenuItem> items = grid.createColumnMenu(grid.getColumn(1)).getItems();
            assertFalse(((RadioMenuItem) items.get(0)).isSelected());
            assertTrue(((RadioMenuItem) items.get(1)).isSelected(), "sorted descending");
            items.get(0).fire();
            assertEquals(List.of(new SortKey(0, true), new SortKey(1, true)), grid.getSortKeys(),
                    "the direction changes in place");
            items.get(3).fire(); // ascending, descending, remove, clear: sorted by two columns
            assertEquals(List.of(), grid.getSortKeys());

            MenuItem notes = grid.createColumnMenu(null).getItems().getLast();
            notes.fire();
            assertFalse(grid.getColumn(1).isVisible(), "Enter on the item switches the column off");
        });
    }

    @Test
    void columnWidthStaysWithinItsLimits() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            GridColumn notes = grid.getColumn(1).setMinWidth(60).setMaxWidth(150);

            assertEquals(150, notes.setWidth(500).getWidth());
            assertEquals(60, notes.setWidth(10).getWidth());
            notes.setMinWidth(90);
            assertEquals(90, notes.getWidth(), "a narrower column grows to a new minimum");
            notes.setMaxWidth(80);
            assertEquals(80, notes.getMaxWidth());
            assertEquals(80, notes.getMinWidth(), "the minimum follows a smaller maximum");
            assertEquals("Notes", notes.getName());
            assertEquals(1, notes.getViewIndex());
        });
    }

    @Test
    void autoFitRespectsTheMaximum() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(10));
            grid.getColumn(1).setMaxWidth(120);
            show(grid);

            assertEquals(120, grid.getColumn(1).getWidth());
        });
    }

    @Test
    void rowHeightsStayWithinTheLimits() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            grid.setRowHeightProvider(row -> row == 1 ? 500 : row == 2 ? 5 : 0);
            grid.setMinRowHeight(20);
            grid.setMaxRowHeight(80);
            layout(grid);

            assertEquals(80, grid.getCellBounds(1, 0).getHeight(), 1e-9);
            assertEquals(20, grid.getCellBounds(2, 0).getHeight(), 1e-9);
            assertEquals(grid.getRowHeight(), grid.getCellBounds(3, 0).getHeight(), 1e-9);
        });
    }

    @Test
    void rightClickSelectsTheCellAndAsksForItsMenu() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            List<GridCell> asked = new ArrayList<>();
            grid.setCellContextMenuFactory(cell -> {
                asked.add(cell);
                return null;
            });
            grid.getSelectionModel().select(0, 0);
            grid.getSelectionModel().extend(2, 1);

            rightClick(grid, 1, 1);
            assertEquals(1, asked.getLast().viewRow());
            assertEquals("item 1", grid.getViewValueAt(asked.getLast().viewRow(), 0));
            assertEquals(1, grid.getSelectionModel().getRanges().size(), "a click inside the selection keeps it");

            rightClick(grid, 5, 0);
            assertEquals(5, asked.getLast().viewRow());
            assertEquals("item 5", asked.getLast().value());
            assertTrue(grid.getSelectionModel().isSelected(5, 0), "a click outside selects the cell");
            assertEquals(1, grid.getSelectionModel().getRanges().getFirst().cellCount());
        });
    }

    @Test
    void wideGridsCostLittle() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new ComputedGridModel(1_000, 100_000_000, (row, column) -> row + column)));
            grid.scrollTo(3, 50_000_010);
            layout(grid);
            assertTrue(grid.getScrollX() > 4_999_000_000.0);

            Bounds b = grid.getCellBounds(3, 50_000_010);
            Fx.assertCell(3, 50_000_010, grid.cellAt(b.getCenterX(), b.getCenterY()));
            assertEquals(50_000_013, grid.cellAt(b.getCenterX(), b.getCenterY()).value());
            assertTrue(grid.getLayout().columnWidths().size() <= Grid.AUTO_FIT_COLUMNS,
                    "only the first columns are fitted");
            assertTrue(grid.getLayout().encode().length() < 4_000);

            grid.getColumn(70_000_000).setVisible(false);
            assertEquals(99_999_999, grid.getViewColumnCount());
            assertFalse(grid.getColumn(70_000_000).isVisible());
        });
    }

    private static final String WIDE = "a value that needs far more room than a hundred pixels";

    /** A grid of {@code rows x columns} cells, unfitted: every column starts 100 pixels wide. */
    private static Grid unfitted(int rows, int columns, ComputedGridModel.Values values) {
        Grid grid = new Grid(new ComputedGridModel(rows, columns, values));
        grid.setAutoFitColumns(false);
        return show(grid);
    }

    @Test
    void fitVisibleColumnsFitsTheColumnsOnScreenToTheRowsOnScreen() throws Exception {
        Fx.run(() -> {
            Grid grid = unfitted(1_000, 20, (row, column) -> row == 500 || column == 1 ? WIDE : "x");

            grid.fitVisibleColumns();
            layout(grid);

            assertTrue(grid.getColumn(0).getWidth() < 100, "narrower: the wide value in row 500 is not on screen");
            assertTrue(grid.getColumn(1).getWidth() >= TextMetrics.width(WIDE, grid.getFont())
                    + 2 * grid.getCellPadding(), "wide enough for the value");
            assertEquals(100, grid.getColumn(19).getWidth(), "not on screen");
        });
    }

    @Test
    void fitVisibleColumnsFitsFrozenColumnsButNotColumnsScrolledOutOfView() throws Exception {
        Fx.run(() -> {
            Grid grid = unfitted(10, 30, (row, column) -> "x");
            grid.setFrozenColumnCount(1);
            grid.scrollTo(0, 29);
            layout(grid);

            grid.fitVisibleColumns();
            layout(grid);

            assertTrue(grid.getColumn(0).getWidth() < 100, "frozen, so on screen");
            assertTrue(grid.getColumn(29).getWidth() < 100, "scrolled into view");
            assertEquals(100, grid.getColumn(1).getWidth(), "scrolled out of view");
        });
    }

    @Test
    void fitVisibleColumnsKeepsEachColumnWithinItsLimits() throws Exception {
        Fx.run(() -> {
            Grid grid = unfitted(10, 3, (row, column) -> column == 0 ? "x" : WIDE);
            grid.getColumn(0).setMinWidth(60);
            grid.getColumn(1).setMaxWidth(150);

            grid.fitVisibleColumns();
            layout(grid);

            assertEquals(60, grid.getColumn(0).getWidth());
            assertEquals(150, grid.getColumn(1).getWidth());
            assertTrue(grid.getColumn(2).getWidth() > 150, "the same values without a limit");
        });
    }

    /** The values of view column 0, top to bottom. */
    private static List<Object> firstColumn(Grid grid) {
        return IntStream.range(0, grid.getViewRowCount()).mapToObj(row -> grid.getViewValueAt(row, 0)).toList();
    }

    @Test
    void aColumnComparatorReplacesTheDefaultOrderAndNullGoesBackToIt() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new Fx.ArrayModel(new Object[][] {{"b"}, {"B"}, {"a"}, {"A"}})));
            Comparator<String> caseFirst = Comparator.naturalOrder();

            grid.getColumn(0).setComparator(caseFirst);
            grid.sort(0, true);
            assertEquals(List.of("A", "B", "a", "b"), firstColumn(grid));
            assertSame(caseFirst, grid.getColumn(0).getComparator());

            grid.getColumn(0).setComparator(null);
            assertEquals(List.of("A", "a", "B", "b"), firstColumn(grid), "sorted again, ignoring case");
            assertNull(grid.getColumn(0).getComparator());
        });
    }

    @Test
    void aNewComparatorSortsAgainOnlyIfTheGridIsSortedByItsColumn() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(0);
            model.setAll(List.of(new Item("b", "1"), new Item("c", "2"), new Item("a", "3")));
            Grid grid = show(new Grid(model));
            grid.sort(0, true);
            assertEquals(List.of("a", "b", "c"), firstColumn(grid));
            model.set(2, new Item("d", "3")); // an updated row keeps its place until the next sort

            grid.getColumn(1).setComparator(Comparator.reverseOrder());
            assertEquals(List.of("d", "b", "c"), firstColumn(grid), "not sorted by the notes");

            grid.getColumn(0).setComparator(Comparator.naturalOrder());
            assertEquals(List.of("b", "c", "d"), firstColumn(grid), "sorted by the names");
        });
    }

    @Test
    void nullsComeFirstAndTheColumnComparatorNeverSeesThem() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new Fx.ArrayModel(new Object[][] {{"bb"}, {null}, {"ccc"}, {null}, {"a"}})));
            grid.getColumn(0).setComparator(Comparator.comparingInt(String::length)); // throws for null

            grid.sort(0, true);
            assertEquals(Arrays.asList(null, null, "a", "bb", "ccc"), firstColumn(grid));

            grid.sort(0, false);
            assertEquals(Arrays.asList("ccc", "bb", "a", null, null), firstColumn(grid),
                    "descending reverses the whole order, as it does without a comparator");
        });
    }

    private static void rightClick(Grid grid, int row, int column) {
        Bounds b = grid.getCellBounds(row, column);
        Node canvas = grid.getChildrenUnmodifiable().getFirst();
        Point2D scene = grid.localToScene(b.getCenterX(), b.getCenterY());
        // x and y are scene coordinates; the test scene has no window, so they serve as screen coordinates too
        Event.fireEvent(canvas, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                scene.getX(), scene.getY(), scene.getX(), scene.getY(), false, null));
    }
}
