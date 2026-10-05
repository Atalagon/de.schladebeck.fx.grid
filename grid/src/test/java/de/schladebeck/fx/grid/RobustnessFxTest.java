package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.center;
import static de.schladebeck.fx.grid.Fx.click;
import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What the grid does when code it calls fails, data shrinks under it, or it gets impossible input. */
class RobustnessFxTest {

    public record Item(String name, int amount) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static ListGridModel<Item> items(int count) {
        return ListGridModel.ofRecords(Item.class,
                IntStream.range(0, count).mapToObj(i -> new Item("item " + i, i)).toList());
    }

    private static int paints(Grid grid) {
        return ((GridSkin) grid.getSkin()).paintCount();
    }

    @Test
    void aThrowingRendererLeavesOnlyItsCellEmpty() throws Exception {
        Fx.run(() -> {
            AtomicInteger painted = new AtomicInteger();
            Grid grid = new Grid(items(100));
            grid.getColumn(0).setRenderer((gc, cell) -> {
                if (cell.getModelRow() == 1) {
                    throw new IllegalStateException("broken renderer");
                }
                painted.incrementAndGet();
            });
            show(grid);
            assertTrue(painted.get() > 10, "the other cells are painted");

            int before = paints(grid);
            grid.setScrollY(100);
            layout(grid);
            assertEquals(before + 1, paints(grid), "the grid goes on painting");
        });
    }

