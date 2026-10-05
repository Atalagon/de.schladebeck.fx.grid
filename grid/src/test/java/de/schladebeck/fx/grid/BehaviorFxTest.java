package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.center;
import static de.schladebeck.fx.grid.Fx.click;
import static de.schladebeck.fx.grid.Fx.drag;
import static de.schladebeck.fx.grid.Fx.key;
import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.mouse;
import static de.schladebeck.fx.grid.Fx.show;
import static de.schladebeck.fx.grid.Fx.wheel;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.SortKey;
import java.util.List;
import java.util.stream.IntStream;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Keyboard and mouse input, sent as events like the user would. */
class BehaviorFxTest {

    public record Item(String name, int amount, boolean active) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static ListGridModel<Item> items(int count) {
        return ListGridModel.ofRecords(Item.class, IntStream.range(0, count)
                .mapToObj(i -> new Item("item " + i, (i * 7) % 10, i % 2 == 0))
                .toList());
    }

    private static int[] focus(Grid grid) {
        return new int[] {grid.getSelectionModel().getFocusedRow(), grid.getSelectionModel().getFocusedColumn()};
    }

    @Test
    void theFirstNavigationKeyFocusesTheTopLeftCellOnScreen() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(50)));
            key(grid, KeyCode.DOWN);
            assertArrayEquals(new int[] {0, 0}, focus(grid), "not the second row");
            key(grid, KeyCode.DOWN);
            key(grid, KeyCode.RIGHT);
            assertArrayEquals(new int[] {1, 1}, focus(grid));

            Grid scrolled = show(new Grid(items(1_000)));
            scrolled.scrollTo(500, 0);
            layout(scrolled);
            int top = scrolled.cellAt(center(scrolled, 0, 0).getX(), scrolled.getHeaderHeight() + 1).viewRow();
            key(scrolled, KeyCode.DOWN);
            assertTrue(top > 400, "scrolled down");
            assertArrayEquals(new int[] {top, 0}, focus(scrolled), "the grid stays where it was scrolled to");
        });
    }

    @Test
    void arrowsHomeEndAndShortcutsMoveTheFocus() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(50)));
            grid.getSelectionModel().select(5, 1);
            key(grid, KeyCode.DOWN, "shortcut");
            assertArrayEquals(new int[] {49, 1}, focus(grid));
            key(grid, KeyCode.HOME);
            assertArrayEquals(new int[] {49, 0}, focus(grid));
            key(grid, KeyCode.END);
            assertArrayEquals(new int[] {49, 2}, focus(grid));
            key(grid, KeyCode.HOME, "shortcut");
            assertArrayEquals(new int[] {0, 0}, focus(grid));
            key(grid, KeyCode.UP);
            assertArrayEquals(new int[] {0, 0}, focus(grid), "stays at the edge");
        });
    }

    @Test
    void shiftExtendsAndShortcutsSelectRowsColumnsAndAll() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(50)));
            grid.getSelectionModel().select(2, 0);
            key(grid, KeyCode.DOWN, "shift");
            key(grid, KeyCode.RIGHT, "shift");
            assertEquals(List.of(new CellRange(2, 0, 3, 1)), grid.getSelectionModel().getRanges());

            key(grid, KeyCode.SPACE, "shift");
            assertEquals(List.of(new CellRange(3, 0, 3, 2)), grid.getSelectionModel().getRanges(), "the row");
            key(grid, KeyCode.SPACE, "shortcut");
            assertEquals(List.of(new CellRange(0, 1, 49, 1)), grid.getSelectionModel().getRanges(), "the column");
            key(grid, KeyCode.A, "shortcut");
            assertEquals(List.of(new CellRange(0, 0, 49, 2)), grid.getSelectionModel().getRanges(), "everything");
        });
    }

    @Test
    void pageDownMovesByTheRealRowHeights() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(500)));
            grid.getSelectionModel().select(0, 0);
            key(grid, KeyCode.PAGE_DOWN);
            int uniform = focus(grid)[0];
            assertTrue(uniform > 10, "a screen of default rows: " + uniform);

            grid.setRowHeightProvider(row -> 3 * grid.getRowHeight());
            layout(grid);
            grid.getSelectionModel().select(0, 0);
            key(grid, KeyCode.PAGE_DOWN);
            int tall = focus(grid)[0];
            assertTrue(tall < uniform / 2, "rows three times as high: " + tall + " vs " + uniform);
            key(grid, KeyCode.PAGE_UP);
            assertEquals(0, focus(grid)[0]);
        });
    }

    @Test
    void anEmptyGridKeepsTheArrowKeys() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(0)));
            boolean[] reachedParent = {false};
            grid.getParent().addEventHandler(KeyEvent.KEY_PRESSED, e -> reachedParent[0] = true);
            key(grid, KeyCode.DOWN);
            assertFalse(reachedParent[0], "consumed, so the focus does not move to the next control");
        });
    }

    @Test
    void clickingAHeaderSortsUpDownAndOff() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            Point2D header = new Point2D(center(grid, 0, 1).getX(), grid.getHeaderHeight() / 2);
            click(grid, header.getX(), header.getY(), 1);
            assertEquals(List.of(new SortKey(1, true)), grid.getSortKeys());
            click(grid, header.getX(), header.getY(), 1);
            assertEquals(List.of(new SortKey(1, false)), grid.getSortKeys());
            click(grid, header.getX(), header.getY(), 1);
            assertEquals(List.of(), grid.getSortKeys());

            grid.setSortable(false);
            click(grid, header.getX(), header.getY(), 1);
            assertEquals(List.of(), grid.getSortKeys(), "not sortable");
        });
    }

    @Test
    void shiftClickingHeadersSortsBySeveralColumns() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            double y = grid.getHeaderHeight() / 2;
            double name = center(grid, 0, 1).getX();
            double value = center(grid, 0, 0).getX();

            click(grid, name, y, 1);
            click(grid, value, y, 1, "shift");
            assertEquals(List.of(new SortKey(1, true), new SortKey(0, true)), grid.getSortKeys());
            click(grid, value, y, 1, "shift");
            assertEquals(List.of(new SortKey(1, true), new SortKey(0, false)), grid.getSortKeys());
            assertTrue(grid.getLayout().encode().contains("sort=1:asc,0:desc"), grid.getLayout().encode());
            click(grid, value, y, 1, "shift");
            assertEquals(List.of(new SortKey(1, true)), grid.getSortKeys());

            click(grid, value, y, 1, "shift");
            click(grid, value, y, 1);
            assertEquals(List.of(new SortKey(0, false)), grid.getSortKeys(),
                    "a plain click sorts by that column alone, going on from its direction");
            click(grid, name, y, 1);
            assertEquals(List.of(new SortKey(1, true)), grid.getSortKeys());
        });
    }

    @Test
    void aDoubleClickOnAHeaderSortsOnceAndWithoutSortingAClickSelectsTheColumn() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            double y = grid.getHeaderHeight() / 2;
            double x = center(grid, 0, 1).getX();
            click(grid, x, y, 1);
            click(grid, x, y, 2);
            assertEquals(List.of(new SortKey(1, true)), grid.getSortKeys());

            grid.setSortable(false);
            click(grid, x, y, 1);
            assertEquals(List.of(new CellRange(0, 1, 19, 1)), grid.getSelectionModel().getRanges());
        });
    }

    @Test
    void draggingAHeaderMovesItsColumn() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            double y = grid.getHeaderHeight() / 2;
            Bounds last = grid.getCellBounds(0, 2);
            drag(grid, center(grid, 0, 0).getX(), y, last.getMaxX() - 2, y);
            assertArrayEquals(new int[] {1, 2, 0}, grid.columns().order());

            grid.setColumnsReorderable(false);
            drag(grid, center(grid, 0, 0).getX(), y, grid.getCellBounds(0, 2).getMaxX() - 2, y);
            assertArrayEquals(new int[] {1, 2, 0}, grid.columns().order(), "not reorderable");
        });
    }

    @Test
    void escapeCancelsAColumnDrag() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            double y = grid.getHeaderHeight() / 2;
            double x = center(grid, 0, 0).getX();
            mouse(grid, MouseEvent.MOUSE_PRESSED, x, y, 1);
            mouse(grid, MouseEvent.MOUSE_DRAGGED, x + 150, y, 1);
            key(grid, KeyCode.ESCAPE);
            mouse(grid, MouseEvent.MOUSE_RELEASED, x + 150, y, 1);
            assertArrayEquals(new int[] {0, 1, 2}, grid.columns().order());
        });
    }

    @Test
    void draggingABorderResizesAndDoubleClickFits() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            double fitted = grid.getColumn(0).getWidth();
            double border = grid.getCellBounds(0, 0).getMaxX() - 1;
            double y = grid.getHeaderHeight() / 2;
            drag(grid, border, y, border + 60, y);
            assertEquals(fitted + 61, grid.getColumn(0).getWidth(), 1.5);

            double wide = grid.getCellBounds(0, 0).getMaxX() - 1;
            click(grid, wide, y, 2);
            assertEquals(fitted, grid.getColumn(0).getWidth(), 1.5, "fitted again");
        });
    }

    @Test
    void draggingPastTheBottomScrollsAndSelects() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(500)));
            Point2D start = center(grid, 2, 0);
            mouse(grid, MouseEvent.MOUSE_PRESSED, start.getX(), start.getY(), 1);
            mouse(grid, MouseEvent.MOUSE_DRAGGED, start.getX(), grid.getHeight() + 80, 1);
            assertTrue(grid.getSelectionModel().getFocusedRow() > 2, "selects to the bottom edge");
            mouse(grid, MouseEvent.MOUSE_RELEASED, start.getX(), grid.getHeight() + 80, 1);
        });
    }

    @Test
    void ctrlWheelZoomsByHowFarTheWheelTurned() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            for (int i = 0; i < 10; i++) {
                wheel(grid, 100, 100, 4, "shortcut");
            }
            double touchpad = grid.getZoom();
            assertTrue(touchpad > 1 && touchpad < 1.2, "ten small steps are one notch: " + touchpad);
            wheel(grid, 100, 100, 40, "shortcut");
            assertEquals(touchpad * 1.1, grid.getZoom(), 1e-9);
        });
    }

    @Test
    void spaceAndAClickToggleCheckBoxesInEditableGrids() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = new ListGridModel<Item>()
                    .column("Name", Item::name)
                    .column("Active", Item::active, (item, active) -> new Item(item.name(), item.amount(), active));
            model.setAll(List.of(new Item("a", 1, true), new Item("b", 2, false)));
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(0, 1);
            key(grid, KeyCode.SPACE);
            assertTrue(model.getItem(0).active(), "not editable");

            grid.setEditable(true);
            key(grid, KeyCode.SPACE);
            assertFalse(model.getItem(0).active());
            Point2D box = center(grid, 1, 1);
            click(grid, box.getX(), box.getY(), 1);
            assertTrue(model.getItem(1).active());
            click(grid, box.getX(), box.getY(), 2);
            assertTrue(model.getItem(1).active(), "the second click of a double-click does not toggle back");
            Bounds cell = grid.getCellBounds(1, 1);
            click(grid, cell.getMinX() + 3, cell.getCenterY(), 1);
            assertTrue(model.getItem(1).active(), "a click beside the box only selects");
            assertEquals(1, grid.getSelectionModel().getFocusedRow());
        });
    }

    @Test
    void enterRunsTheCellAction() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            GridCell[] acted = new GridCell[1];
            grid.setOnCellAction(cell -> acted[0] = cell);
            grid.getSelectionModel().select(3, 2);
            key(grid, KeyCode.ENTER);
            Fx.assertCell(3, 2, acted[0]);
        });
    }
}
