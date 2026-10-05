package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.internal.TextFitter;
import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.text.Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Data from anywhere, row names, row heights, wrapping, auto-fit, layouts, renderers per cell, accessibility. */
class DataSourcesFxTest {

    record Item(String name, String notes, int amount) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static ListGridModel<Item> items(int count) {
        ListGridModel<Item> model = new ListGridModel<Item>()
                .column("Name", Item::name)
                .column("Notes", Item::notes)
                .column("Amount", Item::amount);
        model.setAll(IntStream.range(0, count)
                .mapToObj(i -> new Item("item " + i, "a note that is a good deal longer than the name " + i, i))
                .toList());
        return model;
    }

    @Test
    void computedModelResizesFromAnyThread() throws Exception {
        ComputedGridModel model = Fx.call(() -> new ComputedGridModel(10, 3, (row, column) -> row * column)
                .rowNames(row -> "row " + row));
        Grid grid = Fx.call(() -> show(new Grid(model)));

        Thread resizer = new Thread(() -> model.setSize(1_000, 3));
        resizer.start();
        resizer.join();

        assertEquals(1_000, (int) Fx.call(grid::getViewRowCount));
        assertEquals(998, Fx.call(() -> grid.getViewValueAt(499, 2)));
        assertEquals("row 7", Fx.call(() -> model.getRowName(7)));
    }

    @Test
    void rowNamesWidenTheRowHeaderAndShowTooltips() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(20).rowNames(Item::name);
            Grid grid = show(new Grid(model));
            grid.setRowHeaderVisible(true);
            layout(grid);
            double named = ((GridSkin) grid.getSkin()).geometry().rowHeaderWidth();

            model.rowNames(item -> null);
            layout(grid);
            assertTrue(named > ((GridSkin) grid.getSkin()).geometry().rowHeaderWidth(), "names are wider than numbers");

