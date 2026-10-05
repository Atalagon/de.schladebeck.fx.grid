package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx.SyntheticModel;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.RowFilters;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests that need the JavaFX toolkit. The grid sits in a window-less scene and is laid out by hand. */
class GridFxTest {

    record Item(String name, int amount) {
        Item withName(String newName) {
            return new Item(newName, amount);
        }

        Item withAmount(int newAmount) {
            return new Item(name, newAmount);
        }
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static ListGridModel<Item> items(int count) {
        ListGridModel<Item> model = new ListGridModel<Item>()
                .column("Name", Item::name, Item::withName)
                .column("Amount", Item::amount, Item::withAmount);
        model.setAll(IntStream.range(0, count).mapToObj(i -> new Item("item " + i, i)).toList());
        return model;
    }

    private static Grid fixedWidths(Grid grid) {
        grid.setAutoFitColumns(false);
        return grid;
    }

    @Test
    void nodeCountDoesNotDependOnModelSize() throws Exception {
        int small = Fx.call(() -> Fx.countNodes(show(new Grid(new SyntheticModel(10, 5)))));
        int huge = Fx.call(() -> Fx.countNodes(show(new Grid(new SyntheticModel(1_000_000, 1_000)))));
        int children = Fx.call(
                () -> show(new Grid(new SyntheticModel(1_000_000, 1_000))).getChildrenUnmodifiable().size());

        assertEquals(small, huge);
        assertEquals(3, children);
    }

    @Test
    void cellAtAndCellBoundsAgreeWithFrozenColumnsScrollAndZoom() throws Exception {
        Fx.run(() -> {
            Grid grid = show(fixedWidths(new Grid(new SyntheticModel(1_000, 50))));
            grid.setFrozenColumnCount(2);
            grid.setScrollY(240);
            grid.setScrollX(300);
            for (double zoom : new double[] {1, 2, 0.5}) {
                grid.setZoom(zoom);
                layout(grid);
                for (int[] cell : new int[][] {{15, 5}, {15, 1}, {12, 0}}) {
                    Bounds b = grid.getCellBounds(cell[0], cell[1]);
                    Fx.assertCell(cell[0], cell[1], grid.cellAt(b.getCenterX(), b.getCenterY()));
                }
                assertEquals(1 + 100 * zoom, grid.getCellBounds(15, 1).getMinX(), 1e-9, "frozen column stays");
            }
            assertNull(grid.cellAt(50, 5), "header is not a body cell");
        });
    }

