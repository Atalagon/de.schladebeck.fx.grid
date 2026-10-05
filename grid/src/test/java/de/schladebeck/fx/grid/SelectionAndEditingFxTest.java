package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.StringConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The selection stays on its rows while the view changes; the editor stays on its cell and keeps what was typed. */
class SelectionAndEditingFxTest {

    public enum Level { LOW, MEDIUM, HIGH }

    public record Item(String name, int amount, Level level) {
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    private static List<Item> list(int count) {
        return IntStream.range(0, count).mapToObj(i -> new Item("item " + i, i, Level.values()[i % 3])).toList();
    }

    private static ListGridModel<Item> editable(AtomicInteger writes) {
        ListGridModel<Item> model = new ListGridModel<Item>()
                .column("Name", Item::name, (item, name) -> {
                    writes.incrementAndGet();
                    return new Item(name, item.amount(), item.level());
                })
                .column("Amount", Item::amount, (item, amount) -> {
                    if (amount > 1_000) {
                        throw new IllegalArgumentException("at most 1000");
                    }
                    return new Item(item.name(), amount, item.level());
                })
                .column("Level", Item::level, (item, level) -> new Item(item.name(), item.amount(), level));
        model.setAll(list(20));
        return model;
    }

    private static String focusedName(Grid grid, ListGridModel<Item> model) {
        return model.getItem(grid.getFocusedModelRow()).name();
    }

    // ---- selection

    @Test
    void sortingKeepsTheSelectionOnItsRows() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = ListGridModel.ofRecords(Item.class, list(50));
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(5, 0);
            grid.getSelectionModel().extend(6, 1);
            grid.sort(1, false);

