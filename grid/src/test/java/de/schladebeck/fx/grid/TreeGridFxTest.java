package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.click;
import static de.schladebeck.fx.grid.Fx.key;
import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.TreeGridModel;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TreeGridFxTest {

    record Item(String name, List<Item> children) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static Item item(String name, Item... children) {
        return new Item(name, List.of(children));
    }

    private static TreeGridModel<Item> tree() {
        TreeGridModel<Item> model = new TreeGridModel<Item>(Item::children).column("Name", Item::name)
                .column("Size", item -> item.name().length());
        model.setRoots(List.of(item("Root", item("Kid1", item("Leaf")), item("Kid2")), item("Last")));
        return model;
    }

    private static double step(Grid grid) {
        return Math.round(grid.getFont().getSize() * 1.4);
    }

    @Test
    void openingAnItemWidensTheTreeColumnForItsChildren() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = new TreeGridModel<Item>(Item::children).column("Name", Item::name);
            model.setRoots(List.of(item("Root", item("A child with a much longer name than its parent"))));
            Grid grid = show(new TreeGrid(model));
            double before = grid.getColumn(0).getWidth();
            grid.getSelectionModel().select(0, 0);
            key(grid, KeyCode.RIGHT);
            layout(grid);

            assertEquals(2, grid.getViewRowCount());
            assertTrue(grid.getColumn(0).getWidth() > before + 50, before + " -> " + grid.getColumn(0).getWidth());
        });
    }

    @Test
    void clickingTheTriangleOrDoubleClickingExpandsAndCollapses() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree();
            Grid grid = show(new TreeGrid(model));
            Bounds root = grid.getCellBounds(0, 0);
            click(grid, root.getMinX() + grid.getCellPadding() + step(grid) / 2, root.getCenterY(), 1);
            layout(grid);
            assertEquals(4, grid.getViewRowCount());
            assertEquals(-1, grid.getSelectionModel().getFocusedRow(), "the triangle does not select");

            Bounds kid = grid.getCellBounds(1, 0);
            click(grid, kid.getMaxX() - 5, kid.getCenterY(), 2);
            layout(grid);
            assertEquals(5, grid.getViewRowCount());
            assertEquals("Leaf", grid.getViewValueAt(2, 0));
        });
    }

    @Test
    void rightExpandsLeftCollapsesOrGoesToTheParent() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree();
            Grid grid = show(new TreeGrid(model));
            grid.getSelectionModel().select(0, 0);

            key(grid, KeyCode.RIGHT);
            assertEquals(4, grid.getViewRowCount());
            key(grid, KeyCode.RIGHT);
            assertEquals(1, grid.getSelectionModel().getFocusedColumn(), "expanded: Right moves on");
            key(grid, KeyCode.LEFT);
            key(grid, KeyCode.DOWN);
            key(grid, KeyCode.LEFT);
            assertEquals(0, grid.getSelectionModel().getFocusedRow(), "from Kid1 to its parent");
            key(grid, KeyCode.LEFT);
            assertEquals(2, grid.getViewRowCount());
        });
    }

    @Test
    void theTreeColumnIsIndentedAndFittedWithIt() throws Exception {
        Fx.run(() -> {
            String name = "A name wider than its header";
            TreeGridModel<Item> model = new TreeGridModel<Item>(Item::children).column("Name", Item::name);
            model.setRoots(List.of(item(name, item(name, item(name)))));
            List<Double> starts = new ArrayList<>();
            Grid grid = new TreeGrid(model);
            grid.getColumn(0).setRenderer((gc, cell) -> {
                starts.add(cell.getX());
                gc.fillText(cell.fitText(String.valueOf(cell.getValue())), cell.getX() + cell.getPadding(),
                        cell.getY() + cell.getHeight() / 2);
            });
            show(grid);
            grid.getColumn(0).fitToContent();
            double collapsed = grid.getColumn(0).getWidth();

            model.setExpanded(0, true);
            model.setExpanded(1, true);
            layout(grid);
            grid.getColumn(0).fitToContent();
            starts.clear();
            layout(grid);
            assertEquals(collapsed + 2 * step(grid), grid.getColumn(0).getWidth(), 1, "two levels deeper");
            double cellStart = grid.getCellBounds(0, 0).getMinX() - grid.getInsets().getLeft();
            double deepest = cellStart + 3 * step(grid);
            assertTrue(starts.stream().anyMatch(x -> Math.abs(x - deepest) < 1e-6), deepest + " in " + starts);
        });
    }

    @Test
    void plusAndMinusExpandAndCollapseAndRightGoesToTheFirstChildInRowMode() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree();
            Grid grid = show(new TreeGrid(model));
            grid.getSelectionModel().setMode(SelectionMode.ROW);
            grid.getSelectionModel().select(0, 0);
            key(grid, KeyCode.ADD);
            assertEquals(4, grid.getViewRowCount());
            key(grid, KeyCode.RIGHT);
            assertEquals(1, grid.getSelectionModel().getFocusedRow(), "Right on an expanded item: its first child");
            key(grid, KeyCode.LEFT);
            key(grid, KeyCode.SUBTRACT);
            assertEquals(2, grid.getViewRowCount());
        });
    }

    @Test
    void aDoubleClickRunsTheCellActionWhenThereIsOne() throws Exception {
        Fx.run(() -> {
            TreeGridModel<Item> model = tree();
            Grid grid = show(new TreeGrid(model));
            List<GridCell> actions = new ArrayList<>();
            grid.setOnCellAction(actions::add);
            Bounds root = grid.getCellBounds(0, 0);
            click(grid, root.getMaxX() - 5, root.getCenterY(), 2);
            assertEquals(2, grid.getViewRowCount(), "not expanded");
            assertEquals(1, actions.size());
        });
    }

    @Test
    void aHasChildrenTestSparesLoadingChildrenForTheTriangles() throws Exception {
        Fx.run(() -> {
            List<String> loaded = new ArrayList<>();
            TreeGridModel<Item> model = new TreeGridModel<Item>(item -> {
                loaded.add(item.name());
                return item.children();
            }).column("Name", Item::name).hasChildren(item -> !item.children().isEmpty());
            model.setRoots(List.of(item("Root", item("Kid")), item("Last")));
            show(new TreeGrid(model));
            assertEquals(List.of(), loaded);
            assertTrue(!model.isLeaf(0) && model.isLeaf(1));
        });
    }
}
