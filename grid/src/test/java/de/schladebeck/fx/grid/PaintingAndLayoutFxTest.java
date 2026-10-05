package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.mouse;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.model.SortKey;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What is painted and shown, how layouts are kept, and what assistive technology and the clipboard get. */
class PaintingAndLayoutFxTest {

    public record Item(String name, double price, boolean active) {
    }

    private final Locale before = Locale.getDefault();

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(before);
    }

    private static ListGridModel<Item> items(int count) {
        return ListGridModel.ofRecords(Item.class, IntStream.range(0, count)
                .mapToObj(i -> new Item("item " + i, 1234.5 + i, i % 2 == 0))
                .toList());
    }

    /** A pixel of the node's scene at scene coordinates; the focus ring reaches outside the grid's bounds. */
    private static Color pixel(Node node, double x, double y) {
        WritableImage image = node.getScene().snapshot(null);
        return image.getPixelReader().getColor((int) x, (int) y);
    }

    private static boolean close(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue()) < 0.12;
    }

    @Test
    void everyRendererStartsFromTheSameState() throws Exception {
        Fx.run(() -> {
            List<String> seen = new ArrayList<>();
            Grid grid = new Grid(items(5));
            grid.getColumn(0).setRenderer((gc, cell) -> {
                gc.setGlobalAlpha(0.1);
                gc.setTextAlign(TextAlignment.RIGHT);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.setLineWidth(9);
            });
            grid.getColumn(1).setRenderer((gc, cell) -> seen.add(gc.getGlobalAlpha() + " " + gc.getTextAlign() + " "
                    + gc.getTextBaseline() + " " + gc.getLineWidth() + " " + (gc.getFont() == cell.getFont())));
            show(grid);
            assertFalse(seen.isEmpty());
            assertTrue(seen.stream().allMatch("1.0 LEFT CENTER 1.0 true"::equals), seen.toString());
        });
    }

    @Test
    void theFocusBorderAndSelectionOutlineNeedTheFocus() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(20));
            Stage stage = Fx.window(grid);
            try {
                grid.getSelectionModel().select(2, 0);
                grid.getSelectionModel().extend(4, 1);
                layout(grid);
                Bounds focus = grid.getCellBounds(4, 1);
                Point2D edge = grid.localToScene(focus.getMinX() + 0.5, focus.getCenterY());
                Node root = grid.getParent();
                assertTrue(close(grid.getFocusColor(), pixel(root, edge.getX(), edge.getY())), "focused");

                Bounds outline = grid.getCellBounds(2, 0);
                Point2D top = grid.localToScene(outline.getCenterX(), outline.getMinY() + 0.5);
                assertTrue(close(grid.getFocusColor(), pixel(root, top.getX(), top.getY())), "range outline");

                grid.setFocusTraversable(false);
                stage.getScene().getRoot().requestFocus();
                layout(grid);
                assertFalse(grid.isFocused());
                assertFalse(close(grid.getFocusColor(), pixel(root, edge.getX(), edge.getY())),
                        "no border without focus");
            } finally {
                stage.close();
            }
        });
    }

    /** A cell's background color, between the focus border and the text. */
    private static Color background(Grid grid, int row, int column) {
        Bounds cell = grid.getCellBounds(row, column);
        Point2D point = grid.localToScene(cell.getMinX() + 4, cell.getCenterY());
        return pixel(grid.getParent(), point.getX(), point.getY());
    }

    @Test
    void aWindowMovedToAScreenWithOtherScalingIsPaintedAnew() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(20));
            Stage stage = Fx.window(grid);
            try {
                GridSkin skin = (GridSkin) grid.getSkin();
                int paints = skin.paintCount();
                stage.setRenderScaleX(stage.getRenderScaleX() + 0.5);
                layout(grid);
                assertTrue(skin.paintCount() > paints);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void everyOtherColumnCanBeStripedLikeRows() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(4));
            grid.setStyle("-grid-alternate-cell-background: white; -grid-alternate-column-background: #ff000080;");
            show(grid);
            assertTrue(close(Color.WHITE, background(grid, 0, 0)));
            assertTrue(close(Color.WHITE.interpolate(Color.RED, 0.5), background(grid, 0, 1)), "half see-through");
            assertTrue(close(Color.WHITE, background(grid, 1, 2)));
        });
    }

    @Test
    void theFocusedCellOfALargerSelectionKeepsItsBackground() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(20));
            Stage stage = Fx.window(grid);
            try {
                grid.getSelectionModel().select(2, 0);
                grid.getSelectionModel().extend(4, 1);
                layout(grid);
                assertTrue(close(grid.getCellBackground(), background(grid, 4, 1)), "the focused cell");
                assertTrue(close(grid.getSelectionBackground(), background(grid, 3, 1)), "the rest of the range");

                grid.getSelectionModel().select(4, 1);
                layout(grid);
                assertTrue(close(grid.getSelectionBackground(), background(grid, 4, 1)), "a single selected cell");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void decimalsOnScreenGetTheFractionDigitsTheirColumnNeeds() throws Exception {
        Fx.run(() -> {
            Set<String> digits = new HashSet<>();
            Grid grid = new Grid(new Fx.ArrayModel(new Object[][] {{48065.7, 7.0}, {143048.83, 1.0}, {3.0, 2.0}}));
            grid.setCellRendererProvider((row, column, value) -> (gc, cell) -> {
                digits.add(cell.getModelColumn() + ": " + cell.getFractionDigits());
                CellRenderers.NUMBER.paint(gc, cell);
            });
            show(grid);

            assertEquals(Set.of("0: 2", "1: 0"), digits, "painted and measured alike, column by column");
        });
    }

    @Test
    void rangesOverTheSameColumnsOrRowsAreCopiedTogether() throws Exception {
        Fx.run(() -> {
            Locale.setDefault(Locale.US);
            Grid grid = show(new Grid(items(5)));
            GridSelectionModel selection = grid.getSelectionModel();
            selection.setMode(SelectionMode.ROW);
            selection.select(3, 0);
            selection.add(1, 0);
            assertTrue(grid.copySelection());
            assertEquals("item 1\t1,235.5\tnot checked\nitem 3\t1,237.5\tnot checked\n",
                    Clipboard.getSystemClipboard().getString(), "in view order");

            selection.setMode(SelectionMode.AREA);
            selection.select(0, 0);
            selection.add(1, 1);
            assertFalse(grid.copySelection(), "neither the same rows nor the same columns");

            grid.setModel(new ComputedGridModel(1_000_001, 1, (row, column) -> row));
            selection.selectAll();
            assertFalse(grid.copySelection(), "over the limit");
        });
    }

    @Test
    void copyAndScreenReadersGetWhatIsShown() throws Exception {
        Fx.run(() -> {
            Locale.setDefault(Locale.US);
            Grid grid = show(new Grid(items(3)));
            grid.getSelectionModel().select(0, 0);
            grid.getSelectionModel().extend(1, 2);
            assertTrue(grid.copySelection());
            assertEquals("item 0\t1,234.5\tchecked\nitem 1\t1,235.5\tnot checked\n",
                    Clipboard.getSystemClipboard().getString());

            Node header = (Node) grid.queryAccessibleAttribute(AccessibleAttribute.COLUMN_AT_INDEX, 1);
            assertEquals("Price", header.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            Node cell = (Node) grid.queryAccessibleAttribute(AccessibleAttribute.CELL_AT_ROW_COLUMN, 1, 1);
            assertEquals("1,235.5", cell.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            assertEquals(false, cell.queryAccessibleAttribute(AccessibleAttribute.FOCUSED));
            Node focused = (Node) grid.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM);
            assertEquals("not checked", focused.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            assertEquals(true, focused.queryAccessibleAttribute(AccessibleAttribute.FOCUSED));
        });
    }

    @Test
    void aNumberTooWideForItsColumnKeepsItsFullTextForTheTooltip() throws Exception {
        Fx.run(() -> {
            Locale.setDefault(Locale.US);
            Grid grid = show(new Grid(items(3)));
            grid.getColumn(1).setWidth(25);
            layout(grid);
            assertEquals("1,234.5", ((GridSkin) grid.getSkin()).cutOffText(0, 1));
        });
    }

    @Test
    void fittedWidthsStayFittedAfterALayoutRoundTrip() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(20)));
            grid.getColumn(2).setWidth(90);
            GridLayout saved = grid.getLayout();
            assertEquals(java.util.Map.of(2, 90.0), saved.columnWidths(), "only the width set in code");

            Grid restored = show(new Grid(items(20)));
            restored.setLayout(GridLayout.decode(saved.encode()));
            double fitted = restored.getColumn(0).getWidth();
            restored.setFont(Font.font(restored.getFont().getFamily(), 24));
            layout(restored);
            assertTrue(restored.getColumn(0).getWidth() > fitted, "still fitted to the larger font");
            assertEquals(90, restored.getColumn(2).getWidth());
        });
    }

    @Test
    void layoutChangesAreReported() throws Exception {
        Fx.run(() -> {
            List<GridLayout> reported = new ArrayList<>();
            Grid grid = show(new Grid(items(20)));
            grid.setOnLayoutChanged(reported::add);
            grid.getColumn(0).setWidth(120);
            grid.moveColumn(0, 2);
            grid.sort(1, true);
            grid.setZoom(1.5);
            assertEquals(4, reported.size());
            assertEquals(1.5, reported.getLast().zoom());

            reported.clear();
            grid.setLayout(GridLayout.decode("frozen=1;zoom=2"));
            assertEquals(1, reported.size(), "one report for a whole layout");
        });
    }

    @Test
    void aMovedOrderOfAHundredMillionColumnsStaysSmall() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(new ComputedGridModel(10, 100_000_000, (row, column) -> column)));
            grid.moveColumn(0, 99_999_999);
            String text = grid.getLayout().encode();
            assertTrue(text.startsWith("v=1;order=1-99999999,0;") && text.length() < 120, text);

            Grid restored = show(new Grid(new ComputedGridModel(10, 100_000_000, (row, column) -> column)));
            restored.setLayout(GridLayout.decode(text));
            assertEquals(0, restored.viewToModelColumn(99_999_999));
            assertEquals(1, restored.viewToModelColumn(0));
        });
    }

    @Test
    void tooltipsOnlyWhereThereIsSomethingToShow() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(new ComputedGridModel(3, 2,
                    (row, column) -> "a value much longer than its column " + row)
                    .columnNames(column -> "a column name much longer than its column"));
            grid.getColumn(0).setWidth(40);
            grid.getColumn(1).setWidth(40);
            show(grid);
            Node canvas = grid.getChildrenUnmodifiable().getFirst();
            double x = grid.getCellBounds(0, 0).getCenterX();

            mouse(grid, MouseEvent.MOUSE_MOVED, x, grid.getHeight() - 20, 0);
            assertNull(canvas.getProperties().get("javafx.scene.control.Tooltip"), "nothing below the last row");
            mouse(grid, MouseEvent.MOUSE_MOVED, x, grid.getHeaderHeight() / 2, 0);
            assertNotNull(canvas.getProperties().get("javafx.scene.control.Tooltip"), "the cut-off header");

            grid.setTooltipProvider(cell -> "row " + cell.modelRow());
            Point2D cell = Fx.center(grid, 2, 1);
            mouse(grid, MouseEvent.MOUSE_MOVED, cell.getX(), cell.getY(), 0);
            Tooltip tooltip = (Tooltip) canvas.getProperties().get("javafx.scene.control.Tooltip");
            assertEquals("row 2", tooltip.getText());
        });
    }

    @Test
    void aTooltipCanShowANodeMadeOncePerCell() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(5)));
            List<Integer> asked = new ArrayList<>();
            grid.setTooltipGraphicProvider(cell -> {
                asked.add(cell.viewRow());
                return cell.viewRow() == 3 ? null : new Label("details of row " + cell.viewRow());
            });
            Node canvas = grid.getChildrenUnmodifiable().getFirst();
            Point2D cell = Fx.center(grid, 2, 0);
            mouse(grid, MouseEvent.MOUSE_MOVED, cell.getX(), cell.getY(), 0);
            mouse(grid, MouseEvent.MOUSE_MOVED, cell.getX() + 1, cell.getY(), 0);
            Tooltip tooltip = (Tooltip) canvas.getProperties().get("javafx.scene.control.Tooltip");
            assertEquals("details of row 2", ((Label) tooltip.getGraphic()).getText());
            assertTrue(tooltip.getStyleClass().contains("grid-tooltip"));
            assertEquals(List.of(2), asked, "once while the mouse stays in the cell");

            Point2D none = Fx.center(grid, 3, 0);
            mouse(grid, MouseEvent.MOUSE_MOVED, none.getX(), none.getY(), 0);
            assertNull(canvas.getProperties().get("javafx.scene.control.Tooltip"), "no node and no text: no tooltip");
        });
    }

    @Test
    void theColumnMenuComesFromTheFactory() throws Exception {
        Fx.run(() -> {
            Grid grid = show(new Grid(items(5)));
            assertEquals(4 + 2 + 1 + 1 + 3, grid.createColumnMenu(grid.getColumn(1)).getItems().size(),
                    "sort (3) and a separator, fit and hide, fit visible, a separator and one check box per column");
            List<GridColumn> asked = new ArrayList<>();
            grid.setColumnMenuFactory(column -> {
                asked.add(column);
                return null;
            });
            Node canvas = grid.getChildrenUnmodifiable().getFirst();
            Point2D header = grid.localToScene(grid.getCellBounds(0, 2).getCenterX(), grid.getHeaderHeight() / 2);
            Event.fireEvent(canvas, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, header.getX(),
                    header.getY(), header.getX(), header.getY(), false, null));
            assertEquals(List.of(grid.getColumn(2)), asked);
        });
    }

    /** The thirds of a column header that hold text, in a grid put on screen by {@link Fx#show}. */
    private static List<HPos> headerText(Grid grid, int column) {
        PixelReader pixels = grid.getParent().snapshot(null, null).getPixelReader();
        Bounds cell = grid.getCellBounds(0, column);
        double third = cell.getWidth() / 3;
        int y = (int) (cell.getMinY() - grid.getHeaderHeight() / 2);
        List<HPos> found = new ArrayList<>();
        for (HPos part : HPos.values()) {
            int from = (int) (cell.getMinX() + part.ordinal() * third) + 2;
            boolean ink = false;
            for (int x = from; x < from + third - 4; x++) {
                for (int dy = -3; dy <= 3; dy++) {
                    ink |= !close(grid.getHeaderBackground(), pixels.getColor(x, y + dy));
                }
            }
            if (ink) {
                found.add(part);
            }
        }
        return found;
    }

    @Test
    void headersAreAlignedLikeTheValuesBelowThem() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(items(3));
            for (int column = 0; column < 3; column++) {
                grid.getColumn(column).setWidth(150);
            }
            show(grid);
            assertEquals(List.of(HPos.LEFT), headerText(grid, 0), "text");
            assertEquals(List.of(HPos.RIGHT), headerText(grid, 1), "numbers");
            assertEquals(List.of(HPos.CENTER), headerText(grid, 2), "check boxes");

            grid.getColumn(1).setHeaderStyle(CellStyle.DEFAULT.withAlignment(HPos.LEFT));
            grid.getColumn(2).setHeaderRenderer((gc, cell) -> CellRenderers.drawText(gc, cell, "Active", HPos.LEFT));
            layout(grid);
            assertEquals(List.of(HPos.LEFT), headerText(grid, 1), "the header style wins");
            assertEquals(List.of(HPos.LEFT), headerText(grid, 2), "the column's own header renderer wins");
        });
    }

    @Test
    void theSortNumberKeepsAGapToTheHeaderText() throws Exception {
        Fx.run(() -> {
            List<Double> widths = new ArrayList<>();
            Grid grid = new Grid(items(3));
            grid.getColumn(1).setHeaderRenderer((gc, cell) -> {
                if (cell.getWidth() < 1000) { // not while measuring
                    widths.add(cell.getWidth());
                }
                CellRenderers.TEXT.paint(gc, cell);
            });
            grid.getColumn(1).setWidth(150);
            show(grid);
            grid.sort(List.of(new SortKey(1, true)));
            layout(grid);
            double arrowOnly = widths.getLast();
            grid.sort(List.of(new SortKey(1, true), new SortKey(0, true)));
            layout(grid);

            Font numberFont = Font.font(grid.getHeaderFont().getFamily(), grid.getHeaderFont().getSize() * 0.75);
            assertTrue(arrowOnly - widths.getLast() >= TextMetrics.width("1", numberFont) + 3,
                    "room for the place in the sort order and a gap before it");
            grid.getColumn(1).fitToContent();
            layout(grid);
            assertNull(((GridSkin) grid.getSkin()).cutOffText(-1, 1), "fitting leaves room for the gap");
        });
    }

    /** The brightness of the darkest pixel of a cell, in a grid put on screen by {@link Fx#show}. */
    private static double darkest(Grid grid, int row, int column) {
        PixelReader pixels = grid.getParent().snapshot(null, null).getPixelReader();
        Bounds cell = grid.getCellBounds(row, column);
        double darkest = 1;
        for (int x = (int) cell.getMinX() + 1; x < cell.getMaxX() - 1; x++) {
            for (int y = (int) cell.getMinY() + 1; y < cell.getMaxY() - 1; y++) {
                darkest = Math.min(darkest, pixels.getColor(x, y).getBrightness());
            }
        }
        return darkest;
    }

    @Test
    void checkBoxesThatCannotBeEditedAreMuted() throws Exception {
        Fx.run(() -> {
            ListGridModel<Item> model = items(3).column("Editable", Item::active,
                    (Item item, Boolean active) -> new Item(item.name(), item.price(), active));
            Grid grid = new Grid(model);
            grid.setEditable(true);
            show(grid);
            // Unchecked boxes of row 1: the frame only, whose parts do not overlap; the two lines of a check mark
            // overlap where they meet, which a see-through color shows a little darker.
            double editable = darkest(grid, 1, 3);
            assertTrue(editable < 0.3, "drawn in the text color: " + editable);
            assertTrue(darkest(grid, 1, 2) > editable + 0.3, "the model does not allow editing");
            assertTrue(darkest(show(new Grid(model)), 1, 3) > editable + 0.3, "the grid is not editable");
        });
    }
}
