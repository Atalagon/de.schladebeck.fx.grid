package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javafx.geometry.Bounds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FrozenRowsFxTest {

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

    /** A grid of 1,000 rows with two frozen rows, scrolled down by 500 rows. */
    private static Grid scrolled(ListGridModel<Item> model) {
        Grid grid = show(new Grid(model));
        grid.setFrozenRowCount(2);
        grid.setScrollY(500 * grid.getRowHeight());
        layout(grid);
        return grid;
    }

    @Test
    void frozenRowsStayBelowTheHeaderWhileTheOthersScroll() throws Exception {
        Fx.run(() -> {
            Grid grid = scrolled(items(1_000));
            double x = 10;
            double header = grid.getHeaderHeight();
            double row = grid.getRowHeight();

            assertEquals(0, grid.cellAt(x, header + 1).viewRow());
            assertEquals(1, grid.cellAt(x, header + row + 1).viewRow());
            assertEquals(502, grid.cellAt(x, header + 2 * row + 1).viewRow(), "the scrolled rows start below them");
            assertEquals(grid.getCellBounds(0, 0).getMinY() + row, grid.getCellBounds(1, 0).getMinY(), 1e-9);
        });
    }

    @Test
    void scrollingToARowKeepsItOutOfTheFrozenRows() throws Exception {
        Fx.run(() -> {
            Grid grid = scrolled(items(1_000));
            double before = grid.getScrollY();
            grid.scrollTo(1, 0);
            layout(grid);
            assertEquals(before, grid.getScrollY(), "a frozen row is always visible");

            grid.scrollTo(300, 0);
            layout(grid);
            Bounds bounds = grid.getCellBounds(300, 0);
            assertEquals(grid.getCellBounds(0, 0).getMinY() + 2 * grid.getRowHeight(), bounds.getMinY(), 1e-9,
                    "right below the frozen rows");
        });
    }

    @Test
    void frozenRowsArePaintedOverTheRowsScrolledUnderThem() throws Exception {
        Fx.run(() -> {
            List<Integer> painted = new ArrayList<>();
            Grid grid = scrolled(items(1_000));
            grid.getColumn(0).setRenderer((gc, cell) -> {
                painted.add(cell.getViewRow());
                assertEquals(grid.getRowHeight() - 1, cell.getHeight(), 1e-9, "row " + cell.getViewRow());
                CellRenderers.TEXT.paint(gc, cell);
            });
            layout(grid);

            assertEquals(List.of(0, 1), painted.subList(painted.size() - 2, painted.size()));
            assertTrue(painted.contains(502));
        });
    }

    @Test
    void aChangedFrozenRowIsPaintedWhileScrolledAway() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(1_000);
            Grid grid = scrolled(model);
            GridSkin skin = (GridSkin) grid.getSkin();
            int paints = skin.paintCount();
            model.set(1, new Item("changed", 1));
            layout(grid);
            assertTrue(skin.paintCount() > paints);
        });
    }

    @Test
    void fittingTheVisibleColumnsSeesTheFrozenRows() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(1_000);
            model.set(0, new Item("a name much longer than any other in the column", 0));
            Grid grid = scrolled(model);
            grid.getColumn(0).setWidth(60);
            grid.fitVisibleColumns();
            layout(grid);
            assertTrue(grid.getColumn(0).getWidth() > 200, "width " + grid.getColumn(0).getWidth());
        });
    }

    @Test
    void theLayoutKeepsTheFrozenRows() throws Exception {
        Fx.run(() -> {
            Grid grid = scrolled(items(1_000));
            String saved = grid.getLayout().encode();
            assertTrue(saved.contains("frozenRows=2"), saved);

            grid.setFrozenRowCount(-3);
            assertEquals(0, grid.getFrozenRowCount(), "negative counts become 0");
            grid.setLayout(GridLayout.decode(saved));
            assertEquals(2, grid.getFrozenRowCount());
        });
    }
}