            model.rowNames(item -> "a row name far too long for the row header width");
            grid.setRowHeaderWidth(60);
            layout(grid);
            assertEquals("a row name far too long for the row header width",
                    ((GridSkin) grid.getSkin()).cutOffText(0, -1));
        });
    }

    @Test
    void rowsGetTheirOwnHeights() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(100)));
            grid.setRowHeightProvider(row -> row % 10 == 5 ? 60 : 0);
            layout(grid);

            assertEquals(60, grid.getCellBounds(5, 0).getHeight(), 1e-9);
            assertEquals(grid.getRowHeight(), grid.getCellBounds(6, 0).getHeight(), 1e-9);
            assertEquals(grid.getCellBounds(5, 0).getMaxY(), grid.getCellBounds(6, 0).getMinY(), 1e-9);
            for (int row : new int[] {4, 5, 6, 15}) {
                Bounds b = grid.getCellBounds(row, 1);
                Fx.assertCell(row, 1, grid.cellAt(b.getCenterX(), b.getCenterY()));
            }

            grid.sort(2, false);
            layout(grid);
            assertEquals(60, grid.getCellBounds(4, 0).getHeight(), 1e-9, "model row 95 is now view row 4");
        });
    }

    @Test
    void textWrapsAtSpacesLineBreaksAndLongWords() throws Exception {
        Fx.run(() -> {
            TextFitter fitter = new TextFitter();
            Font font = Font.font(13);
            double width = fitter.width("aaaa bbbb", font) + 1;

            assertEquals(List.of("aaaa bbbb", "cccc"), fitter.wrap("aaaa bbbb cccc", font, width, 10));
            assertEquals(List.of("one", "two"), fitter.wrap("one\ntwo", font, width, 10));
            assertTrue(fitter.wrap("x".repeat(30), font, width, 10).size() > 1, "long words break");
            List<String> cut = fitter.wrap("aaaa bbbb cccc dddd eeee", font, width, 2);
            assertEquals(2, cut.size());
            assertTrue(cut.getLast().endsWith(TextFitter.ELLIPSIS), cut.toString());
            assertEquals(3, TextMetrics.lineCount("aaaa bbbb cccc dddd eeee", font, width));
        });
    }

    @Test
    void columnsFitTheirContentOnceDataArrives() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = new ListGridModel<Item>()
                    .column("Name", Item::name)
                    .column("Notes", Item::notes)
                    .column("Amount", Item::amount);
            Grid grid = show(new Grid(model));
            grid.getColumn(2).setWidth(77);
            model.setAll(items(10).getItems());
            layout(grid);

            assertTrue(grid.getColumn(1).getWidth() > grid.getColumn(0).getWidth(), "notes are longer than names");
            assertTrue(grid.getColumn(1).getWidth() <= Grid.MAX_AUTO_FIT_WIDTH);
            assertEquals(77, grid.getColumn(2).getWidth(), "an explicit width is kept");

            double fitted = grid.getColumn(0).getWidth();
            model.add(new Item("a much much longer name than any before", "", 0));
            layout(grid);
            assertEquals(fitted, grid.getColumn(0).getWidth(), "fitting happens once, widths stay stable");
        });
    }

    @Test
    void layoutSurvivesARoundTrip() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            grid.moveColumn(2, 0);
            grid.getColumn(1).setVisible(false);
            grid.getColumn(0).setWidth(150);
            grid.sort(2, false);
            grid.setFrozenColumnCount(1);
            String saved = grid.getLayout().encode();

            Grid other = show(new Grid(items(10)));
            other.setLayout(GridLayout.decode(saved));

            assertEquals(grid.getLayout(), other.getLayout());
            assertEquals(2, other.viewToModelColumn(0));
            assertEquals(2, other.getViewColumnCount());
            assertEquals(9, other.getViewValueAt(0, 0));
        });
    }

    @Test
    void rendererProviderDecidesPerCell() throws Exception {
        Fx.run(() -> {
            int[] calls = {0};
            CellRenderer counting = (gc, cell) -> calls[0]++;
            Grid grid = new Grid(items(10));
            grid.setAutoFitColumns(false); // fitting runs the renderers too
            grid.setCellRendererProvider((row, column, value) -> row == 3 ? counting : null);
            show(grid);

            assertEquals(3, calls[0], "one call per column of row 3");
        });
    }

    @Test
    void aDefaultRendererPaintsTheColumnsOfItsClassUnlessTheyHaveTheirOwn() throws Exception {
        Fx.run(() -> {
            Set<Integer> painted = new TreeSet<>();
            Class<?>[] types = {String.class, Integer.class, int.class, Double.class};
            Grid grid = new Grid(new ComputedGridModel(3, 4, (row, column) -> column == 0 ? "x" : row)
                    .columnTypes(column -> types[column]));
            grid.setAutoFitColumns(false); // fitting runs the renderers too
            show(grid);

            grid.setDefaultRenderer(Number.class, (gc, cell) -> painted.add(cell.getModelColumn()));
            layout(grid);
            assertEquals(Set.of(1, 2, 3), painted, "subclasses, and int counts as Integer");

            painted.clear();
            grid.getColumn(3).setRenderer(CellRenderers.TEXT);
            layout(grid);
            assertEquals(Set.of(1, 2), painted, "the column's own renderer wins");
        });
    }

    @Test
    void inColumnsOfMixedTypesEachValueGetsTheDefaultRendererOfItsNearestType() throws Exception {
        Fx.run(() -> {
            Set<String> painted = new TreeSet<>();
            Grid grid = new Grid(new Fx.ArrayModel(new Object[][] {{7}, {2.5}, {"text"}, {true}}));
            grid.setAutoFitColumns(false);
            grid.setDefaultRenderer(Integer.class, (gc, cell) -> painted.add("Integer " + cell.getValue()));
            grid.setDefaultRenderer(Number.class, (gc, cell) -> painted.add("Number " + cell.getValue()));
            grid.setDefaultRenderer(CharSequence.class, (gc, cell) -> painted.add("CharSequence " + cell.getValue()));
            show(grid);

            assertEquals(Set.of("Integer 7", "Number 2.5", "CharSequence text"), painted,
                    "the class itself, then a superclass, then an interface; booleans stay check boxes");
        });
    }

    @Test
    void aPrototypeValueMakesRoomForLargeValuesBeforeTheyArrive() throws Exception {
        Fx.run(() -> {
            long prototype = 1_234_567_890_123L;
            List<Integer> prototypeRows = new ArrayList<>();
            ListGridModel<Long> ids = new ListGridModel<Long>().column("Id", id -> id);
            ids.setAll(LongStream.rangeClosed(1, 10).boxed().toList());
            Grid grid = new Grid(ids);
            grid.getColumn(0).setPrototypeValue(prototype).setRenderer((gc, cell) -> {
                if (Long.valueOf(prototype).equals(cell.getValue())) {
                    prototypeRows.add(cell.getViewRow());
                }
                CellRenderers.NUMBER.paint(gc, cell);
            });
            show(grid);
            ids.add(9_876_543_210_987L);
            layout(grid);

            assertEquals(prototype, grid.getColumn(0).getPrototypeValue());
            assertTrue(grid.getColumn(0).getWidth() >= TextMetrics.width("9876543210987", grid.getFont())
                    + 2 * grid.getCellPadding(), "the large id that came later fits");
            assertFalse(prototypeRows.isEmpty(), "measured");
            assertEquals(Set.of(-1), Set.copyOf(prototypeRows), "never shown in a row");
        });
    }

    @Test
    void fitToContentMeasuresThePrototypeValueUntilItIsSetToNull() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(10));
            grid.setAutoFitColumns(false);
            show(grid);
            GridColumn amount = grid.getColumn(2);

            double withPrototype = amount.setPrototypeValue(1_234_567_890_123L).fitToContent().getWidth();
            double without = amount.setPrototypeValue(null).fitToContent().getWidth();

            assertTrue(withPrototype >= TextMetrics.width("1234567890123", grid.getFont())
                    + 2 * grid.getCellPadding(), "wide enough for the prototype value");
            assertTrue(without < withPrototype, without + " < " + withPrototype);
            assertNull(amount.getPrototypeValue());
        });
    }

    @Test
    void screenReadersSeeTheFocusedCell() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(10)));
            int nodes = grid.getChildrenUnmodifiable().size();
            assertEquals(10, grid.queryAccessibleAttribute(AccessibleAttribute.ROW_COUNT));
            assertEquals(3, grid.queryAccessibleAttribute(AccessibleAttribute.COLUMN_COUNT));
            assertNull(grid.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM), "nothing focused yet");
            assertEquals(nodes, grid.getChildrenUnmodifiable().size(), "no placeholders without a screen reader");

            grid.getSelectionModel().select(4, 0);
            Node cell = (Node) grid.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM);
            assertNotNull(cell);
            assertEquals(4, cell.queryAccessibleAttribute(AccessibleAttribute.ROW_INDEX));
            assertEquals(0, cell.queryAccessibleAttribute(AccessibleAttribute.COLUMN_INDEX));
            assertEquals("item 4", cell.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            assertEquals(true, cell.queryAccessibleAttribute(AccessibleAttribute.SELECTED));
            assertEquals(cell, grid.queryAccessibleAttribute(AccessibleAttribute.CELL_AT_ROW_COLUMN, 4, 0));
        });
    }
}