    @Test
    void rowHeaderShiftsColumnsAndHiddenColumnHeaderShiftsRows() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new SyntheticModel(1_000, 50)));
            Bounds before = grid.getCellBounds(3, 0);
            grid.setRowHeaderVisible(true);
            grid.setColumnHeaderVisible(false);
            layout(grid);
            Bounds after = grid.getCellBounds(3, 0);

            double rowHeaderWidth = ((GridSkin) grid.getSkin()).geometry().rowHeaderWidth();
            assertTrue(rowHeaderWidth > 0);
            assertEquals(before.getMinX() + rowHeaderWidth, after.getMinX(), 1e-9);
            assertEquals(before.getMinY() - grid.getHeaderHeight(), after.getMinY(), 1e-9);
            Fx.assertCell(3, 0, grid.cellAt(after.getCenterX(), after.getCenterY()));
            assertNull(grid.cellAt(rowHeaderWidth / 2, after.getCenterY()), "row header is not a body cell");
        });
    }

    @Test
    void scrollToMakesCellVisible() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new SyntheticModel(100_000, 200)));
            grid.scrollTo(50_000, 150);
            layout(grid);
            Bounds b = grid.getCellBounds(50_000, 150);
            assertTrue(b.getMinY() >= grid.getHeaderHeight() && b.getMaxY() <= grid.getHeight(), b.toString());
            assertTrue(b.getMinX() >= 0 && b.getMaxX() <= grid.getWidth(), b.toString());
        });
    }

    @Test
    void scrollToWorksBeforeTheGridIsShownAndRightAfterRowsArrive() throws Exception {
        Fx.run(() -> {
            ListGridModel<Integer> numbers = new ListGridModel<Integer>().column("Number", n -> n);
            numbers.setAll(IntStream.range(0, 100).boxed().toList());
            Grid grid = new Grid(numbers);
            grid.scrollTo(80, 0);
            show(grid);
            assertVisible(grid, 80);

            numbers.addAll(IntStream.range(100, 10_000).boxed().toList());
            grid.scrollTo(9_999, 0);
            layout(grid);
            assertVisible(grid, 9_999);
            assertThrows(IndexOutOfBoundsException.class, () -> grid.scrollTo(10_000, 0));
        });
    }

    private static void assertVisible(Grid grid, int row) {
        Bounds b = grid.getCellBounds(row, 0);
        assertTrue(b.getMinY() >= grid.getHeaderHeight() && b.getMaxY() <= grid.getHeight(), b.toString());
    }

    @Test
    void manyModelEventsCauseOneRepaint() throws Exception {
        Fx.run(() -> {
            SyntheticModel model = new SyntheticModel(1_000, 20);
            Grid grid = show(new Grid(model));
            GridSkin skin = (GridSkin) grid.getSkin();
            int before = skin.paintCount();

            for (int i = 0; i < 10_000; i++) {
                model.update(i % 1_000, i % 20);
            }
            assertEquals(before, skin.paintCount(), "events must not paint directly");
            assertTrue(grid.isNeedsLayout(), "events must request a layout pass");

            layout(grid);
            assertEquals(before + 1, skin.paintCount());
        });
    }

    @Test
    void updatesOfRowsOutOfViewDoNotRepaint() throws Exception {
        Fx.run(() -> {
            SyntheticModel model = new SyntheticModel(1_000, 20);
            Grid grid = show(new Grid(model));
            layout(grid); // the first pass creates the scroll bar skins, which ask for one more
            assertFalse(grid.isNeedsLayout());

            model.update(500, 3);
            assertFalse(grid.isNeedsLayout());
            model.update(5, 3);
            assertTrue(grid.isNeedsLayout());
        });
    }

    @Test
    void eventsFromOtherThreadsAreRejected() throws Exception {
        SyntheticModel model = new SyntheticModel(10, 2);
        Fx.run(() -> show(new Grid(model)));

        assertThrows(IllegalStateException.class, () -> model.update(1, 1));
    }

    @Test
    void insertAboveViewKeepsVisibleRowsAndShiftsSelection() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(1_000);
            Grid grid = show(new Grid(model));
            grid.setScrollY(100 * grid.getRowHeight());
            layout(grid);
            grid.getSelectionModel().select(105, 1);

            model.add(0, new Item("new", -1));
            layout(grid);

            assertEquals(101 * grid.getRowHeight(), grid.getScrollY(), 1e-9);
            assertEquals(106, grid.getSelectionModel().getFocusedRow());
            assertEquals("item 105", model.getItem(grid.viewToModelRow(106)).name());
        });
    }

    @Test
    void selectedCellsAreVisitedOnceRowByRow() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            GridSelectionModel selection = grid.getSelectionModel();
            selection.select(3, 1);
            selection.extend(5, 1);
            selection.add(0, 0);
            selection.extend(4, 0);
            selection.add(8, 1);
            List<String> cells = new ArrayList<>();
            grid.forEachSelectedCell((row, column, value) -> cells.add(row + ":" + column));

            assertEquals(List.of("0:0", "1:0", "2:0", "3:0", "3:1", "4:0", "4:1", "5:1", "8:1"), cells);
        });
    }

    @Test
    void selectedDataInModelCoordinates() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(10);
            Grid grid = show(new Grid(model));
            grid.sort(1, false);
            grid.moveColumn(1, 0);
            GridSelectionModel selection = grid.getSelectionModel();
            selection.select(0, 0);
            selection.extend(1, 1);
            selection.add(1, 0);

            assertArrayEquals(new int[] {9, 8}, grid.getSelectedModelRows());
            assertArrayEquals(new int[] {1, 0}, grid.getSelectedModelColumns());
            List<Object> values = new ArrayList<>();
            grid.forEachSelectedCell((row, column, value) -> values.add(value));
            assertEquals(List.of(9, "item 9", 8, "item 8"), values, "overlapping ranges count once");
            assertEquals(List.of("item 9", "item 8"),
                    model.getItems(grid.getSelectedModelRows()).stream().map(Item::name).toList());
        });
    }

    @Test
    void filterAndSortCombine() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(100);
            Grid grid = show(new Grid(model));
            grid.setRowFilter(model.matching(item -> item.amount() % 10 == 0));
            grid.sort(1, false);

            assertEquals(10, grid.getViewRowCount());
            assertEquals(90, grid.getViewValueAt(0, 1));
            assertEquals(-1, grid.modelToViewRow(5));

            grid.setRowFilter(RowFilters.containsText(model, "ITEM 4"));
            assertEquals(11, grid.getViewRowCount(), "item 4 and item 40..49");
        });
    }

    @Test
    void reorderAndHideColumns() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new SyntheticModel(10, 4)));
            grid.moveColumn(0, 2);
            assertArrayEquals(new int[] {1, 2, 0, 3}, grid.columns().order());
            assertEquals(0, grid.viewToModelColumn(2));
            assertEquals(31, grid.getViewValueAt(1, 2), "row 1, model column 0");

            grid.getColumn(2).setVisible(false);
            layout(grid);
            assertEquals(3, grid.getViewColumnCount());
            assertEquals(-1, grid.modelToViewColumn(2));
            Bounds b = grid.getCellBounds(0, 1);
            Fx.assertCell(0, 1, grid.cellAt(b.getCenterX(), b.getCenterY()));
            assertEquals(0, grid.viewToModelColumn(1));

            grid.getColumn(2).setVisible(true);
            grid.setLayout(GridLayout.decode("order=0-3"));
            assertEquals(2, grid.viewToModelColumn(2));
        });
    }

    @Test
    void inlineEditingIsOptIn() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(5);
            Grid grid = show(new Grid(model));
            assertFalse(grid.editCell(1, 0), "off by default");

            grid.setEditable(true);
            CellEditor editor = ((GridSkin) grid.getSkin()).editor();
            assertTrue(grid.editCell(1, 0));
            layout(grid);
            assertEquals(4, grid.getChildrenUnmodifiable().size(), "editor node only while editing");
            editor.field().setText("renamed");
            assertTrue(editor.commit());
            assertEquals("renamed", model.getItem(1).name());
            assertEquals(3, grid.getChildrenUnmodifiable().size());
        });
    }

    @Test
    void cutOffTextFeedsTooltipAndAutoSize() throws Exception {
        Fx.run(() -> {
            String longText = "a rather long text that does not fit into a hundred pixels";
            Grid grid = show(fixedWidths(new Grid(new Fx.ArrayModel(new Object[][] {{longText, "short"}}))));
            GridSkin skin = (GridSkin) grid.getSkin();

            assertEquals(longText, skin.cutOffText(0, 0));
            assertNull(skin.cutOffText(0, 1));

            grid.getColumn(0).fitToContent();
            layout(grid);
            assertTrue(grid.getColumn(0).getWidth() > Grid.DEFAULT_COLUMN_WIDTH);
            assertNull(skin.cutOffText(0, 0), "fits after auto-size");
        });
    }

    @Test
    void shrinksWithItsContainer() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(new SyntheticModel(1_000, 50));
            javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(grid);
            new Scene(root);
            root.resize(900, 700);
            root.applyCss();
            root.layout();
            root.resize(300, 200);
            root.layout();

            assertEquals(300, grid.getWidth(), 1e-9);
            assertEquals(200, grid.getHeight(), 1e-9);
            assertTrue(grid.minWidth(-1) < 100, "minimum size does not depend on the canvas");
        });
    }
}