    @Test
    void aThrowingStyleProviderOrModelDoesNotStopPainting() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(new ComputedGridModel(100, 3, (row, column) -> {
                if (row == 2) {
                    throw new IllegalArgumentException("broken model");
                }
                return row;
            }));
            grid.setCellStyleProvider((row, column, value) -> {
                throw new IllegalStateException("broken style provider");
            });
            show(grid);
            int before = paints(grid);
            grid.setScrollY(50);
            layout(grid);
            assertEquals(before + 1, paints(grid));
        });
    }

    @Test
    void impossibleNumbersAreIgnored() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(100)));
            grid.setLayout(GridLayout.decode("zoom=NaN;widths=0:NaN,1:-5"));
            grid.setZoom(Double.NaN);
            grid.setRowHeightProvider(row -> row == 3 ? Double.POSITIVE_INFINITY : row == 4 ? Double.NaN : 0);
            layout(grid);

            assertEquals(1, grid.effectiveZoom());
            assertEquals(grid.getRowHeight(), grid.getCellBounds(4, 0).getHeight(), 1e-9);
            assertTrue(Double.isFinite(grid.getCellBounds(50, 0).getMinY()));
            assertTrue(grid.getColumn(0).getWidth() > 0);
        });
    }

    @Test
    void theSelectionFitsWhenRowsShrink() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(10);
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(8, 0);
            grid.getSelectionModel().extend(9, 1);
            model.setAll(items(3).getItems());

            assertTrue(grid.getSelectionModel().getRanges().stream().allMatch(r -> r.lastRow() <= 2));
            assertTrue(grid.getSelectionModel().getFocusedRow() <= 2);
            grid.forEachSelectedCell((row, column, value) -> assertTrue(row < 3));

            model.setAll(items(10).getItems());
            grid.getSelectionModel().select(9, 0);
            model.removeRows(5, 6, 7, 8, 9);
            assertTrue(grid.getSelectionModel().getFocusedRow() <= 4);
        });
    }

    @Test
    void clicksOnRowsDeletedSinceTheLastLayoutAreIgnored() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(10);
            Grid grid = show(new Grid(model));
            Point2D gone = center(grid, 9, 0);
            grid.getSelectionModel().select(1, 0);
            model.removeRows(5, 6, 7, 8, 9);

            assertNull(grid.cellAt(gone.getX(), gone.getY()));
            click(grid, gone.getX(), gone.getY(), 1);
            assertEquals(1, grid.getSelectionModel().getFocusedRow());
        });
    }

    @Test
    void aNewModelStartsInNaturalOrderWithoutFilter() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            grid.moveColumn(0, 1);
            grid.setRowFilter(row -> row % 2 == 0);
            grid.setModel(items(30));

            assertTrue(grid.getLayout().columnOrder().isEmpty());
            assertNull(grid.getRowFilter());
            assertEquals(30, grid.getViewRowCount());
        });
    }

    @Test
    void eventsOfAModelTheGridLeftAreIgnored() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> first = items(10);
            ListGridModel<Item> second = items(10);
            Grid grid = new Grid();
            first.addGridModelListener(event -> grid.setModel(second));
            grid.setModel(first);
            show(grid);
            first.add(0, new Item("new", 0));

            assertEquals(second, grid.getModel());
            grid.getSelectionModel().select(1, 0);
            first.add(0, new Item("newer", 0));
            assertEquals(1, grid.getSelectionModel().getFocusedRow(), "the old model's insert moved nothing");
        });
    }

    @Test
    void wrongIndicesAreRejectedWithClearMessages() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            String message = assertThrows(IndexOutOfBoundsException.class,
                    () -> grid.getSelectionModel().select(1_000, 0)).getMessage();
            assertTrue(message.contains("10 rows"), message);
            assertThrows(IndexOutOfBoundsException.class, () -> grid.sort(50, true));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.moveColumn(0, 50));
            assertThrows(IllegalStateException.class, () -> new Grid().getViewValueAt(0, 0));
            assertThrows(IllegalArgumentException.class, () -> grid.getColumn("no such column"));

            grid.setLayout(GridLayout.decode("frozen=99;zoom=12"));
            assertEquals(2, grid.getFrozenColumnCount());
            assertEquals(Grid.MAX_ZOOM, grid.getZoom());
        });
    }

    @Test
    void sparseModelsAreFittedOnce() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new ComputedGridModel(100, 5, (row, column) -> null)));
            assertFalse(grid.needsAutoFit(), "no values to wait for");
        });
    }

    @Test
    void thePlaceholderShowsWhileThereAreNoRows() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(0);
            Label nothing = new Label("Nothing here");
            Grid grid = new Grid(model);
            grid.setPlaceholder(nothing);
            show(grid);
            assertTrue(grid.getChildrenUnmodifiable().contains(nothing));

            model.add(new Item("a", 1));
            layout(grid);
            assertFalse(grid.getChildrenUnmodifiable().contains(nothing));

            grid.setPlaceholder(null);
            model.remove(0);
            layout(grid);
            assertTrue(grid.getChildrenUnmodifiable().stream()
                    .anyMatch(node -> node instanceof Label label && label.getStyleClass().contains("placeholder")),
                    "without a placeholder of its own the grid says it has no rows");
        });
    }

    @Test
    void anUpdateAsksTheRowHeightProviderOnlyForItsRows() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(10_000);
            AtomicInteger asked = new AtomicInteger();
            Grid grid = new Grid(model);
            grid.setRowHeightProvider(row -> {
                asked.incrementAndGet();
                return row % 2 == 0 ? 40 : 0;
            });
            show(grid);
            asked.set(0);
            model.set(7, new Item("changed", 7));
            layout(grid);
            assertEquals(1, asked.get());
        });
    }

    @Test
    void insertedAndDeletedRowsMoveTheRowHeightsAlong() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(10_000);
            AtomicInteger asked = new AtomicInteger();
            RowHeightProvider tallEveryThird = row -> model.getItem(row).amount() % 3 == 0 ? 50 : 0;
            Grid grid = new Grid(model);
            grid.setRowHeightProvider(row -> {
                asked.incrementAndGet();
                return tallEveryThird.heightOf(row);
            });
            show(grid);
            for (boolean sorted : new boolean[] {false, true}) {
                if (sorted) {
                    grid.sort(1, false);
                    layout(grid);
                }
                asked.set(0);
                model.addAll(0, List.of(new Item("a", 3), new Item("b", 4)));
                model.add(5_000, new Item("c", 9));
                model.removeRows(IntStream.range(20, 30).toArray());
                layout(grid);
                assertEquals(3, asked.get(), "only the new rows are measured");

                Grid fresh = new Grid(model);
                fresh.setRowHeightProvider(tallEveryThird);
                fresh.sort(grid.getSortKeys());
                show(fresh);
                for (int row = 0; row < model.getRowCount(); row += 97) {
                    assertEquals(fresh.getCellBounds(row, 0).getMinY() + fresh.getScrollY(),
                            grid.getCellBounds(row, 0).getMinY() + grid.getScrollY(), "row " + row);
                }
            }
        });
    }

    @Test
    void rowsInsertedAboveKeepTheTopRowInPlace() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(1_000);
            Grid grid = new Grid(model);
            grid.setRowHeightProvider(row -> model.getItem(row).amount() % 3 == 0 ? 50 : 0);
            show(grid);
            grid.scrollTo(300, 0);
            layout(grid);
            GridCell top = grid.cellAt(5, grid.getHeaderHeight() + 2);

            model.addAll(0, List.of(new Item("a", 1), new Item("b", 3), new Item("c", 6)));
            layout(grid);
            GridCell after = grid.cellAt(5, grid.getHeaderHeight() + 2);
            assertEquals(top.value(), after.value(), "the same item stays at the top");
            assertNotEquals(top.modelRow(), after.modelRow());
        });
    }

    @Test
    void theFocusedModelRowCanBeFollowedAndSelected() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(100)));
            grid.sort(1, false);
            assertTrue(grid.selectModelRow(10));
            assertEquals(10, grid.getFocusedModelRow());
            assertEquals(89, grid.getSelectionModel().getFocusedRow());
            grid.setRowFilter(row -> row != 10);
            assertFalse(grid.selectModelRow(10), "filtered out");

            assertEquals(1, grid.getColumn("Amount").getModelIndex());
            grid.moveColumn(0, 1);
            assertEquals(grid.getColumn(0), grid.getColumnAtView(1));
        });
    }
}