            assertEquals("item 6", focusedName(grid, model), "the focus stays on its row too");
            assertEquals(List.of(5, 6), Arrays.stream(grid.getSelectedModelRows()).sorted().boxed().toList());
            assertEquals(1, grid.getSelectionModel().getRanges().size(), "rows 6 and 5 are still next to each other");
        });
    }

    @Test
    void insertsIntoASortedViewKeepTheSelection() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = ListGridModel.ofRecords(Item.class, list(50));
            Grid grid = show(new Grid(model));
            grid.sort(1, false);
            grid.selectModelRow(10);
            model.add(0, new Item("new", 100, Level.LOW));
            model.add(new Item("other", -1, Level.LOW));

            assertEquals("item 10", focusedName(grid, model));
            assertEquals(List.of(11), Arrays.stream(grid.getSelectedModelRows()).boxed().toList());
        });
    }

    @Test
    void aSelectionTooLargeToRemapMovesWithSingleRowsInASortedView() throws Exception {
        Fx.run(() -> {
            int rows = Grid.MAX_REMAPPED_ROWS + 100;
            ListGridModel<Item> model = ListGridModel.ofRecords(Item.class, list(rows));
            Grid grid = show(new Grid(model));
            grid.sort(1, false);
            grid.getSelectionModel().selectRange(new CellRange(10, 0, rows - 11, 2));
            model.add(new Item("first", rows, Level.LOW));
            model.add(new Item("inside", rows / 2, Level.LOW));
            model.remove(rows - 20);

            assertEquals(List.of(new CellRange(11, 0, rows - 10, 2)), grid.getSelectionModel().getRanges(),
                    "moved down by the first row, grown by the one inside, shrunk by the deleted one");
        });
    }

    @Test
    void filteringKeepsTheRowsThatAreStillShown() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = ListGridModel.ofRecords(Item.class, list(50));
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().select(2, 0);
            grid.getSelectionModel().extend(5, 0);
            grid.setRowFilter(row -> row % 2 == 0);

            assertEquals(List.of(2, 4), Arrays.stream(grid.getSelectedModelRows()).boxed().toList());
        });
    }

    @Test
    void wholeColumnsGrowOverInsertedRows() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = ListGridModel.ofRecords(Item.class, list(10));
            Grid grid = show(new Grid(model));
            grid.getSelectionModel().selectColumn(1, false);
            model.add(0, new Item("new", 0, Level.LOW));
            model.add(new Item("last", 0, Level.LOW));

            assertEquals(List.of(new CellRange(0, 1, 11, 1)), grid.getSelectionModel().getRanges());
        });
    }

    // ---- editing

    @Test
    void theEditorFollowsItsRowAndStopsWhenItIsGone() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = editable(new AtomicInteger());
            Grid grid = show(new Grid(model));
            grid.setEditable(true);
            CellEditor editor = ((GridSkin) grid.getSkin()).editor();

            assertTrue(grid.editCell(5, 0));
            model.add(0, new Item("pushed in", 0, Level.LOW));
            editor.field().setText("edited");
            assertTrue(editor.commit());
            assertEquals("edited", model.getItem(6).name(), "the item that was edited, now one row lower");
            assertEquals("item 4", model.getItem(5).name());

            assertTrue(grid.editCell(3, 0));
            model.remove(3);
            layout(grid);
            assertFalse(editor.isEditing(), "the edited row was deleted");

            assertTrue(grid.editCell(3, 0));
            grid.setModel(editable(new AtomicInteger()));
            assertFalse(editor.isEditing(), "another model");
        });
    }

    @Test
    void f2AndTabWhileEditing() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = editable(new AtomicInteger());
            Grid grid = show(new Grid(model));
            grid.setEditable(true);
            CellEditor editor = ((GridSkin) grid.getSkin()).editor();
            grid.getSelectionModel().select(2, 0);
            assertTrue(grid.editCell(2, 0));
            editor.field().setText("typed");

            Event.fireEvent(editor.field(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2,
                    false, false, false, false));
            assertEquals("typed", editor.field().getText(), "F2 keeps what was typed");

            Event.fireEvent(editor.field(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                    false, false, false, false));
            assertEquals("typed", model.getItem(2).name());
            assertEquals(1, grid.getSelectionModel().getFocusedColumn(), "Tab moves to the next cell");
            assertTrue(editor.isEditing(), "and edits it");
            assertEquals("2", editor.field().getText());
        });
    }

    @Test
    void typingOnACellEditsItStartingWithTheTypedText() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = editable(new AtomicInteger());
            Grid grid = show(new Grid(model));
            CellEditor editor = ((GridSkin) grid.getSkin()).editor();
            grid.getSelectionModel().select(3, 1);
            Fx.type(grid, "7");
            assertFalse(editor.isEditing(), "not editable");

            grid.setEditable(true);
            Fx.type(grid, "7");
            assertTrue(editor.isEditing());
            assertEquals("7", editor.field().getText());
            editor.field().setText("x");
            assertFalse(editor.commit());
            assertTrue(editor.problem().getText().contains("3"), "the old value shows what is valid");
        });
    }

    @Test
    void invalidOrRefusedInputStaysInTheEditor() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = editable(new AtomicInteger());
            Grid grid = show(new Grid(model));
            grid.setEditable(true);
            CellEditor editor = ((GridSkin) grid.getSkin()).editor();

            grid.getSelectionModel().select(1, 1);
            assertTrue(grid.editCell(1, 1));
            editor.field().setText("12x");
            assertFalse(editor.commit());
            assertTrue(editor.isEditing());
            assertEquals("12x", editor.field().getText());

            editor.field().setText("5000");
            assertFalse(editor.commit(), "the model refuses it");
            assertTrue(editor.isEditing());
            assertEquals("5000", editor.field().getText());

            editor.field().setText("12x");
            Point2D other = Fx.center(grid, 0, 0);
            Fx.click(grid, other.getX(), other.getY(), 1);
            assertTrue(editor.isEditing(), "a click elsewhere does not drop invalid input");
            assertEquals(1, grid.getSelectionModel().getFocusedRow(), "and does not move the selection");
            assertFalse(grid.editCell(0, 1), "nor does editing another cell");
            assertEquals("12x", editor.field().getText());

            editor.field().setText("500");
            assertTrue(editor.commit());
            assertEquals(500, model.getItem(1).amount());
        });
    }

    @Test
    void enumsConvertersAndUnsupportedTypes() throws Exception {
        Fx.run(() -> {
            AtomicInteger writes = new AtomicInteger();
            ListGridModel<Item> model = editable(writes);
            Grid grid = show(new Grid(model));
            grid.setEditable(true);
            CellEditor editor = ((GridSkin) grid.getSkin()).editor();

            assertTrue(grid.editCell(0, 2));
            assertEquals("LOW", editor.field().getText());
            editor.field().setText("high");
            assertTrue(editor.commit());
            assertEquals(Level.HIGH, model.getItem(0).level());

            grid.getColumn(1).setConverter(new StringConverter<Integer>() {
                @Override
                public String toString(Integer value) {
                    return "#" + value;
                }

                @Override
                public Integer fromString(String text) {
                    return Integer.valueOf(text.replace("#", ""));
                }
            });
            assertTrue(grid.editCell(3, 1));
            assertEquals("#3", editor.field().getText());
            editor.field().setText("#33");
            assertTrue(editor.commit());
            assertEquals(33, model.getItem(3).amount());

            assertTrue(grid.editCell(4, 0));
            assertTrue(editor.commit(), "unchanged text");
            assertEquals(0, writes.get(), "nothing written");

            ListGridModel<Item> records = new ListGridModel<Item>().column("Item", item -> item, (item, value) -> item);
            records.setAll(list(2));
            grid.setModel(records);
            assertFalse(grid.editCell(0, 0), "a record cannot be typed in");
        });
    }
}
